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

const opts = { limit: 3, windowSec: 60 };

test("allows limit then blocks limit+1", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const { ApiError } = await import("@/lib/mobile/errors");
  for (let i = 0; i < 3; i++) await rateLimit("login:a", opts);
  const err = await rateLimit("login:a", opts).catch((e) => e);
  expect(err).toBeInstanceOf(ApiError);
  expect(err.status).toBe(429);
  expect(err.code).toBe("rate_limited");
  expect(err.retryAfterSec).toBeGreaterThanOrEqual(1);
  expect(err.retryAfterSec).toBeLessThanOrEqual(60);
});

test("window resets after expiry", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const RateLimit = (await import("@/lib/models/rate-limit")).default;
  for (let i = 0; i < 3; i++) await rateLimit("login:b", opts);
  await expect(rateLimit("login:b", opts)).rejects.toMatchObject({ status: 429 });
  await RateLimit.updateOne({ key: "login:b" }, { $set: { expiresAt: new Date(Date.now() - 1000) } });
  await expect(rateLimit("login:b", opts)).resolves.toBeUndefined();
  const doc = await RateLimit.findOne({ key: "login:b" }).lean();
  expect(doc!.count).toBe(1);
  expect(doc!.expiresAt.getTime()).toBeGreaterThan(Date.now());
});

test("keys are independent", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  for (let i = 0; i < 3; i++) await rateLimit("k1", opts);
  await expect(rateLimit("k1", opts)).rejects.toMatchObject({ status: 429 });
  await expect(rateLimit("k2", opts)).resolves.toBeUndefined();
});

test("concurrent calls on a fresh key lose no increments", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const RateLimit = (await import("@/lib/models/rate-limit")).default;
  const res = await Promise.allSettled(Array.from({ length: 10 }, () => rateLimit("c1", opts)));
  expect(res.filter((r) => r.status === "fulfilled")).toHaveLength(3);
  expect(res.filter((r) => r.status === "rejected")).toHaveLength(7);
  expect(await RateLimit.countDocuments({ key: "c1" })).toBe(1);
  expect((await RateLimit.findOne({ key: "c1" }).lean())!.count).toBe(10);
});

test("concurrent calls at the window boundary create one fresh window", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const RateLimit = (await import("@/lib/models/rate-limit")).default;
  await RateLimit.init();
  await RateLimit.create({ key: "c2", count: 99, expiresAt: new Date(Date.now() - 1000) });
  const res = await Promise.allSettled(Array.from({ length: 10 }, () => rateLimit("c2", opts)));
  expect(res.filter((r) => r.status === "fulfilled")).toHaveLength(3);
  expect(await RateLimit.countDocuments({ key: "c2" })).toBe(1);
  expect((await RateLimit.findOne({ key: "c2" }).lean())!.count).toBe(10);
});
