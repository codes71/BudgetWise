import { afterAll, beforeAll, beforeEach, expect, test } from "vitest";
import { startMongo, stopMongo, clearDb } from "./helpers/db";

beforeAll(async () => {
  await startMongo();
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
});

test("connects and round-trips a Transaction", async () => {
  const { default: Transaction } = await import("@/lib/models/transaction");
  await Transaction.create({
    userId: "u1",
    date: "2026-10-21",
    description: "coffee",
    amount: 4,
    category: "Food",
    type: "expense",
  });
  const rows = await Transaction.find({ userId: "u1" });
  expect(rows).toHaveLength(1);
});
