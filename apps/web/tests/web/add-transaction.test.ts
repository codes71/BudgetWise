import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import mongoose from "mongoose";
import { startMongo, stopMongo, clearDb } from "../helpers/db";

const userId = new mongoose.Types.ObjectId().toString();

vi.mock("@/lib/auth", () => ({
  verifySession: vi.fn(async () => ({ userId, email: "web@x.com" })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));

let addTransaction: typeof import("@/app/db-actions").addTransaction;
let Tx: typeof import("@/lib/models/transaction").default;

const base = {
  date: "2026-10-01",
  description: "Coffee",
  amount: 4,
  category: "Food",
  type: "expense" as const,
};

beforeAll(async () => {
  await startMongo();
  ({ addTransaction } = await import("@/app/db-actions"));
  Tx = (await import("@/lib/models/transaction")).default;
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
});

describe("addTransaction", () => {
  it("stores source web even if caller passes voice", async () => {
    const res = await addTransaction({ ...base, source: "voice" } as never);
    expect(res).not.toBeNull();
    const row = await Tx.findOne({ userId }).lean();
    expect(row?.source).toBe("web");
  });

  it("ignores caller clientId and transcript", async () => {
    await addTransaction({ ...base, clientId: "evil-1", transcript: "spoofed" } as never);
    const row = await Tx.findOne({ userId }).lean();
    expect(row?.clientId).toBeUndefined();
    expect(row?.transcript).toBeUndefined();
  });

  it("stores whole kyat amounts", async () => {
    await addTransaction({ ...base, amount: 12500 });
    const row = await Tx.findOne({ userId }).lean();
    expect(row?.amount).toBe(12500);
  });

  it.each([12.5, 0.3, 0, -5, 2 ** 53, Number.NaN])("refuses amount %s", async (amount) => {
    expect(await addTransaction({ ...base, amount })).toBeNull();
    expect(await Tx.countDocuments({ userId })).toBe(0);
  });
});
