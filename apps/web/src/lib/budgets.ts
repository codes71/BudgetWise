import { db, sha256Hex, toDate, userDoc } from "@/lib/firestore";
import type { Budget } from "@/lib/types";

const budgetsCol = (userId: string) => userDoc(userId).collection("budgets");

/** Oldest first, matching the order budgets were added. */
export async function listBudgets(userId: string): Promise<Budget[]> {
  const snap = await budgetsCol(userId).get();
  return snap.docs
    .map((doc) => ({ doc, createdAt: toDate(doc.get("createdAt"))?.getTime() ?? 0 }))
    .sort((a, b) => a.createdAt - b.createdAt || (a.doc.id < b.doc.id ? -1 : a.doc.id > b.doc.id ? 1 : 0))
    .map(({ doc }) => ({ _id: doc.id, userId, category: doc.get("category"), limit: doc.get("limit") }));
}

/** One budget per category (doc id is a hash of it). Updating keeps createdAt, so the order holds. */
export async function setBudgetLimit(userId: string, category: string, limit: number): Promise<Budget> {
  const ref = budgetsCol(userId).doc(sha256Hex(category));
  await db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = new Date();
    if (snap.exists) tx.update(ref, { limit, updatedAt: now });
    else tx.create(ref, { userId, category, limit, createdAt: now, updatedAt: now });
  });
  return { _id: ref.id, userId, category, limit };
}
