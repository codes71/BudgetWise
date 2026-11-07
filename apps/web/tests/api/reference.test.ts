import { scopedId } from "@/lib/mongodb";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHash } from "node:crypto";
import { clearDb, newUserId, seedAccount, testDb } from "../helpers/db";
import { req } from "../helpers/request";

let GET_CATEGORIES: (r: Request, c?: unknown) => Promise<Response>;
let GET_BUDGETS: (r: Request, c?: unknown) => Promise<Response>;
let tokenA: string;
const userA = newUserId();
const userB = newUserId();
const sha = (s: string) => createHash("sha256").update(s).digest("hex");

beforeAll(async () => {
  GET_CATEGORIES = (await import("@/app/api/v1/categories/route")).GET as typeof GET_CATEGORIES;
  GET_BUDGETS = (await import("@/app/api/v1/budgets/route")).GET as typeof GET_BUDGETS;
  const { signMobileToken } = await import("@/lib/mobile/token");
  tokenA = (await signMobileToken({ id: userA, email: "a@x.com" })).token;
});
beforeEach(async () => {
  await clearDb();
  await Promise.all([seedAccount(userA, "a@x.com"), seedAccount(userB, "b@x.com")]);
});

describe("GET /api/v1/categories", () => {
  it("returns global plus user-specific, excludes other users'", async () => {
    const fs = testDb();
    await testDb().collection("categories").replaceOne({ _id: "food" }, { ...{ name: "Food", sort: 0 }, _id: "food" }, { upsert: true });
    await testDb().collection("categories").replaceOne({ _id: "rent" }, { ...{ name: "Rent", sort: 1 }, _id: "rent" }, { upsert: true });
    await testDb().collection("userCategories").replaceOne({ _id: scopedId(userA, "c1"), userId: userA }, { ...{ name: "Mine" }, _id: scopedId(userA, "c1"), userId: userA }, { upsert: true });
    await testDb().collection("userCategories").replaceOne({ _id: scopedId(userB, "c2"), userId: userB }, { ...{ name: "Theirs" }, _id: scopedId(userB, "c2"), userId: userB }, { upsert: true });
    const res = await GET_CATEGORIES(req("GET", "/api/v1/categories", { token: tokenA }));
    expect(res.status).toBe(200);
    const { categories } = await res.json();
    expect([...categories].sort()).toEqual(["Food", "Mine", "Rent"]);
  });

  it("lists globals in seeded order, then the user's own", async () => {
    const { seedCategories, DEFAULT_CATEGORIES } = await import("../../scripts/seedCategories");
    await seedCategories(testDb());
    await testDb().collection("userCategories").replaceOne({ _id: scopedId(userA, "c1"), userId: userA }, { ...{ name: "Mine" }, _id: scopedId(userA, "c1"), userId: userA }, { upsert: true });
    const { categories } = await (await GET_CATEGORIES(req("GET", "/api/v1/categories", { token: tokenA }))).json();
    expect(categories).toEqual([...DEFAULT_CATEGORIES, "Mine"]);
  });

  it("401 without token", async () => {
    const res = await GET_CATEGORIES(req("GET", "/api/v1/categories"));
    expect(res.status).toBe(401);
    expect((await res.json()).error.code).toBeTruthy();
  });
});

describe("GET /api/v1/budgets", () => {
  it("returns only caller's, as {category, limit}", async () => {
    const fs = testDb();
    await testDb().collection("budgets").replaceOne({ _id: scopedId(userA, sha("Food")), userId: userA }, { ...{ userId: userA, category: "Food", limit: 5000, createdAt: new Date() }, _id: scopedId(userA, sha("Food")), userId: userA }, { upsert: true });
    await testDb().collection("budgets").replaceOne({ _id: scopedId(userB, sha("Rent")), userId: userB }, { ...{ userId: userB, category: "Rent", limit: 9000, createdAt: new Date() }, _id: scopedId(userB, sha("Rent")), userId: userB }, { upsert: true });
    const res = await GET_BUDGETS(req("GET", "/api/v1/budgets", { token: tokenA }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ budgets: [{ category: "Food", limit: 5000 }] });
  });

  it("401 without token", async () => {
    const res = await GET_BUDGETS(req("GET", "/api/v1/budgets"));
    expect(res.status).toBe(401);
  });
});
