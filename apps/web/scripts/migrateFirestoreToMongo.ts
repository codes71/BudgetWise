/** Read-only Firestore export/verification; create-only imports are now restricted to isolated fixtures. */
import { BSON, Long, MongoClient, type Db, type Document } from "mongodb";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { assertSyntheticFirestoreSource, assertSyntheticMongoTarget, RECOVERY_WRITE_BLOCKED } from "./recoveryWriteSafety";
import {
  captureSource, digest, MigrationError, readSnapshot, summarize, writeSnapshot,
  type Encoded, type FirestoreSnapshot, type SnapshotDocument,
} from "./firestoreSnapshot";

export interface PlannedDocument { collection: string; document: Document & { _id: string }; }
const ROOT_COLLECTIONS = new Set(["users", "userEmails", "categories", "rateLimits"]);
const OWNED_COLLECTIONS: Record<string, string> = { transactions: "transactions", budgets: "budgets", budgetMonths: "budgetMonths", categories: "userCategories" };
const CONTROL = "_migrationControl";
const CONTROL_ID = "firestore-to-mongo-v1";

function sorted(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sorted);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, v]) => [key, sorted(v)]));
  return value;
}

/** Canonical BSON comparison handles dates, bytes, NaN and integer types without JSON precision loss. */
export function canonicalBson(value: unknown): string {
  return JSON.stringify(sorted(BSON.EJSON.serialize(value, { relaxed: false })));
}

function decode(value: Encoded, exact: [string[], Encoded][], path: string[] = []): unknown {
  switch (value[0]) {
    case "null": return null;
    case "boolean": case "string": return value[1];
    case "number": return Number(value[1]);
    case "integer": return Long.fromString(value[1]);
    case "date": return new Date(value[1]);
    case "timestamp": {
      if (value[2] % 1_000_000 !== 0) exact.push([path, value]);
      return new Timestamp(Number(value[1]), value[2]).toDate();
    }
    case "bytes": return Buffer.from(value[1], "base64");
    case "geo": exact.push([path, value]); return { latitude: value[1], longitude: value[2] };
    case "reference": exact.push([path, value]); return { projectId: value[1], databaseId: value[2], path: value[3] };
    case "array": return value[1].map((v, i) => decode(v, exact, [...path, String(i)]));
    case "map": return Object.fromEntries(value[1].map(([key, v]) => [key, decode(v, exact, [...path, key])]));
  }
}

function mapDocument(source: SnapshotDocument): PlannedDocument[] {
  const parts = source.path.split("/");
  const ownerField = source.data[0] === "map" ? source.data[1].find(([key]) => key === "userId")?.[1] : undefined;
  const ownerId = parts[0] === "users" ? parts[1] : ownerField?.[0] === "string" ? ownerField[1] : undefined;
  const root = parts.length === 2 && ROOT_COLLECTIONS.has(parts[0]);
  const owned = parts.length === 4 && parts[0] === "users" && Object.hasOwn(OWNED_COLLECTIONS, parts[2]);
  if (!root && !owned) return [{ collection: "_firestoreArchive", document: { _id: source.path, sourcePath: source.path, ...(ownerId ? { ownerId } : {}), data: source.data, createTime: source.createTime, updateTime: source.updateTime } }];
  const exact: [string[], Encoded][] = [];
  const data = decode(source.data, exact) as Document;
  // Reserved Mongo identity/ownership metadata must never silently replace a user field.
  if (Object.hasOwn(data, "_id")) throw new MigrationError("A source document has a reserved _id field. Resolve its mapping before importing.");
  if (owned && Object.hasOwn(data, "userId") && data.userId !== ownerId) throw new MigrationError("A user-scoped source document has conflicting ownership. Resolve it before importing.");
  const collection = owned ? OWNED_COLLECTIONS[parts[2]] : parts[0];
  const _id = owned ? `${ownerId}/${parts[3]}` : parts[1];
  const out: PlannedDocument[] = [{ collection, document: { ...data, _id, ...(owned ? { userId: ownerId } : {}) } }];
  if (exact.length) out.push({ collection: "_firestoreTypes", document: { _id: `${collection}/${_id}`, sourcePath: source.path, ...(ownerId ? { ownerId } : {}), fields: exact } });
  return out;
}

export function planMigration(snapshot: FirestoreSnapshot): PlannedDocument[] {
  const plan = snapshot.documents.flatMap(mapDocument).sort((a, b) => a.collection < b.collection ? -1 : a.collection > b.collection ? 1 : a.document._id < b.document._id ? -1 : a.document._id > b.document._id ? 1 : 0);
  let previous: string | undefined;
  for (const row of plan) {
    const key = `${row.collection}\0${row.document._id}`;
    if (key === previous) throw new MigrationError("Two source documents map to the same destination identity.");
    previous = key;
    // Validate BSON size before any write. MongoDB's document limit is 16 MiB.
    if (BSON.calculateObjectSize(row.document) > 16 * 1024 * 1024) throw new MigrationError("A mapped document exceeds MongoDB's size limit.");
  }
  return plan;
}

export function planSummary(plan: PlannedDocument[]) {
  const collectionRows = new Map<string, PlannedDocument[]>(), userRows = new Map<string, PlannedDocument[]>();
  for (const row of plan) {
    collectionRows.set(row.collection, [...(collectionRows.get(row.collection) ?? []), row]);
    const owner = row.document.userId ?? row.document.ownerId ?? (row.collection === "users" ? row.document._id : undefined);
    if (typeof owner === "string") { const key = digest(owner); userRows.set(key, [...(userRows.get(key) ?? []), row]); }
  }
  const group = (rows: Map<string, PlannedDocument[]>) => Object.fromEntries([...rows].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, values]) => [key, { count: values.length, sha256: digest(values.map((v) => `${v.collection}\0${canonicalBson(v.document)}`).join("\n")) }]));
  return { documents: plan.length, collections: group(collectionRows), users: group(userRows), sha256: digest(plan.map((row) => `${row.collection}\0${canonicalBson(row.document)}`).join("\n")) };
}

interface DestinationInspection { missing: PlannedDocument[]; matching: number; }

/** Full target scan rejects extra documents as well as conflicting versions. Nothing is merged implicitly. */
export async function inspectDestination(mongo: Db, plan: PlannedDocument[], snapshot: FirestoreSnapshot): Promise<DestinationInspection> {
  const expected = new Map(plan.map((row) => [`${row.collection}\0${row.document._id}`, row]));
  let matching = 0;
  for (const collection of await mongo.listCollections({}, { nameOnly: true }).toArray()) {
    if (collection.name.startsWith("system.")) continue;
    if (collection.name === CONTROL) {
      const controls = await mongo.collection<Document & { _id: string }>(CONTROL).find({}).toArray();
      if (controls.length > 1 || controls.some((c) => c._id !== CONTROL_ID || c.snapshotSha256 !== snapshot.sha256 || c.sourceProjectId !== snapshot.projectId || c.destinationDb !== mongo.databaseName)) throw new MigrationError("Destination belongs to a different migration; use a new empty database.");
      continue;
    }
    for await (const doc of mongo.collection(collection.name).find({})) {
      const key = `${collection.name}\0${String(doc._id)}`, row = expected.get(key);
      if (!row || canonicalBson(doc) !== canonicalBson(row.document)) throw new MigrationError(`Destination collection ${collection.name} contains extra or conflicting data; nothing was overwritten.`);
      expected.delete(key); matching++;
    }
  }
  return { missing: [...expected.values()], matching };
}

/** Only insertOne is used for app records. Existing matching records are resumable; differences abort. */
export async function importSnapshot(mongo: Db, snapshot: FirestoreSnapshot, plan: PlannedDocument[]): Promise<{ inserted: number; matching: number }> {
  assertSyntheticFirestoreSource(snapshot.projectId);
  assertSyntheticMongoTarget(mongo);
  const before = await inspectDestination(mongo, plan, snapshot);
  const controls = mongo.collection<Document & { _id: string }>(CONTROL);
  const current = await controls.findOne({ _id: CONTROL_ID });
  if (!current) {
    // Prevent accidental adoption of an arbitrary existing database that happens to have matching records.
    if (before.matching !== 0) throw new MigrationError("Destination is not empty and has no matching migration marker; refusing to adopt it.");
    await controls.insertOne({ _id: CONTROL_ID, snapshotSha256: snapshot.sha256, sourceProjectId: snapshot.projectId, destinationDb: mongo.databaseName, startedAt: new Date(), formatVersion: 1 });
  }
  let inserted = 0;
  for (const row of before.missing) {
    try { await mongo.collection<Document & { _id: string }>(row.collection).insertOne(row.document); inserted++; }
    catch (error) {
      // Another exact resume may have inserted the same record. Never replace it.
      if ((error as { code?: number }).code !== 11000) throw error;
      const existing = await mongo.collection<Document & { _id: string }>(row.collection).findOne({ _id: row.document._id });
      if (!existing || canonicalBson(existing) !== canonicalBson(row.document)) throw new MigrationError("An import collision differs from the immutable snapshot; import stopped.");
    }
  }
  const after = await inspectDestination(mongo, plan, snapshot);
  if (after.missing.length) throw new MigrationError("Destination verification is incomplete; do not switch traffic.");
  return { inserted, matching: after.matching };
}

export async function assertSourceUnchanged(source: Firestore, snapshot: FirestoreSnapshot): Promise<void> {
  const current = await captureSource(source, snapshot.projectId);
  if (current.sha256 !== snapshot.sha256) throw new MigrationError("Firestore changed since the snapshot. Do not cut over. Freeze writes and export a new snapshot into a new destination.");
}

function parseArgs(args: string[]): Record<string, string> {
  const allowed = new Set(["mode", "source-project", "snapshot", "mongo-db", "expect-sha256"]), out: Record<string, string> = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]?.replace(/^--/, ""), value = args[i + 1];
    if (!args[i]?.startsWith("--") || !allowed.has(key) || !value || value.startsWith("--") || Object.hasOwn(out, key)) throw new MigrationError("Use unique --mode, --source-project, --snapshot, --mongo-db and --expect-sha256 arguments with values.");
    out[key] = value;
  }
  if (!["export", "dry-run", "import", "verify", "verify-source"].includes(out.mode) || !out["source-project"] || !out.snapshot) throw new MigrationError("Specify --mode export|dry-run|import|verify|verify-source, --source-project and --snapshot.");
  if (!/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(out["source-project"])) throw new MigrationError("Invalid explicit source project ID.");
  return out;
}

function connectSource(projectId: string): Firestore {
  if (process.env.FIREBASE_PROJECT_ID !== projectId) throw new MigrationError("FIREBASE_PROJECT_ID must exactly match --source-project.");
  const emulator = process.env.FIRESTORE_EMULATOR_HOST;
  if (emulator && (!projectId.startsWith("demo-") || !/^127\.0\.0\.1:\d+$/.test(emulator))) throw new MigrationError("Emulator export is restricted to demo- projects on localhost.");
  const email = process.env.FIREBASE_CLIENT_EMAIL, key = process.env.FIREBASE_PRIVATE_KEY;
  if (!emulator && !(email && key) && !process.env.GOOGLE_APPLICATION_CREDENTIALS) throw new MigrationError("Supply the established read-only source credential environment; interactive Google login is not used.");
  return new Firestore({ projectId, databaseId: "(default)", ...(email && key ? { credentials: { client_email: email, private_key: key.replace(/\\n/g, "\n") } } : {}) });
}

export async function main(args = process.argv.slice(2)): Promise<void> {
  const flags = parseArgs(args), mode = flags.mode, project = flags["source-project"];
  if (mode === "import") throw new MigrationError(RECOVERY_WRITE_BLOCKED);
  let source: Firestore | undefined, client: MongoClient | undefined;
  try {
    if (mode === "export") {
      source = connectSource(project);
      // Two complete scans reject exports taken while visible writes are changing the source.
      const snapshot = await captureSource(source, project);
      await assertSourceUnchanged(source, snapshot);
      await writeSnapshot(flags.snapshot, snapshot);
      console.log(JSON.stringify({ mode, source: summarize(snapshot), snapshotWritten: true, sourceWrites: false, destinationWrites: false }, null, 2));
      return;
    }
    const snapshot = await readSnapshot(flags.snapshot, project);
    if (flags["expect-sha256"] !== snapshot.sha256) throw new MigrationError("--expect-sha256 must equal the independently reviewed snapshot fingerprint.");
    const plan = planMigration(snapshot);
    if (mode === "verify-source") {
      source = connectSource(project); await assertSourceUnchanged(source, snapshot);
      console.log(JSON.stringify({ mode, sourceUnchanged: true, source: summarize(snapshot), sourceWrites: false, destinationWrites: false }, null, 2)); return;
    }
    const database = flags["mongo-db"];
    if (!database || !/^[A-Za-z][A-Za-z0-9_-]{2,62}$/.test(database) || ["admin", "local", "config"].includes(database.toLowerCase()) || process.env.MONGODB_DB !== database) throw new MigrationError("MONGODB_DB must exactly match the explicit non-system --mongo-db destination.");
    const uri = process.env.MONGODB_URI;
    if (!uri || !/^mongodb(?:\+srv)?:\/\//.test(uri)) throw new MigrationError("Set the existing Atlas connection in MONGODB_URI; never pass it as an argument.");
    client = new MongoClient(uri, { maxPoolSize: 3, serverSelectionTimeoutMS: 15000, promoteLongs: false });
    await client.connect(); const mongo = client.db(database);
    if (mode === "import") {
      source = connectSource(project); await assertSourceUnchanged(source, snapshot);
      const result = await importSnapshot(mongo, snapshot, plan);
      // Source drift after import means this target must not be promoted.
      await assertSourceUnchanged(source, snapshot);
      console.log(JSON.stringify({ mode, destinationDb: database, result, sourceUnchanged: true, source: summarize(snapshot), destination: planSummary(plan), sourceWrites: false, destinationWrites: true, verified: true }, null, 2)); return;
    }
    const inspected = await inspectDestination(mongo, plan, snapshot);
    if (mode === "verify" && inspected.missing.length) throw new MigrationError("Destination has missing records; verification failed.");
    if (mode === "verify" && !(await mongo.collection<Document & { _id: string }>(CONTROL).findOne({ _id: CONTROL_ID }))) throw new MigrationError("Destination has no migration marker; verification failed.");
    console.log(JSON.stringify({ mode, destinationDb: database, source: summarize(snapshot), destination: planSummary(plan), matching: inspected.matching, missing: inspected.missing.length, sourceWrites: false, destinationWrites: false, verified: mode === "verify" }, null, 2));
  } finally { await client?.close(); await source?.terminate(); }
}

if (process.argv[1] && /migrateFirestoreToMongo\.[cm]?[jt]s$/.test(process.argv[1])) {
  main().catch((error) => {
    // Driver errors can include connection strings or document values. Only deliberately safe errors are printed.
    console.error(error instanceof MigrationError ? error.message : "Migration failed. No credentials or source records were printed. Inspect locally with a redacted debugger; do not promote this destination.");
    process.exitCode = 1;
  });
}
