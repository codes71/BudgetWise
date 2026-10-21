import { afterAll, beforeAll, beforeEach, expect, test } from "vitest";
import { startMongo, stopMongo, clearDb } from "../helpers/db";

beforeAll(async () => {
  await startMongo();
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
});

const row = {
  userId: "u1",
  date: "2026-10-19",
  description: "coffee",
  amount: 4,
  category: "Other",
  type: "expense",
};

test("two legacy rows without clientId coexist", async () => {
  const { default: TransactionModel } = await import("@/lib/models/transaction");
  await TransactionModel.init();
  await TransactionModel.create(row);
  await expect(TransactionModel.create(row)).resolves.toBeTruthy();
});

test("duplicate (userId, clientId) rejects with code 11000", async () => {
  const { default: TransactionModel } = await import("@/lib/models/transaction");
  await TransactionModel.init();
  await TransactionModel.create({ ...row, clientId: "c-1" });
  await expect(TransactionModel.create({ ...row, clientId: "c-1" })).rejects.toMatchObject({ code: 11000 });
});

test("same clientId for different users is allowed", async () => {
  const { default: TransactionModel } = await import("@/lib/models/transaction");
  await TransactionModel.init();
  await TransactionModel.create({ ...row, clientId: "c-1" });
  await expect(TransactionModel.create({ ...row, userId: "u2", clientId: "c-1" })).resolves.toBeTruthy();
});

test("defaults source to web and adds timestamps", async () => {
  const { default: TransactionModel } = await import("@/lib/models/transaction");
  const doc = await TransactionModel.create(row);
  expect(doc.source).toBe("web");
  expect(doc.createdAt).toBeInstanceOf(Date);
  expect(doc.updatedAt).toBeInstanceOf(Date);
});
