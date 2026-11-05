import bcrypt from "bcryptjs";
import { db, isDocId, sha256Hex, userDoc } from "@/lib/firestore";
import { emailDocId } from "@/lib/users";
import { ApiError } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";

const userRateKeys = (userId: string) => [
  `scan:user:${userId}`, `voice:user:${userId}`, `delete-account:user:${userId}`,
];

async function purgeChildren(userId: string): Promise<void> {
  for (const collection of await userDoc(userId).listCollections()) await db().recursiveDelete(collection);
}

/** Earlier versions allowed orphan child records; absence of the root alone is not proof deletion completed. */
async function finishMissingAccount(userId: string): Promise<void> {
  try {
    await purgeChildren(userId);
    const indexes = await db().collection("userEmails").where("userId", "==", userId).get();
    await db().runTransaction(async (tx) => {
      const [root, ...claims] = await tx.getAll(userDoc(userId), ...indexes.docs.map((doc) => doc.ref));
      if (root.exists) throw new Error("Account identity unexpectedly exists");
      for (const claim of claims) if (claim.exists && claim.get("userId") === userId) tx.delete(claim.ref);
      for (const key of userRateKeys(userId)) tx.delete(db().collection("rateLimits").doc(sha256Hex(key)));
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
  const root = userDoc(userId);
  const before = await root.get();
  // A signed identity can retry a lost successful response. It cannot authorize deletion of another uid.
  if (!before.exists) return finishMissingAccount(userId);

  try {
    await rateLimit(`delete-account:user:${userId}`, { limit: 5, windowSec: 15 * 60 });
  } catch (error) {
    // A concurrent confirmed deletion may remove the root between the initial read and limiter transaction.
    if (!(await root.get()).exists) return finishMissingAccount(userId);
    throw error;
  }
  const passwordHash = before.get("password");
  if (typeof passwordHash !== "string" || !(await bcrypt.compare(password, passwordHash))) {
    throw new ApiError(401, "invalid_password", "Current password is incorrect");
  }

  const claimed = await db().runTransaction(async (tx) => {
    const current = await tx.get(root);
    if (!current.exists) return null;
    if (current.get("password") !== passwordHash) {
      throw new ApiError(401, "invalid_password", "Current password is incorrect");
    }
    const emailLower = current.get("emailLower");
    if (typeof emailLower !== "string" || !emailLower) throw new Error("Account email index is missing");
    if (!current.get("deletionStartedAt")) tx.update(root, { deletionStartedAt: new Date() });
    return { emailLower };
  });
  if (!claimed) return finishMissingAccount(userId);

  try {
    // Current and migrated financial/category records all live below this account root. Recursive deletion also
    // covers nested/future subcollections without relying on a hard-coded list of financial record types.
    await purgeChildren(userId);

    const emailRef = db().collection("userEmails").doc(emailDocId(claimed.emailLower));
    const ownedIndexes = await db().collection("userEmails").where("userId", "==", userId).get();
    const indexes = [emailRef, ...ownedIndexes.docs.map((doc) => doc.ref)]
      .filter((ref, index, all) => all.findIndex((other) => other.path === ref.path) === index);
    await db().runTransaction(async (tx) => {
      const [current, ...emails] = await tx.getAll(root, ...indexes);
      if (!current.exists) return; // Another identical deletion already completed.
      if (!current.get("deletionStartedAt") || current.get("emailLower") !== claimed.emailLower) {
        throw new Error("Account deletion lock changed");
      }
      // A reused or inconsistent index must never delete another account's email claim.
      for (const email of emails) if (email.exists && email.get("userId") === userId) tx.delete(email.ref);
      const ownedEmail = emails.some((email) => email.ref.path === emailRef.path && email.exists && email.get("userId") === userId);
      const accountRateKeys = [...userRateKeys(userId), ...(ownedEmail ? [`auth:email:${sha256Hex(claimed.emailLower)}`] : [])];
      for (const key of accountRateKeys) tx.delete(db().collection("rateLimits").doc(sha256Hex(key)));
      tx.delete(root);
    });
  } catch {
    // Retain the lock and password for a same-identity, password-verified retry. Never report partial success.
    throw new ApiError(503, "deletion_incomplete", "Deletion has started but could not finish. Please try again.");
  }
}
