import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Firestore } from "firebase-admin/firestore";
import type { Db } from "mongodb";
import { encodeValue, makeSnapshot } from "../../scripts/firestoreSnapshot";
import { planMigration, planSummary } from "../../scripts/migrateFirestoreToMongo";
import { assertNoTtlIndexes, liveImportArgs, runLiveImport, verifyLiveDestination, verifyRuntimeIndexes, type LiveImportDependencies, type LiveImportFlags } from "../../scripts/importFirestoreLiveToMongo";

const project = "demo-pocketpeek-live-import", databaseName = "pocketpeek_migration_test";
const snapshot = makeSnapshot(project, [{ path: "users/u", data: encodeValue({ password: "synthetic-hash", emailLower: "synthetic@example.com" }), createTime: null, updateTime: null }]);
const flags: LiveImportFlags = { "expect-source-project": project, "expect-source-sha256": snapshot.sha256, "mongo-db": databaseName, "public-key": "recipient-public.pem", "encrypted-output": "opaque-encrypted.json" };
const args = (value: object) => Object.entries(value).flatMap(([key, text]) => [`--${key}`, String(text)]);

function dependencies() {
  const events: string[] = [], sourceWrites = { set: vi.fn(), update: vi.fn(), delete: vi.fn() };
  const source = { ...sourceWrites, terminate: async () => { events.push("terminate-source"); } } as unknown as Firestore;
  const database = { databaseName } as Db;
  const deps: LiveImportDependencies = {
    source: () => { events.push("configure-source"); return { source, projectId: project }; },
    publicKey: async () => { events.push("public-key"); return Buffer.from("synthetic-public-key"); },
    stableSource: async () => { events.push("stable-source"); return snapshot; },
    encryptedBackup: async () => { events.push("encrypted-backup"); },
    connectMongo: async () => { events.push("connect-target"); return { database, close: async () => { events.push("close-target"); } }; },
    sourceUnchanged: async () => { events.push("source-check"); },
    noTtl: async () => { events.push("no-ttl"); },
    importRecords: async () => { events.push("import-records"); return { inserted: 1, matching: 1 }; },
    verifyRecords: async (_db, plan) => { events.push("verify-records"); return planSummary(plan); },
    createIndexes: async () => { events.push("create-indexes"); },
    verifyIndexes: async () => { events.push("verify-indexes"); return { verifiedIndexes: 11 }; },
  };
  return { deps, events, sourceWrites };
}

beforeEach(() => {
  vi.stubEnv("MONGODB_URI", "mongodb://127.0.0.1:1/never-connect-in-pure-test");
  vi.stubEnv("MONGODB_DB", databaseName);
});
afterEach(() => vi.unstubAllEnvs());

describe("live source import target and fingerprint guards", () => {
  it("requires every explicit guard and rejects duplicates, weak fingerprints, system targets and same-file paths", () => {
    expect(liveImportArgs(args(flags))).toEqual(flags);
    expect(() => liveImportArgs(args({ ...flags, "expect-source-sha256": "wrong" }))).toThrow("reviewed");
    expect(() => liveImportArgs(args({ ...flags, "mongo-db": "admin" }))).toThrow("non-system");
    expect(() => liveImportArgs(args({ ...flags, "encrypted-output": flags["public-key"] }))).toThrow("different");
    expect(() => liveImportArgs(args(flags).slice(0, -2))).toThrow("requires");
    expect(() => liveImportArgs([...args(flags), "--mongo-db", "another"])).toThrow("exactly once");
  });
  it("rejects an environment database mismatch before touching either source or target", async () => {
    const { deps, events } = dependencies(); vi.stubEnv("MONGODB_DB", "wrong_database");
    await expect(runLiveImport(flags, deps)).rejects.toThrow("exactly match"); expect(events).toEqual([]);
  });
  it("encrypts a durable backup before target writes, brackets import with source checks, and creates indexes after parity", async () => {
    const { deps, events, sourceWrites } = dependencies();
    const receipt = await runLiveImport(flags, deps);
    expect(events).toEqual(["public-key", "configure-source", "stable-source", "encrypted-backup", "connect-target", "no-ttl", "source-check", "import-records", "source-check", "verify-records", "create-indexes", "verify-indexes", "verify-records", "source-check", "close-target", "terminate-source"]);
    for (const write of Object.values(sourceWrites)) expect(write).not.toHaveBeenCalled();
    expect(receipt).toMatchObject({ sourceWrites: false, plaintextFileWritten: false, sourceUnchanged: true, sourceWriteFreezeVerified: false, verified: true });
    expect(JSON.stringify(receipt)).not.toContain("synthetic@example.com"); expect(JSON.stringify(receipt)).not.toContain("synthetic-hash");
  });
  it("does not connect or write a target if the final source digest or identity changed", async () => {
    for (const mismatch of ["identity", "fingerprint"]) {
      const { deps, events } = dependencies();
      if (mismatch === "identity") deps.source = () => ({ source: { terminate: async () => { events.push("terminate-source"); } } as unknown as Firestore, projectId: "another-project" });
      else deps.stableSource = async () => ({ ...snapshot, sha256: "0".repeat(64) });
      await expect(runLiveImport(flags, deps)).rejects.toThrow();
      expect(events).not.toContain("connect-target"); expect(events).not.toContain("encrypted-backup"); expect(events).toContain("terminate-source");
    }
  });
  it("does not connect a target when encrypted backup creation fails", async () => {
    const { deps, events } = dependencies(); deps.encryptedBackup = async () => { throw new Error("synthetic output failure"); };
    await expect(runLiveImport(flags, deps)).rejects.toThrow("synthetic output failure");
    expect(events).not.toContain("connect-target"); expect(events).toContain("terminate-source");
  });
  it("refuses unexpected connected database or TTL policy without inserting records", async () => {
    for (const mismatch of ["target", "ttl"]) {
      const { deps, events } = dependencies();
      if (mismatch === "target") deps.connectMongo = async () => ({ database: { databaseName: "unexpected" } as Db, close: async () => { events.push("close-target"); } });
      else deps.noTtl = async () => { throw new Error("synthetic TTL policy"); };
      await expect(runLiveImport(flags, deps)).rejects.toThrow();
      expect(events).not.toContain("import-records"); expect(events).toContain("close-target"); expect(events).toContain("terminate-source");
    }
  });
  it("does not create indexes or report success after import/source/parity failure", async () => {
    for (const failure of ["import", "post-source", "parity"]) {
      const { deps, events } = dependencies();
      if (failure === "import") deps.importRecords = async () => { throw new Error("synthetic import collision"); };
      if (failure === "post-source") { let count = 0; deps.sourceUnchanged = async () => { if (++count === 2) throw new Error("synthetic source drift"); }; }
      if (failure === "parity") deps.verifyRecords = async () => { throw new Error("synthetic parity failure"); };
      await expect(runLiveImport(flags, deps)).rejects.toThrow();
      expect(events).not.toContain("create-indexes"); expect(events).toContain("close-target"); expect(events).toContain("terminate-source");
    }
  });
  it("does not report success after final index or final source verification fails", async () => {
    for (const failure of ["index", "last-source"]) {
      const { deps, events } = dependencies();
      if (failure === "index") deps.verifyIndexes = async () => { throw new Error("synthetic index mismatch"); };
      else { let count = 0; deps.sourceUnchanged = async () => { if (++count === 3) throw new Error("synthetic late source drift"); }; }
      await expect(runLiveImport(flags, deps)).rejects.toThrow();
      expect(events).toContain("close-target"); expect(events).toContain("terminate-source");
    }
  });
});

function indexDatabase(change?: (indexes: Record<string, Record<string, unknown>[]>) => void): Db {
  const indexes: Record<string, Record<string, unknown>[]> = {
    users: [{ key: { emailLower: 1 }, unique: true, partialFilterExpression: { emailLower: { $type: "string" } } }],
    userEmails: [{ key: { userId: 1 } }],
    transactions: [{ key: { userId: 1, clientId: 1 }, unique: true, partialFilterExpression: { clientId: { $type: "string" } } }, { key: { userId: 1, updatedAt: 1 } }],
    budgets: [{ key: { userId: 1 } }], budgetMonths: [{ key: { userId: 1 } }, { key: { userId: 1, month: 1 } }], userCategories: [{ key: { userId: 1 } }],
    _firestoreArchive: [{ key: { ownerId: 1 } }], _firestoreTypes: [{ key: { ownerId: 1 } }], rateLimits: [{ key: { expiresAt: 1 } }],
  };
  change?.(indexes);
  return { listCollections: () => ({ toArray: async () => Object.keys(indexes).map((name) => ({ name })) }), collection: (name: string) => ({ listIndexes: () => ({ toArray: async () => indexes[name] ?? [] }) }) } as unknown as Db;
}

describe("independent runtime index readback", () => {
  it("verifies all required index keys, owner uniqueness and optional-string partial filters", async () => {
    await expect(verifyRuntimeIndexes(indexDatabase())).resolves.toEqual({ verifiedIndexes: 11 });
  });
  it("rejects missing uniqueness and a partial filter that excludes different data", async () => {
    await expect(verifyRuntimeIndexes(indexDatabase((indexes) => { delete indexes.users[0].unique; }))).rejects.toThrow("contract");
    await expect(verifyRuntimeIndexes(indexDatabase((indexes) => { indexes.transactions[0].partialFilterExpression = { clientId: { $exists: true } }; }))).rejects.toThrow("contract");
  });
  it("refuses any TTL index, including an unrelated extra index", async () => {
    await expect(assertNoTtlIndexes(indexDatabase((indexes) => { indexes.extra = [{ key: { createdAt: 1 }, expireAfterSeconds: 0 }]; }))).rejects.toThrow("TTL");
  });
});

describe("independent live destination fingerprint", () => {
  function destination(mutate?: (records: Record<string, Record<string, unknown>[]>) => void, drift = false): Db {
    const plan = planMigration(snapshot), records: Record<string, Record<string, unknown>[]> = {
      users: plan.filter((row) => row.collection === "users").map((row) => row.document),
      _migrationControl: [{ _id: "firestore-to-mongo-v1", snapshotSha256: snapshot.sha256, sourceProjectId: project, destinationDb: databaseName }],
    };
    mutate?.(records);
    let userReads = 0;
    return {
      databaseName,
      listCollections: () => ({ toArray: async () => Object.keys(records).map((name) => ({ name })) }),
      collection: (name: string) => ({
        find: () => {
          const values = drift && name === "users" && ++userReads > 1 ? [{ ...records.users[0], password: "changed-between-scans" }] : records[name] ?? [];
          return { toArray: async () => values, async *[Symbol.asyncIterator]() { yield* values; } };
        },
      }),
    } as unknown as Db;
  }
  it("computes the expected fingerprint from actual readback records", async () => {
    const plan = planMigration(snapshot);
    await expect(verifyLiveDestination(destination(), plan, snapshot)).resolves.toEqual(planSummary(plan));
  });
  it("refuses extra or conflicting records instead of reporting the planned hash as actual", async () => {
    const plan = planMigration(snapshot);
    await expect(verifyLiveDestination(destination((records) => { records.unexpected = [{ _id: "extra" }]; }), plan, snapshot)).rejects.toThrow("conflicting");
    await expect(verifyLiveDestination(destination((records) => { records.users[0] = { ...records.users[0], password: "different" }; }), plan, snapshot)).rejects.toThrow("conflicting");
  });
  it("detects changes between the exact inspection and the independent hashing read", async () => {
    await expect(verifyLiveDestination(destination(undefined, true), planMigration(snapshot), snapshot)).rejects.toThrow("changed during");
  });
});
