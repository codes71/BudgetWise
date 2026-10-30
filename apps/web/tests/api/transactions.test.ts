import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { Timestamp } from "firebase-admin/firestore";
import { clearDb, newUserId, testDb } from "../helpers/db";
import { req } from "../helpers/request";

let POST: (r: Request, c?: unknown) => Promise<Response>;
let GET: (r: Request, c?: unknown) => Promise<Response>;
let DELETE: (r: Request, c: { params: Promise<{ id: string }> }) => Promise<Response>;
let tokenA: string;
let tokenB: string;
const userA = newUserId();
const userB = newUserId();
const txCol = (userId: string) => testDb().collection("users").doc(userId).collection("transactions");
const count = async (userId: string) => (await txCol(userId).count().get()).data().count;

beforeAll(async () => {
  const list = await import("@/app/api/v1/transactions/route");
  POST = list.POST as typeof POST;
  GET = list.GET as typeof GET;
  DELETE = (await import("@/app/api/v1/transactions/[id]/route")).DELETE as typeof DELETE;
  const { signMobileToken } = await import("@/lib/mobile/token");
  tokenA = (await signMobileToken({ id: userA, email: "a@x.com" })).token;
  tokenB = (await signMobileToken({ id: userB, email: "b@x.com" })).token;
});
beforeEach(async () => {
  await clearDb();
});

const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const item = (n: number, over: Record<string, unknown> = {}) => ({
  clientId: uuid(n),
  type: "expense",
  amount: 100 + n,
  category: "Food",
  description: `item ${n}`,
  date: "2026-10-01",
  source: "manual",
  ...over,
});
const post = (token: string | undefined, items: unknown[]) =>
  POST(req("POST", "/api/v1/transactions", { token, body: { transactions: items } }));
const del = (token: string | undefined, id: string) =>
  DELETE(req("DELETE", `/api/v1/transactions/${id}`, { token }), { params: Promise.resolve({ id }) });

describe("POST /api/v1/transactions", () => {
  it("saves and returns DTOs with ids and timestamps", async () => {
    const res = await post(tokenA, [item(1), item(2)]);
    expect(res.status).toBe(200);
    const { saved: transactions } = await res.json();
    expect(transactions).toHaveLength(2);
    expect(transactions[0]).toMatchObject({ clientId: uuid(1), amount: 101, source: "manual" });
    expect(transactions[1].clientId).toBe(uuid(2));
    for (const t of transactions) {
      // New mobile rows use the clientId as their doc id.
      expect(t.id).toMatch(/^[A-Za-z0-9_-]{1,128}$/);
      expect(t.id).toBe(t.clientId);
      expect(new Date(t.createdAt).getTime()).toBeGreaterThan(0);
      expect(new Date(t.updatedAt).getTime()).toBeGreaterThan(0);
    }
    const stored = await txCol(userA).doc(uuid(1)).get();
    expect(stored.get("createdAt")).toBeInstanceOf(Timestamp);
    expect(stored.get("userId")).toBe(userA);
    expect(stored.get("transcript")).toBeUndefined();
  });

  it("stores only the contract fields", async () => {
    await post(tokenA, [item(1, { source: "voice", transcript: "kafe 101", extra: "dropped" })]);
    const stored = (await txCol(userA).doc(uuid(1)).get()).data()!;
    expect(Object.keys(stored).sort()).toEqual(
      ["amount", "category", "clientId", "createdAt", "date", "description", "source", "transcript", "type", "updatedAt", "userId"],
    );
    expect(stored.transcript).toBe("kafe 101");
  });

  it("resubmitting same clientId returns existing row, count stays 1", async () => {
    const first = await (await post(tokenA, [item(1)])).json();
    await new Promise((r) => setTimeout(r, 25));
    const second = await (await post(tokenA, [item(1, { amount: 999, description: "changed" })])).json();
    expect(second.saved[0].id).toBe(first.saved[0].id);
    expect(second.saved[0].amount).toBe(101);
    expect(second.saved[0].description).toBe("item 1");
    expect(second.saved[0].updatedAt).toBe(first.saved[0].updatedAt);
    expect(second.saved[0].createdAt).toBe(first.saved[0].createdAt);
    expect(await count(userA)).toBe(1);
  });

  it("a clientId repeated inside one batch is stored once", async () => {
    const res = await post(tokenA, [item(1), item(1, { amount: 999 })]);
    expect(res.status).toBe(200);
    const { saved } = await res.json();
    expect(saved).toHaveLength(2);
    expect(saved[1].id).toBe(saved[0].id);
    expect(saved[1].amount).toBe(101);
    expect(await count(userA)).toBe(1);
  });

  it("matches a row copied from Mongo by its clientId field, without a duplicate", async () => {
    const legacyId = "64b0000000000000000000aa";
    const createdAt = new Date("2026-09-01T00:00:00.000Z");
    await txCol(userA).doc(legacyId).set({
      userId: userA, clientId: uuid(7), date: "2026-09-01", description: "copied", amount: 70,
      category: "Food", type: "expense", source: "manual", createdAt, updatedAt: createdAt,
    });
    const { saved } = await (await post(tokenA, [item(7), item(8)])).json();
    expect(saved[0]).toMatchObject({ id: legacyId, clientId: uuid(7), description: "copied" });
    expect(saved[0].updatedAt).toBe(createdAt.toISOString());
    expect(saved[1].id).toBe(uuid(8));
    expect(await count(userA)).toBe(2);
  });

  it("finds copied rows past the 30-value in-filter limit", async () => {
    const batch = testDb().batch();
    for (let n = 1; n <= 35; n++) {
      batch.set(txCol(userA).doc(`64b0000000000000000000${String(n).padStart(2, "0")}`), {
        userId: userA, clientId: uuid(n), date: "2026-09-01", description: `copied ${n}`, amount: n,
        category: "Food", type: "expense", source: "manual",
      });
    }
    await batch.commit();
    const { saved } = await (await post(tokenA, Array.from({ length: 36 }, (_, i) => item(i + 1)))).json();
    expect(saved).toHaveLength(36);
    expect(saved.slice(0, 35).every((t: any) => t.description.startsWith("copied"))).toBe(true);
    expect(saved[35].id).toBe(uuid(36));
    expect(await count(userA)).toBe(36);
  });

  it("same clientId for two users stays separate", async () => {
    const a = await (await post(tokenA, [item(1)])).json();
    const b = await (await post(tokenB, [item(1)])).json();
    expect(a.saved[0].clientId).toBe(b.saved[0].clientId);
    expect(await count(userA)).toBe(1);
    expect(await count(userB)).toBe(1);
  });

  it("concurrent identical batches yield one row each", async () => {
    const items = [1, 2, 3, 4, 5].map((n) => item(n));
    const [r1, r2] = await Promise.all([post(tokenA, items), post(tokenA, items)]);
    expect(r1.status).toBe(200);
    expect(r2.status).toBe(200);
    const [b1, b2] = [await r1.json(), await r2.json()];
    expect(await count(userA)).toBe(5);
    expect(b1.saved.map((t: any) => t.id)).toEqual(b2.saved.map((t: any) => t.id));
    expect(b1.saved.map((t: any) => t.createdAt)).toEqual(b2.saved.map((t: any) => t.createdAt));
  });

  it("no token 401; 51 items 422; malformed JSON 400", async () => {
    expect((await post(undefined, [item(1)])).status).toBe(401);
    const many = Array.from({ length: 51 }, (_, i) => item(i + 1));
    const big = await post(tokenA, many);
    expect(big.status).toBe(422);
    expect((await big.json()).error.code).toBe("validation_failed");
    const bad = await POST(req("POST", "/api/v1/transactions", { token: tokenA, body: "{not json" }));
    expect(bad.status).toBe(400);
    expect((await bad.json()).error.code).toBe("invalid_json");
  });
});

describe("GET /api/v1/transactions", () => {
  const get = (token: string, qs = "") => GET(req("GET", `/api/v1/transactions${qs}`, { token }));

  it("returns all rows incl. legacy ones without updatedAt, ordered by updatedAt then id", async () => {
    await post(tokenA, [item(1), item(2), item(3)]);
    await txCol(userA).add({
      userId: userA, date: "2026-01-01", description: "legacy", amount: 5, category: "Misc", type: "expense",
    });
    const res = await get(tokenA);
    expect(res.status).toBe(200);
    const { transactions } = await res.json();
    expect(transactions).toHaveLength(4);
    const times = transactions.map((t: any) => new Date(t.updatedAt).getTime());
    expect([...times].sort((a, b) => a - b)).toEqual(times);
    expect(transactions.find((t: any) => t.description === "legacy").updatedAt).toBe(new Date(0).toISOString());
    const legacy = transactions.find((t: any) => t.description === "legacy");
    expect(legacy).toMatchObject({ source: "web", clientId: legacy.id });
    expect(transactions[0].id).toBe(legacy.id);
    const again = await (await get(tokenA)).json();
    expect(again.transactions.map((t: any) => t.id)).toEqual(transactions.map((t: any) => t.id));
  });

  it("orders rows with the same updatedAt by id", async () => {
    const same = new Date("2026-10-05T00:00:00.000Z");
    const { saved } = await (await post(tokenA, [item(3), item(1), item(2)])).json();
    for (const t of saved) await txCol(userA).doc(t.id).update({ updatedAt: same });
    const body = await (await get(tokenA)).json();
    expect(body.transactions.map((t: any) => t.clientId)).toEqual([uuid(1), uuid(2), uuid(3)]);
  });

  it("includes rows updated exactly at the cursor", async () => {
    const { saved: transactions } = await (await post(tokenA, [item(1), item(2)])).json();
    const cursor = new Date("2026-10-10T00:00:00.000Z");
    await txCol(userA).doc(transactions[0].id).update({ updatedAt: cursor });
    await txCol(userA).doc(transactions[1].id).update({ updatedAt: new Date(cursor.getTime() - 1) });
    const res = await get(tokenA, `?updatedSince=${encodeURIComponent(cursor.toISOString())}`);
    const body = await res.json();
    expect(body.transactions.map((t: any) => t.id)).toEqual([transactions[0].id]);
  });

  it("rejects bad updatedSince with 422", async () => {
    expect((await get(tokenA, "?updatedSince=yesterday")).status).toBe(422);
  });

  it("excludes other users' rows", async () => {
    await post(tokenA, [item(1)]);
    await post(tokenB, [item(2)]);
    const body = await (await get(tokenB)).json();
    expect(body.transactions).toHaveLength(1);
    expect(body.transactions[0].clientId).toBe(uuid(2));
  });

  it("no token 401", async () => {
    expect((await GET(req("GET", "/api/v1/transactions"))).status).toBe(401);
  });
});

describe("DELETE /api/v1/transactions/:id", () => {
  it("own row 204; other user's row 404; unknown or malformed id 404 not 500", async () => {
    const { saved: transactions } = await (await post(tokenA, [item(1)])).json();
    const id = transactions[0].id;
    const other = await del(tokenB, id);
    expect(other.status).toBe(404);
    expect((await other.json()).error.code).toBe("not_found");
    expect(await count(userA)).toBe(1);
    expect((await del(tokenA, id)).status).toBe(204);
    expect(await count(userA)).toBe(0);
    expect((await del(tokenA, id)).status).toBe(404);
    expect((await del(tokenA, "not-an-id")).status).toBe(404);
    for (const bad of ["a/b", "..", "__x__", "x".repeat(129), "has space", ""]) {
      expect((await del(tokenA, bad)).status).toBe(404);
    }
    expect((await del(undefined, id)).status).toBe(401);
  });
});
