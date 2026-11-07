import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { open } from "node:fs/promises";
import { Timestamp, GeoPoint, DocumentReference, type Firestore } from "firebase-admin/firestore";

/** Tagged tuples avoid collisions with field names in actual user documents. */
export type Encoded =
  | ["null"] | ["boolean", boolean] | ["string", string] | ["number", string]
  | ["integer", string] | ["date", string] | ["timestamp", string, number]
  | ["bytes", string] | ["geo", number, number] | ["reference", string, string, string]
  | ["array", Encoded[]] | ["map", [string, Encoded][]];

export interface SnapshotDocument {
  path: string;
  data: Encoded;
  createTime: Encoded | null;
  updateTime: Encoded | null;
}

export interface FirestoreSnapshot {
  format: "pocket-peek-firestore-snapshot";
  version: 1;
  projectId: string;
  databaseId: "(default)";
  exportedAt: string;
  documents: SnapshotDocument[];
  sha256: string;
}

export class MigrationError extends Error {
  constructor(message: string) { super(message); this.name = "MigrationError"; }
}

export const digest = (text: string): string => createHash("sha256").update(text).digest("hex");

export function encodeValue(value: unknown): Encoded {
  if (value === null) return ["null"];
  if (typeof value === "string") return ["string", value];
  if (typeof value === "boolean") return ["boolean", value];
  if (typeof value === "bigint") return ["integer", String(value)];
  if (typeof value === "number") {
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
      throw new MigrationError("A source integer exceeds JavaScript's exact range; export with an exact-integer reader before continuing.");
    }
    return ["number", Object.is(value, -0) ? "-0" : String(value)];
  }
  if (value instanceof Timestamp) return ["timestamp", String(value.seconds), value.nanoseconds];
  if (value instanceof Date) {
    if (!Number.isFinite(value.getTime())) throw new MigrationError("Source contains an invalid Date.");
    return ["date", value.toISOString()];
  }
  if (Buffer.isBuffer(value) || value instanceof Uint8Array) return ["bytes", Buffer.from(value).toString("base64")];
  if (value instanceof GeoPoint) return ["geo", value.latitude, value.longitude];
  if (value instanceof DocumentReference) {
    // The SDK exposes projectId at runtime after its first request, although this version omits its TS getter.
    const projectId = (value.firestore as unknown as { projectId?: string }).projectId;
    if (!projectId) throw new MigrationError("A document reference has no resolved project identity; export stopped.");
    return ["reference", projectId, value.firestore.databaseId, value.path];
  }
  if (Array.isArray(value)) return ["array", value.map(encodeValue)];
  if (typeof value === "object" && value !== null && Object.getPrototypeOf(value) === Object.prototype) {
    return ["map", Object.keys(value).sort().map((key) => [key, encodeValue((value as Record<string, unknown>)[key])])];
  }
  throw new MigrationError("Source contains an unsupported value type; no value was silently omitted.");
}

/** Strictly validate untrusted on-disk tuples before conversion or MongoDB writes. */
export function validateEncoded(value: unknown): asserts value is Encoded {
  if (!Array.isArray(value) || typeof value[0] !== "string") throw new MigrationError("Invalid snapshot value.");
  const [tag, a, b, c] = value;
  const exact = (length: number) => { if (value.length !== length) throw new MigrationError("Invalid snapshot tuple length."); };
  switch (tag) {
    case "null": exact(1); return;
    case "boolean": exact(2); if (typeof a === "boolean") return; break;
    case "string": exact(2); if (typeof a === "string") return; break;
    case "number": exact(2); if (typeof a === "string" && ["NaN", "Infinity", "-Infinity", "-0"].includes(a)) return;
      if (typeof a === "string" && a !== "" && Number.isFinite(Number(a)) && String(Number(a)) === a && (!Number.isInteger(Number(a)) || Number.isSafeInteger(Number(a)))) return;
      break;
    case "integer": exact(2); if (typeof a === "string" && /^-?(0|[1-9][0-9]*)$/.test(a) && BigInt(a) >= -(BigInt(1) << BigInt(63)) && BigInt(a) < (BigInt(1) << BigInt(63))) return; break;
    case "date": exact(2); if (typeof a === "string" && Number.isFinite(Date.parse(a)) && new Date(a).toISOString() === a) return; break;
    case "timestamp": exact(3); if (typeof a === "string" && /^-?(0|[1-9][0-9]*)$/.test(a) && Number.isSafeInteger(Number(a)) && typeof b === "number" && Number.isInteger(b) && b >= 0 && b < 1_000_000_000 && Number.isFinite(new Date(Number(a) * 1000 + Math.floor(b / 1e6)).getTime())) return; break;
    case "bytes": exact(2); if (typeof a === "string" && Buffer.from(a, "base64").toString("base64") === a) return; break;
    case "geo": exact(3); if (typeof a === "number" && typeof b === "number" && Number.isFinite(a) && Number.isFinite(b) && Math.abs(a) <= 90 && Math.abs(b) <= 180) return; break;
    case "reference": exact(4); if ([a, b, c].every((x) => typeof x === "string" && x.length > 0) && validDocumentPath(c)) return; break;
    case "array": exact(2); if (Array.isArray(a)) { for (const v of a) validateEncoded(v); return; } break;
    case "map": {
      exact(2); if (!Array.isArray(a)) break;
      let previous: string | undefined;
      for (const pair of a) {
        if (!Array.isArray(pair) || pair.length !== 2 || typeof pair[0] !== "string" || (previous !== undefined && pair[0] <= previous)) throw new MigrationError("Snapshot map keys must be unique and sorted.");
        previous = pair[0]; validateEncoded(pair[1]);
      }
      return;
    }
  }
  throw new MigrationError("Invalid snapshot value payload.");
}

export function validDocumentPath(path: unknown): path is string {
  return typeof path === "string" && path.length > 0 && path.split("/").length % 2 === 0 && path.split("/").every((x) => x.length > 0 && x !== "." && x !== "..");
}

export function snapshotHash(snapshot: Pick<FirestoreSnapshot, "projectId" | "databaseId" | "documents">): string {
  return digest(JSON.stringify([snapshot.projectId, snapshot.databaseId, snapshot.documents.map((d) => [d.path, d.data, d.createTime, d.updateTime])]));
}

export function makeSnapshot(projectId: string, documents: SnapshotDocument[]): FirestoreSnapshot {
  const snapshot: FirestoreSnapshot = { format: "pocket-peek-firestore-snapshot", version: 1, projectId, databaseId: "(default)", exportedAt: new Date().toISOString(), documents: [...documents].sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0), sha256: "" };
  snapshot.sha256 = snapshotHash(snapshot);
  validateSnapshot(snapshot, projectId);
  return snapshot;
}

export function validateSnapshot(value: unknown, expectedProject: string): asserts value is FirestoreSnapshot {
  if (!value || typeof value !== "object") throw new MigrationError("Invalid snapshot envelope.");
  const s = value as FirestoreSnapshot;
  if (s.format !== "pocket-peek-firestore-snapshot" || s.version !== 1 || s.databaseId !== "(default)" || s.projectId !== expectedProject || !Array.isArray(s.documents) || !Number.isFinite(Date.parse(s.exportedAt))) throw new MigrationError("Snapshot format or expected source project does not match.");
  let previous: string | undefined;
  for (const doc of s.documents) {
    if (!doc || !validDocumentPath(doc.path) || (previous !== undefined && doc.path <= previous)) throw new MigrationError("Snapshot document paths must be valid, unique and sorted.");
    previous = doc.path; validateEncoded(doc.data);
    if (doc.data[0] !== "map") throw new MigrationError("Snapshot document data must be a map.");
    for (const t of [doc.createTime, doc.updateTime]) { if (t !== null) { validateEncoded(t); if (t[0] !== "timestamp") throw new MigrationError("Invalid snapshot document version."); } }
  }
  if (!/^[a-f0-9]{64}$/.test(s.sha256) || snapshotHash(s) !== s.sha256) throw new MigrationError("Snapshot checksum does not match; nothing may be imported.");
}

// One shared request budget across the entire recursive traversal, including concurrent captures.
// A permit covers only the network request and is released before any recursive child walk.
const SOURCE_READ_CONCURRENCY = 8;
let activeSourceReads = 0;
const sourceReadWaiters: (() => void)[] = [];

async function sourceRead<T>(run: () => Promise<T>): Promise<T> {
  await new Promise<void>((resolve) => {
    if (activeSourceReads < SOURCE_READ_CONCURRENCY) { activeSourceReads++; resolve(); }
    else sourceReadWaiters.push(resolve);
  });
  try { return await run(); }
  finally {
    const next = sourceReadWaiters.shift();
    if (next) next(); // Transfer this permit directly to the next queued request.
    else activeSourceReads--;
  }
}

/** Firestore listDocuments includes nonexistent parent documents with existing descendants. */
export async function captureSource(firestore: Firestore, projectId: string): Promise<FirestoreSnapshot> {
  const documents: SnapshotDocument[] = [];
  async function walkCollection(collection: FirebaseFirestore.CollectionReference): Promise<void> {
    const refs = await sourceRead(() => collection.listDocuments());
    await Promise.all(refs.map(async (ref) => {
      const [snap, children] = await Promise.all([
        sourceRead(() => ref.get()), sourceRead(() => ref.listCollections()),
      ]);
      if (snap.exists) documents.push({ path: ref.path, data: encodeValue(snap.data()), createTime: snap.createTime ? encodeValue(snap.createTime) : null, updateTime: snap.updateTime ? encodeValue(snap.updateTime) : null });
      await Promise.all(children.map(walkCollection));
    }));
  }
  const roots = await sourceRead(() => firestore.listCollections());
  await Promise.all(roots.map(walkCollection));
  return makeSnapshot(projectId, documents);
}

export async function writeSnapshot(path: string, snapshot: FirestoreSnapshot): Promise<void> {
  // O_NOFOLLOW and O_EXCL avoid following a symlink or overwriting a previous backup.
  const file = await open(path, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW, 0o600);
  try { await file.writeFile(JSON.stringify(snapshot) + "\n", "utf8"); await file.sync(); } finally { await file.close(); }
}

export async function readSnapshot(path: string, projectId: string): Promise<FirestoreSnapshot> {
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await file.stat();
    if (!info.isFile() || (info.mode & 0o077) !== 0 || (process.getuid && info.uid !== process.getuid())) throw new MigrationError("Snapshot must be an owner-only regular file (0600).");
    const value: unknown = JSON.parse(await file.readFile("utf8")); validateSnapshot(value, projectId); return value;
  } finally { await file.close(); }
}

export interface SnapshotSummary {
  documents: number;
  collections: Record<string, { count: number; sha256: string }>;
  users: Record<string, { count: number; sha256: string }>;
  sha256: string;
}

export function summarize(snapshot: FirestoreSnapshot): SnapshotSummary {
  const collections = new Map<string, SnapshotDocument[]>(), users = new Map<string, SnapshotDocument[]>();
  for (const doc of snapshot.documents) {
    const parts = doc.path.split("/"), collection = parts.length === 2 ? parts[0] : parts.filter((_, i) => i % 2 === 0).join("/");
    collections.set(collection, [...(collections.get(collection) ?? []), doc]);
    if (parts[0] === "users") { const key = digest(parts[1]); users.set(key, [...(users.get(key) ?? []), doc]); }
  }
  const counts = (groups: Map<string, SnapshotDocument[]>) => Object.fromEntries([...groups].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, docs]) => [key, { count: docs.length, sha256: digest(JSON.stringify(docs)) }]));
  return { documents: snapshot.documents.length, collections: counts(collections), users: counts(users), sha256: snapshot.sha256 };
}
