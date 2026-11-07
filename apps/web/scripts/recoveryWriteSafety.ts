import type { Db } from "mongodb";
import type { Firestore } from "firebase-admin/firestore";
import { MigrationError } from "./firestoreSnapshot";

export const RECOVERY_WRITE_BLOCKED = "Historical production imports are disabled. A reviewed restore must preserve the current deletion journal outside the restored snapshot and apply authoritative deletion suppression before writing.";

/** Old migration utilities are fixture-only writers until an authoritative-journal restore exists. */
export function assertSyntheticMongoTarget(mongo: Db): void {
  const options = mongo.client?.options;
  if (!/^test-pocketpeek-[a-z0-9-]+$/.test(mongo.databaseName)
      || options?.srvHost || !options?.hosts?.length
      || options.hosts.some((host) => host.host !== "127.0.0.1" || host.socketPath || !host.port)) {
    throw new MigrationError(RECOVERY_WRITE_BLOCKED);
  }
}

export function assertSyntheticFirestoreSource(projectId: string): void {
  if (!/^demo-[a-z0-9-]+$/.test(projectId)) throw new MigrationError(RECOVERY_WRITE_BLOCKED);
}

/** Inspect the constructed transport, not env vars that may have changed after client creation. */
export function assertSyntheticFirestoreTarget(firestore: Firestore): void {
  const client = firestore as unknown as { projectId?: unknown; _settings?: { servicePath?: unknown; port?: unknown; ssl?: unknown } };
  const settings = client._settings;
  if (typeof client.projectId !== "string") throw new MigrationError(RECOVERY_WRITE_BLOCKED);
  assertSyntheticFirestoreSource(client.projectId);
  // This retained Admin SDK exposes no public transport getter. An unknown SDK shape must fail closed.
  if (settings?.servicePath !== "127.0.0.1" || typeof settings.port !== "number"
      || !Number.isInteger(settings.port) || settings.port < 1 || settings.port > 65535 || settings.ssl !== false) {
    throw new MigrationError(RECOVERY_WRITE_BLOCKED);
  }
}
