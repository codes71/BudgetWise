import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearDb, testDb } from "../helpers/db";
import bcrypt from "bcryptjs";
import { req } from "../helpers/request";
import { scopedId } from "@/lib/mongodb";
import { yangonDate } from "@/lib/mobile/scan";

const { jar, requestHeaders } = vi.hoisted(() => ({ jar: new Map<string, string>(), requestHeaders: new Headers() }));

vi.mock("next/headers", () => ({
  headers: vi.fn(async () => requestHeaders),
  cookies: vi.fn(async () => ({
    get: (name: string) => (jar.has(name) ? { name, value: jar.get(name)! } : undefined),
    set: (name: string, value: string) => void jar.set(name, value),
  })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

let actions: typeof import("@/app/actions");

const form = (fields: Record<string, string>) => {
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.set(k, v);
  return fd;
};

beforeAll(async () => {
  actions = await import("@/app/actions");
});
beforeEach(async () => {
  await clearDb();
  jar.clear();
  requestHeaders.set("x-forwarded-for", "203.0.113.10");
  vi.restoreAllMocks();
});

describe("signUp and signIn", () => {
  it("creates the user, claims the email and signs in", async () => {
    const res = await actions.signUp(form({ email: "  Dana@Example.com ", password: "pw-123456" }));
    if (!("user" in res)) throw new Error(res.error);
    expect(res.user).toMatchObject({ email: "Dana@Example.com", fullName: "Dana" });
    const user = await testDb().collection("users").findOne({ _id: res.user.userId });
    expect(user).toMatchObject({ email: "Dana@Example.com", emailLower: "dana@example.com" });
    expect(user!.password).toMatch(/^\$2[aby]\$10\$/);
    const emails = await testDb().collection("userEmails").find({  }).toArray();
    expect(emails.map((d) => ({ userId: d.userId }))).toEqual([{ userId: res.user.userId }]);
    expect(jar.get("session")).toBeTruthy();
  });

  it("rejects a case-variant duplicate email", async () => {
    await actions.signUp(form({ email: "dana@example.com", password: "pw-123456" }));
    const res = await actions.signUp(form({ email: "DANA@Example.COM", password: "pw-234567" }));
    expect(res).toEqual({ error: "User with this email already exists." });
    expect((await testDb().collection("users").find({  }).toArray()).length).toBe(1);
  });

  it("concurrent signups for one email create one account", async () => {
    const results = await Promise.all(
      ["race@example.com", "Race@example.com", "RACE@example.com"].map((email) =>
        actions.signUp(form({ email, password: "pw-123456" })),
      ),
    );
    expect(results.filter((r) => "user" in r)).toHaveLength(1);
    expect((await testDb().collection("users").find({  }).toArray()).length).toBe(1);
  });

  it("signs in case-insensitively and refuses a wrong password", async () => {
    await actions.signUp(form({ email: "erin@example.com", password: "right-password" }));
    jar.clear();
    const ok = await actions.signIn(form({ email: "ERIN@example.com", password: "right-password" }));
    expect("user" in ok && ok.user.email).toBe("erin@example.com");
    expect(await actions.signIn(form({ email: "erin@example.com", password: "wrong" }))).toEqual({
      error: "Invalid email or password.",
    });
    expect(await actions.signIn(form({ email: "nobody@example.com", password: "right" }))).toEqual({
      error: "Invalid email or password.",
    });
  });
});

describe("shared authentication controls", () => {
  it("validates signup on the server and rejects file-valued fields without hashing or creating accounts", async () => {
    const spy = vi.spyOn(bcrypt, "hash");
    for (const input of [
      { email: "not-an-email", password: "password" },
      { email: "valid@example.com", password: "short" },
      { email: "valid@example.com", password: "x".repeat(201) },
    ]) expect(await actions.signUp(form(input))).toHaveProperty("error");
    const fileForm = form({ email: "valid@example.com" });
    fileForm.set("password", new Blob(["password"]), "password.txt");
    expect(await actions.signUp(fileForm)).toHaveProperty("error");
    expect(await actions.signIn(fileForm)).toEqual({ error: "Invalid email or password." });
    expect(spy).not.toHaveBeenCalled();
    expect(await testDb().collection("users").countDocuments()).toBe(0);
  });

  it("does one bcrypt comparison for missing, passwordless, deleting and wrong-password accounts", async () => {
    const { createUser } = await import("@/lib/users");
    const deleting = await createUser({ email: "deleting@example.com", passwordHash: await bcrypt.hash("password", 10) });
    await testDb().collection("users").updateOne({ _id: deleting!.id }, { $set: { deletionStartedAt: new Date() } });
    await createUser({ email: "passwordless@example.com" });
    await createUser({ email: "wrong@example.com", passwordHash: await bcrypt.hash("password", 10) });
    const spy = vi.spyOn(bcrypt, "compare");
    for (const email of ["missing@example.com", "passwordless@example.com", "deleting@example.com", "wrong@example.com"]) {
      spy.mockClear();
      expect(await actions.signIn(form({ email, password: "incorrect" }))).toEqual({ error: "Invalid email or password." });
      expect(spy).toHaveBeenCalledTimes(1);
      expect(String(spy.mock.calls[0][1])).toMatch(/^\$2[aby]\$10\$/);
    }
    expect(jar.has("session")).toBe(false);
  });

  it("preserves legacy short and over-200-character passwords and non-RFC account emails", async () => {
    const { createUser } = await import("@/lib/users");
    for (const [email, password] of [["legacy@localhost", "x"], ["long@example.com", "p".repeat(300)]]) {
      await createUser({ email, passwordHash: await bcrypt.hash(password, 10) });
      expect(await actions.signIn(form({ email, password }))).toHaveProperty("user");
    }
  });

  it("shares the normalized email login allowance across browser and mobile", async () => {
    const mobileLogin = (await import("@/app/api/v1/auth/token/route")).POST;
    for (let i = 0; i < 5; i++) {
      expect(await actions.signIn(form({ email: "  Mixed@Example.COM ", password: "bad" }))).toEqual({ error: "Invalid email or password." });
      expect((await mobileLogin(req("POST", "/api/v1/auth/token", { body: { email: "mixed@example.com", password: "bad" }, ip: `10.0.0.${i}` }), undefined)).status).toBe(401);
    }
    const spy = vi.spyOn(bcrypt, "compare");
    expect(await actions.signIn(form({ email: "mixed@example.com", password: "bad" }))).toEqual({ error: "Too many requests. Please try again later." });
    expect((await mobileLogin(req("POST", "/api/v1/auth/token", { body: { email: "MIXED@example.com", password: "bad" }, ip: "10.0.1.1" }), undefined)).status).toBe(429);
    expect(spy).not.toHaveBeenCalled();
  });

  it("shares the first forwarded IP login allowance across browser and mobile", async () => {
    const mobileLogin = (await import("@/app/api/v1/auth/token/route")).POST;
    requestHeaders.set("x-forwarded-for", "198.51.100.7, 10.1.1.1");
    for (let i = 0; i < 15; i++) {
      expect(await actions.signIn(form({ email: `web${i}@example.com`, password: "bad" }))).toEqual({ error: "Invalid email or password." });
      expect((await mobileLogin(req("POST", "/api/v1/auth/token", { body: { email: `mobile${i}@example.com`, password: "bad" }, ip: "198.51.100.7, 10.2.2.2" }), undefined)).status).toBe(401);
    }
    expect(await actions.signIn(form({ email: "last@example.com", password: "bad" }))).toHaveProperty("error", "Too many requests. Please try again later.");
  });

  it("shares signup IP allowance across browser and mobile without spending login counters", async () => {
    const mobileRegister = (await import("@/app/api/v1/auth/register/route")).POST;
    for (let i = 0; i < 10; i++) {
      expect(await actions.signUp(form({ email: `web${i}@example.com`, password: "password" }))).toHaveProperty("user");
      expect((await mobileRegister(req("POST", "/api/v1/auth/register", { body: { email: `mobile${i}@example.com`, password: "password" }, ip: "203.0.113.10" }), undefined)).status).toBe(201);
    }
    expect(await actions.signUp(form({ email: "last@example.com", password: "password" }))).toEqual({ error: "Too many requests. Please try again later." });
    expect(await testDb().collection("rateLimits").countDocuments({ key: /^auth:/ })).toBe(0);
  });
});

describe("getUserDetails and updateUser", () => {
  it("returns the signed-in user without the password and updates the profile", async () => {
    const res = await actions.signUp(form({ email: "fay@example.com", password: "pw-123456" }));
    if (!("user" in res)) throw new Error(res.error);
    const id = res.user.userId;

    const details = await actions.getUserDetails();
    expect(details).toMatchObject({ _id: id, userId: id, email: "fay@example.com", fullName: "fay" });
    expect(details).not.toHaveProperty("password");
    expect(typeof details.createdAt).toBe("string");

    const updated = await actions.updateUser(
      form({ fullName: "Fay F", phoneNumber: "+95 9 123", profilePhotoUrl: "https://placehold.co/64" }),
    );
    expect(updated).toMatchObject({ success: true, user: { _id: id, fullName: "Fay F", phoneNumber: "+95 9 123" } });
    expect(updated.user).not.toHaveProperty("password");
    expect((await testDb().collection("users").findOne({ _id: id }))!.fullName).toBe("Fay F");
    // The refreshed session carries the new name.
    expect(await actions.getUserDetails()).toMatchObject({ fullName: "Fay F", profilePhotoUrl: "https://placehold.co/64" });
  });

  it("returns null for a session whose user is gone", async () => {
    const res = await actions.signUp(form({ email: "gone@example.com", password: "pw-123456" }));
    if (!("user" in res)) throw new Error(res.error);
    await testDb().collection("users").deleteOne({ _id: res.user.userId });
    expect(await actions.getUserDetails()).toBeNull();
    expect(await actions.updateUser(form({ fullName: "x" }))).toEqual({ error: "User not found" });
  });
});


describe("budget observations use authenticated server data", () => {
  it("requires sign-in instead of comparing arbitrary caller data", async () => {
    expect(await actions.generateSuggestions([], [])).toEqual({ error: 'Sign in to compare your saved spending and budgets.' });
  });
  it("ignores client financial arrays and isolates the current owner and month", async () => {
    const signed = await actions.signUp(form({ email: 'compare@example.com', password: 'synthetic-password' }));
    if (!('user' in signed)) throw new Error(signed.error);
    const userId = signed.user.userId;
    const today = yangonDate(new Date());
    const row = { userId, category: 'Food', amount: 12_000, type: 'expense', date: today };
    await testDb().collection('transactions').insertMany([
      { ...row, _id: scopedId(userId, 'own') },
      { ...row, _id: scopedId('another-owner', 'other'), userId: 'another-owner', category: 'OTHER-OWNER-PRIVATE', amount: 900_000 },
      { ...row, _id: scopedId(userId, 'old'), date: '2020-01-01', amount: 700_000 },
      { ...row, _id: scopedId(userId, 'future'), date: '2099-01-01', amount: 800_000 },
    ]);
    await testDb().collection('budgets').insertOne({ _id: scopedId(userId, 'food'), userId, category: 'Food', limit: 10_000 });
    const result = await actions.generateSuggestions([{ category: 'CLIENT-INJECTED', amount: 999_999 }] as never, []);
    expect(result.suggestions).toHaveLength(1);
    expect(result.suggestions?.[0]).toContain('12,000 Ks. This is 2,000 Ks over the 10,000 Ks budget');
    expect(JSON.stringify(result)).not.toContain('OTHER-OWNER-PRIVATE');
    expect(JSON.stringify(result)).not.toContain('CLIENT-INJECTED');
  });
});
