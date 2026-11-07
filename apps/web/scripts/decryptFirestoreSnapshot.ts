/** Local-only key generation/decryption. Never add to a deployment/build command. */
import { MigrationError, summarize, writeSnapshot } from "./firestoreSnapshot";
import { decryptSnapshot, readEncryptedSnapshot, readPrivateSnapshotKey, writeSnapshotKeyPair } from "./snapshotEncryption";

function parseArgs(args: string[]): Record<string, string> {
  const allowed = new Set(["mode", "private-key", "public-key", "encrypted", "snapshot", "expect-source-project", "expect-source-sha256"]), flags: Record<string, string> = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]?.replace(/^--/, ""), value = args[i + 1];
    if (!args[i]?.startsWith("--") || !allowed.has(key) || !value || value.startsWith("--") || Object.hasOwn(flags, key)) throw new MigrationError("Use unique local keygen/decrypt arguments with values.");
    flags[key] = value;
  }
  return flags;
}

export async function main(args = process.argv.slice(2)): Promise<void> {
  if (process.env.VERCEL) throw new MigrationError("Private-key operations must remain local and cannot run in Vercel.");
  const flags = parseArgs(args);
  if (flags.mode === "keygen") {
    if (!flags["private-key"] || !flags["public-key"]) throw new MigrationError("Keygen needs different new --private-key and --public-key files in a private local directory.");
    const result = await writeSnapshotKeyPair(flags["private-key"], flags["public-key"]);
    console.log(JSON.stringify({ mode: "local-keygen", ...result, privateKeyPrinted: false, privateKeyDeploymentAllowed: false })); return;
  }
  if (flags.mode !== "decrypt" || !flags["private-key"] || !flags.encrypted || !flags.snapshot || !flags["expect-source-project"] || !flags["expect-source-sha256"]) throw new MigrationError("Decrypt needs --private-key, --encrypted, new --snapshot, --expect-source-project and --expect-source-sha256.");
  if (new Set([flags["private-key"], flags.encrypted, flags.snapshot]).size !== 3) throw new MigrationError("Private key, encrypted input and private output paths must be different.");
  const privateKey = await readPrivateSnapshotKey(flags["private-key"]);
  try {
    const envelope = await readEncryptedSnapshot(flags.encrypted);
    const snapshot = decryptSnapshot(envelope, privateKey, flags["expect-source-project"], flags["expect-source-sha256"]);
    await writeSnapshot(flags.snapshot, snapshot);
    console.log(JSON.stringify({ mode: "local-decrypt", authenticated: true, source: summarize(snapshot), privateSnapshotWritten: true, sourceWrites: false, destinationWrites: false }, null, 2));
  } finally { privateKey.fill(0); }
}

if (process.argv[1] && /decryptFirestoreSnapshot\.[cm]?[jt]s$/.test(process.argv[1])) {
  main().catch((error) => {
    console.error(error instanceof MigrationError ? error.message : "Local snapshot operation failed. No key material or plaintext snapshot was logged.");
    process.exitCode = 1;
  });
}
