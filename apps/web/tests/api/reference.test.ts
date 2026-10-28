import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import mongoose from "mongoose";
import { startMongo, stopMongo, clearDb } from "../helpers/db";
import { req } from "../helpers/request";

let GET_CATEGORIES: (r: Request, c?: unknown) => Promise<Response>;
let GET_BUDGETS: (r: Request, c?: unknown) => Promise<Response>;
let Category: typeof import("@/lib/models/category").default;
let Budget: typeof import("@/lib/models/budget").default;
let tokenA: string;
const userA = new mongoose.Types.ObjectId().toString();
const userB = new mongoose.Types.ObjectId().toString();

beforeAll(async () => {
  await startMongo();
  GET_CATEGORIES = (await import("@/app/api/v1/categories/route")).GET as typeof GET_CATEGORIES;
  GET_BUDGETS = (await import("@/app/api/v1/budgets/route")).GET as typeof GET_BUDGETS;
  Category = (await import("@/lib/models/category")).default;
  Budget = (await import("@/lib/models/budget")).default;
  const { signMobileToken } = await import("@/lib/mobile/token");
  tokenA = (await signMobileToken({ id: userA, email: "a@x.com" })).token;
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
});

describe("GET /api/v1/categories", () => {
  it("returns global plus user-specific, excludes other users'", async () => {
    await Category.create([
      { name: "Food" },
      { name: "Rent" },
      { name: "Mine", userId: userA },
      { name: "Theirs", userId: userB },
    ]);
    const res = await GET_CATEGORIES(req("GET", "/api/v1/categories", { token: tokenA }));
    expect(res.status).toBe(200);
    const { categories } = await res.json();
    expect([...categories].sort()).toEqual(["Food", "Mine", "Rent"]);
  });

  it("401 without token", async () => {
    const res = await GET_CATEGORIES(req("GET", "/api/v1/categories"));
    expect(res.status).toBe(401);
    expect((await res.json()).error.code).toBeTruthy();
  });
});

describe("GET /api/v1/budgets", () => {
  it("returns only caller's, as {category, limit}", async () => {
    await Budget.create([
      { userId: userA, category: "Food", limit: 5000 },
      { userId: userB, category: "Rent", limit: 9000 },
    ]);
    const res = await GET_BUDGETS(req("GET", "/api/v1/budgets", { token: tokenA }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ budgets: [{ category: "Food", limit: 5000 }] });
  });

  it("401 without token", async () => {
    const res = await GET_BUDGETS(req("GET", "/api/v1/budgets"));
    expect(res.status).toBe(401);
  });
});
