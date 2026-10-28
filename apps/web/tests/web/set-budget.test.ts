import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import mongoose from "mongoose";
import { startMongo, stopMongo, clearDb } from "../helpers/db";

const userId = new mongoose.Types.ObjectId().toString();

vi.mock("@/lib/auth", () => ({
  verifySession: vi.fn(async () => ({ userId, email: "web@x.com" })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));

let setBudget: typeof import("@/app/db-actions").setBudget;
let Budget: typeof import("@/lib/models/budget").default;

beforeAll(async () => {
  await startMongo();
  ({ setBudget } = await import("@/app/db-actions"));
  Budget = (await import("@/lib/models/budget")).default;
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
});

describe("setBudget", () => {
  it("stores a whole kyat limit", async () => {
    expect(await setBudget({ category: "Food", limit: 50000 })).not.toBeNull();
    const row = await Budget.findOne({ userId }).lean();
    expect(row?.limit).toBe(50000);
  });

  it.each([12.5, 0, -1, 2 ** 53])("refuses limit %s", async (limit) => {
    expect(await setBudget({ category: "Food", limit })).toBeNull();
    expect(await Budget.countDocuments({ userId })).toBe(0);
  });
});
