import { beforeEach, expect, test } from "vitest";
import { createHash } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";

import { clearDb, seedAccount, testDb } from "../helpers/db";

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

test("an earlier valid account identity cannot recreate scan or voice counters after deletion", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const { signMobileToken } = await import("@/lib/mobile/token");
  const { requireUser } = await import("@/lib/mobile/bearer");
  await seedAccount("deleted", "deleted@example.com");
  const { token } = await signMobileToken({ id: "deleted", email: "deleted@example.com" });
  const identity = await requireUser(new Request("http://localhost/x", { headers: { authorization: `Bearer ${token}` } }));
  await testDb().collection("users").doc(identity.userId).delete();
  for (const purpose of ["scan", "voice", "delete-account"]) {
    const key = `${purpose}:user:${identity.userId}`;
    await expect(rateLimit(key, opts)).rejects.toMatchObject({ status: 401 });
    expect((await doc(key).get()).exists).toBe(false);
  }
});

test("only the deletion retry limiter may write while the account deletion lock is held", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  await seedAccount("deleting", "deleting@example.com");
  await testDb().collection("users").doc("deleting").update({ deletionStartedAt: new Date() });
  for (const purpose of ["scan", "voice"]) {
    const key = `${purpose}:user:deleting`;
    await expect(rateLimit(key, opts)).rejects.toMatchObject({ status: 401 });
    expect((await doc(key).get()).exists).toBe(false);
  }
  await expect(rateLimit("delete-account:user:deleting", opts)).resolves.toBeUndefined();
  expect((await doc("delete-account:user:deleting").get()).get("count")).toBe(1);
  await expect(rateLimit("auth:ip:203.0.113.5", opts)).resolves.toBeUndefined();
});
