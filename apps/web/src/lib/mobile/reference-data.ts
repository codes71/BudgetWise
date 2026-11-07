import { listBudgets } from "@/lib/budgets";
import { db, isDocId, toDate, type MongoRecord } from "@/lib/mongodb";

const byName = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

// Global categories in their seeded order, then the user's own categories.
export async function getCategoriesForUser(userId: string): Promise<string[]> {
  const database = await db();
  const [globals, own] = await Promise.all([
    database.collection<MongoRecord>("categories").find({}).toArray(),
    isDocId(userId) ? database.collection<MongoRecord>("userCategories").find({ userId }).toArray() : [],
  ]);
  const sortKey = (doc: MongoRecord) => typeof doc.sort === "number" ? doc.sort : Number.MAX_SAFE_INTEGER;
  const created = (doc: MongoRecord) => toDate(doc.createdAt)?.getTime() ?? 0;
  const names = [
    ...globals
      .sort((a, b) => sortKey(a) - sortKey(b) || byName(a.name, b.name))
      .map((doc) => doc.name),
    ...own
      .sort((a, b) => created(a) - created(b) || byName(a.name, b.name))
      .map((doc) => doc.name),
  ];
  return [...new Set(names.filter((name): name is string => typeof name === "string" && name.length > 0))];
}

export async function getBudgetsForUser(userId: string): Promise<{ category: string; limit: number }[]> {
  const rows = await listBudgets(userId);
  return rows.map((b) => ({ category: b.category, limit: b.limit }));
}
