import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import bcrypt from "bcryptjs";
import { clearDb } from "../helpers/db";
import { req } from "../helpers/request";

let POST: (r: Request, c?: unknown) => Promise<Response>;
let findUserByEmail: typeof import("@/lib/users").findUserByEmail;
let createUser: typeof import("@/lib/users").createUser;

beforeAll(async () => {
  POST = (await import("@/app/api/v1/auth/register/route")).POST as typeof POST;
  ({ findUserByEmail, createUser } = await import("@/lib/users"));
});
beforeEach(async () => {
  await clearDb();
});

let ipCounter = 0;
const freshIp = () => `10.1.${Math.floor(++ipCounter / 250)}.${ipCounter % 250}`;
const register = (body: unknown, ip = freshIp()) =>
  POST(req("POST", "/api/v1/auth/register", { body, ip }));

describe("POST /api/v1/auth/register", () => {
  it("creates the account and signs it in", async () => {
    const res = await register({ email: " Aye@Example.com ", password: "teashop26", fullName: " Aye Chan " });
    expect(res.status).toBe(201);
    const body = await res.json();
    expect(body.user).toEqual({ id: expect.any(String), email: "Aye@Example.com", fullName: "Aye Chan" });
    const { verifyMobileToken } = await import("@/lib/mobile/token");
    expect(await verifyMobileToken(body.token)).toEqual({ userId: body.user.id, email: "Aye@Example.com" });
    const stored = await findUserByEmail("aye@example.com");
    expect(stored?.id).toBe(body.user.id);
    expect(await bcrypt.compare("teashop26", stored!.password!)).toBe(true);
    expect(stored!.password).toMatch(/^\$2[aby]\$10\$/);
  });

  it("names the account after the email like the web signup when no name is given", async () => {
    const res = await register({ email: "mg.mg@example.com", password: "teashop26" });
    expect(res.status).toBe(201);
    expect((await res.json()).user.fullName).toBe("mg.mg");
  });

  it("409 email_taken when the email exists in any letter case", async () => {
    await createUser({ email: "aye@example.com", passwordHash: await bcrypt.hash("other-pass", 10) });
    const res = await register({ email: "AYE@example.com", password: "teashop26" });
    expect(res.status).toBe(409);
    expect((await res.json()).error.code).toBe("email_taken");
  });

  it("422 for a short password or an email without @", async () => {
    expect((await register({ email: "aye@example.com", password: "tea2" })).status).toBe(422);
    expect((await register({ email: "aye.example.com", password: "teashop26" })).status).toBe(422);
    expect((await register({ email: "", password: "teashop26" })).status).toBe(422);
    expect(await findUserByEmail("aye@example.com")).toBeNull();
  });

  it("400 for a body that is not JSON", async () => {
    const res = await register("{not json");
    expect(res.status).toBe(400);
    expect((await res.json()).error.code).toBe("invalid_json");
  });

  it("21st sign up from one address within the hour is 429", async () => {
    const ip = freshIp();
    for (let i = 0; i < 20; i++) {
      expect((await register({ email: `u${i}@example.com`, password: "teashop26" }, ip)).status).toBe(201);
    }
    const res = await register({ email: "u20@example.com", password: "teashop26" }, ip);
    expect(res.status).toBe(429);
    expect(Number(res.headers.get("Retry-After"))).toBeGreaterThan(0);
  });
});
