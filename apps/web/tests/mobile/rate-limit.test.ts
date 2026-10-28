import { beforeEach, expect, test } from "vitest";
import { createHash } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";

import { clearDb, testDb } from "../helpers/db";

beforeEach(async () => {
  await clearDb();
});

const opts = { limit: 3, windowSec: 60 };
const doc = (key: string) =>
  testDb().collection("rateLimits").doc(createHash("sha256").update(key).digest("hex"));

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

test("stores key, count and expiresAt under a hashed doc id", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  await rateLimit("auth:ip:10.0.0.1", opts);
  const snap = await doc("auth:ip:10.0.0.1").get();
  expect(snap.get("key")).toBe("auth:ip:10.0.0.1");
  expect(snap.get("count")).toBe(1);
  expect(snap.get("expiresAt")).toBeInstanceOf(Timestamp);
});

test("window resets after expiry", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  for (let i = 0; i < 3; i++) await rateLimit("login:b", opts);
  await expect(rateLimit("login:b", opts)).rejects.toMatchObject({ status: 429 });
  await doc("login:b").update({ expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
  await expect(rateLimit("login:b", opts)).resolves.toBeUndefined();
  const snap = await doc("login:b").get();
  expect(snap.get("count")).toBe(1);
  expect(snap.get("expiresAt").toMillis()).toBeGreaterThan(Date.now());
});

test("keys are independent", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  for (let i = 0; i < 3; i++) await rateLimit("k1", opts);
  await expect(rateLimit("k1", opts)).rejects.toMatchObject({ status: 429 });
  await expect(rateLimit("k2", opts)).resolves.toBeUndefined();
});

test("concurrent calls on a fresh key lose no increments", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const res = await Promise.allSettled(Array.from({ length: 10 }, () => rateLimit("c1", opts)));
  expect(res.filter((r) => r.status === "fulfilled")).toHaveLength(3);
  expect(res.filter((r) => r.status === "rejected")).toHaveLength(7);
  for (const r of res) if (r.status === "rejected") expect(r.reason).toMatchObject({ status: 429 });
  expect((await doc("c1").get()).get("count")).toBe(10);
});

test("concurrent calls at the window boundary create one fresh window", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  await doc("c2").set({ key: "c2", count: 99, expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
  const res = await Promise.allSettled(Array.from({ length: 10 }, () => rateLimit("c2", opts)));
  expect(res.filter((r) => r.status === "fulfilled")).toHaveLength(3);
  const snap = await doc("c2").get();
  expect(snap.get("count")).toBe(10);
  expect(snap.get("expiresAt").toMillis()).toBeGreaterThan(Date.now());
});
