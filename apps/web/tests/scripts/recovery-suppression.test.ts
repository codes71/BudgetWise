import { afterEach, describe, expect, it, vi } from "vitest";
import { chmod, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { deletionIdentityKey } from "@/lib/account-deletion-journal";
import { encodeValue, makeSnapshot, writeSnapshot, type SnapshotDocument } from "../../scripts/firestoreSnapshot";
import { journalFingerprint, main, planRecoverySuppression, runRecoveryPlanningCli, validateJournalExport, type JournalExport } from "../../scripts/planRecoverySuppression";

const project = "demo-pocketpeek-recovery";
const source = "synthetic-journal-source";
const at = "2026-11-08T00:00:00.000Z";
const row = (path: string, data: Record<string, unknown>): SnapshotDocument => ({ path, data: encodeValue(data), createTime: null, updateTime: null });
const request = (id: string, complete = false) => ({
  _id: deletionIdentityKey(id), formatVersion: 1 as const, requestedAt: at,
  activeStore: complete ? "complete" as const : "pending" as const,
  ...(complete ? { activeStoreCompletedAt: at } : {}), recoveryCleanup: complete ? "complete" as const : "pending" as const,
});
const journal = (...ids: string[]): JournalExport => ({
  format: "pocket-peek-deletion-journal", version: 1, sourceId: source, exportedAt: at,
  requests: ids.map((id) => request(id)).sort((a, b) => a._id.localeCompare(b._id)),
});
const expected = (snapshot: ReturnType<typeof makeSnapshot>, history: JournalExport) => ({
  sourceProject: project, snapshotSha256: snapshot.sha256, journalSource: source, journalSha256: journalFingerprint(history),
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs(); });

describe("local recovery suppression planning", () => {
  it("suppresses a deleted root, all descendants, email claims and flat/archive ownership while preserving a new email owner", () => {
    const snapshot = makeSnapshot(project, [
      row("users/deleted", { email: "same@example.com", password: "private-password" }),
      row("users/deleted/transactions/one", { description: "private-receipt-sentinel", amount: 123 }),
      row("users/deleted/future/nested/details/one", { arbitrary: "private-words" }),
      row("userEmails/deleted-claim", { userId: "deleted" }),
      row("legacyTransactions/one", { userId: "deleted", transcript: "private-transcript" }),
      row("legacyArchive/one", { ownerId: "deleted" }),
      row("users/new-owner", { email: "same@example.com" }),
      row("users/new-owner/transactions/one", { amount: 2 }),
      row("categories/food", { name: "Food" }),
    ]);
    const history = journal("deleted");
    const result = planRecoverySuppression(snapshot, history, expected(snapshot, history));
    expect(result).toMatchObject({ sourceDocuments: 9, suppressedDocuments: 6, otherwiseEligibleDocuments: 3, ownershipReviewDocuments: 0,
      restoreAuthorized: false, journalCompletenessVerified: false, liveSourceAccess: false, destinationWrites: false });
    const output = JSON.stringify(result);
    for (const privateValue of ["deleted", "new-owner", "same@example.com", "private-password", "private-receipt-sentinel", "private-words", "private-transcript"]) {
      expect(output).not.toContain(privateValue);
    }
    expect(snapshot.documents).toHaveLength(9); // Inputs are not modified or emitted as importable records.
  });

  it("suppresses every requested identity, even when both cleanup statuses are complete", () => {
    const snapshot = makeSnapshot(project, [row("users/deleted", {})]);
    const history = journal(); history.requests = [request("deleted", true)];
    expect(planRecoverySuppression(snapshot, history, expected(snapshot, history)).suppressedDocuments).toBe(1);
  });

  it("never restores snapshot journal rows, migration markers or stale rate counters", () => {
    const snapshot = makeSnapshot(project, [row("_accountDeletionJournal/old", {}), row("rateLimits/old", {}), row("ratelimits/older", {}), row("_migrationControl/one", {})]);
    const history = journal();
    expect(planRecoverySuppression(snapshot, history, expected(snapshot, history))).toMatchObject({ withheldSystemDocuments: 4, otherwiseEligibleDocuments: 0 });
  });

  it("holds unowned unknown or email-claim records for explicit mapping review", () => {
    const snapshot = makeSnapshot(project, [row("unknown/one", { amount: 2 }), row("userEmails/no-owner", {})]);
    const history = journal();
    expect(planRecoverySuppression(snapshot, history, expected(snapshot, history))).toMatchObject({ ownershipReviewDocuments: 2, otherwiseEligibleDocuments: 0 });
  });

  it("rejects conflicting path/userId/ownerId ownership, rather than retaining or suppressing it by one field", () => {
    const history = journal("deleted");
    for (const document of [row("users/deleted/transactions/one", { userId: "other" }), row("legacy/one", { userId: "deleted", ownerId: "other" }), row("users/deleted", { userId: 1 })]) {
      const snapshot = makeSnapshot(project, [document]);
      expect(() => planRecoverySuppression(snapshot, history, expected(snapshot, history))).toThrow(/ownership/);
    }
  });

  it("requires independent snapshot and journal fingerprints and the exact journal source", () => {
    const snapshot = makeSnapshot(project, [row("users/deleted", {})]);
    const history = journal("deleted");
    for (const change of [{ snapshotSha256: "0".repeat(64) }, { journalSha256: "0".repeat(64) }, { journalSource: "other-source" }, { sourceProject: "another-project" }]) {
      expect(() => planRecoverySuppression(snapshot, history, { ...expected(snapshot, history), ...change })).toThrow();
    }
    const oldHash = journalFingerprint(history); history.requests = [];
    expect(() => planRecoverySuppression(snapshot, history, { ...expected(snapshot, history), journalSha256: oldHash })).toThrow("fingerprint");
  });

  it("rejects malformed, duplicate, unsorted or content-bearing journal records", () => {
    const good = journal("deleted");
    const invalid: unknown[] = [
      { ...good, extra: "not permitted" },
      { ...good, requests: [{ ...good.requests[0], email: "private@example.com" }] },
      { ...good, requests: [good.requests[0], good.requests[0]] },
      { ...good, requests: [{ ...good.requests[0], _id: "raw-user-id" }] },
      { ...good, requests: [{ ...good.requests[0], activeStore: "complete" }] },
      { ...good, requests: [{ ...good.requests[0], requestedAt: "not-a-date" }] },
      { ...good, requests: [{ ...good.requests[0], recoveryCleanup: "expired" }] },
      { ...good, requests: [...journal("a", "b").requests].reverse() },
    ];
    for (const value of invalid) expect(() => validateJournalExport(value, source)).toThrow();
  });

  it("has no write mode and refuses deployment execution before opening files", async () => {
    await expect(main(["--write", "true"])).rejects.toThrow("no write mode");
    vi.stubEnv("VERCEL", "1");
    await expect(main([])).rejects.toThrow("outside deployment");
  });

  it("reads only private local files and emits a content-free report without changing them", async () => {
    const directory = await mkdtemp(join(tmpdir(), "pocketpeek-recovery-plan-"));
    try {
      const snapshot = makeSnapshot(project, [row("users/deleted", { password: "private-sentinel" })]);
      const history = journal("deleted"), snapshotPath = join(directory, "snapshot.json"), journalPath = join(directory, "journal.json");
      await writeSnapshot(snapshotPath, snapshot);
      const journalText = JSON.stringify(history); await writeFile(journalPath, journalText, { mode: 0o600 });
      const flags = ["--snapshot", snapshotPath, "--journal", journalPath, "--expect-source-project", project, "--expect-snapshot-sha256", snapshot.sha256,
        "--expect-journal-source", source, "--expect-journal-sha256", journalFingerprint(history)];
      const output = vi.spyOn(console, "log").mockImplementation(() => {});
      await main(flags);
      expect(JSON.parse(output.mock.calls[0][0])).toMatchObject({ suppressedDocuments: 1, destinationWrites: false, restoreAuthorized: false });
      expect(JSON.stringify(output.mock.calls)).not.toContain("private-sentinel");
      expect(await readFile(journalPath, "utf8")).toBe(journalText);
      await chmod(journalPath, 0o644);
      await expect(main(flags)).rejects.toThrow("owner-only");
      await chmod(journalPath, 0o600);
      await symlink(journalPath, join(directory, "link.json"));
      await expect(main(flags.map((value) => value === journalPath ? join(directory, "link.json") : value))).rejects.toThrow();
    } finally { await rm(directory, { recursive: true, force: true }); }
  });

  it("never prints helper or JSON parser messages, input paths or sentinel values at the CLI boundary", async () => {
    const directory = await mkdtemp(join(tmpdir(), "pocketpeek-private-path-sentinel-"));
    const previousCode = process.exitCode;
    try {
      const file = join(directory, "private-content-sentinel.json");
      await writeFile(file, '{"private-content-sentinel": malformed private-account-sentinel}', { mode: 0o600 });
      const output = vi.spyOn(console, "log").mockImplementation(() => {});
      const errors = vi.spyOn(console, "error").mockImplementation(() => {});
      await runRecoveryPlanningCli(["--snapshot", file, "--journal", file, "--expect-source-project", project,
        "--expect-snapshot-sha256", "0".repeat(64), "--expect-journal-source", source, "--expect-journal-sha256", "0".repeat(64)]);
      expect(process.exitCode).toBe(1);
      expect(output).not.toHaveBeenCalled();
      expect(errors).toHaveBeenCalledExactlyOnceWith("Local recovery planning failed. No records, identifiers, paths or file contents were printed.");
      expect(JSON.stringify(errors.mock.calls)).not.toContain("sentinel");
      expect(JSON.stringify(errors.mock.calls)).not.toContain(directory);
    } finally {
      process.exitCode = previousCode;
      await rm(directory, { recursive: true, force: true });
    }
  });
});
