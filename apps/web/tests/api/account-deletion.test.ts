import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import bcrypt from "bcryptjs";
import { SignJWT } from "jose";
import { Collection } from "mongodb";
import { clearDb, testDb } from "../helpers/db";
import { req } from "../helpers/request";
import { createUser, emailDocId, updateUserProfile } from "@/lib/users";
import { signMobileToken } from "@/lib/mobile/token";
import { scopedId, sha256Hex } from "@/lib/mongodb";
import { requireUser } from "@/lib/mobile/bearer";
import { saveBatch } from "@/lib/mobile/transactions-service";
import { saveEndCheck, saveMonthPlan, setBudgetLimit } from "@/lib/budgets";
import { DELETION_JOURNAL, deletionIdentityKey } from "@/lib/account-deletion-journal";

let DELETE: (request: Request, context?: unknown) => Promise<Response>;
let LOGIN: (request: Request, context?: unknown) => Promise<Response>;
let userA: string;
let userB: string;
let tokenA: string;
const password = "deletion-test-password";
const user = (id: string) => testDb().collection("users").findOne({ _id: id });
const emailClaim = (email: string) => testDb().collection("userEmails").findOne({ _id: emailDocId(email) });
const journal = (id: string) => testDb().collection(DELETION_JOURNAL).findOne({ _id: deletionIdentityKey(id) });
const remove = (token = tokenA, body: unknown = { password }) => DELETE(req("DELETE", "/api/v1/account", { token, body }));
const item = { clientId: "00000000-0000-4000-8000-000000000001", type: "expense" as const,
  amount: 1, category: "Food", description: "late sync", date: "2026-11-01", source: "manual" as const };

async function ownedCount(userId: string): Promise<number> {
  let count = 0;
  for (const name of ["transactions", "budgets", "budgetMonths", "userCategories", "futureRecords"]) {
    count += await testDb().collection(name).countDocuments({ userId });
  }
  for (const name of ["_firestoreArchive", "_firestoreTypes"]) {
    count += await testDb().collection(name).countDocuments({ ownerId: userId });
  }
  return count;
}

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
    await testDb().collection("transactions").insertOne({ _id: scopedId(id, "entry"), userId: id, amount: 100, transcript: "private words" });
    await testDb().collection("budgets").insertOne({ _id: scopedId(id, "food"), userId: id, category: "Food", limit: 500 });
    await testDb().collection("budgetMonths").insertOne({ _id: scopedId(id, "2026-11"), userId: id, month: "2026-11", newMoney: 500, carriedOver: 0, shares: [] });
    await testDb().collection("userCategories").insertOne({ _id: scopedId(id, "personal"), userId: id, name: "Personal" });
    await testDb().collection("futureRecords").insertOne({ _id: scopedId(id, "one"), userId: id, value: 1 });
    const nested = `users/${id}/future-records/nested/details/one`;
    await testDb().collection("_firestoreArchive").insertOne({ _id: nested, ownerId: id, data: { value: 1 } });
    await testDb().collection("_firestoreTypes").insertOne({ _id: nested, ownerId: id, data: { value: { type: "number" } } });
  }
}

describe("DELETE /api/v1/account", () => {
  it("deletes the authenticated account's complete owned data and email claim, preserving every other account", async () => {
    await seedFinancialData();
    const keys = [`scan:user:${userA}`, `voice:user:${userA}`, `auth:email:${sha256Hex("delete-a@example.com")}`];
    for (const key of [...keys, "auth:ip:203.0.113.1", `scan:user:${userB}`]) {
      await testDb().collection("rateLimits").insertOne({ _id: sha256Hex(key), key, count: 1 });
    }
    const response = await remove();
    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");
    expect(await user(userA)).toBeNull();
    expect(await ownedCount(userA)).toBe(0);
    expect(await emailClaim("delete-a@example.com")).toBeNull();
    expect(await journal(userA)).toMatchObject({ activeStore: "complete", recoveryCleanup: "pending" });
    expect((await journal(userA))!.requestedAt).toBeInstanceOf(Date);
    expect((await journal(userA))!.activeStoreCompletedAt).toBeInstanceOf(Date);
    expect(await journal(userB)).toBeNull();
    const retained = JSON.stringify(await journal(userA));
    for (const secret of [userA, "delete-a@example.com", "password", "private words", "email", "userId"]) {
      expect(retained).not.toContain(secret);
    }
    expect(await user(userB)).not.toBeNull();
    expect(await testDb().collection("transactions").findOne({ _id: scopedId(userB, "entry") })).not.toBeNull();
    expect(await testDb().collection("_firestoreArchive").findOne({ _id: `users/${userB}/future-records/nested/details/one` })).not.toBeNull();
    expect(await ownedCount(userB)).toBe(7);
    for (const key of keys) expect(await testDb().collection("rateLimits").findOne({ _id: sha256Hex(key) })).toBeNull();
    expect(await testDb().collection("rateLimits").findOne({ _id: sha256Hex("auth:ip:203.0.113.1") })).not.toBeNull();
    await expect(requireUser(req("GET", "/api/v1/transactions", { token: tokenA }))).rejects.toMatchObject({ status: 401 });
  });

  it("requires both a valid bearer and the current password, with no body-selected target", async () => {
    expect((await DELETE(req("DELETE", "/api/v1/account", { body: { password } }))).status).toBe(401);
    const wrong = await remove(tokenA, { password: "wrong" });
    expect(wrong.status).toBe(401);
    expect((await wrong.json()).error.code).toBe("invalid_password");
    expect((await remove(tokenA, { password, userId: userB })).status).toBe(422);
    expect((await remove(tokenA, { password: "" })).status).toBe(422);
    expect((await user(userA))!.deletionStartedAt).toBeUndefined();
    expect(await user(userB)).not.toBeNull();
  });

  it("keeps a failed partial deletion locked and permits password-verified retry through existing login", async () => {
    await seedFinancialData();
    const original = Collection.prototype.deleteMany;
    let failOnce = true;
    const failure = vi.spyOn(Collection.prototype, "deleteMany").mockImplementation(function (this: Collection, filter: any, options?: any) {
      if (this.collectionName === "transactions" && filter.userId === userA && failOnce) {
        failOnce = false;
        return Promise.reject(new Error("temporary failure"));
      }
      return original.call(this, filter, options);
    });
    const first = await remove();
    expect(first.status).toBe(503);
    expect((await first.json()).error.code).toBe("deletion_incomplete");
    expect((await user(userA))!.deletionStartedAt).toBeTruthy();
    expect(await journal(userA)).toMatchObject({ activeStore: "pending", recoveryCleanup: "pending" });
    const requestedAt = (await journal(userA))!.requestedAt;
    await expect(requireUser(req("GET", "/x", { token: tokenA }))).rejects.toMatchObject({ status: 401 });
    const login = await LOGIN(req("POST", "/api/v1/auth/token", {
      body: { email: "delete-a@example.com", password }, ip: "203.0.113.3",
    }));
    expect(login.status).toBe(200);
    const retryToken = (await login.json()).token;
    await expect(requireUser(req("GET", "/x", { token: retryToken }))).rejects.toMatchObject({ status: 401 });
    failure.mockRestore();
    expect((await remove(retryToken)).status).toBe(204);
    expect(await user(userA)).toBeNull();
    expect(await journal(userA)).toMatchObject({ activeStore: "complete", requestedAt, recoveryCleanup: "pending" });
  });

  it("blocks all account-owned writers while owned-data deletion is in progress", async () => {
    await seedFinancialData();
    const original = Collection.prototype.deleteMany;
    let unlock!: () => void;
    let entered!: () => void;
    const paused = new Promise<void>((resolve) => { entered = resolve; });
    const resume = new Promise<void>((resolve) => { unlock = resolve; });
    vi.spyOn(Collection.prototype, "deleteMany").mockImplementation(async function (this: Collection, filter: any, options?: any) {
      if (this.collectionName === "transactions" && filter.userId === userA) { entered(); await resume; }
      return original.call(this, filter, options);
    });
    const deletion = remove();
    await paused;
    try {
      await expect(saveBatch(userA, [item])).rejects.toMatchObject({ status: 401 });
      await expect(setBudgetLimit(userA, "Food", 1)).rejects.toMatchObject({ status: 401 });
      await expect(saveMonthPlan(userA, "2026-11", { newMoney: 1, carriedOver: 0, shares: [] })).rejects.toMatchObject({ status: 401 });
      await expect(saveEndCheck(userA, "2026-11", 1)).rejects.toMatchObject({ status: 401 });
      expect(await updateUserProfile(userA, { fullName: "late", phoneNumber: null, profilePhotoUrl: null })).toBeNull();
    } finally { unlock(); }
    expect((await deletion).status).toBe(204);
    expect(await ownedCount(userA)).toBe(0);
  });

  it("a writer already holding the account fence cannot resurrect data after a competing deletion", async () => {
    const originalInsert = Collection.prototype.insertOne;
    const originalFence = Collection.prototype.findOneAndUpdate;
    let releaseWriter!: () => void;
    let writerEntered!: () => void;
    let deletionEntered!: () => void;
    const writing = new Promise<void>((resolve) => { writerEntered = resolve; });
    const gate = new Promise<void>((resolve) => { releaseWriter = resolve; });
    const deleting = new Promise<void>((resolve) => { deletionEntered = resolve; });
    let held = false;
    vi.spyOn(Collection.prototype, "insertOne").mockImplementation(async function (this: Collection, doc: any, options?: any) {
      if (this.collectionName === "transactions" && doc.userId === userA && !held) {
        held = true; writerEntered(); await gate;
      }
      return originalInsert.call(this, doc, options);
    });
    vi.spyOn(Collection.prototype, "findOneAndUpdate").mockImplementation((function (this: Collection, filter: any, update: any, options?: any) {
      if (held && this.collectionName === "users" && filter._id === userA) deletionEntered();
      return (originalFence as any).call(this, filter, update, options);
    }) as any);
    const write = saveBatch(userA, [item]);
    await writing;
    const deletion = remove();
    try { await deleting; } finally { releaseWriter(); }
    await write;
    expect((await deletion).status).toBe(204);
    expect(await user(userA)).toBeNull();
    expect(await ownedCount(userA)).toBe(0);
    await expect(saveBatch(userA, [item])).rejects.toMatchObject({ status: 401 });
  });

  it("is idempotent after a lost success response and never deletes a re-registered email owner", async () => {
    expect((await remove()).status).toBe(204);
    const firstJournal = await journal(userA);
    const replacement = await createUser({ email: "delete-a@example.com", passwordHash: await bcrypt.hash(password, 10) });
    expect(replacement!.id).not.toBe(userA);
    expect((await remove(tokenA, { password: "already gone" })).status).toBe(204);
    expect((await emailClaim("delete-a@example.com"))!.userId).toBe(replacement!.id);
    expect(await user(replacement!.id)).not.toBeNull();
    expect(await journal(userA)).toEqual(firstJournal);
    expect(await journal(replacement!.id)).toBeNull();
  });

  it("cleans old orphan owned records and email claims before a missing-root204", async () => {
    await seedFinancialData();
    await testDb().collection("users").deleteOne({ _id: userA });
    expect((await remove(tokenA, { password: "root already gone" })).status).toBe(204);
    expect(await ownedCount(userA)).toBe(0);
    expect(await emailClaim("delete-a@example.com")).toBeNull();
    expect(await journal(userA)).toMatchObject({ activeStore: "complete", recoveryCleanup: "pending" });
    expect(await testDb().collection("transactions").findOne({ _id: scopedId(userB, "entry") })).not.toBeNull();
  });

  it("does not remove an email-index entry owned by a different account", async () => {
    await testDb().collection("userEmails").updateOne({ _id: emailDocId("delete-a@example.com") }, { $set: { userId: userB } });
    const rateKey = sha256Hex(`auth:email:${sha256Hex("delete-a@example.com")}`);
    await testDb().collection("rateLimits").insertOne({ _id: rateKey, count: 4 });
    expect((await remove()).status).toBe(204);
    expect((await emailClaim("delete-a@example.com"))!.userId).toBe(userB);
    expect(await user(userB)).not.toBeNull();
    expect((await testDb().collection("rateLimits").findOne({ _id: rateKey }))!.count).toBe(4);
  });

  it("rolls back the account lock and never purges if recovery-cleanup intent cannot be recorded", async () => {
    await seedFinancialData();
    const original = Collection.prototype.updateOne;
    vi.spyOn(Collection.prototype, "updateOne").mockImplementation(function (this: Collection, ...args: any[]) {
      if (this.collectionName === DELETION_JOURNAL) return Promise.reject(new Error("journal unavailable"));
      return (original as any).apply(this, args);
    });
    expect((await remove()).status).toBeGreaterThanOrEqual(500);
    expect((await user(userA))!.deletionStartedAt).toBeUndefined();
    expect(await ownedCount(userA)).toBe(7);
    expect(await journal(userA)).toBeNull();
  });

  it("keeps the same durable cleanup request through simultaneous deletion retries", async () => {
    await seedFinancialData();
    const results = await Promise.all([remove(), remove()]);
    expect(results.map((response) => response.status)).toEqual([204, 204]);
    expect(await testDb().collection(DELETION_JOURNAL).countDocuments({})).toBe(1);
    expect(await journal(userA)).toMatchObject({ activeStore: "complete", recoveryCleanup: "pending" });
    expect(await ownedCount(userA)).toBe(0);
    expect(await user(userB)).not.toBeNull();
  });

  it("does not purge missing-root orphans until their recovery-cleanup request is durable", async () => {
    await seedFinancialData();
    await testDb().collection("users").deleteOne({ _id: userA });
    const original = Collection.prototype.updateOne;
    vi.spyOn(Collection.prototype, "updateOne").mockImplementation(function (this: Collection, ...args: any[]) {
      if (this.collectionName === DELETION_JOURNAL) return Promise.reject(new Error("journal unavailable"));
      return (original as any).apply(this, args);
    });
    expect((await remove()).status).toBe(503);
    expect(await ownedCount(userA)).toBe(7);
    expect(await journal(userA)).toBeNull();
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
