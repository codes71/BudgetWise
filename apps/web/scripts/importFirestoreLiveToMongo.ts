/** Explicit one-time server-side import. Source credentials and plaintext snapshots never leave process memory. */
import { MongoClient, type Db, type Document } from "mongodb";
import type { Firestore } from "firebase-admin/firestore";
import { ensureIndexes, mongoClient as runtimeMongoClient } from "../src/lib/mongodb";
import { MigrationError, summarize, type FirestoreSnapshot } from "./firestoreSnapshot";
import { configuredSource, captureStableSource } from "./exportFirestoreEncrypted";
import { assertSourceUnchanged, canonicalBson, importSnapshot, inspectDestination, planMigration, planSummary, type PlannedDocument } from "./migrateFirestoreToMongo";
import { encryptSnapshot, readPublicSnapshotKey, writeEncryptedSnapshot } from "./snapshotEncryption";

export interface LiveImportFlags {
  "expect-source-project": string;
  "expect-source-sha256": string;
  "mongo-db": string;
  "public-key": string;
  "encrypted-output": string;
}

export function liveImportArgs(args: string[]): LiveImportFlags {
  const required = ["expect-source-project", "expect-source-sha256", "mongo-db", "public-key", "encrypted-output"], flags: Record<string, string> = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]?.replace(/^--/, ""), value = args[i + 1];
    if (!args[i]?.startsWith("--") || !required.includes(key) || !value || value.startsWith("--") || Object.hasOwn(flags, key)) throw new MigrationError("Use each required live-import flag exactly once with a value.");
    flags[key] = value;
  }
  if (required.some((key) => !flags[key])) throw new MigrationError("Live import requires --expect-source-project, --expect-source-sha256, --mongo-db, --public-key and --encrypted-output.");
  if (!/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(flags["expect-source-project"]) || !/^[a-f0-9]{64}$/.test(flags["expect-source-sha256"])) throw new MigrationError("Supply the independently reviewed source project and SHA-256 fingerprint.");
  if (!/^[A-Za-z][A-Za-z0-9_-]{2,62}$/.test(flags["mongo-db"]) || ["admin", "local", "config"].includes(flags["mongo-db"].toLowerCase())) throw new MigrationError("Live import requires an explicit non-system destination database.");
  if (flags["public-key"] === flags["encrypted-output"]) throw new MigrationError("The public key and new encrypted backup output must use different paths.");
  return flags as unknown as LiveImportFlags;
}

interface RequiredIndex { collection: string; key: Record<string, 1>; unique?: true; partialStringField?: string; }
const REQUIRED_INDEXES: RequiredIndex[] = [
  { collection: "users", key: { emailLower: 1 }, unique: true, partialStringField: "emailLower" },
  { collection: "userEmails", key: { userId: 1 } },
  { collection: "transactions", key: { userId: 1, clientId: 1 }, unique: true, partialStringField: "clientId" },
  { collection: "transactions", key: { userId: 1, updatedAt: 1 } },
  ...["budgets", "budgetMonths", "userCategories"].map((collection) => ({ collection, key: { userId: 1 as const } })),
  { collection: "budgetMonths", key: { userId: 1, month: 1 } },
  ...["_firestoreArchive", "_firestoreTypes"].map((collection) => ({ collection, key: { ownerId: 1 as const } })),
  { collection: "rateLimits", key: { expiresAt: 1 } },
];

/** No TTL deletion policy is allowed anywhere in a migration destination. */
export async function assertNoTtlIndexes(database: Db): Promise<void> {
  for (const { name } of await database.listCollections({}, { nameOnly: true }).toArray()) {
    if (name.startsWith("system.")) continue;
    const indexes = await database.collection(name).listIndexes().toArray();
    if (indexes.some((index) => Object.hasOwn(index, "expireAfterSeconds"))) throw new MigrationError("Destination contains a TTL deletion index. Use a reviewed fresh database; nothing may be promoted.");
  }
}

export async function verifyRuntimeIndexes(database: Db): Promise<{ verifiedIndexes: number }> {
  await assertNoTtlIndexes(database);
  for (const expected of REQUIRED_INDEXES) {
    const indexes = await database.collection(expected.collection).listIndexes().toArray();
    const matching = indexes.find((index) => {
      if (JSON.stringify(Object.entries(index.key)) !== JSON.stringify(Object.entries(expected.key)) || Boolean(index.unique) !== Boolean(expected.unique) || index.sparse) return false;
      if (!expected.partialStringField) return index.partialFilterExpression === undefined;
      const filter = index.partialFilterExpression, field = expected.partialStringField;
      if (!filter || Object.keys(filter).length !== 1 || !filter[field] || Object.keys(filter[field]).length !== 1) return false;
      return filter[field].$type === "string" || filter[field].$type === 2;
    });
    if (!matching) throw new MigrationError("Required runtime index readback does not match its uniqueness/filter contract. Do not promote.");
  }
  return { verifiedIndexes: REQUIRED_INDEXES.length };
}

/** Full exact inspection plus an independently computed summary from the records read back from Mongo. */
export async function verifyLiveDestination(database: Db, plan: PlannedDocument[], snapshot: FirestoreSnapshot) {
  const inspected = await inspectDestination(database, plan, snapshot);
  if (inspected.missing.length) throw new MigrationError("Destination parity has missing records. Do not promote.");
  const actual = new Map<string, Document & { _id: string }>();
  for (const collection of new Set(plan.map((row) => row.collection))) {
    for await (const row of database.collection<Document & { _id: string }>(collection).find({})) actual.set(`${collection}\0${row._id}`, row);
  }
  const actualPlan = plan.map((row) => {
    const document = actual.get(`${row.collection}\0${row.document._id}`);
    if (!document || canonicalBson(document) !== canonicalBson(row.document)) throw new MigrationError("Destination changed during its independent readback. Do not promote.");
    return { collection: row.collection, document };
  });
  if (actual.size !== plan.length) throw new MigrationError("Destination gained extra data during verification. Do not promote.");
  const summary = planSummary(actualPlan);
  if (summary.sha256 !== planSummary(plan).sha256) throw new MigrationError("Destination fingerprint differs from the reviewed source mapping. Do not promote.");
  return summary;
}

export interface LiveImportDependencies {
  source(expectedProject: string): { source: Firestore; projectId: string };
  publicKey(path: string): Promise<Buffer>;
  stableSource(source: Firestore, project: string, expectedSha256: string): Promise<FirestoreSnapshot>;
  encryptedBackup(snapshot: FirestoreSnapshot, publicKey: Buffer, path: string): Promise<void>;
  connectMongo(uri: string, database: string): Promise<{ database: Db; close(): Promise<void> }>;
  sourceUnchanged(source: Firestore, snapshot: FirestoreSnapshot): Promise<void>;
  noTtl(database: Db): Promise<void>;
  importRecords(database: Db, snapshot: FirestoreSnapshot, plan: PlannedDocument[]): ReturnType<typeof importSnapshot>;
  verifyRecords(database: Db, plan: PlannedDocument[], snapshot: FirestoreSnapshot): ReturnType<typeof verifyLiveDestination>;
  createIndexes(): Promise<void>;
  verifyIndexes(database: Db): ReturnType<typeof verifyRuntimeIndexes>;
}

const defaults: LiveImportDependencies = {
  source: configuredSource, publicKey: readPublicSnapshotKey, stableSource: captureStableSource,
  encryptedBackup: async (snapshot, publicKey, path) => { await writeEncryptedSnapshot(path, encryptSnapshot(snapshot, publicKey)); },
  connectMongo: async (uri, name) => {
    const client = new MongoClient(uri, { maxPoolSize: 3, serverSelectionTimeoutMS: 15000, promoteLongs: false });
    try { await client.connect(); return { database: client.db(name), close: () => client.close() }; }
    catch (error) { await client.close(); throw error; }
  },
  sourceUnchanged: assertSourceUnchanged, noTtl: assertNoTtlIndexes, importRecords: importSnapshot, verifyRecords: verifyLiveDestination,
  createIndexes: async () => {
    // The runtime helper uses its own cached pool. Close it explicitly so the one-time build can exit.
    const helperClient = await runtimeMongoClient();
    try { await ensureIndexes(); } finally { await helperClient.close(); }
  },
  verifyIndexes: verifyRuntimeIndexes,
};

/** Injectable only for local pure tests. The CLI always uses the real guarded dependencies above. */
export async function runLiveImport(flags: LiveImportFlags, dependencies: LiveImportDependencies = defaults) {
  // Validate programmatic callers as strictly as the CLI before using either credential environment.
  liveImportArgs(Object.entries(flags).flatMap(([key, value]) => [`--${key}`, value]));
  if (process.env.MONGODB_DB !== flags["mongo-db"]) throw new MigrationError("MONGODB_DB must exactly match the explicit --mongo-db destination.");
  const uri = process.env.MONGODB_URI;
  if (!uri || !/^mongodb(?:\+srv)?:\/\//.test(uri)) throw new MigrationError("Set the authorized Atlas connection only in MONGODB_URI, never a command argument.");
  const publicKey = await dependencies.publicKey(flags["public-key"]);
  const { source, projectId } = dependencies.source(flags["expect-source-project"]);
  let target: Awaited<ReturnType<LiveImportDependencies["connectMongo"]>> | undefined;
  try {
    if (projectId !== flags["expect-source-project"]) throw new MigrationError("Connected source identity differs from the independently reviewed source project.");
    const snapshot = await dependencies.stableSource(source, projectId, flags["expect-source-sha256"]);
    if (snapshot.projectId !== projectId || snapshot.sha256 !== flags["expect-source-sha256"]) throw new MigrationError("Live source differs from the independently reviewed final export.");
    const plan = planMigration(snapshot);
    // An authenticated encrypted backup must be durably written before any target records or indexes can be created.
    await dependencies.encryptedBackup(snapshot, publicKey, flags["encrypted-output"]);
    target = await dependencies.connectMongo(uri, flags["mongo-db"]);
    if (target.database.databaseName !== flags["mongo-db"]) throw new MigrationError("Connected MongoDB destination does not match the explicit target.");
    await dependencies.noTtl(target.database);
    await dependencies.sourceUnchanged(source, snapshot);
    const result = await dependencies.importRecords(target.database, snapshot, plan);
    await dependencies.sourceUnchanged(source, snapshot);
    await dependencies.verifyRecords(target.database, plan, snapshot);
    await dependencies.createIndexes();
    const indexes = await dependencies.verifyIndexes(target.database);
    const destination = await dependencies.verifyRecords(target.database, plan, snapshot);
    await dependencies.sourceUnchanged(source, snapshot);
    return { mode: "live-source-import", sourceProjectId: projectId, destinationDb: flags["mongo-db"], source: summarize(snapshot), destination, result, ...indexes, encryptedBackupWritten: true, plaintextFileWritten: false, sourceWrites: false, destinationWrites: true, sourceUnchanged: true, sourceWriteFreezeVerified: false, verified: true };
  } finally { try { await target?.close(); } finally { await source.terminate(); } }
}

export async function main(args = process.argv.slice(2)): Promise<void> {
  console.log(JSON.stringify(await runLiveImport(liveImportArgs(args)), null, 2));
}

if (process.argv[1] && /importFirestoreLiveToMongo\.[cm]?[jt]s$/.test(process.argv[1])) {
  main().catch((error) => {
    console.error(error instanceof MigrationError ? error.message : "Live-source import failed. No credentials, records or plaintext snapshots were printed. Do not promote this destination.");
    process.exitCode = 1;
  });
}
