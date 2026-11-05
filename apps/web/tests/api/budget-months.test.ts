import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHash } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import { clearDb, newUserId, seedAccount, testDb } from "../helpers/db";
import { req } from "../helpers/request";
import { yangonMonth } from "@/lib/budgets";

type Ctx = { params: Promise<{ month: string }> };
let GET: (r: Request, c?: unknown) => Promise<Response>;
let PUT_PLAN: (r: Request, c: Ctx) => Promise<Response>;
let PUT_END_CHECK: (r: Request, c: Ctx) => Promise<Response>;
let GET_BUDGETS: (r: Request, c?: unknown) => Promise<Response>;
let tokenA: string;
let tokenB: string;
const userA = newUserId();
const userB = newUserId();
const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const monthsCol = (userId: string) => testDb().collection("users").doc(userId).collection("budgetMonths");
const budgetsCol = (userId: string) => testDb().collection("users").doc(userId).collection("budgets");

/** "2026-11" plus n months. */
const addMonths = (month: string, n: number) => {
  const [y, m] = month.split("-").map(Number);
  const i = y * 12 + (m - 1) + n;
  return `${Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, "0")}`;
};
const thisMonth = yangonMonth(new Date());
const lastMonth = addMonths(thisMonth, -1);
const nextMonth = addMonths(thisMonth, 1);

beforeAll(async () => {
  GET = (await import("@/app/api/v1/budget-months/route")).GET as typeof GET;
  PUT_PLAN = (await import("@/app/api/v1/budget-months/[month]/route")).PUT as typeof PUT_PLAN;
  PUT_END_CHECK = (await import("@/app/api/v1/budget-months/[month]/end-check/route")).PUT as typeof PUT_END_CHECK;
  GET_BUDGETS = (await import("@/app/api/v1/budgets/route")).GET as typeof GET_BUDGETS;
  const { signMobileToken } = await import("@/lib/mobile/token");
  tokenA = (await signMobileToken({ id: userA, email: "a@x.com" })).token;
  tokenB = (await signMobileToken({ id: userB, email: "b@x.com" })).token;
});
beforeEach(async () => {
  await clearDb();
  await Promise.all([seedAccount(userA, "a@x.com"), seedAccount(userB, "b@x.com")]);
  const fs = testDb();
  for (const [i, name] of ["Food", "Housing", "Transport", "Bills", "Rent"].entries()) {
    await fs.collection("categories").doc(name.toLowerCase()).set({ name, sort: i });
  }
  await fs.collection("users").doc(userA).collection("categories").doc("c1").set({ name: "Pets" });
});

const plan = (over: Record<string, unknown> = {}) => ({
  newMoney: 2000000,
  carriedOver: 280000,
  shares: [
    { category: "Housing", limit: 500000 },
    { category: "Food", limit: 1100000 },
  ],
  ...over,
});
const list = (token: string | undefined, query: string) =>
  GET(req("GET", `/api/v1/budget-months${query}`, { token }));
const put = (token: string | undefined, month: string, body: unknown) =>
  PUT_PLAN(req("PUT", `/api/v1/budget-months/${month}`, { token, body }), { params: Promise.resolve({ month }) });
const endCheck = (token: string | undefined, month: string, body: unknown) =>
  PUT_END_CHECK(req("PUT", `/api/v1/budget-months/${month}/end-check`, { token, body }), {
    params: Promise.resolve({ month }),
  });
const errorCode = async (res: Response) => (await res.json()).error.code;
const legacy = async (userId: string) =>
  (await budgetsCol(userId).get()).docs.map((d) => ({ id: d.id, ...d.data() }));
const seedLegacy = async (userId: string, category: string, limit: number, createdAt: Date) =>
  budgetsCol(userId).doc(sha(category)).set({ userId, category, limit, createdAt, updatedAt: createdAt });

describe("yangonMonth", () => {
  it("turns over at midnight in Yangon (UTC+06:30)", () => {
    expect(yangonMonth(new Date("2026-10-31T17:29:59.999Z"))).toBe("2026-10");
    expect(yangonMonth(new Date("2026-10-31T17:30:00.000Z"))).toBe("2026-11");
    expect(yangonMonth(new Date("2026-12-31T17:30:00.000Z"))).toBe("2027-01");
  });
});

describe("auth", () => {
  it("401 on every route without a token", async () => {
    expect((await list(undefined, `?from=${lastMonth}&to=${thisMonth}`)).status).toBe(401);
    expect((await put(undefined, thisMonth, plan())).status).toBe(401);
    expect((await endCheck(undefined, lastMonth, { onHand: 1 })).status).toBe(401);
    expect(await errorCode(await put("not-a-token", thisMonth, plan()))).toBe("unauthorized");
    expect((await monthsCol(userA).get()).size).toBe(0);
  });
});

describe("PUT /api/v1/budget-months/{month}", () => {
  it("creates the plan and returns it", async () => {
    const res = await put(tokenA, lastMonth, plan());
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toEqual({
      month: {
        month: lastMonth,
        newMoney: 2000000,
        carriedOver: 280000,
        shares: [
          { category: "Housing", limit: 500000 },
          { category: "Food", limit: 1100000 },
        ],
        endCheck: null,
        updatedAt: expect.any(String),
      },
    });
    expect(new Date(body.month.updatedAt).getTime()).toBeGreaterThan(0);

    const stored = (await monthsCol(userA).doc(lastMonth).get()).data()!;
    expect(Object.keys(stored).sort()).toEqual(["carriedOver", "createdAt", "month", "newMoney", "shares", "updatedAt"]);
    expect(stored.month).toBe(lastMonth);
    expect(stored.createdAt).toBeInstanceOf(Timestamp);
    expect(stored.shares).toEqual(body.month.shares);
  });

  it("keeps the shares in the order sent and drops unknown fields", async () => {
    const shares = [
      { category: "Transport", limit: 100, note: "dropped" },
      { category: "Bills", limit: 200 },
      { category: "Food", limit: 300 },
    ];
    const { month } = await (await put(tokenA, lastMonth, plan({ shares, extra: 1 }))).json();
    expect(month.shares).toEqual([
      { category: "Transport", limit: 100 },
      { category: "Bills", limit: 200 },
      { category: "Food", limit: 300 },
    ]);
    const stored = (await monthsCol(userA).doc(lastMonth).get()).data()!;
    expect(stored.shares).toEqual(month.shares);
    expect(stored).not.toHaveProperty("extra");
  });

  it("accepts no shares, a split equal to the total, and the user's own categories", async () => {
    expect((await put(tokenA, lastMonth, plan({ shares: [] }))).status).toBe(200);
    const exact = plan({ newMoney: 0, carriedOver: 1000, shares: [{ category: "Pets", limit: 1000 }] });
    expect((await put(tokenA, addMonths(thisMonth, -2), exact)).status).toBe(200);
  });

  it.each(["2026-13", "2026-00", "2026-1", "26-11", "november", "2026-11-01"])("400 invalid_month for %j", async (month) => {
    const res = await put(tokenA, month, plan());
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe("invalid_month");
  });

  it("400 invalid_json for a body that is not JSON", async () => {
    const res = await put(tokenA, lastMonth, "{not json");
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe("invalid_json");
  });

  it("422 validation_failed for bad amounts and shares, and stores nothing", async () => {
    const tooMany = Array.from({ length: 51 }, (_, i) => ({ category: `C${i}`, limit: 1 }));
    for (const over of [
      { newMoney: -1 },
      { newMoney: 1.5 },
      { newMoney: "2000000" },
      { carriedOver: -1 },
      { carriedOver: undefined },
      { newMoney: 0, carriedOver: 0, shares: [] },
      { newMoney: Number.MAX_SAFE_INTEGER, carriedOver: 1 },
      { shares: undefined },
      { shares: [{ category: "Food", limit: 0 }] },
      { shares: [{ category: "Food", limit: 10.5 }] },
      { shares: [{ category: "", limit: 10 }] },
      { shares: [{ category: "Food", limit: 10 }, { category: "Food", limit: 20 }] },
      { shares: tooMany },
    ]) {
      const res = await put(tokenA, lastMonth, plan(over));
      expect(res.status, JSON.stringify(over)).toBe(422);
      expect(await errorCode(res)).toBe("validation_failed");
    }
    expect((await monthsCol(userA).get()).size).toBe(0);
  });

  it("422 split_over_total when the shares add up to more than the total", async () => {
    const res = await put(tokenA, lastMonth, plan({ shares: [{ category: "Food", limit: 2280001 }] }));
    expect(res.status).toBe(422);
    expect(await errorCode(res)).toBe("split_over_total");
    expect((await monthsCol(userA).doc(lastMonth).get()).exists).toBe(false);
  });

  it("422 unknown_category for a name not in GET /categories, including another user's", async () => {
    await testDb().collection("users").doc(userB).collection("categories").doc("c2").set({ name: "Theirs" });
    for (const category of ["Holidays", "food", "Theirs"]) {
      const res = await put(tokenA, lastMonth, plan({ shares: [{ category, limit: 100 }] }));
      expect(res.status).toBe(422);
      expect(await errorCode(res)).toBe("unknown_category");
    }
    expect((await monthsCol(userA).doc(lastMonth).get()).exists).toBe(false);
  });

  it("replacing a plan keeps its createdAt and end check", async () => {
    const month = addMonths(thisMonth, -2);
    await put(tokenA, month, plan());
    const checked = await (await endCheck(tokenA, month, { onHand: 250000 })).json();
    const before = (await monthsCol(userA).doc(month).get()).data()!;
    await new Promise((r) => setTimeout(r, 25));

    const res = await put(tokenA, month, { newMoney: 1500000, carriedOver: 0, shares: [{ category: "Rent", limit: 700000 }] });
    expect(res.status).toBe(200);
    const { month: dto } = await res.json();
    expect(dto).toMatchObject({
      newMoney: 1500000,
      carriedOver: 0,
      shares: [{ category: "Rent", limit: 700000 }],
      endCheck: checked.month.endCheck,
    });
    expect(dto.updatedAt > checked.month.updatedAt).toBe(true);

    const after = (await monthsCol(userA).doc(month).get()).data()!;
    expect(after.createdAt.isEqual(before.createdAt)).toBe(true);
    expect(after.endCheck.onHand).toBe(250000);
    expect(after.endCheck.checkedAt.isEqual(before.endCheck.checkedAt)).toBe(true);
    expect(after.shares).toEqual([{ category: "Rent", limit: 700000 }]);
  });

  it("each user has their own plans", async () => {
    await put(tokenA, lastMonth, plan());
    await put(tokenB, lastMonth, plan({ newMoney: 5, carriedOver: 0, shares: [] }));
    expect((await monthsCol(userA).doc(lastMonth).get()).get("newMoney")).toBe(2000000);
    expect((await monthsCol(userB).doc(lastMonth).get()).get("newMoney")).toBe(5);
  });
});

describe("this month's plan is what the budgets list shows", () => {
  const budgetsList = async (token: string) =>
    (await (await GET_BUDGETS(req("GET", "/api/v1/budgets", { token }))).json()).budgets;
  const created = new Date("2026-01-01T00:00:00Z");
  const seedTwo = async () => {
    await seedLegacy(userA, "Food", 50000, created);
    await seedLegacy(userA, "Rent", 90000, new Date("2026-01-02T00:00:00Z"));
  };

  it("without a plan for this month, lists the old budgets oldest first", async () => {
    await seedTwo();
    expect(await budgetsList(tokenA)).toEqual([
      { category: "Food", limit: 50000 },
      { category: "Rent", limit: 90000 },
    ]);
  });

  it("with a plan for this month, lists its shares in plan order", async () => {
    await seedTwo();
    const shares = ["Transport", "Bills", "Food"].map((category, i) => ({ category, limit: 1000 * (i + 1) }));
    expect((await put(tokenA, thisMonth, plan({ shares }))).status).toBe(200);
    expect(await budgetsList(tokenA)).toEqual(shares);

    const { listBudgets } = await import("@/lib/budgets");
    expect(await listBudgets(userA)).toEqual(
      shares.map(({ category, limit }) => ({ _id: sha(category), userId: userA, category, limit })),
    );
    // Another user still sees their own.
    await seedLegacy(userB, "Rent", 70000, created);
    expect(await budgetsList(tokenB)).toEqual([{ category: "Rent", limit: 70000 }]);
  });

  it("a plan for this month with no shares lists nothing", async () => {
    await seedTwo();
    await put(tokenA, thisMonth, plan({ shares: [] }));
    expect(await budgetsList(tokenA)).toEqual([]);
  });

  it.each([
    ["last", lastMonth],
    ["next", nextMonth],
  ])("a plan for %s month does not change this month's list", async (_, month) => {
    await seedTwo();
    await put(tokenA, month, plan({ shares: [{ category: "Bills", limit: 1000 }] }));
    expect((await budgetsList(tokenA)).map((b: any) => b.category)).toEqual(["Food", "Rent"]);
  });

  it.each([
    ["this", thisMonth],
    ["last", lastMonth],
    ["next", nextMonth],
  ])("saving %s month's plan leaves the old budget rows untouched", async (_, month) => {
    await seedTwo();
    const before = (await budgetsCol(userA).get()).docs.map((d) => [d.id, d.data()]);
    const res = await put(tokenA, month, plan({ shares: [{ category: "Food", limit: 700000 }, { category: "Bills", limit: 300000 }] }));
    expect(res.status).toBe(200);
    expect((await budgetsCol(userA).get()).docs.map((d) => [d.id, d.data()])).toEqual(before);
  });
});

describe("GET /api/v1/budget-months", () => {
  it("returns the caller's months that have a plan in the range, oldest first", async () => {
    const twoAgo = addMonths(thisMonth, -2);
    await put(tokenA, thisMonth, plan());
    await put(tokenA, twoAgo, plan({ newMoney: 1000, carriedOver: 0, shares: [] }));
    await put(tokenA, addMonths(thisMonth, -6), plan());
    await put(tokenA, nextMonth, plan());
    await put(tokenB, lastMonth, plan());
    await endCheck(tokenA, twoAgo, { onHand: 400 });

    const res = await list(tokenA, `?from=${addMonths(thisMonth, -3)}&to=${thisMonth}`);
    expect(res.status).toBe(200);
    const { months } = await res.json();
    expect(months.map((m: any) => m.month)).toEqual([twoAgo, thisMonth]);
    expect(months[0]).toEqual({
      month: twoAgo,
      newMoney: 1000,
      carriedOver: 0,
      shares: [],
      endCheck: { onHand: 400, checkedAt: expect.any(String) },
      updatedAt: expect.any(String),
    });
    expect(new Date(months[0].endCheck.checkedAt).toISOString()).toBe(months[0].endCheck.checkedAt);
    expect(months[1]).toMatchObject({ month: thisMonth, newMoney: 2000000, carriedOver: 280000, endCheck: null });
    expect(months[1].shares).toEqual(plan().shares);
  });

  it("includes both ends and returns an empty list when nothing is planned", async () => {
    await put(tokenA, lastMonth, plan());
    await put(tokenA, thisMonth, plan());
    const both = await (await list(tokenA, `?from=${lastMonth}&to=${thisMonth}`)).json();
    expect(both.months.map((m: any) => m.month)).toEqual([lastMonth, thisMonth]);
    const one = await (await list(tokenA, `?from=${thisMonth}&to=${thisMonth}`)).json();
    expect(one.months.map((m: any) => m.month)).toEqual([thisMonth]);
    expect(await (await list(tokenB, `?from=${lastMonth}&to=${thisMonth}`)).json()).toEqual({ months: [] });
  });

  it("allows 24 months", async () => {
    expect((await list(tokenA, "?from=2025-01&to=2026-12")).status).toBe(200);
  });

  it.each([
    "",
    "?from=2026-01",
    "?to=2026-01",
    "?from=2026-13&to=2026-12",
    "?from=2026-1&to=2026-02",
    "?from=2026-03&to=2026-02",
    "?from=2025-01&to=2027-01",
  ])("400 invalid_month for %j", async (query) => {
    const res = await list(tokenA, query);
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe("invalid_month");
  });
});

describe("PUT /api/v1/budget-months/{month}/end-check", () => {
  it("saves the end check for a month that is over, and saving again replaces it", async () => {
    await put(tokenA, lastMonth, plan());
    const res = await endCheck(tokenA, lastMonth, { onHand: 280000 });
    expect(res.status).toBe(200);
    const first = (await res.json()).month;
    expect(first).toMatchObject({ month: lastMonth, newMoney: 2000000, carriedOver: 280000, shares: plan().shares });
    expect(first.endCheck).toEqual({ onHand: 280000, checkedAt: expect.any(String) });
    expect(first.updatedAt).toBe(first.endCheck.checkedAt);

    await new Promise((r) => setTimeout(r, 25));
    const second = (await (await endCheck(tokenA, lastMonth, { onHand: 0 })).json()).month;
    expect(second.endCheck.onHand).toBe(0);
    expect(second.endCheck.checkedAt > first.endCheck.checkedAt).toBe(true);

    const stored = (await monthsCol(userA).doc(lastMonth).get()).data()!;
    expect(stored.endCheck.onHand).toBe(0);
    expect(stored.endCheck.checkedAt).toBeInstanceOf(Timestamp);
    expect(stored.shares).toEqual(plan().shares);
  });

  it("does not touch the old budgets", async () => {
    await seedLegacy(userA, "Food", 50000, new Date());
    await put(tokenA, lastMonth, plan());
    await endCheck(tokenA, lastMonth, { onHand: 1 });
    expect((await legacy(userA)).map((r: any) => [r.category, r.limit])).toEqual([["Food", 50000]]);
  });

  it("404 not_found when the month has no plan", async () => {
    await put(tokenB, lastMonth, plan());
    const res = await endCheck(tokenA, lastMonth, { onHand: 1 });
    expect(res.status).toBe(404);
    expect(await errorCode(res)).toBe("not_found");
    expect((await monthsCol(userA).doc(lastMonth).get()).exists).toBe(false);
  });

  it.each([
    ["this", thisMonth],
    ["next", nextMonth],
  ])("422 month_not_over for %s month", async (_, month) => {
    await put(tokenA, month, plan());
    const res = await endCheck(tokenA, month, { onHand: 1 });
    expect(res.status).toBe(422);
    expect(await errorCode(res)).toBe("month_not_over");
    expect((await monthsCol(userA).doc(month).get()).get("endCheck")).toBeUndefined();
  });

  it("422 validation_failed for a bad amount", async () => {
    await put(tokenA, lastMonth, plan());
    for (const body of [{ onHand: -1 }, { onHand: 1.5 }, { onHand: "1" }, {}]) {
      const res = await endCheck(tokenA, lastMonth, body);
      expect(res.status, JSON.stringify(body)).toBe(422);
      expect(await errorCode(res)).toBe("validation_failed");
    }
    expect((await monthsCol(userA).doc(lastMonth).get()).get("endCheck")).toBeUndefined();
  });

  it("400 invalid_month", async () => {
    const res = await endCheck(tokenA, "2026-13", { onHand: 1 });
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe("invalid_month");
  });
});
