import { describe, it, expect, afterEach } from "vitest";
import { SignJWT, jwtVerify, decodeJwt } from "jose";
import * as z from "zod/v4";
import { signMobileToken, verifyMobileToken } from "@/lib/mobile/token";
import { requireUser } from "@/lib/mobile/bearer";
import { ApiError, handle } from "@/lib/mobile/errors";
import { req } from "../helpers/request";

const secret = () => new TextEncoder().encode(process.env.JWT_SECRET!);
const ORIGINAL = process.env.JWT_SECRET;
afterEach(() => {
  process.env.JWT_SECRET = ORIGINAL;
});

describe("mobile token", () => {
  it("round-trips claims sub/email/aud and 30d expiry", async () => {
    const { token, expiresAt } = await signMobileToken({ id: "u1", email: "a@b.com" });
    const { payload, protectedHeader } = await jwtVerify(token, secret(), {
      audience: "budgetwise-mobile",
    });
    expect(protectedHeader.alg).toBe("HS256");
    expect(payload.sub).toBe("u1");
    expect(payload.email).toBe("a@b.com");
    expect(payload.aud).toBe("budgetwise-mobile");
    expect(payload.exp! - payload.iat!).toBe(30 * 24 * 3600);
    expect(new Date(expiresAt).getTime()).toBe(payload.exp! * 1000);
    expect(await verifyMobileToken(token)).toEqual({ userId: "u1", email: "a@b.com" });
  });

  it("rejects web session JWT as bearer", async () => {
    const web = await new SignJWT({ userId: "u1", email: "a@b.com" })
      .setProtectedHeader({ alg: "HS256" })
      .setExpirationTime("1h")
      .sign(secret());
    await expect(verifyMobileToken(web)).rejects.toThrow();
    await expect(requireUser(req("GET", "/x", { token: web }))).rejects.toMatchObject({
      status: 401,
      code: "unauthorized",
    });
  });

  it("rejects expired and wrong-alg tokens", async () => {
    const expired = await new SignJWT({ email: "a@b.com" })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject("u1")
      .setAudience("budgetwise-mobile")
      .setIssuedAt(Math.floor(Date.now() / 1000) - 7200)
      .setExpirationTime(Math.floor(Date.now() / 1000) - 3600)
      .sign(secret());
    await expect(verifyMobileToken(expired)).rejects.toThrow();
    const hs512 = await new SignJWT({ email: "a@b.com" })
      .setProtectedHeader({ alg: "HS512" })
      .setSubject("u1")
      .setAudience("budgetwise-mobile")
      .setExpirationTime("1h")
      .sign(secret());
    await expect(verifyMobileToken(hs512)).rejects.toThrow();
    const none = [
      Buffer.from('{"alg":"none"}').toString("base64url"),
      Buffer.from(
        JSON.stringify({ sub: "u1", email: "a@b.com", aud: "budgetwise-mobile", exp: 9999999999 }),
      ).toString("base64url"),
      "",
    ].join(".");
    await expect(verifyMobileToken(none)).rejects.toThrow();
  });

  it("rejects wrong audience and missing sub/email", async () => {
    const mk = (aud: string, sub?: string, email?: string) => {
      const j = new SignJWT({ ...(email ? { email } : {}) })
        .setProtectedHeader({ alg: "HS256" })
        .setAudience(aud)
        .setExpirationTime("1h");
      if (sub) j.setSubject(sub);
      return j.sign(secret());
    };
    await expect(verifyMobileToken(await mk("other", "u1", "a@b.com"))).rejects.toThrow();
    await expect(verifyMobileToken(await mk("budgetwise-mobile", undefined, "a@b.com"))).rejects.toThrow();
    await expect(verifyMobileToken(await mk("budgetwise-mobile", "u1"))).rejects.toThrow();
  });

  it("mobile token is not a valid web session", async () => {
    const { token } = await signMobileToken({ id: "u1", email: "a@b.com" });
    const payload = decodeJwt(token);
    expect(typeof (payload as any).userId === "string").toBe(false);
  });

  it("throws when JWT_SECRET missing or short", async () => {
    const { token } = await signMobileToken({ id: "u1", email: "a@b.com" });
    delete process.env.JWT_SECRET;
    await expect(signMobileToken({ id: "u1", email: "a@b.com" })).rejects.toThrow(/JWT_SECRET/);
    await expect(verifyMobileToken(token)).rejects.toThrow(/JWT_SECRET/);
    process.env.JWT_SECRET = "short-secret-31-chars-aaaaaaaaa"; // 31
    expect(process.env.JWT_SECRET.length).toBe(31);
    await expect(signMobileToken({ id: "u1", email: "a@b.com" })).rejects.toThrow(/JWT_SECRET/);
    await expect(verifyMobileToken(token)).rejects.toThrow(/JWT_SECRET/);
    // the web fallback secret must not verify mobile tokens
    process.env.JWT_SECRET = "";
    await expect(signMobileToken({ id: "u1", email: "a@b.com" })).rejects.toThrow(/JWT_SECRET/);
  });
});

describe("requireUser", () => {
  it("accepts a valid bearer token", async () => {
    const { token } = await signMobileToken({ id: "u1", email: "a@b.com" });
    expect(await requireUser(req("GET", "/x", { token }))).toEqual({ userId: "u1", email: "a@b.com" });
  });

  it("rejects missing, malformed and non-Bearer headers with 401", async () => {
    const bad = [
      new Request("http://localhost/x"),
      new Request("http://localhost/x", { headers: { authorization: "Bearer" } }),
      new Request("http://localhost/x", { headers: { authorization: "Basic abc" } }),
      new Request("http://localhost/x", { headers: { authorization: "Bearer not.a.jwt" } }),
    ];
    for (const r of bad) {
      await expect(requireUser(r)).rejects.toMatchObject({ status: 401, code: "unauthorized" });
    }
  });

  it("maps JWT_SECRET misconfiguration to 500 not 401", async () => {
    const { token } = await signMobileToken({ id: "u1", email: "a@b.com" });
    delete process.env.JWT_SECRET;
    const res = await handle(async (r) => {
      await requireUser(r);
      return Response.json({});
    })(req("GET", "/x", { token }), {});
    expect(res.status).toBe(500);
    expect((await res.json()).error.code).toBe("internal_error");
  });
});

describe("handle", () => {
  it("maps ApiError, ZodError, unknown errors to the envelope", async () => {
    const api = await handle(async () => {
      throw new ApiError(409, "conflict", "nope");
    })(req("GET", "/x"), {});
    expect(api.status).toBe(409);
    expect(await api.json()).toEqual({ error: { code: "conflict", message: "nope" } });

    const zod = await handle(async () => {
      z.object({ a: z.string() }).parse({});
      return Response.json({});
    })(req("GET", "/x"), {});
    expect(zod.status).toBe(422);
    expect((await zod.json()).error.code).toBe("validation_failed");

    const boom = await handle(async () => {
      throw new Error("secret stack detail");
    })(req("GET", "/x"), {});
    expect(boom.status).toBe(500);
    const body = await boom.json();
    expect(body).toEqual({ error: { code: "internal_error", message: "Internal server error" } });
  });

  it("sets Retry-After and passes through success", async () => {
    const r = await handle(async () => {
      throw new ApiError(429, "rate_limited", "slow down", 42);
    })(req("GET", "/x"), {});
    expect(r.status).toBe(429);
    expect(r.headers.get("retry-after")).toBe("42");
    const ok = await handle(async () => Response.json({ ok: 1 }, { status: 201 }))(req("GET", "/x"), {});
    expect(ok.status).toBe(201);
  });
});
