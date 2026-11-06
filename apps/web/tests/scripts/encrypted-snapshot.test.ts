import { beforeAll, describe, expect, it } from "vitest";
import { mkdtemp, readFile, chmod, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { generateKeyPairSync } from "node:crypto";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { encodeValue, makeSnapshot } from "../../scripts/firestoreSnapshot";
import { captureStableSource, encryptedExportArgs } from "../../scripts/exportFirestoreEncrypted";
import { main as decryptMain } from "../../scripts/decryptFirestoreSnapshot";
import {
  decryptSnapshot, encryptSnapshot, generateSnapshotKeyPair, readEncryptedSnapshot,
  readPrivateSnapshotKey, validateEnvelope, writeEncryptedSnapshot, writeSnapshotKeyPair,
} from "../../scripts/snapshotEncryption";

const project = "demo-pocketpeek-encrypted";
const snapshot = makeSnapshot(project, [{ path: "users/private-user", data: encodeValue({ email: "private@example.com", password: "never-log-this-hash", amount: 123456789, exact: new Timestamp(1700000000, 123456789) }), createTime: null, updateTime: null }]);
let pair: ReturnType<typeof generateSnapshotKeyPair>;
beforeAll(() => { pair = generateSnapshotKeyPair(); }, 30000);

describe("authenticated encrypted snapshots", () => {
  it("round-trips exact tagged values without exposing plaintext in the envelope", () => {
    const envelope = encryptSnapshot(snapshot, pair.publicKey), serialized = JSON.stringify(envelope);
    for (const secret of ["private-user", "private@example.com", "never-log-this-hash", "123456789"]) expect(serialized).not.toContain(secret);
    expect(decryptSnapshot(envelope, pair.privateKey, project, snapshot.sha256)).toEqual(snapshot);
    expect(envelope.recipientSha256).toBe(pair.recipientSha256);
  });
  it("uses fresh AES keys and nonces for repeated exports", () => {
    const first = encryptSnapshot(snapshot, pair.publicKey), second = encryptSnapshot(snapshot, pair.publicKey);
    expect(first.iv).not.toBe(second.iv); expect(first.encryptedKey).not.toBe(second.encryptedKey); expect(first.ciphertext).not.toBe(second.ciphertext);
    expect(decryptSnapshot(first, pair.privateKey, project, snapshot.sha256).sha256).toBe(snapshot.sha256);
  });
  it("rejects a modified ciphertext, tag, nonce or wrapped key", () => {
    const envelope = encryptSnapshot(snapshot, pair.publicKey);
    for (const field of ["ciphertext", "tag", "iv", "encryptedKey"] as const) {
      const bytes = Buffer.from(envelope[field], "base64"); bytes[0] ^= 1;
      expect(() => decryptSnapshot({ ...envelope, [field]: bytes.toString("base64") }, pair.privateKey, project, snapshot.sha256)).toThrow();
    }
  });
  it("rejects metadata changes, extra fields, malformed encoding and wrong expected identity", () => {
    const envelope = encryptSnapshot(snapshot, pair.publicKey);
    expect(() => decryptSnapshot({ ...envelope, version: 2 }, pair.privateKey, project, snapshot.sha256)).toThrow("format");
    expect(() => decryptSnapshot({ ...envelope, recipientSha256: "0".repeat(64) }, pair.privateKey, project, snapshot.sha256)).toThrow("different local key");
    expect(() => validateEnvelope({ ...envelope, plaintext: "injected" })).toThrow("format");
    expect(() => validateEnvelope({ ...envelope, ciphertext: envelope.ciphertext + "!" })).toThrow("encoding");
    expect(() => decryptSnapshot(envelope, pair.privateKey, "different-project", snapshot.sha256)).toThrow("source project");
    expect(() => decryptSnapshot(envelope, pair.privateKey, project, "0".repeat(64))).toThrow("fingerprint");
  });
  it("refuses private-key material on the exporter and weaker RSA recipient keys", () => {
    expect(() => encryptSnapshot(snapshot, pair.privateKey)).toThrow("public key");
    const weak = generateKeyPairSync("rsa", { modulusLength: 2048, publicKeyEncoding: { format: "pem", type: "spki" }, privateKeyEncoding: { format: "pem", type: "pkcs8" } });
    expect(() => encryptSnapshot(snapshot, weak.publicKey)).toThrow("4096");
  });
  it("writes only a private immutable ciphertext envelope", async () => {
    const directory = await mkdtemp(join(tmpdir(), "encrypted-ppeek-")), path = join(directory, "opaque.json");
    try {
      const envelope = encryptSnapshot(snapshot, pair.publicKey); await writeEncryptedSnapshot(path, envelope);
      expect((await stat(path)).mode & 0o777).toBe(0o600);
      expect(await readEncryptedSnapshot(path)).toEqual(envelope);
      await expect(writeEncryptedSnapshot(path, envelope)).rejects.toThrow();
      expect((await readFile(path, "utf8"))).not.toContain("private@example.com");
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  it("creates owner-only local keys without overwriting earlier key material", async () => {
    const directory = await mkdtemp(join(tmpdir(), "keys-ppeek-")), privatePath = join(directory, "private.pem"), publicPath = join(directory, "public.pem");
    try {
      const result = await writeSnapshotKeyPair(privatePath, publicPath);
      expect(result.recipientSha256).toMatch(/^[a-f0-9]{64}$/);
      expect((await stat(privatePath)).mode & 0o777).toBe(0o600);
      await readPrivateSnapshotKey(privatePath);
      const before = await readFile(privatePath, "utf8");
      await expect(writeSnapshotKeyPair(privatePath, publicPath)).rejects.toThrow();
      expect(await readFile(privatePath, "utf8")).toBe(before);
      await chmod(privatePath, 0o644); await expect(readPrivateSnapshotKey(privatePath)).rejects.toThrow("owner-only");
    } finally { await rm(directory, { recursive: true, force: true }); }
  }, 30000);
  it("never runs local key operations inside Vercel", async () => {
    const before = process.env.VERCEL; process.env.VERCEL = "1";
    try { await expect(decryptMain(["--mode", "keygen", "--private-key", "unused", "--public-key", "unused-public"])).rejects.toThrow("remain local"); }
    finally { if (before === undefined) delete process.env.VERCEL; else process.env.VERCEL = before; }
  });
});

describe("server-side capture guards", () => {
  it("requires explicit output and public key paths and validates optional source fingerprint", () => {
    expect(encryptedExportArgs(["--public-key", "recipient.pem", "--output", "opaque.json"])).toMatchObject({ "public-key": "recipient.pem", output: "opaque.json" });
    expect(() => encryptedExportArgs(["--public-key", "same", "--output", "same"])).toThrow("different");
    expect(() => encryptedExportArgs(["--public-key", "key", "--output", "opaque", "--expect-source-sha256", "bad"])).toThrow("SHA-256");
    expect(() => encryptedExportArgs(["--output", "opaque", "--output", "again"])).toThrow("unique");
  });
  it("rejects source changes between scans and optional expected-fingerprint drift", async () => {
    let calls = 0;
    const source = {
      listCollections: async () => [{ listDocuments: async () => [{ path: "users/u", get: async () => ({ exists: true, data: () => ({ changing: calls++ }), createTime: undefined, updateTime: undefined }), listCollections: async () => [] }] }],
    } as unknown as Firestore;
    await expect(captureStableSource(source, project)).rejects.toThrow("changed during");
    const stable = { listCollections: async () => [] } as unknown as Firestore;
    const captured = await captureStableSource(stable, project);
    await expect(captureStableSource(stable, project, captured.sha256)).resolves.toMatchObject({ sha256: captured.sha256 });
    await expect(captureStableSource(stable, project, "0".repeat(64))).rejects.toThrow("expected source fingerprint");
  });
});
