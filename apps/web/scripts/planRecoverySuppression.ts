/** Local files only. Produces counts for review, never records to import or a production writer. */
import { constants } from "node:fs";
import { open } from "node:fs/promises";
import { DELETION_JOURNAL, deletionIdentityKey } from "../src/lib/account-deletion-journal";
import { isDocId } from "../src/lib/mongodb";
import { digest, MigrationError, readSnapshot, validateSnapshot, type FirestoreSnapshot, type SnapshotDocument } from "./firestoreSnapshot";

interface JournalRequest {
  _id: string;
  formatVersion: 1;
  requestedAt: string;
  activeStore: "pending" | "complete";
  activeStoreCompletedAt?: string;
  recoveryCleanup: "pending" | "complete";
}

export interface JournalExport {
  format: "pocket-peek-deletion-journal";
  version: 1;
  sourceId: string;
  exportedAt: string;
  requests: JournalRequest[];
}

const isoDate = (value: unknown): value is string => typeof value === "string"
  && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
const exactKeys = (value: object, keys: string[]) => Object.keys(value).every((key) => keys.includes(key));

/** An independent receipt must attest completeness/currentness; JSON and a checksum cannot prove either. */
export function validateJournalExport(value: unknown, expectedSource: string): asserts value is JournalExport {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new MigrationError("Invalid deletion journal export.");
  const journal = value as JournalExport;
  if (!exactKeys(journal, ["format", "version", "sourceId", "exportedAt", "requests"])
    || journal.format !== "pocket-peek-deletion-journal" || journal.version !== 1
    || !/^[A-Za-z0-9_-]{1,128}$/.test(expectedSource) || journal.sourceId !== expectedSource
    || !isoDate(journal.exportedAt) || !Array.isArray(journal.requests)) {
    throw new MigrationError("Deletion journal format or expected source does not match.");
  }
  let previous: string | undefined;
  for (const request of journal.requests) {
    if (!request || typeof request !== "object" || Array.isArray(request)
      || !exactKeys(request, ["_id", "formatVersion", "requestedAt", "activeStore", "activeStoreCompletedAt", "recoveryCleanup"])
      || typeof request._id !== "string" || !/^[a-f0-9]{64}$/.test(request._id)
      || (previous !== undefined && request._id <= previous) || request.formatVersion !== 1
      || !isoDate(request.requestedAt) || !["pending", "complete"].includes(request.activeStore)
      || !["pending", "complete"].includes(request.recoveryCleanup)
      || (request.activeStore === "complete" ? !isoDate(request.activeStoreCompletedAt) : request.activeStoreCompletedAt !== undefined)) {
      throw new MigrationError("Deletion journal requests must be minimal, valid, unique and sorted.");
    }
    previous = request._id;
  }
}

/** Includes export identity, time and every request/status. Obtain the expected hash from a separate trusted receipt. */
export function journalFingerprint(journal: JournalExport): string {
  return digest(JSON.stringify([journal.format, journal.version, journal.sourceId, journal.exportedAt,
    journal.requests.map((request) => [request._id, request.formatVersion, request.requestedAt,
      request.activeStore, request.activeStoreCompletedAt ?? null, request.recoveryCleanup])]));
}

function documentOwner(document: SnapshotDocument): string | undefined {
  const parts = document.path.split("/");
  const owners = new Set<string>();
  if (parts[0] === "users") owners.add(parts[1]);
  if (document.data[0] === "map") {
    for (const [key, value] of document.data[1]) {
      if (key !== "userId" && key !== "ownerId") continue;
      if (value[0] !== "string" || !isDocId(value[1])) throw new MigrationError("Snapshot contains invalid ownership metadata; review mapping before recovery.");
      owners.add(value[1]);
    }
  }
  if (owners.size > 1) throw new MigrationError("Snapshot contains conflicting ownership metadata; review mapping before recovery.");
  const owner = [...owners][0];
  if (owner !== undefined && !isDocId(owner)) throw new MigrationError("Snapshot contains an unsupported account identity; review mapping before recovery.");
  return owner;
}

export function planRecoverySuppression(
  snapshot: FirestoreSnapshot, journal: JournalExport,
  expected: { sourceProject: string; snapshotSha256: string; journalSource: string; journalSha256: string },
) {
  validateSnapshot(snapshot, expected.sourceProject);
  validateJournalExport(journal, expected.journalSource);
  if (!/^[a-f0-9]{64}$/.test(expected.snapshotSha256) || snapshot.sha256 !== expected.snapshotSha256
    || !/^[a-f0-9]{64}$/.test(expected.journalSha256) || journalFingerprint(journal) !== expected.journalSha256) {
    throw new MigrationError("Snapshot or deletion journal differs from its independently reviewed fingerprint.");
  }
  // No completed request expires out of suppression, including completed recovery cleanup.
  const suppressed = new Set(journal.requests.map((request) => request._id));
  let suppressedDocuments = 0, withheldSystemDocuments = 0, ownershipReviewDocuments = 0, otherwiseEligibleDocuments = 0;
  for (const document of snapshot.documents) {
    const parts = document.path.split("/");
    // Never restore old suppression history or stale counters. The current journal remains separate.
    if ([DELETION_JOURNAL, "rateLimits", "ratelimits", "_migrationControl"].includes(parts[0])) {
      withheldSystemDocuments++; continue;
    }
    const owner = documentOwner(document);
    if (owner && suppressed.has(deletionIdentityKey(owner))) suppressedDocuments++;
    else if (owner || (parts.length === 2 && parts[0] === "categories")) otherwiseEligibleDocuments++;
    else ownershipReviewDocuments++;
  }
  return {
    mode: "local-recovery-suppression-dry-run" as const,
    snapshotSha256: snapshot.sha256, journalSha256: expected.journalSha256,
    journalRequests: journal.requests.length, sourceDocuments: snapshot.documents.length,
    suppressedDocuments, withheldSystemDocuments, ownershipReviewDocuments, otherwiseEligibleDocuments,
    restoreAuthorized: false, journalCompletenessVerified: false, liveSourceAccess: false, destinationWrites: false,
  };
}

async function readJournal(path: string): Promise<unknown> {
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await file.stat();
    if (!info.isFile() || (info.mode & 0o077) !== 0 || (process.getuid && info.uid !== process.getuid()) || info.size > 16 * 1024 * 1024) {
      throw new MigrationError("Journal must be a bounded owner-only regular file (0600).");
    }
    return JSON.parse(await file.readFile("utf8"));
  } finally { await file.close(); }
}

export async function main(args = process.argv.slice(2)): Promise<void> {
  if (process.env.VERCEL) throw new MigrationError("Recovery planning must remain local, outside deployment builds.");
  const required = ["snapshot", "journal", "expect-source-project", "expect-snapshot-sha256", "expect-journal-source", "expect-journal-sha256"];
  const flags: Record<string, string> = {};
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index], value = args[index + 1], key = flag?.slice(2);
    if (!flag?.startsWith("--") || !required.includes(key) || !value || value.startsWith("--") || Object.hasOwn(flags, key)) {
      throw new MigrationError("Use each required recovery dry-run flag exactly once; no write mode exists.");
    }
    flags[key] = value;
  }
  if (required.some((key) => !flags[key])) throw new MigrationError("Recovery dry-run needs private snapshot/journal files and their independently reviewed source identities/fingerprints.");
  const snapshot = await readSnapshot(flags.snapshot, flags["expect-source-project"]);
  const journal = await readJournal(flags.journal);
  validateJournalExport(journal, flags["expect-journal-source"]);
  console.log(JSON.stringify(planRecoverySuppression(snapshot, journal, {
    sourceProject: flags["expect-source-project"], snapshotSha256: flags["expect-snapshot-sha256"],
    journalSource: flags["expect-journal-source"], journalSha256: flags["expect-journal-sha256"],
  }), null, 2));
}

/** Keep even parser/OS/helper errors content-free at the terminal boundary. Inspect details only locally. */
export async function runRecoveryPlanningCli(args = process.argv.slice(2)): Promise<void> {
  try { await main(args); }
  catch {
    console.error("Local recovery planning failed. No records, identifiers, paths or file contents were printed.");
    process.exitCode = 1;
  }
}

if (process.argv[1] && /planRecoverySuppression\.[cm]?[jt]s$/.test(process.argv[1])) {
  void runRecoveryPlanningCli();
}
