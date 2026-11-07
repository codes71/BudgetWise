import { describe, expect, it } from "vitest";
import { mkdtemp, chmod, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Timestamp, GeoPoint, type Firestore } from "firebase-admin/firestore";
import { Long, MongoClient, type Db, type Document } from "mongodb";
import { captureSource, encodeValue, makeSnapshot, readSnapshot, summarize, validateSnapshot, writeSnapshot, type SnapshotDocument } from "../../scripts/firestoreSnapshot";
import { assertSourceUnchanged, canonicalBson, importSnapshot, inspectDestination, planMigration, planSummary } from "../../scripts/migrateFirestoreToMongo";

const project = "demo-pocketpeek-migration";
const row = (path: string, data: Record<string, unknown>): SnapshotDocument => ({ path, data: encodeValue(data), createTime: null, updateTime: null });
const snapshot = (...rows: SnapshotDocument[]) => makeSnapshot(project, rows);

/** Minimal in-memory Mongo surface, so these tests cannot contact a database. */
function fakeMongo(initial: Record<string, Document[]> = {}) {
  const collections = new Map(Object.entries(initial).map(([key, rows]) => [key, new Map(rows.map((r) => [String(r._id), r]))]));
  const writes: string[] = [];
  const db = {
    databaseName: "test-pocketpeek-migration",
    client: new MongoClient("mongodb://127.0.0.1:27017"), // Never connected.
    listCollections() { return { toArray: async () => [...collections.keys()].map((name) => ({ name })) }; },
    collection(name: string) {
      return {
        find() {
          const values = [...(collections.get(name)?.values() ?? [])];
          return { toArray: async () => values, async *[Symbol.asyncIterator]() { yield* values; } };
        },
        async findOne(query: { _id: string }) { return collections.get(name)?.get(query._id) ?? null; },
        async insertOne(value: Document) {
          if (collections.get(name)?.has(String(value._id))) throw Object.assign(new Error("duplicate"), { code: 11000 });
          if (!collections.has(name)) collections.set(name, new Map());
          collections.get(name)!.set(String(value._id), value); writes.push(name);
        },
      };
    },
  };
  return { db: db as unknown as Db, collections, writes };
}

function fakeSource(path: string, data: Record<string, unknown>) {
  const leaf = { path, get: async () => ({ exists: true, data: () => data, createTime: undefined, updateTime: undefined }), listCollections: async () => [] };
  // A missing users/u parent still has its nested orphan record.
  const parent = { path: "users/u", get: async () => ({ exists: false }), listCollections: async () => [{ listDocuments: async () => [leaf] }] };
  return { listCollections: async () => [{ listDocuments: async () => [parent] }] } as unknown as Firestore;
}

/** Hierarchical fake with delayed network-shaped calls, including ancestors with no stored document. */
function concurrentSource(records: [string, Record<string, unknown>][], reverse = false, shared = { active: 0, max: 0 }) {
  const values = new Map(records), docPaths = new Set<string>(), collectionPaths = new Set<string>();
  for (const [path] of records) {
    const parts = path.split("/");
    for (let length = 1; length <= parts.length; length++) {
      (length % 2 ? collectionPaths : docPaths).add(parts.slice(0, length).join("/"));
    }
  }
  const stats = { active: 0, max: 0, calls: 0, completions: [] as string[] };
  async function request<T>(key: string, value: T): Promise<T> {
    stats.active++; stats.calls++; stats.max = Math.max(stats.max, stats.active);
    shared.active++; shared.max = Math.max(shared.max, shared.active);
    try {
      // Differing latency makes completion order differ from path order and between the two sources.
      const delay = (key.length + key.charCodeAt(key.length - 1) + (reverse ? 1 : 0)) % 4 + 1;
      await new Promise((resolve) => setTimeout(resolve, delay));
      stats.completions.push(key); return value;
    } finally { stats.active--; shared.active--; }
  }
  const order = <T,>(items: T[]) => reverse ? [...items].reverse() : items;
  const parent = (path: string) => path.slice(0, path.lastIndexOf("/"));
  const collection = (path: string): object => ({
    listDocuments: () => request(`listDocuments:${path}`, order([...docPaths].filter((doc) => parent(doc) === path)).map(document)),
  });
  const document = (path: string): object => ({
    path,
    get: () => request(`get:${path}`, { exists: values.has(path), data: () => values.get(path), createTime: values.has(path) ? new Timestamp(1700000000, 123000000) : undefined, updateTime: values.has(path) ? new Timestamp(1700000001, 456000000) : undefined }),
    listCollections: () => request(`listCollections:${path}`, order([...collectionPaths].filter((child) => parent(child) === path)).map(collection)),
  });
  const source = { listCollections: () => request("rootCollections", order([...collectionPaths].filter((path) => !path.includes("/"))).map(collection)) } as unknown as Firestore;
  return { source, stats };
}

describe("lossless Firestore snapshot", () => {
  it("preserves exact timestamps, special numbers, binary and map-key ordering", () => {
    expect(encodeValue({ z: new Timestamp(1700000000, 123456789), zero: -0, bytes: Buffer.from([0, 255]), n: NaN, yes: true }))
      .toEqual(["map", [["bytes", ["bytes", "AP8="]], ["n", ["number", "NaN"]], ["yes", ["boolean", true]], ["z", ["timestamp", "1700000000", 123456789]], ["zero", ["number", "-0"]]]]);
    expect(encodeValue({ point: new GeoPoint(16.8, 96.1), big: BigInt("9223372036854775807") })).toEqual(["map", [["big", ["integer", "9223372036854775807"]], ["point", ["geo", 16.8, 96.1]]]]);
  });
  it("fails instead of rounding an unsafe integer or silently dropping unsupported data", () => {
    expect(() => encodeValue(Number.MAX_SAFE_INTEGER + 1)).toThrow("exact range");
    expect(() => encodeValue({ bad: undefined })).toThrow("unsupported");
    expect(() => encodeValue(new Map())).toThrow("unsupported");
  });
  it("binds the digest to source project, document versions and every data value", () => {
    const s = snapshot(row("users/u", { password: "already-hashed", total: 123 }));
    expect(() => validateSnapshot(s, project)).not.toThrow();
    expect(() => validateSnapshot(s, "different-project")).toThrow();
    expect(() => validateSnapshot({ ...s, documents: [row("users/u", { total: 124 })] }, project)).toThrow("checksum");
    expect(() => snapshot(row("users/u", {}), row("users/u", {}))).toThrow("unique");
  });
  it("traverses descendants of missing parent documents", async () => {
    const s = await captureSource(fakeSource("users/u/future/nested", { kept: true }), project);
    expect(s.documents.map((d) => d.path)).toEqual(["users/u/future/nested"]);
  });
  it("bounds all recursive source requests at eight while walking nested and missing-parent records concurrently", async () => {
    const records: [string, Record<string, unknown>][] = [
      ...Array.from({ length: 20 }, (_, i): [string, Record<string, unknown>] => [`users/u${i}/transactions/t${i}`, { amount: i + 1 }]),
      ["users/u1", { email: "synthetic@example.com" }],
      ["users/u2/future/missing/more/leaf", { preserve: "deep" }],
      ["legacy/missing/children/kept", { preserve: "orphan" }],
      ["categories/a", { name: "Food" }],
    ];
    const fake = concurrentSource(records);
    const captured = await captureSource(fake.source, project);
    expect(captured.documents.map((d) => d.path)).toEqual(records.map(([path]) => path).sort());
    expect(captured.documents).toHaveLength(records.length);
    expect(fake.stats.max).toBeGreaterThan(1); expect(fake.stats.max).toBeLessThanOrEqual(8);
    expect(fake.stats.active).toBe(0);
    // Only existing records are captured; missing ancestors were traversed without inventing new documents.
    expect(captured.documents.some((d) => d.path === "users/u0" || d.path === "legacy/missing")).toBe(false);
  });
  it("keeps stable fingerprints despite reversed traversal and out-of-order completion", async () => {
    const records: [string, Record<string, unknown>][] = Array.from({ length: 14 }, (_, i) => [`users/u${i}/future/deep/children/item`, { value: i, exact: new Timestamp(1, i) }]);
    const shared = { active: 0, max: 0 };
    const first = concurrentSource(records, false, shared), second = concurrentSource(records, true, shared);
    // Concurrent captures share the same global eight-request budget, not eight per traversal branch.
    const [a, b] = await Promise.all([captureSource(first.source, project), captureSource(second.source, project)]);
    expect(a.sha256).toBe(b.sha256); expect(a.documents).toEqual(b.documents);
    expect(first.stats.completions).not.toEqual(second.stats.completions);
    expect(first.stats.max).toBeGreaterThan(1); expect(second.stats.max).toBeGreaterThan(1);
    expect(shared.max).toBeGreaterThan(1); expect(shared.max).toBeLessThanOrEqual(8); expect(shared.active).toBe(0);
  });
  it("creates a private immutable file and rejects public permissions", async () => {
    const dir = await mkdtemp(join(tmpdir(), "pocketpeek-migration-")), file = join(dir, "snapshot.json");
    const s = snapshot(row("users/u", { password: "already-hashed" }));
    try {
      await writeSnapshot(file, s);
      expect((await stat(file)).mode & 0o777).toBe(0o600);
      expect((await readSnapshot(file, project)).sha256).toBe(s.sha256);
      await expect(writeSnapshot(file, s)).rejects.toThrow();
      await chmod(file, 0o644); await expect(readSnapshot(file, project)).rejects.toThrow("owner-only");
    } finally { await rm(dir, { recursive: true, force: true }); }
  });
  it("reports per-user hashes without email, password or financial field values", () => {
    const s = snapshot(row("users/private-user", { email: "secret@example.com", password: "private-hash" }), row("users/private-user/transactions/t", { amount: 987654321 }));
    const report = JSON.stringify(summarize(s));
    for (const secret of ["private-user", "secret@example.com", "private-hash", "987654321"]) expect(report).not.toContain(secret);
    expect(Object.values(summarize(s).users)[0].count).toBe(2);
  });
});

describe("native Mongo mapping", () => {
  it("keeps users, hashes, API leaf identities and both owners' same month IDs", () => {
    const plan = planMigration(snapshot(row("users/u", { password: "bcrypt-value", emailLower: "a@example.com" }), row("users/u/budgetMonths/2026-11", { newMoney: 300 }), row("users/v/budgetMonths/2026-11", { newMoney: 400 })));
    expect(plan.find((p) => p.collection === "users")!.document).toEqual({ _id: "u", password: "bcrypt-value", emailLower: "a@example.com" });
    expect(plan.filter((p) => p.collection === "budgetMonths").map((p) => p.document._id)).toEqual(["u/2026-11", "v/2026-11"]);
  });
  it("preserves sub-millisecond timestamp metadata while supplying native dates", () => {
    const t = new Timestamp(1700000000, 123456789);
    const plan = planMigration(snapshot(row("users/u/transactions/t", { updatedAt: t, nested: { time: t }, amount: 123 })));
    const data = plan.find((p) => p.collection === "transactions")!.document;
    expect(data.updatedAt).toEqual(new Date(1700000000123));
    const exact = plan.find((p) => p.collection === "_firestoreTypes")!.document;
    expect(exact.ownerId).toBe("u");
    expect(exact.fields).toContainEqual([["nested", "time"], ["timestamp", "1700000000", 123456789]]);
  });
  it("matches Firestore Date conversion at pre-epoch sub-millisecond boundaries", () => {
    for (const value of [new Timestamp(-1, 999999999), new Timestamp(-1, 1), new Timestamp(0, 999999)]) {
      const plan = planMigration(snapshot(row("users/u", { createdAt: value })));
      expect(plan.find((p) => p.collection === "users")!.document.createdAt).toEqual(value.toDate());
      expect(plan.find((p) => p.collection === "_firestoreTypes")!.document.fields).toContainEqual([["createdAt"], ["timestamp", String(value.seconds), value.nanoseconds]]);
    }
  });
  it("keeps unknown data in a lossless archive with deletion ownership", () => {
    const source = row("users/u/future/doc/children/leaf", { value: new Timestamp(1, 1), arr: [null, "x"] });
    const archive = planMigration(snapshot(source))[0];
    expect(archive.collection).toBe("_firestoreArchive");
    expect(archive.document).toMatchObject({ _id: source.path, sourcePath: source.path, ownerId: "u", data: source.data });
    expect(planMigration(snapshot(row("oldTransactions/t", { userId: "u", amount: 55 })))[0].document.ownerId).toBe("u");
  });
  it("archives unknown collection names that collide with Object.prototype keys", () => {
    for (const name of ["constructor", "toString", "__proto__"]) {
      const source = row(`users/u/${name}/kept`, { amount: 123, nested: { preserved: true } });
      const planned = planMigration(snapshot(source));
      expect(planned).toHaveLength(1);
      expect(planned[0]).toEqual({ collection: "_firestoreArchive", document: { _id: source.path, sourcePath: source.path, ownerId: "u", data: source.data, createTime: null, updateTime: null } });
    }
  });
  it("attributes exact metadata on user roots and email claims to their actual owner", () => {
    const t = new Timestamp(1, 1);
    const plan = planMigration(snapshot(row("users/u", { createdAt: t }), row("userEmails/hash", { userId: "u", createdAt: t })));
    expect(plan.filter((p) => p.collection === "_firestoreTypes").every((p) => p.document.ownerId === "u")).toBe(true);
  });
  it("rejects reserved field collisions and mismatched ownership", () => {
    expect(() => planMigration(snapshot(row("users/u", { _id: "overwritten" })))).toThrow("reserved");
    expect(() => planMigration(snapshot(row("users/u/transactions/t", { userId: "v" })))).toThrow("ownership");
  });
  it("canonical BSON comparisons ignore key order but retain integer types and binary", () => {
    expect(canonicalBson({ a: Long.fromString("9223372036854775807"), b: Buffer.from([1]) })).toBe(canonicalBson({ b: Buffer.from([1]), a: Long.fromString("9223372036854775807") }));
    expect(canonicalBson({ a: Long.fromString("1") })).not.toBe(canonicalBson({ a: 1 }));
  });
});

describe("create-only migration verification", () => {
  const s = snapshot(row("users/u", { password: "bcrypt-value" }), row("users/u/transactions/t", { userId: "u", amount: 55 }));
  const plan = planMigration(s);
  it("imports into an empty database and permits exact idempotent resumes only", async () => {
    const mongo = fakeMongo();
    expect(await importSnapshot(mongo.db, s, plan)).toEqual({ inserted: 2, matching: 2 });
    expect(await importSnapshot(mongo.db, s, plan)).toEqual({ inserted: 0, matching: 2 });
    expect(mongo.writes).toEqual(["_migrationControl", "transactions", "users"]);
    expect((await inspectDestination(mongo.db, plan, s)).missing).toHaveLength(0);
  });
  it("rejects conflicts and extra rows before writing anything", async () => {
    const cases: Record<string, Document[]>[] = [{ users: [{ _id: "u", password: "different" }] }, { unrelated: [{ _id: "extra", value: 1 }] }];
    for (const initial of cases) {
      const mongo = fakeMongo(initial); await expect(importSnapshot(mongo.db, s, plan)).rejects.toThrow("conflicting"); expect(mongo.writes).toEqual([]);
    }
  });
  it("will not adopt matching documents without its own immutable marker", async () => {
    const mongo = fakeMongo({ users: [plan.find((p) => p.collection === "users")!.document] });
    await expect(importSnapshot(mongo.db, s, plan)).rejects.toThrow("no matching migration marker");
    expect(mongo.writes).toEqual([]);
  });
  it("rejects a marker from another snapshot or database", async () => {
    const mongo = fakeMongo({ _migrationControl: [{ _id: "firestore-to-mongo-v1", snapshotSha256: "wrong", sourceProjectId: project, destinationDb: "test-pocketpeek-migration" }] });
    await expect(inspectDestination(mongo.db, plan, s)).rejects.toThrow("different migration");
  });
  it("rejects source drift, even though the destination could already be exact", async () => {
    const source = fakeSource("users/u/transactions/t", { amount: 55 });
    const captured = await captureSource(source, project);
    await expect(assertSourceUnchanged(source, captured)).resolves.toBeUndefined();
    await expect(assertSourceUnchanged(fakeSource("users/u/transactions/t", { amount: 56 }), captured)).rejects.toThrow("changed since");
  });
  it("has deterministic destination and per-user fingerprints", () => {
    expect(planSummary(plan)).toEqual(planSummary(planMigration(s)));
    expect(Object.values(planSummary(plan).users)[0].count).toBe(2);
  });
});
