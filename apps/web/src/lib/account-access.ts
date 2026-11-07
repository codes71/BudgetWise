import type { ClientSession } from "mongodb";
import { db, isDocId, type MongoRecord } from "@/lib/mongodb";
import { ApiError } from "@/lib/mobile/errors";

const unauthorized = () => new ApiError(401, "unauthorized", "Authentication required");

/** Deletion-retry writes may access a locked account, but must still conflict with its final removal. */
export async function requireAccountRecord(userId: string, session?: ClientSession): Promise<MongoRecord> {
  if (!isDocId(userId)) throw unauthorized();
  const users = (await db()).collection<MongoRecord>("users");
  const record = session
    ? await users.findOneAndUpdate({ _id: userId }, { $inc: { _writeFence: 1 } }, {
      session, returnDocument: "after", includeResultMetadata: false,
    })
    : await users.findOne({ _id: userId });
  if (!record) throw unauthorized();
  return record;
}

/**
 * All account-owned writes call this inside their Mongo transaction. Reading alone is insufficient with
 * snapshot isolation: touching the root forces a conflict with a concurrent deletion claim/removal.
 */
export async function requireActiveAccount(userId: string, session?: ClientSession): Promise<void> {
  if (!isDocId(userId)) throw unauthorized();
  if (!session) {
    if ((await requireAccountRecord(userId)).deletionStartedAt) throw unauthorized();
    return;
  }
  const record = await (await db()).collection<MongoRecord>("users").findOneAndUpdate(
    { _id: userId, $or: [{ deletionStartedAt: { $exists: false } }, { deletionStartedAt: null }] },
    { $inc: { _writeFence: 1 } },
    { session, returnDocument: "after", includeResultMetadata: false },
  );
  if (!record) throw unauthorized();
}
