import { afterAll, beforeAll, beforeEach, expect, test } from "vitest";
import mongoose from "mongoose";

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

const legacy = (extra: Record<string, unknown> = {}) => ({
  _id: new mongoose.Types.ObjectId(),
  userId: "u1",
  date: "2026-10-19",
  description: "coffee",
  amount: 4.5,
  category: "Other",
  type: "expense",
  ...extra,
});

const col = () => mongoose.connection.db!.collection("transactions");

test("sets createdAt/updatedAt from ObjectId and source web, idempotent", async () => {
  const { backfillTransactions } = await import("../../scripts/backfillTransactions");
  const a = legacy();
  const b = legacy({ source: "voice", clientId: "c1" });
  await col().insertMany([a, b]);

  const first = await backfillTransactions({ apply: true });
  expect(first).toEqual({ matched: 2, modified: 2 });

  const ra = await col().findOne({ _id: a._id });
  expect(ra!.source).toBe("web");
  expect(ra!.createdAt).toEqual(a._id.getTimestamp());
  expect(ra!.updatedAt).toEqual(a._id.getTimestamp());
  // untouched fields
  expect(ra!.date).toBe("2026-10-19");
  expect(ra!.amount).toBe(4.5);
  expect(ra!.clientId).toBeUndefined();

  const rb = await col().findOne({ _id: b._id });
  expect(rb!.source).toBe("voice");
  expect(rb!.clientId).toBe("c1");

  const second = await backfillTransactions({ apply: true });
  expect(second).toEqual({ matched: 0, modified: 0 });
});

test("keeps existing createdAt/updatedAt", async () => {
  const { backfillTransactions } = await import("../../scripts/backfillTransactions");
  const createdAt = new Date("2026-01-02T03:04:05Z");
  const d = legacy({ createdAt });
  await col().insertOne(d);
  await backfillTransactions({ apply: true });
  const r = await col().findOne({ _id: d._id });
  expect(r!.createdAt).toEqual(createdAt);
  expect(r!.updatedAt).toEqual(d._id.getTimestamp());
});

test("dry run reports matches and leaves docs untouched", async () => {
  const { backfillTransactions } = await import("../../scripts/backfillTransactions");
  const d = legacy();
  await col().insertOne(d);
  const res = await backfillTransactions({ apply: false });
  expect(res).toEqual({ matched: 1, modified: 0 });
  const r = await col().findOne({ _id: d._id });
  expect(r).toEqual(d);
});
