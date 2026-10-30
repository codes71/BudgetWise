import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import bcrypt from "bcryptjs";
import { clearDb } from "../helpers/db";
import { req } from "../helpers/request";

let POST: (r: Request, c?: unknown) => Promise<Response>;
let createUser: typeof import("@/lib/users").createUser;
let userId: string;

const seedUser = async (email: string, password?: string, fullName?: string) => {
  const u = await createUser({
    email,
    passwordHash: password ? await bcrypt.hash(password, 10) : undefined,
    fullName,
  });
  if (!u) throw new Error("seed user already exists");
  return u;
};

beforeAll(async () => {
  POST = (await import("@/app/api/v1/auth/token/route")).POST as typeof POST;
  ({ createUser } = await import("@/lib/users"));
});
beforeEach(async () => {
  await clearDb();
  userId = (await seedUser("Alice@Example.com", "correct-horse", "Alice A")).id;
  await seedUser("oauth@example.com", undefined, "No Password");
  vi.restoreAllMocks();
});

let ipCounter = 0;
const freshIp = () => `10.0.${Math.floor(++ipCounter / 250)}.${ipCounter % 250}`;
const login = (body: unknown, ip = freshIp()) =>
  POST(req("POST", "/api/v1/auth/token", { body, ip }));

describe("POST /api/v1/auth/token", () => {
  it("accepts non-RFC emails that web signup allows (test@localhost)", async () => {
    const u = await seedUser("test@localhost", "correct-horse", "Local");
    const res = await login({ email: "test@localhost", password: "correct-horse" });
    expect(res.status).toBe(200);
    expect((await res.json()).user.id).toBe(u.id);
  });

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

  it("logs in an account whose doc id is a copied Mongo ObjectId", async () => {
    const { testDb } = await import("../helpers/db");
    const id = "64b0000000000000000000b1";
    await testDb().collection("users").doc(id).set({
      email: "Copied@Example.com",
      emailLower: "copied@example.com",
      password: await bcrypt.hash("old-pass", 10),
      fullName: "Copied",
    });
    const res = await login({ email: "copied@example.com", password: "old-pass" });
    expect(res.status).toBe(200);
    expect((await res.json()).user).toEqual({ id, email: "Copied@Example.com", fullName: "Copied" });
  });

  it("looks the account up with a single query whether or not it exists", async () => {
    const { testDb } = await import("../helpers/db");
    const Query = Object.getPrototypeOf(testDb().collection("users").where("emailLower", "==", "x"));
    const spy = vi.spyOn(Query, "get");
    await login({ email: "alice@example.com", password: "correct-horse" });
    expect(spy).toHaveBeenCalledTimes(1);
    spy.mockClear();
    await login({ email: "ghost@example.com", password: "whatever" });
    expect(spy).toHaveBeenCalledTimes(1);
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
    expect((await login({ email: "", password: "x" })).status).toBe(422);
    expect((await login({ email: "a@b.co" })).status).toBe(422);
  });
});
