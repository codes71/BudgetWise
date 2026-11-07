import type { ClientSession, Db } from "mongodb";
import { sha256Hex, type MongoRecord } from "./mongodb";

/** Kept outside account-owned data. Never TTL, replace or restore this collection from an older snapshot. */
export const DELETION_JOURNAL = "_accountDeletionJournal";
export const deletionIdentityKey = (userId: string): string => sha256Hex(`account-deletion:v1:${userId}`);

/** Every recorded request suppresses normal access, regardless of cleanup progress or an old restored root. */
export async function hasDeletionRequest(database: Db, userId: string, session?: ClientSession): Promise<boolean> {
  return Boolean(await database.collection<MongoRecord>(DELETION_JOURNAL).findOne(
    { _id: deletionIdentityKey(userId) }, { session, projection: { _id: 1 } },
  ));
}

/** No email, password, transcript or financial data belongs in the suppression journal. */
export async function recordDeletionRequest(database: Db, userId: string, session: ClientSession): Promise<void> {
  await database.collection<MongoRecord>(DELETION_JOURNAL).updateOne(
    { _id: deletionIdentityKey(userId) },
    { $setOnInsert: {
      formatVersion: 1, requestedAt: new Date(), activeStore: "pending", recoveryCleanup: "pending",
    } },
    { upsert: true, session },
  );
}

/** Active-store completion does not mean that historical Firestore or encrypted copies were removed. */
export async function recordActiveStoreDeletion(database: Db, userId: string, session: ClientSession): Promise<void> {
  const result = await database.collection<MongoRecord>(DELETION_JOURNAL).updateOne(
    { _id: deletionIdentityKey(userId), activeStore: "pending" },
    { $set: { activeStore: "complete", activeStoreCompletedAt: new Date() } },
    { session },
  );
  if (!result.matchedCount && !(await database.collection<MongoRecord>(DELETION_JOURNAL).findOne(
    { _id: deletionIdentityKey(userId), activeStore: "complete" }, { session },
  ))) throw new Error("Account deletion request is missing");
}
