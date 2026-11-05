import type { Transaction } from "firebase-admin/firestore";
import { isDocId, userDoc } from "@/lib/firestore";
import { ApiError } from "@/lib/mobile/errors";

/** Must be read inside each account-owned write transaction, so deletion invalidates in-flight writers. */
export async function requireAccountRecord(userId: string, transaction?: Transaction) {
  if (!isDocId(userId)) throw new ApiError(401, "unauthorized", "Authentication required");
  const ref = userDoc(userId);
  const snapshot = await (transaction ? transaction.get(ref) : ref.get());
  if (!snapshot.exists) {
    throw new ApiError(401, "unauthorized", "Authentication required");
  }
  return snapshot;
}

export async function requireActiveAccount(userId: string, transaction?: Transaction): Promise<void> {
  if ((await requireAccountRecord(userId, transaction)).get("deletionStartedAt")) {
    throw new ApiError(401, "unauthorized", "Authentication required");
  }
}
