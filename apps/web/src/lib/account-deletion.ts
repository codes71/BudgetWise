import bcrypt from "bcryptjs";
import { db, isDocId, sha256Hex, withTransaction, type MongoRecord } from "@/lib/mongodb";
import { emailDocId } from "@/lib/users";
import { ApiError } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";

const userRateKeys = (userId: string) => [
  `scan:user:${userId}`, `voice:user:${userId}`, `delete-account:user:${userId}`,
];

async function purgeChildren(userId: string): Promise<void> {
  const database = await db();
  const shared = new Set(["users", "userEmails", "rateLimits", "categories"]);
  for (const { name } of await database.listCollections({}, { nameOnly: true }).toArray()) {
    if (shared.has(name) || name.startsWith("system.")) continue;
    // Known runtime records and future flat collections use userId. Lossless migration companions use ownerId.
    const filter = name === "_firestoreArchive" || name === "_firestoreTypes"
      ? { $or: [{ ownerId: userId }, { _id: `users/${userId}` }] }
      : { userId };
    await database.collection<MongoRecord>(name).deleteMany(filter);
  }
}

/** Earlier versions allowed orphan child records; absence of the root alone is not proof deletion completed. */
async function finishMissingAccount(userId: string): Promise<void> {
  try {
    await purgeChildren(userId);
    const database = await db();
    await withTransaction(async (session) => {
      if (await database.collection<MongoRecord>("users").findOne({ _id: userId }, { session })) {
        throw new Error("Account identity unexpectedly exists");
      }
      await database.collection<MongoRecord>("userEmails").deleteMany({ userId }, { session });
      await database.collection<MongoRecord>("rateLimits").deleteMany(
        { _id: { $in: userRateKeys(userId).map(sha256Hex) } }, { session },
      );
    });
  } catch {
    throw new ApiError(503, "deletion_incomplete", "Deletion has started but could not finish. Please try again.");
  }
}

/**
 * Deletes one authenticated account. The root remains as a write lock and reauthentication record until all
 * owned child collections are gone. No permanent identity tombstone is retained after success.
 */
export async function deleteAccount(userId: string, password: string): Promise<void> {
  if (!isDocId(userId)) throw new ApiError(401, "unauthorized", "Authentication required");
  const database = await db();
  const users = database.collection<MongoRecord>("users");
  const before = await users.findOne({ _id: userId });
  // A signed identity can retry a lost successful response. It cannot authorize deletion of another uid.
  if (!before) return finishMissingAccount(userId);

  try {
    await rateLimit(`delete-account:user:${userId}`, { limit: 5, windowSec: 15 * 60 });
  } catch (error) {
    // A concurrent confirmed deletion may remove the root between the initial read and limiter transaction.
    if (!(await users.findOne({ _id: userId }))) return finishMissingAccount(userId);
    throw error;
  }
  const passwordHash = before.password;
  if (typeof passwordHash !== "string" || !(await bcrypt.compare(password, passwordHash))) {
    throw new ApiError(401, "invalid_password", "Current password is incorrect");
  }

  const claimed = await withTransaction(async (session) => {
    const current = await users.findOne({ _id: userId }, { session });
    if (!current) return null;
    if (current.password !== passwordHash) {
      throw new ApiError(401, "invalid_password", "Current password is incorrect");
    }
    const emailLower = current.emailLower;
    if (typeof emailLower !== "string" || !emailLower) throw new Error("Account email index is missing");
    // Always touch the root, including retries, to conflict with any in-flight account-owned writer.
    await users.updateOne({ _id: userId }, {
      $set: { deletionStartedAt: current.deletionStartedAt || new Date() }, $inc: { _writeFence: 1 },
    }, { session });
    return { emailLower };
  });
  if (!claimed) return finishMissingAccount(userId);

  try {
    // The locked root fences account-owned writes while all runtime and archived descendant data is purged.
    await purgeChildren(userId);

    await withTransaction(async (session) => {
      const current = await users.findOne({ _id: userId }, { session });
      if (!current) return; // Another identical deletion already completed.
      if (!current.deletionStartedAt || current.emailLower !== claimed.emailLower) {
        throw new Error("Account deletion lock changed");
      }
      const emails = database.collection<MongoRecord>("userEmails");
      const ownedEmail = await emails.findOne({ _id: emailDocId(claimed.emailLower), userId }, { session });
      // Reused or inconsistent claims owned by another account are deliberately preserved.
      await emails.deleteMany({ userId }, { session });
      const accountRateKeys = [...userRateKeys(userId), ...(ownedEmail ? [`auth:email:${sha256Hex(claimed.emailLower)}`] : [])];
      await database.collection<MongoRecord>("rateLimits").deleteMany(
        { _id: { $in: accountRateKeys.map(sha256Hex) } }, { session },
      );
      await users.deleteOne({ _id: userId }, { session });
    });
  } catch {
    // Retain the lock and password for a same-identity, password-verified retry. Never report partial success.
    throw new ApiError(503, "deletion_incomplete", "Deletion has started but could not finish. Please try again.");
  }
}
