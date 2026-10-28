import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHash } from "node:crypto";
import bcrypt from "bcryptjs";
import { MongoClient, ObjectId, type Db } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";
import { Timestamp } from "firebase-admin/firestore";
import { clearDb, testDb } from "../helpers/db";
import { req } from "../helpers/request";
import { copyMongoToFirestore, formatReport } from "../../scripts/copyMongoToFirestore";

let server: MongoMemoryServer;
let client: MongoClient;
let mongo: Db;
let passwordHash: string;

const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const at = (iso: string) => new Date(iso);
const oid = (iso: string) => ObjectId.createFromTime(at(iso).getTime() / 1000);

// Fixed ids so assertions can name them.
const alice = oid("2026-01-01T00:00:00Z");
const aliceDup = oid("2026-02-01T00:00:00Z");
const bob = oid("2026-03-01T00:00:00Z");
const noEmail = oid("2026-03-02T00:00:00Z");
const legacyTx = oid("2026-04-01T00:00:00Z");
const mobileTx = oid("2026-04-02T00:00:00Z");
const dupUserTx = oid("2026-04-03T00:00:00Z");
const orphanTx = oid("2026-04-04T00:00:00Z");
const badTx = oid("2026-04-05T00:00:00Z");
const budget = oid("2026-05-01T00:00:00Z");
const groceries = oid("2026-06-01T00:00:00Z");
const foodDrink = oid("2026-06-02T00:00:00Z");
const bobCategory = oid("2026-06-03T00:00:00Z");
const mobileClientId = "00000000-0000-4000-8000-000000000001";
const mobileTimes = at("2026-04-02T10:00:00Z");

async function seedMongo() {
  // The newer, lowercase duplicate is inserted first: age decides, not insertion order.
  await mongo.collection("users").insertMany([
    { _id: aliceDup, email: "alice@example.com", password: passwordHash, fullName: "Dup", createdAt: at("2026-02-01T00:00:00Z") },
    { _id: alice, email: "Alice@Example.com", password: passwordHash, fullName: "Alice A", createdAt: at("2026-01-01T00:00:00Z"), updatedAt: at("2026-01-05T00:00:00Z") },
    { _id: bob, email: "  bob@example.com ", fullName: "Bob", phoneNumber: null },
    { _id: noEmail, fullName: "Nobody" },
  ]);
  await mongo.collection("transactions").insertMany([
    { _id: legacyTx, userId: alice.toHexString(), date: "2026-04-01", description: "legacy", amount: 4.5, category: "Other", type: "expense" },
    {
      _id: mobileTx, userId: alice.toHexString(), date: "2026-04-02", description: "voice", amount: 1200, category: "Food",
      type: "expense", clientId: mobileClientId, source: "voice", transcript: "kafe", createdAt: mobileTimes, updatedAt: mobileTimes,
    },
    { _id: dupUserTx, userId: aliceDup.toHexString(), date: "2026-04-03", description: "dup", amount: 1, category: "Other", type: "expense" },
    { _id: orphanTx, userId: new ObjectId().toHexString(), date: "2026-04-04", description: "orphan", amount: 1, category: "Other", type: "expense" },
    { _id: badTx, userId: alice.toHexString(), date: "2026-04-05", description: "no amount", category: "Other", type: "expense" },
  ]);
  await mongo.collection("budgets").insertMany([
    { _id: budget, userId: alice.toHexString(), category: "Food", limit: 5000 },
    { userId: "nobody-here", category: "Rent", limit: 1 },
  ]);
  await mongo.collection("categories").insertMany([
    { _id: groceries, name: "Groceries" },
    { _id: foodDrink, name: "Food and Drink Item" },
    { _id: bobCategory, name: "Mine", userId: bob },
  ]);
  await mongo.collection("ratelimits").insertMany([
    { key: "auth:ip:1.2.3.4", count: 3, expiresAt: new Date() },
    { key: "auth:email:abc", count: 1, expiresAt: new Date() },
  ]);
}

const users = () => testDb().collection("users");
const run = (write: boolean) => copyMongoToFirestore(mongo, testDb(), { write });

beforeAll(async () => {
  server = await MongoMemoryServer.create();
  client = await MongoClient.connect(server.getUri());
  mongo = client.db("budgetwise_copy_test");
  passwordHash = await bcrypt.hash("correct-horse", 10);
});
afterAll(async () => {
  await client?.close();
  await server?.stop();
});
beforeEach(async () => {
  await clearDb();
  await mongo.dropDatabase();
  await seedMongo();
});

describe("copyMongoToFirestore", () => {
  it("dry run writes nothing and reports what it would create", async () => {
    const report = await run(false);
    expect(report.users).toEqual({ source: 4, created: 2, alreadyCopied: 0, duplicateEmail: 1, emailTaken: 0, invalid: 1 });
    expect(report.transactions).toMatchObject({ source: 5, created: 2, orphaned: 1, userSkipped: 1, invalid: 1 });
    expect(await testDb().listCollections()).toEqual([]);
    expect(formatReport(report, false)).toMatch(/^Mode: dry run/);
  });

  it("copies users, emails, transactions, budgets and categories with their Mongo ids", async () => {
    const report = await run(true);
    expect(report).toEqual({
      users: { source: 4, created: 2, alreadyCopied: 0, duplicateEmail: 1, emailTaken: 0, invalid: 1 },
      transactions: { source: 5, created: 2, existing: 0, duplicate: 0, orphaned: 1, userSkipped: 1, userAlreadyCopied: 0, invalid: 1 },
      budgets: { source: 2, created: 1, existing: 0, duplicate: 0, orphaned: 1, userSkipped: 0, userAlreadyCopied: 0, invalid: 0 },
      userCategories: { source: 1, created: 1, existing: 0, duplicate: 0, orphaned: 0, userSkipped: 0, userAlreadyCopied: 0, invalid: 0 },
      globalCategories: { source: 2, created: 2, existing: 0, duplicate: 0, invalid: 0 },
      rateLimitsSkipped: 2,
    });

    const a = (await users().doc(alice.toHexString()).get()).data()!;
    expect(a).toMatchObject({ email: "Alice@Example.com", emailLower: "alice@example.com", password: passwordHash, fullName: "Alice A" });
    expect(a.createdAt.toDate()).toEqual(at("2026-01-01T00:00:00Z"));
    expect(a.updatedAt.toDate()).toEqual(at("2026-01-05T00:00:00Z"));
    expect((await users().doc(aliceDup.toHexString()).get()).exists).toBe(false);
    expect((await users().doc(noEmail.toHexString()).get()).exists).toBe(false);

    const b = (await users().doc(bob.toHexString()).get()).data()!;
    expect(b).toMatchObject({ email: "bob@example.com", emailLower: "bob@example.com", phoneNumber: null });
    expect(b.password).toBeUndefined();
    expect(b.createdAt.toDate()).toEqual(bob.getTimestamp());

    const emails = await testDb().collection("userEmails").get();
    expect(Object.fromEntries(emails.docs.map((d) => [d.id, d.get("userId")]))).toEqual({
      [sha("alice@example.com")]: alice.toHexString(),
      [sha("bob@example.com")]: bob.toHexString(),
    });

    const txs = users().doc(alice.toHexString()).collection("transactions");
    const legacy = (await txs.doc(legacyTx.toHexString()).get()).data()!;
    expect(legacy).toMatchObject({ userId: alice.toHexString(), amount: 4.5, source: "web" });
    expect(legacy.clientId).toBeUndefined();
    expect(legacy.createdAt).toBeInstanceOf(Timestamp);
    expect(legacy.createdAt.toDate()).toEqual(legacyTx.getTimestamp());
    expect(legacy.updatedAt.toDate()).toEqual(legacyTx.getTimestamp());
    const mobile = (await txs.doc(mobileTx.toHexString()).get()).data()!;
    expect(mobile).toMatchObject({ clientId: mobileClientId, source: "voice", transcript: "kafe" });
    expect(mobile.updatedAt.toDate()).toEqual(mobileTimes);
    expect((await txs.get()).size).toBe(2);

    const budgetDoc = (await users().doc(alice.toHexString()).collection("budgets").doc(sha("Food")).get()).data()!;
    expect(budgetDoc).toMatchObject({ userId: alice.toHexString(), category: "Food", limit: 5000 });

    const globals = await testDb().collection("categories").get();
    expect(Object.fromEntries(globals.docs.map((d) => [d.id, d.data()]))).toEqual({
      groceries: { name: "Groceries", sort: 0 },
      "food-and-drink-item": { name: "Food and Drink Item", sort: 1 },
    });
    const own = await users().doc(bob.toHexString()).collection("categories").doc(bobCategory.toHexString()).get();
    expect(own.get("name")).toBe("Mine");
    expect((await testDb().collection("rateLimits").get()).size).toBe(0);
  });

  it("copied accounts work with the mobile API: login, list, and resubmitting a copied clientId", async () => {
    await run(true);
    const { POST: login } = await import("@/app/api/v1/auth/token/route");
    const res = await (login as (r: Request) => Promise<Response>)(
      req("POST", "/api/v1/auth/token", { body: { email: "ALICE@example.com", password: "correct-horse" }, ip: "10.7.7.7" }),
    );
    expect(res.status).toBe(200);
    const { token, user } = await res.json();
    expect(user.id).toBe(alice.toHexString());

    const list = await import("@/app/api/v1/transactions/route");
    const get = (await (list.GET as (r: Request) => Promise<Response>)(req("GET", "/api/v1/transactions", { token }))).json();
    expect((await get).transactions.map((t: any) => t.id).sort()).toEqual([legacyTx.toHexString(), mobileTx.toHexString()].sort());

    const again = await (list.POST as (r: Request) => Promise<Response>)(
      req("POST", "/api/v1/transactions", {
        token,
        body: { transactions: [{ clientId: mobileClientId, type: "expense", amount: 1200, category: "Food", description: "voice", date: "2026-04-02", source: "voice" }] },
      }),
    );
    expect((await again.json()).saved[0].id).toBe(mobileTx.toHexString());
    expect((await users().doc(alice.toHexString()).collection("transactions").get()).size).toBe(2);
  });

  it("is idempotent and never brings back a doc deleted after the copy", async () => {
    await run(true);
    const txs = users().doc(alice.toHexString()).collection("transactions");
    await txs.doc(legacyTx.toHexString()).delete();
    const second = await run(true);
    expect(second.users).toMatchObject({ created: 0, alreadyCopied: 2 });
    expect(second.transactions).toMatchObject({ created: 0, userAlreadyCopied: 3 });
    expect(second.globalCategories).toMatchObject({ created: 0, existing: 2 });
    expect((await txs.doc(legacyTx.toHexString()).get()).exists).toBe(false);
  });

  it("finishes a user left half-copied by an interrupted run", async () => {
    const txs = users().doc(alice.toHexString()).collection("transactions");
    await txs.doc(mobileTx.toHexString()).set({ partial: true });
    const report = await run(true);
    expect(report.transactions).toMatchObject({ created: 1, existing: 1 });
    expect((await txs.doc(mobileTx.toHexString()).get()).data()).toEqual({ partial: true });
    expect((await txs.doc(legacyTx.toHexString()).get()).exists).toBe(true);
    expect((await users().doc(alice.toHexString()).get()).exists).toBe(true);
  });

  it("skips a Mongo user whose email already signed up on Firestore, with their data", async () => {
    const { createUser } = await import("@/lib/users");
    const fresh = await createUser({ email: "BOB@example.com" });
    const report = await run(true);
    expect(report.users).toMatchObject({ created: 1, emailTaken: 1 });
    expect(report.userCategories).toMatchObject({ created: 0, userSkipped: 1 });
    expect((await users().doc(bob.toHexString()).get()).exists).toBe(false);
    expect((await testDb().collection("userEmails").doc(sha("bob@example.com")).get()).get("userId")).toBe(fresh!.id);
  });

  it("prints counts only, no emails or names", async () => {
    const text = formatReport(await run(true), true);
    expect(text).toMatch(/^Mode: write\./);
    expect(text).toContain("users: source 4, created 2");
    expect(text).not.toMatch(/@|alice|bob|nobody|kafe/i);
  });
});
