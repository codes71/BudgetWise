import { describe, expect, it, vi } from "vitest";
import { MongoClient, type Db } from "mongodb";
import type { Firestore } from "firebase-admin/firestore";
import { assertSyntheticFirestoreTarget, assertSyntheticMongoTarget } from "../../scripts/recoveryWriteSafety";
import { importSnapshot, planMigration } from "../../scripts/migrateFirestoreToMongo";
import { runLiveImport, type LiveImportFlags } from "../../scripts/importFirestoreLiveToMongo";
import { copyMongoToFirestore } from "../../scripts/copyMongoToFirestore";
import { encodeValue, makeSnapshot } from "../../scripts/firestoreSnapshot";

const target = (uri = "mongodb://127.0.0.1:27017", databaseName = "test-pocketpeek-restore"): Db => ({
  databaseName, client: new MongoClient(uri), // Options only, never connected.
}) as Db;
const firestore = (projectId = "demo-pocketpeek-restore", host = "127.0.0.1:8085", ssl = false): Firestore => ({
  projectId, _settings: { servicePath: host.split(":")[0], port: Number(host.split(":")[1]), ssl },
}) as unknown as Firestore;

describe("historical recovery writes fail closed", () => {
  it("allows only isolated Mongo fixture databases with entirely loopback transports", () => {
    expect(() => assertSyntheticMongoTarget(target())).not.toThrow();
    for (const db of [target(undefined, "pocketpeek"), target("mongodb://db.example.com:27017"),
      target("mongodb://127.0.0.1:27017,db.example.com:27017"), target("mongodb+srv://cluster.example.com"),
      { databaseName: "test-pocketpeek-restore" } as Db]) {
      expect(() => assertSyntheticMongoTarget(db)).toThrow("Historical production imports are disabled");
    }
  });

  it("requires the actual Firestore client to use a demo project and insecure loopback emulator", () => {
    expect(() => assertSyntheticFirestoreTarget(firestore())).not.toThrow();
    for (const db of [firestore("pocketpeek-production"), firestore(undefined, "firestore.googleapis.com"),
      firestore(undefined, undefined, true), { projectId: "demo-pocketpeek-restore" } as unknown as Firestore]) {
      expect(() => assertSyntheticFirestoreTarget(db)).toThrow("Historical production imports are disabled");
    }
  });

  it("rejects a historical production snapshot even when its Mongo destination is a local fixture", async () => {
    const snapshot = makeSnapshot("pocketpeek-production", [{
      path: "users/previously-deleted", data: encodeValue({ email: "synthetic@example.com" }), createTime: null, updateTime: null,
    }]);
    const db = target();
    const read = vi.fn();
    Object.assign(db, { listCollections: read });
    await expect(importSnapshot(db, snapshot, planMigration(snapshot))).rejects.toThrow("deletion journal");
    expect(read).not.toHaveBeenCalled();
  });

  it("rejects the real live-import entry point before reading keys, credentials or source records", async () => {
    await expect(runLiveImport({} as LiveImportFlags)).rejects.toThrow("deletion journal");
  });

  it("rejects the obsolete reverse copy before reading either production store", async () => {
    const read = vi.fn();
    const db = target(undefined, "pocketpeek");
    Object.assign(db, { collection: read });
    await expect(copyMongoToFirestore(db, firestore(), { write: true })).rejects.toThrow("deletion journal");
    expect(read).not.toHaveBeenCalled();
  });
});
