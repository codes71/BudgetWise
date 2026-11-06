import { beforeEach, expect, test } from "vitest";
import { createHash } from "node:crypto";
import { clearDb, seedAccount, testDb } from "../helpers/db";

beforeEach(clearDb);
const opts = { limit: 3, windowSec: 60 };
const keyId = (key: string) => createHash("sha256").update(key).digest("hex");
const read = (key: string) => testDb().collection("rateLimits").findOne({ _id: keyId(key) });

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

test("stores key, count and expiresAt under a hashed id", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  await rateLimit("auth:ip:10.0.0.1", opts);
  const doc = (await read("auth:ip:10.0.0.1"))!;
  expect(doc.key).toBe("auth:ip:10.0.0.1");
  expect(doc.count).toBe(1);
  expect(doc.expiresAt).toBeInstanceOf(Date);
});

test("window resets after expiry", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  for (let i = 0; i < 3; i++) await rateLimit("login:b", opts);
  await expect(rateLimit("login:b", opts)).rejects.toMatchObject({ status: 429 });
  await testDb().collection("rateLimits").updateOne({ _id: keyId("login:b") }, { $set: { expiresAt: new Date(Date.now() - 1000) } });
  await expect(rateLimit("login:b", opts)).resolves.toBeUndefined();
  const doc = (await read("login:b"))!;
  expect(doc.count).toBe(1);
  expect(doc.expiresAt.getTime()).toBeGreaterThan(Date.now());
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
  expect((await read("c1"))!.count).toBe(10);
});

test("concurrent calls at the window boundary create one fresh window", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  await testDb().collection("rateLimits").insertOne({ _id: keyId("c2"), key: "c2", count: 99, expiresAt: new Date(Date.now() - 1000) });
  const res = await Promise.allSettled(Array.from({ length: 10 }, () => rateLimit("c2", opts)));
  expect(res.filter((r) => r.status === "fulfilled")).toHaveLength(3);
  const doc = (await read("c2"))!;
  expect(doc.count).toBe(10);
  expect(doc.expiresAt.getTime()).toBeGreaterThan(Date.now());
});

test("an earlier valid account identity cannot recreate scan or voice counters after deletion", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  const { signMobileToken } = await import("@/lib/mobile/token");
  const { requireUser } = await import("@/lib/mobile/bearer");
  await seedAccount("deleted", "deleted@example.com");
  const { token } = await signMobileToken({ id: "deleted", email: "deleted@example.com" });
  const identity = await requireUser(new Request("http://localhost/x", { headers: { authorization: `Bearer ${token}` } }));
  await testDb().collection("users").deleteOne({ _id: identity.userId });
  for (const purpose of ["scan", "voice", "delete-account"]) {
    const key = `${purpose}:user:${identity.userId}`;
    await expect(rateLimit(key, opts)).rejects.toMatchObject({ status: 401 });
    expect(await read(key)).toBeNull();
  }
});

test("only the deletion retry limiter may write while the account deletion lock is held", async () => {
  const { rateLimit } = await import("@/lib/mobile/rate-limit");
  await seedAccount("deleting", "deleting@example.com");
  await testDb().collection("users").updateOne({ _id: "deleting" }, { $set: { deletionStartedAt: new Date() } });
  for (const purpose of ["scan", "voice"]) {
    const key = `${purpose}:user:deleting`;
    await expect(rateLimit(key, opts)).rejects.toMatchObject({ status: 401 });
    expect(await read(key)).toBeNull();
  }
  await expect(rateLimit("delete-account:user:deleting", opts)).resolves.toBeUndefined();
  expect((await read("delete-account:user:deleting"))!.count).toBe(1);
  await expect(rateLimit("auth:ip:203.0.113.5", opts)).resolves.toBeUndefined();
});
