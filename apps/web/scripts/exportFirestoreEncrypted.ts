/** Explicit one-time build-side export. Never imported by the app or normal package/build scripts. */
import { Firestore } from "firebase-admin/firestore";
import { captureSource, MigrationError, summarize, type FirestoreSnapshot } from "./firestoreSnapshot";
import { encryptSnapshot, readPublicSnapshotKey, writeEncryptedSnapshot } from "./snapshotEncryption";

export function encryptedExportArgs(args: string[]): Record<string, string> {
  const allowed = new Set(["public-key", "output", "expect-source-project", "expect-source-sha256"]), flags: Record<string, string> = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]?.replace(/^--/, ""), value = args[i + 1];
    if (!args[i]?.startsWith("--") || !allowed.has(key) || !value || value.startsWith("--") || Object.hasOwn(flags, key)) throw new MigrationError("Use unique --public-key, --output, optional --expect-source-project and --expect-source-sha256 arguments with values.");
    flags[key] = value;
  }
  if (!flags["public-key"] || !flags.output || flags.output === flags["public-key"]) throw new MigrationError("Provide a recipient public-key file and a different new encrypted output file.");
  if (flags["expect-source-sha256"] && !/^[a-f0-9]{64}$/.test(flags["expect-source-sha256"])) throw new MigrationError("Expected source fingerprint must be SHA-256.");
  return flags;
}

/** Matches the established production Firestore runtime configuration; no credential value is printed. */
export function configuredSource(expectedProject?: string): { source: Firestore; projectId: string } {
  const projectId = process.env.FIREBASE_PROJECT_ID;
  if (!projectId || !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(projectId)) throw new MigrationError("The established FIREBASE_PROJECT_ID must identify the active source project.");
  if (expectedProject && projectId !== expectedProject) throw new MigrationError("Active Firestore project does not match the expected source identity.");
  if (process.env.FIRESTORE_EMULATOR_HOST) throw new MigrationError("This build-side exporter refuses emulator configuration; use the general local export tool for emulator fixtures.");
  const email = process.env.FIREBASE_CLIENT_EMAIL, key = process.env.FIREBASE_PRIVATE_KEY;
  if (!(email && key) && !process.env.GOOGLE_APPLICATION_CREDENTIALS) throw new MigrationError("Established Firestore source credentials are unavailable in this environment.");
  return { projectId, source: new Firestore({ projectId, databaseId: "(default)", ...(email && key ? { credentials: { client_email: email, private_key: key.replace(/\\n/g, "\n") } } : {}) }) };
}

/** Two stable captures are only a drift check. This does not establish or claim a source write freeze. */
export async function captureStableSource(source: Firestore, projectId: string, expectedSha256?: string): Promise<FirestoreSnapshot> {
  const first = await captureSource(source, projectId), second = await captureSource(source, projectId);
  if (first.sha256 !== second.sha256) throw new MigrationError("Firestore changed during the encrypted export. Nothing was written; freeze all source writers before retrying.");
  if (expectedSha256 && first.sha256 !== expectedSha256) throw new MigrationError("Firestore no longer matches the expected source fingerprint. Nothing was written.");
  return first;
}

export async function main(args = process.argv.slice(2)): Promise<void> {
  const flags = encryptedExportArgs(args), publicKey = await readPublicSnapshotKey(flags["public-key"]);
  const { source, projectId } = configuredSource(flags["expect-source-project"]);
  try {
    const snapshot = await captureStableSource(source, projectId, flags["expect-source-sha256"]);
    // Both the raw snapshot and AES key exist only in memory. Only this authenticated encrypted envelope reaches disk.
    const envelope = encryptSnapshot(snapshot, publicKey);
    await writeEncryptedSnapshot(flags.output, envelope);
    console.log(JSON.stringify({ mode: "encrypted-export", sourceProjectId: projectId, source: summarize(snapshot), recipientSha256: envelope.recipientSha256, encryptedFileWritten: true, plaintextFileWritten: false, sourceWrites: false, sourceStableAcrossTwoScans: true, sourceWriteFreezeVerified: false }, null, 2));
  } finally { await source.terminate(); }
}

if (process.argv[1] && /exportFirestoreEncrypted\.[cm]?[jt]s$/.test(process.argv[1])) {
  main().catch((error) => {
    console.error(error instanceof MigrationError ? error.message : "Encrypted export failed. No plaintext snapshot or credentials were logged. Do not publish or promote this build.");
    process.exitCode = 1;
  });
}
