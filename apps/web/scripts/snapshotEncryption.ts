import {
  constants as cryptoConstants, createCipheriv, createDecipheriv, createHash, createPrivateKey,
  createPublicKey, generateKeyPairSync, privateDecrypt, publicEncrypt, randomBytes, type KeyObject,
} from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { open } from "node:fs/promises";
import { MigrationError, validateSnapshot, type FirestoreSnapshot } from "./firestoreSnapshot";

const FORMAT = "pocket-peek-encrypted-firestore-snapshot";
const CIPHER = "AES-256-GCM";
const KEY_ALGORITHM = "RSA-OAEP-SHA256";
const MAX_ENVELOPE_BYTES = 256 * 1024 * 1024;
const MAX_PEM_BYTES = 32 * 1024;

export interface EncryptedSnapshot {
  format: typeof FORMAT;
  version: 1;
  cipher: typeof CIPHER;
  keyAlgorithm: typeof KEY_ALGORITHM;
  recipientSha256: string;
  encryptedKey: string;
  iv: string;
  tag: string;
  ciphertext: string;
}

function recipientDigest(key: KeyObject): string {
  return createHash("sha256").update(key.export({ type: "spki", format: "der" })).digest("hex");
}

function validateRsaKey(key: KeyObject): void {
  if (key.asymmetricKeyType !== "rsa" || key.asymmetricKeyDetails?.modulusLength !== 4096) {
    throw new MigrationError("Use a dedicated RSA 4096-bit snapshot key pair.");
  }
}

function loadPublicKey(pem: string | Buffer): KeyObject {
  if (Buffer.byteLength(pem) > MAX_PEM_BYTES || !pem.toString().includes("-----BEGIN PUBLIC KEY-----") || pem.toString().includes("PRIVATE KEY")) throw new MigrationError("Exporter requires only an SPKI public key, never a private key.");
  try { const key = createPublicKey(pem); validateRsaKey(key); return key; }
  catch { throw new MigrationError("Snapshot recipient public key is invalid or is not RSA 4096-bit."); }
}

function loadPrivateKey(pem: string | Buffer): KeyObject {
  if (Buffer.byteLength(pem) > MAX_PEM_BYTES || !pem.toString().includes("-----BEGIN PRIVATE KEY-----")) throw new MigrationError("Use the local dedicated PKCS8 private key; it must never be deployed.");
  try { const key = createPrivateKey(pem); validateRsaKey(key); return key; }
  catch { throw new MigrationError("Local snapshot private key is invalid or is not RSA 4096-bit."); }
}

function aad(envelope: Pick<EncryptedSnapshot, "format" | "version" | "cipher" | "keyAlgorithm" | "recipientSha256">): Buffer {
  return Buffer.from(JSON.stringify([envelope.format, envelope.version, envelope.cipher, envelope.keyAlgorithm, envelope.recipientSha256]), "utf8");
}

/** Pure: encryption happens in memory. This function never writes or logs plaintext. */
export function encryptSnapshot(snapshot: FirestoreSnapshot, publicKeyPem: string | Buffer): EncryptedSnapshot {
  validateSnapshot(snapshot, snapshot.projectId);
  const publicKey = loadPublicKey(publicKeyPem);
  const key = randomBytes(32), iv = randomBytes(12);
  const header: Pick<EncryptedSnapshot, "format" | "version" | "cipher" | "keyAlgorithm" | "recipientSha256"> = { format: FORMAT, version: 1, cipher: CIPHER, keyAlgorithm: KEY_ALGORITHM, recipientSha256: recipientDigest(publicKey) };
  let plaintext: Buffer | undefined;
  try {
    plaintext = Buffer.from(JSON.stringify(snapshot), "utf8");
    if (plaintext.length > MAX_ENVELOPE_BYTES / 2) throw new MigrationError("Snapshot is too large for this bounded in-memory exporter; no file was written.");
    const cipher = createCipheriv("aes-256-gcm", key, iv);
    cipher.setAAD(aad(header));
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    return {
      ...header,
      encryptedKey: publicEncrypt({ key: publicKey, padding: cryptoConstants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, key).toString("base64"),
      iv: iv.toString("base64"), tag: cipher.getAuthTag().toString("base64"), ciphertext: ciphertext.toString("base64"),
    };
  } finally { key.fill(0); plaintext?.fill(0); }
}

function decodeBase64(value: unknown, expectedBytes?: number): Buffer {
  if (typeof value !== "string" || value.length > MAX_ENVELOPE_BYTES || value.length === 0) throw new MigrationError("Invalid encrypted snapshot encoding.");
  const bytes = Buffer.from(value, "base64");
  if (bytes.toString("base64") !== value || (expectedBytes !== undefined && bytes.length !== expectedBytes)) throw new MigrationError("Invalid encrypted snapshot encoding or field length.");
  return bytes;
}

export function validateEnvelope(value: unknown): asserts value is EncryptedSnapshot {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new MigrationError("Invalid encrypted snapshot envelope.");
  const e = value as EncryptedSnapshot;
  const keys = ["cipher", "ciphertext", "encryptedKey", "format", "iv", "keyAlgorithm", "recipientSha256", "tag", "version"];
  if (Object.keys(e).sort().join("\0") !== keys.join("\0") || e.format !== FORMAT || e.version !== 1 || e.cipher !== CIPHER || e.keyAlgorithm !== KEY_ALGORITHM || typeof e.recipientSha256 !== "string" || !/^[a-f0-9]{64}$/.test(e.recipientSha256)) throw new MigrationError("Encrypted snapshot format or algorithms do not match.");
  decodeBase64(e.encryptedKey, 512); decodeBase64(e.iv, 12); decodeBase64(e.tag, 16); decodeBase64(e.ciphertext);
}

/** Pure: authenticate, decrypt and validate in memory before the caller may write a private snapshot. */
export function decryptSnapshot(
  envelope: unknown, privateKeyPem: string | Buffer, expectedProject: string, expectedSnapshotSha256: string,
): FirestoreSnapshot {
  validateEnvelope(envelope);
  if (!/^[a-f0-9]{64}$/.test(expectedSnapshotSha256)) throw new MigrationError("An independently reviewed source snapshot SHA-256 is required.");
  const privateKey = loadPrivateKey(privateKeyPem), publicKey = createPublicKey(privateKey);
  if (recipientDigest(publicKey) !== envelope.recipientSha256) throw new MigrationError("Encrypted snapshot is addressed to a different local key.");
  let key: Buffer | undefined, plaintext: Buffer | undefined;
  try {
    key = privateDecrypt({ key: privateKey, padding: cryptoConstants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, decodeBase64(envelope.encryptedKey, 512));
    if (key.length !== 32) throw new Error("Invalid key size");
    const decipher = createDecipheriv("aes-256-gcm", key, decodeBase64(envelope.iv, 12));
    decipher.setAAD(aad(envelope)); decipher.setAuthTag(decodeBase64(envelope.tag, 16));
    plaintext = Buffer.concat([decipher.update(decodeBase64(envelope.ciphertext)), decipher.final()]);
    const snapshot: unknown = JSON.parse(plaintext.toString("utf8"));
    validateSnapshot(snapshot, expectedProject);
    if (snapshot.sha256 !== expectedSnapshotSha256) throw new MigrationError("Decrypted snapshot does not match the independently reviewed source fingerprint.");
    return snapshot;
  } catch (error) {
    if (error instanceof MigrationError) throw error;
    throw new MigrationError("Encrypted snapshot could not be authenticated or decrypted. No plaintext file was written.");
  } finally { key?.fill(0); plaintext?.fill(0); }
}

export async function readPublicSnapshotKey(path: string): Promise<Buffer> {
  return readBoundedFile(path, MAX_PEM_BYTES, false);
}

export async function readPrivateSnapshotKey(path: string): Promise<Buffer> {
  const bytes = await readBoundedFile(path, MAX_PEM_BYTES, true);
  loadPrivateKey(bytes); return bytes;
}

async function readBoundedFile(path: string, maxBytes: number, privateOnly: boolean): Promise<Buffer> {
  const file = await open(path, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
  try {
    const info = await file.stat();
    if (!info.isFile() || info.size > maxBytes || (privateOnly && ((info.mode & 0o077) !== 0 || (process.getuid && info.uid !== process.getuid())))) throw new MigrationError(privateOnly ? "Private key must be an owner-only regular file (0600)." : "Expected a bounded regular file.");
    return await file.readFile();
  } finally { await file.close(); }
}

export async function readEncryptedSnapshot(path: string): Promise<EncryptedSnapshot> {
  const data = await readBoundedFile(path, MAX_ENVELOPE_BYTES, false);
  const envelope: unknown = JSON.parse(data.toString("utf8")); validateEnvelope(envelope); return envelope;
}

export async function writeEncryptedSnapshot(path: string, envelope: EncryptedSnapshot): Promise<void> {
  validateEnvelope(envelope);
  const file = await open(path, fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW, 0o600);
  try { await file.writeFile(JSON.stringify(envelope) + "\n", "utf8"); await file.sync(); } finally { await file.close(); }
}

/** Local only. Never run this on Vercel or put the returned private PEM in a build tree. */
export function generateSnapshotKeyPair(): { privateKey: string; publicKey: string; recipientSha256: string } {
  const pair = generateKeyPairSync("rsa", { modulusLength: 4096, publicKeyEncoding: { type: "spki", format: "pem" }, privateKeyEncoding: { type: "pkcs8", format: "pem" } });
  return { ...pair, recipientSha256: recipientDigest(createPublicKey(pair.publicKey)) };
}

export async function writeSnapshotKeyPair(privatePath: string, publicPath: string): Promise<{ recipientSha256: string }> {
  if (process.env.VERCEL) throw new MigrationError("Key generation is local-only and must never run in a Vercel build.");
  if (privatePath === publicPath) throw new MigrationError("Private and public key files must be different.");
  const pair = generateSnapshotKeyPair();
  const flags = fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW;
  const privateFile = await open(privatePath, flags, 0o600);
  try { await privateFile.writeFile(pair.privateKey, "utf8"); await privateFile.sync(); } finally { await privateFile.close(); }
  // Do not remove or overwrite the private key if a second write fails. Inspect and choose new paths locally.
  const publicFile = await open(publicPath, flags, 0o600);
  try { await publicFile.writeFile(pair.publicKey, "utf8"); await publicFile.sync(); } finally { await publicFile.close(); }
  return { recipientSha256: pair.recipientSha256 };
}
