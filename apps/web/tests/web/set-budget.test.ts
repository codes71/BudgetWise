import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearDb, testDb } from "../helpers/db";

const { userId } = vi.hoisted(() => ({ userId: `budget${Date.now()}` }));

vi.mock("@/lib/auth", () => ({
  verifySession: vi.fn(async () => ({ userId, email: "web@x.com" })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));

let setBudget: typeof import("@/app/db-actions").setBudget;
let getBudgets: typeof import("@/app/db-actions").getBudgets;
const budgets = async () => (await testDb().collection("users").doc(userId).collection("budgets").get()).docs;

beforeAll(async () => {
  ({ setBudget, getBudgets } = await import("@/app/db-actions"));
});
beforeEach(async () => {
  await clearDb();
});

describe("setBudget", () => {
  it("stores a whole kyat limit", async () => {
    const saved = await setBudget({ category: "Food", limit: 50000 });
    const [row] = await budgets();
    expect(saved).toEqual({ _id: row.id, userId, category: "Food", limit: 50000 });
    expect(row.get("limit")).toBe(50000);
  });

  it("updates the same category in place and keeps insertion order", async () => {
    await setBudget({ category: "Food", limit: 1000 });
    await setBudget({ category: "Rent", limit: 2000 });
    await setBudget({ category: "Bills", limit: 3000 });
    const first = (await budgets()).find((b) => b.get("category") === "Food")!;
    await setBudget({ category: "Food", limit: 1500 });
    expect(await budgets()).toHaveLength(3);
    const after = (await budgets()).find((b) => b.get("category") === "Food")!;
    expect(after.get("createdAt").isEqual(first.get("createdAt"))).toBe(true);
    expect((await getBudgets()).map((b) => [b.category, b.limit])).toEqual([
      ["Food", 1500],
      ["Rent", 2000],
      ["Bills", 3000],
    ]);
  });

  it.each([12.5, 0, -1, 2 ** 53])("refuses limit %s", async (limit) => {
    expect(await setBudget({ category: "Food", limit })).toBeNull();
    expect(await budgets()).toHaveLength(0);
  });
});
