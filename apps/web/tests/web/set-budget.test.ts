import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearDb, seedAccount, testDb } from "../helpers/db";
import { yangonMonth } from "@/lib/budgets";

const { userId } = vi.hoisted(() => ({ userId: `budget${Date.now()}` }));

vi.mock("@/lib/auth", () => ({
  verifySession: vi.fn(async () => ({ userId, email: "web@x.com" })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));

let setBudget: typeof import("@/app/db-actions").setBudget;
let getBudgets: typeof import("@/app/db-actions").getBudgets;
let getMonthPlanSummary: typeof import("@/app/db-actions").getMonthPlanSummary;
const budgets = async () => (await testDb().collection("users").doc(userId).collection("budgets").get()).docs;

const thisMonth = yangonMonth(new Date());
const [year, month] = thisMonth.split("-").map(Number);
const nextMonth = month === 12 ? `${year + 1}-01` : `${year}-${String(month + 1).padStart(2, "0")}`;
const monthName = new Date(Date.UTC(year, month - 1, 1)).toLocaleString("en-US", { month: "long", timeZone: "UTC" });
const planDoc = (m: string) => testDb().collection("users").doc(userId).collection("budgetMonths").doc(m);
const seedPlan = async (m: string, newMoney: number, carriedOver: number, shares: { category: string; limit: number }[]) => {
  const at = new Date("2026-01-01T00:00:00Z");
  await planDoc(m).set({ month: m, newMoney, carriedOver, shares, createdAt: at, updatedAt: at });
};

beforeAll(async () => {
  ({ setBudget, getBudgets, getMonthPlanSummary } = await import("@/app/db-actions"));
});
beforeEach(async () => {
  await clearDb();
  await seedAccount(userId, "web@x.com");
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

  it("without a plan for this month, writes only the old budget", async () => {
    await setBudget({ category: "Food", limit: 50000 });
    expect((await testDb().collection("users").doc(userId).collection("budgetMonths").get()).size).toBe(0);
  });
});

describe("setBudget with this month's plan", () => {
  it("sets an existing share's limit in the plan too", async () => {
    await seedPlan(thisMonth, 100000, 0, [
      { category: "Food", limit: 30000 },
      { category: "Rent", limit: 50000 },
    ]);
    const saved = await setBudget({ category: "Food", limit: 40000 });
    expect(saved).toMatchObject({ category: "Food", limit: 40000 });
    const plan = (await planDoc(thisMonth).get()).data()!;
    expect(plan.shares).toEqual([
      { category: "Food", limit: 40000 },
      { category: "Rent", limit: 50000 },
    ]);
    expect(plan.updatedAt.toDate().getTime()).toBeGreaterThan(plan.createdAt.toDate().getTime());
    expect(plan.newMoney).toBe(100000);
    // The page lists the plan; the old budget is still written for months without one.
    expect((await getBudgets()).map((b) => [b.category, b.limit])).toEqual([
      ["Food", 40000],
      ["Rent", 50000],
    ]);
    expect((await budgets()).map((b) => [b.get("category"), b.get("limit")])).toEqual([["Food", 40000]]);
  });

  it("adds a new category to the end of the plan", async () => {
    await seedPlan(thisMonth, 100000, 0, [{ category: "Food", limit: 30000 }]);
    const saved = await setBudget({ category: "Rent", limit: 50000 });
    expect((await planDoc(thisMonth).get()).get("shares")).toEqual([
      { category: "Food", limit: 30000 },
      { category: "Rent", limit: 50000 },
    ]);
    // Same id as the listed row, so the page can swap it in.
    expect((await getBudgets()).find((b) => b.category === "Rent")).toEqual(saved);
  });

  it("counts the carried over money in the total", async () => {
    await seedPlan(thisMonth, 60000, 40000, [{ category: "Food", limit: 30000 }]);
    expect(await setBudget({ category: "Rent", limit: 70000 })).toMatchObject({ category: "Rent", limit: 70000 });
  });

  it("refuses when the split would go over the month's budget, and writes nothing", async () => {
    await seedPlan(thisMonth, 2000000, 280000, [{ category: "Food", limit: 1600000 }]);
    await testDb().collection("users").doc(userId).collection("budgets").doc("x").set({
      userId, category: "Old", limit: 1, createdAt: new Date(),
    });
    const before = (await planDoc(thisMonth).get()).data()!;

    expect(await setBudget({ category: "Rent", limit: 680001 })).toEqual({
      error: "The split would go over this month's budget of 2,280,000 Ks.",
    });
    expect(await setBudget({ category: "Food", limit: 2280001 })).toEqual({
      error: "The split would go over this month's budget of 2,280,000 Ks.",
    });
    expect((await planDoc(thisMonth).get()).data()).toEqual(before);
    expect((await budgets()).map((b) => [b.get("category"), b.get("limit")])).toEqual([["Old", 1]]);
  });

  it("does not touch another month's plan, which does not change this month's list", async () => {
    await seedPlan(nextMonth, 1000, 0, [{ category: "Food", limit: 1000 }]);
    const before = (await planDoc(nextMonth).get()).data()!;
    expect(await setBudget({ category: "Rent", limit: 50000 })).toMatchObject({ category: "Rent" });
    expect((await planDoc(nextMonth).get()).data()).toEqual(before);
    expect((await getBudgets()).map((b) => [b.category, b.limit])).toEqual([["Rent", 50000]]);
  });
});

describe("getMonthPlanSummary", () => {
  it("is null without a plan for this month", async () => {
    await seedPlan(nextMonth, 1000, 0, []);
    expect(await getMonthPlanSummary()).toBeNull();
  });

  it("gives the month's total and what is not split", async () => {
    await seedPlan(thisMonth, 2000000, 280000, [
      { category: "Housing", limit: 500000 },
      { category: "Food", limit: 1100000 },
    ]);
    expect(await getMonthPlanSummary()).toBe(`${monthName} budget: 2,280,000 Ks (22.8 lakh). 680,000 Ks not split.`);
  });

  it("follows a web change to a share", async () => {
    await seedPlan(thisMonth, 2000000, 0, []);
    await setBudget({ category: "Food", limit: 500000 });
    expect(await getMonthPlanSummary()).toBe(`${monthName} budget: 2,000,000 Ks (20 lakh). 1,500,000 Ks not split.`);
  });
});
