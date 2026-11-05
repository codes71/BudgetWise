import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import bcrypt from "bcryptjs";
import { SignJWT } from "jose";
import { clearDb, testDb } from "../helpers/db";
import { req } from "../helpers/request";
import { createUser, emailDocId, updateUserProfile } from "@/lib/users";
import { signMobileToken } from "@/lib/mobile/token";
import { sha256Hex } from "@/lib/firestore";
import { requireUser } from "@/lib/mobile/bearer";
import { saveBatch } from "@/lib/mobile/transactions-service";
import { saveEndCheck, saveMonthPlan, setBudgetLimit } from "@/lib/budgets";

let DELETE: (request: Request, context?: unknown) => Promise<Response>;
let LOGIN: (request: Request, context?: unknown) => Promise<Response>;
let userA: string;
let userB: string;
let tokenA: string;
const password = "deletion-test-password";
const user = (id: string) => testDb().collection("users").doc(id);
const remove = (token = tokenA, body: unknown = { password }) => DELETE(req("DELETE", "/api/v1/account", { token, body }));

beforeAll(async () => {
  DELETE = (await import("@/app/api/v1/account/route")).DELETE as typeof DELETE;
  LOGIN = (await import("@/app/api/v1/auth/token/route")).POST as typeof LOGIN;
});

beforeEach(async () => {
  vi.restoreAllMocks();
  await clearDb();
  const hash = await bcrypt.hash(password, 10);
  userA = (await createUser({ email: "delete-a@example.com", passwordHash: hash }))!.id;
  userB = (await createUser({ email: "keep-b@example.com", passwordHash: hash }))!.id;
  tokenA = (await signMobileToken({ id: userA, email: "delete-a@example.com" })).token;
});

async function seedFinancialData() {
  for (const id of [userA, userB]) {
    await user(id).collection("transactions").doc("entry").set({ userId: id, amount: 100, transcript: "private words" });
    await user(id).collection("budgets").doc("food").set({ userId: id, category: "Food", limit: 500 });
    await user(id).collection("budgetMonths").doc("2026-11").set({ month: "2026-11", newMoney: 500, carriedOver: 0, shares: [] });
    await user(id).collection("categories").doc("personal").set({ name: "Personal" });
    await user(id).collection("future-records").doc("nested").collection("details").doc("one").set({ value: 1 });
  }
}

describe("DELETE /api/v1/account", () => {
  it("deletes the authenticated account's complete subtree and email claim, preserving every other account", async () => {
    await seedFinancialData();
    const keys = [`scan:user:${userA}`, `voice:user:${userA}`, `auth:email:${sha256Hex("delete-a@example.com")}`];
    for (const key of [...keys, "auth:ip:203.0.113.1", `scan:user:${userB}`]) {
      await testDb().collection("rateLimits").doc(sha256Hex(key)).set({ key, count: 1 });
    }
    const response = await remove();
    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");
    expect((await user(userA).get()).exists).toBe(false);
    expect(await user(userA).listCollections()).toHaveLength(0);
    expect((await testDb().collection("userEmails").doc(emailDocId("delete-a@example.com")).get()).exists).toBe(false);
    expect((await user(userB).get()).exists).toBe(true);
    expect((await user(userB).collection("transactions").doc("entry").get()).exists).toBe(true);
    expect((await user(userB).collection("future-records").doc("nested").collection("details").doc("one").get()).exists).toBe(true);
    for (const key of keys) expect((await testDb().collection("rateLimits").doc(sha256Hex(key)).get()).exists).toBe(false);
    expect((await testDb().collection("rateLimits").doc(sha256Hex("auth:ip:203.0.113.1")).get()).exists).toBe(true);
    await expect(requireUser(req("GET", "/api/v1/transactions", { token: tokenA }))).rejects.toMatchObject({ status: 401 });
  });

  it("requires both a valid bearer and the current password, with no body-selected target", async () => {
    expect((await DELETE(req("DELETE", "/api/v1/account", { body: { password } }))).status).toBe(401);
    const wrong = await remove(tokenA, { password: "wrong" });
    expect(wrong.status).toBe(401);
    expect((await wrong.json()).error.code).toBe("invalid_password");
    expect((await remove(tokenA, { password, userId: userB })).status).toBe(422);
    expect((await remove(tokenA, { password: "" })).status).toBe(422);
    expect((await user(userA).get()).get("deletionStartedAt")).toBeUndefined();
    expect((await user(userB).get()).exists).toBe(true);
  });

  it("keeps a failed partial deletion locked and permits password-verified retry through existing login", async () => {
    await seedFinancialData();
    const failure = vi.spyOn(testDb(), "recursiveDelete").mockRejectedValueOnce(new Error("temporary failure"));
    const first = await remove();
    expect(first.status).toBe(503);
    expect((await first.json()).error.code).toBe("deletion_incomplete");
    expect((await user(userA).get()).get("deletionStartedAt")).toBeTruthy();
    await expect(requireUser(req("GET", "/x", { token: tokenA }))).rejects.toMatchObject({ status: 401 });
    const login = await LOGIN(req("POST", "/api/v1/auth/token", {
      body: { email: "delete-a@example.com", password }, ip: "203.0.113.3",
    }));
    expect(login.status).toBe(200);
    const retryToken = (await login.json()).token;
    await expect(requireUser(req("GET", "/x", { token: retryToken }))).rejects.toMatchObject({ status: 401 });
    failure.mockRestore();
    expect((await remove(retryToken)).status).toBe(204);
    expect((await user(userA).get()).exists).toBe(false);
  });

  it("blocks all account-owned writers while recursive deletion is in progress", async () => {
    await seedFinancialData();
    const original = testDb().recursiveDelete.bind(testDb());
    let unlock!: () => void;
    let entered!: () => void;
    const paused = new Promise<void>((resolve) => { entered = resolve; });
    const resume = new Promise<void>((resolve) => { unlock = resolve; });
    vi.spyOn(testDb(), "recursiveDelete").mockImplementation(async (reference) => {
      entered(); await resume; return original(reference);
    });
    const deletion = remove();
    await paused;
    try {
      const item = { clientId: "00000000-0000-4000-8000-000000000001", type: "expense" as const,
        amount: 1, category: "Food", description: "late sync", date: "2026-11-01", source: "manual" as const };
      await expect(saveBatch(userA, [item])).rejects.toMatchObject({ status: 401 });
      await expect(setBudgetLimit(userA, "Food", 1)).rejects.toMatchObject({ status: 401 });
      await expect(saveMonthPlan(userA, "2026-11", { newMoney: 1, carriedOver: 0, shares: [] })).rejects.toMatchObject({ status: 401 });
      await expect(saveEndCheck(userA, "2026-11", 1)).rejects.toMatchObject({ status: 401 });
      expect(await updateUserProfile(userA, { fullName: "late", phoneNumber: null, profilePhotoUrl: null })).toBeNull();
    } finally { unlock(); }
    expect((await deletion).status).toBe(204);
    expect(await user(userA).listCollections()).toHaveLength(0);
  });

  it("is idempotent after a lost success response and never deletes a re-registered email owner", async () => {
    expect((await remove()).status).toBe(204);
    const replacement = await createUser({ email: "delete-a@example.com", passwordHash: await bcrypt.hash(password, 10) });
    expect(replacement!.id).not.toBe(userA);
    expect((await remove(tokenA, { password: "already gone" })).status).toBe(204);
    expect((await testDb().collection("userEmails").doc(emailDocId("delete-a@example.com")).get()).get("userId")).toBe(replacement!.id);
    expect((await user(replacement!.id).get()).exists).toBe(true);
  });

  it("cleans old orphan subcollections and owned email claims before a missing-root204", async () => {
    await seedFinancialData();
    await user(userA).delete();
    expect((await remove(tokenA, { password: "root already gone" })).status).toBe(204);
    expect(await user(userA).listCollections()).toHaveLength(0);
    expect((await testDb().collection("userEmails").doc(emailDocId("delete-a@example.com")).get()).exists).toBe(false);
    expect((await user(userB).collection("transactions").doc("entry").get()).exists).toBe(true);
  });

  it("does not remove an email-index entry owned by a different account", async () => {
    await testDb().collection("userEmails").doc(emailDocId("delete-a@example.com")).set({ userId: userB });
    const emailRateRef = testDb().collection("rateLimits").doc(sha256Hex(`auth:email:${sha256Hex("delete-a@example.com")}`));
    await emailRateRef.set({ count: 4 });
    expect((await remove()).status).toBe(204);
    expect((await testDb().collection("userEmails").doc(emailDocId("delete-a@example.com")).get()).get("userId")).toBe(userB);
    expect((await user(userB).get()).exists).toBe(true);
    expect((await emailRateRef.get()).get("count")).toBe(4);
  });

  it("expires only a matching valid browser session cookie", async () => {
    const cookie = async (id: string) => new SignJWT({ userId: id, email: "cookie@example.com" })
      .setProtectedHeader({ alg: "HS256" }).setExpirationTime("1h")
      .sign(new TextEncoder().encode(process.env.JWT_SECRET!));
    const unrelated = new Request(req("DELETE", "/api/v1/account", { token: tokenA, body: { password } }), {
      headers: { authorization: `Bearer ${tokenA}`, cookie: `session=${await cookie(userB)}`, "content-type": "application/json" },
    });
    expect((await DELETE(unrelated)).headers.has("set-cookie")).toBe(false);
    const matching = new Request(req("DELETE", "/api/v1/account", { token: tokenA, body: { password } }), {
      headers: { authorization: `Bearer ${tokenA}`, cookie: `session=${await cookie(userA)}`, "content-type": "application/json" },
    });
    expect((await DELETE(matching)).headers.get("set-cookie")).toContain("Max-Age=0");
  });
});
