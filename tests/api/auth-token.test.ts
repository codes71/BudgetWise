import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import bcrypt from "bcryptjs";
import { startMongo, stopMongo, clearDb } from "../helpers/db";
import { req } from "../helpers/request";

let POST: (r: Request, c?: unknown) => Promise<Response>;
let User: typeof import("@/lib/models/user").default;
let userId: string;

beforeAll(async () => {
  await startMongo();
  POST = (await import("@/app/api/v1/auth/token/route")).POST as typeof POST;
  User = (await import("@/lib/models/user")).default;
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
  const u = await User.create({
    email: "Alice@Example.com",
    password: await bcrypt.hash("correct-horse", 10),
    fullName: "Alice A",
  });
  await User.create({ email: "oauth@example.com", fullName: "No Password" });
  userId = String(u._id);
  vi.restoreAllMocks();
});

let ipCounter = 0;
const freshIp = () => `10.0.${Math.floor(++ipCounter / 250)}.${ipCounter % 250}`;
const login = (body: unknown, ip = freshIp()) =>
  POST(req("POST", "/api/v1/auth/token", { body, ip }));

describe("POST /api/v1/auth/token", () => {
  it("valid credentials 200 with verifiable token", async () => {
    const res = await login({ email: "Alice@Example.com", password: "correct-horse" });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.user).toEqual({ id: userId, email: "Alice@Example.com", fullName: "Alice A" });
    expect(new Date(body.expiresAt).getTime()).toBeGreaterThan(Date.now());
    const { verifyMobileToken } = await import("@/lib/mobile/token");
    expect(await verifyMobileToken(body.token)).toEqual({ userId, email: "Alice@Example.com" });
  });

  it("email matched case-insensitively and trimmed", async () => {
    const res = await login({ email: "  alice@example.COM ", password: "correct-horse" });
    expect(res.status).toBe(200);
    expect((await res.json()).user.id).toBe(userId);
  });

  it("case-variant duplicate accounts: exact case wins, no cross-account login", async () => {
    const lower = await User.create({
      email: "bob@example.com",
      password: await bcrypt.hash("lower-pass", 10),
      fullName: "Lower",
    });
    const upper = await User.create({
      email: "BOB@example.com",
      password: await bcrypt.hash("upper-pass", 10),
      fullName: "Upper",
    });
    const a = await login({ email: "BOB@example.com", password: "upper-pass" });
    expect(a.status).toBe(200);
    expect((await a.json()).user.id).toBe(String(upper._id));
    const b = await login({ email: "bob@example.com", password: "lower-pass" });
    expect(b.status).toBe(200);
    expect((await b.json()).user.id).toBe(String(lower._id));
    // exact case with the other account's password is rejected
    expect((await login({ email: "BOB@example.com", password: "lower-pass" })).status).toBe(401);
    // case matching neither: deterministic (oldest variant), other's password rejected
    const c = await login({ email: "Bob@example.com", password: "lower-pass" });
    expect(c.status).toBe(200);
    expect((await c.json()).user.id).toBe(String(lower._id));
    expect((await login({ email: "Bob@example.com", password: "upper-pass" })).status).toBe(401);
  });

  it("wrong password and unknown email both 401 with identical body", async () => {
    const wrong = await login({ email: "alice@example.com", password: "nope" });
    const unknown = await login({ email: "ghost@example.com", password: "nope" });
    expect(wrong.status).toBe(401);
    expect(unknown.status).toBe(401);
    const a = await wrong.text();
    expect(a).toBe(await unknown.text());
    expect(JSON.parse(a).error.code).toBe("invalid_credentials");
  });

  it("unknown email still runs bcrypt compare against a dummy hash", async () => {
    const spy = vi.spyOn(bcrypt, "compare");
    const res = await login({ email: "ghost@example.com", password: "whatever" });
    expect(res.status).toBe(401);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0][0]).toBe("whatever");
    expect(String(spy.mock.calls[0][1])).toMatch(/^\$2[aby]\$10\$/);
  });

  it("user without password (OAuth-style) 401", async () => {
    const spy = vi.spyOn(bcrypt, "compare");
    const res = await login({ email: "oauth@example.com", password: "anything" });
    expect(res.status).toBe(401);
    expect((await res.json()).error.code).toBe("invalid_credentials");
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("11th attempt for same email within window is 429 with Retry-After", async () => {
    for (let i = 0; i < 10; i++) {
      const r = await login({ email: "alice@example.com", password: "bad" });
      expect(r.status).toBe(401);
    }
    const res = await login({ email: "ALICE@example.com", password: "bad" });
    expect(res.status).toBe(429);
    expect(Number(res.headers.get("Retry-After"))).toBeGreaterThan(0);
    expect((await res.json()).error.code).toBe("rate_limited");
  });

  it("31st attempt from the same ip is 429 even for different emails", async () => {
    for (let i = 0; i < 30; i++) {
      const r = await login({ email: `u${i}@example.com`, password: "bad" }, "203.0.113.9");
      expect(r.status).toBe(401);
    }
    const res = await login({ email: "u99@example.com", password: "bad" }, "203.0.113.9");
    expect(res.status).toBe(429);
  });

  it("uses the first x-forwarded-for entry", async () => {
    for (let i = 0; i < 30; i++) {
      await login({ email: `v${i}@example.com`, password: "bad" }, "198.51.100.7, 10.1.1.1");
    }
    const res = await login({ email: "v99@example.com", password: "bad" }, "198.51.100.7, 10.2.2.2");
    expect(res.status).toBe(429);
  });

  it("invalid json 400, bad shape 422", async () => {
    expect((await login("{not json")).status).toBe(400);
    expect((await login({ email: "nope", password: "x" })).status).toBe(422);
    expect((await login({ email: "a@b.co" })).status).toBe(422);
  });
});
