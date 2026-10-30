import type { QueryDocumentSnapshot } from "firebase-admin/firestore";
import { listBudgets } from "@/lib/budgets";
import { db, isDocId, toDate, userDoc } from "@/lib/firestore";

const byName = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

// Global categories (categories/{slug}) in their seeded order, then the user's own.
export async function getCategoriesForUser(userId: string): Promise<string[]> {
  const [globals, own] = await Promise.all([
    db().collection("categories").get(),
    isDocId(userId) ? userDoc(userId).collection("categories").get() : null,
  ]);
  const sortKey = (doc: QueryDocumentSnapshot) => {
    const sort = doc.get("sort");
    return typeof sort === "number" ? sort : Number.MAX_SAFE_INTEGER;
  };
  const created = (doc: QueryDocumentSnapshot) => toDate(doc.get("createdAt"))?.getTime() ?? 0;
  const names = [
    ...globals.docs
      .sort((a, b) => sortKey(a) - sortKey(b) || byName(a.get("name"), b.get("name")))
      .map((doc) => doc.get("name")),
    ...(own?.docs ?? [])
      .sort((a, b) => created(a) - created(b) || byName(a.get("name"), b.get("name")))
      .map((doc) => doc.get("name")),
  ];
  return [...new Set(names.filter((name): name is string => typeof name === "string" && name.length > 0))];
}

export async function getBudgetsForUser(userId: string): Promise<{ category: string; limit: number }[]> {
  const rows = await listBudgets(userId);
  return rows.map((b) => ({ category: b.category, limit: b.limit }));
}
