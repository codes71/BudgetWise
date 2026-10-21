import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import mongoose from "mongoose";
import { startMongo, stopMongo, clearDb } from "../helpers/db";
import { req } from "../helpers/request";

let POST: (r: Request, c?: unknown) => Promise<Response>;
let GET: (r: Request, c?: unknown) => Promise<Response>;
let DELETE: (r: Request, c: { params: Promise<{ id: string }> }) => Promise<Response>;
let Tx: typeof import("@/lib/models/transaction").default;
let tokenA: string;
let tokenB: string;
const userA = new mongoose.Types.ObjectId().toString();
const userB = new mongoose.Types.ObjectId().toString();

beforeAll(async () => {
  await startMongo();
  const list = await import("@/app/api/v1/transactions/route");
  POST = list.POST as typeof POST;
  GET = list.GET as typeof GET;
  DELETE = (await import("@/app/api/v1/transactions/[id]/route")).DELETE as typeof DELETE;
  Tx = (await import("@/lib/models/transaction")).default;
  const { signMobileToken } = await import("@/lib/mobile/token");
  tokenA = (await signMobileToken({ id: userA, email: "a@x.com" })).token;
  tokenB = (await signMobileToken({ id: userB, email: "b@x.com" })).token;
});
afterAll(async () => {
  await stopMongo();
});
beforeEach(async () => {
  await clearDb();
  await Tx.init();
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
      expect(t.id).toMatch(/^[0-9a-f]{24}$/);
      expect(new Date(t.createdAt).getTime()).toBeGreaterThan(0);
      expect(new Date(t.updatedAt).getTime()).toBeGreaterThan(0);
    }
    const stored = await Tx.findOne({ userId: userA, clientId: uuid(1) }).lean();
    expect(stored?.createdAt).toBeInstanceOf(Date);
  });

  it("resubmitting same clientId returns existing row, count stays 1", async () => {
    const first = await (await post(tokenA, [item(1)])).json();
    const second = await (await post(tokenA, [item(1, { amount: 999, description: "changed" })])).json();
    expect(second.saved[0].id).toBe(first.saved[0].id);
    expect(second.saved[0].amount).toBe(101);
    expect(second.saved[0].description).toBe("item 1");
    expect(await Tx.countDocuments({ userId: userA })).toBe(1);
  });

  it("same clientId for two users stays separate", async () => {
    const a = await (await post(tokenA, [item(1)])).json();
    const b = await (await post(tokenB, [item(1)])).json();
    expect(a.saved[0].id).not.toBe(b.saved[0].id);
    expect(await Tx.countDocuments({})).toBe(2);
  });

  it("concurrent identical batches yield one row each", async () => {
    const items = [1, 2, 3, 4, 5].map((n) => item(n));
    const [r1, r2] = await Promise.all([post(tokenA, items), post(tokenA, items)]);
    expect(r1.status).toBe(200);
    expect(r2.status).toBe(200);
    const [b1, b2] = [await r1.json(), await r2.json()];
    expect(await Tx.countDocuments({ userId: userA })).toBe(5);
    expect(b1.saved.map((t: any) => t.id)).toEqual(b2.saved.map((t: any) => t.id));
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

  it("returns all rows incl. legacy ones without updatedAt, ordered by updatedAt then _id", async () => {
    await post(tokenA, [item(1), item(2), item(3)]);
    await Tx.collection.insertOne({
      userId: userA, date: "2026-01-01", description: "legacy", amount: 5, category: "Misc", type: "expense",
    });
    const res = await get(tokenA);
    expect(res.status).toBe(200);
    const { transactions } = await res.json();
    expect(transactions).toHaveLength(4);
    const times = transactions.map((t: any) => new Date(t.updatedAt).getTime());
    expect([...times].sort((a, b) => a - b)).toEqual(times);
    expect(transactions.find((t: any) => t.description === "legacy").updatedAt).toBe(new Date(0).toISOString());
    const again = await (await get(tokenA)).json();
    expect(again.transactions.map((t: any) => t.id)).toEqual(transactions.map((t: any) => t.id));
  });

  it("includes rows updated exactly at the cursor", async () => {
    const { saved: transactions } = await (await post(tokenA, [item(1), item(2)])).json();
    const cursor = new Date("2026-10-10T00:00:00.000Z");
    await Tx.collection.updateOne({ _id: new mongoose.Types.ObjectId(transactions[0].id) }, { $set: { updatedAt: cursor } });
    await Tx.collection.updateOne({ _id: new mongoose.Types.ObjectId(transactions[1].id) }, { $set: { updatedAt: new Date(cursor.getTime() - 1) } });
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
  it("own row 204; other user's row 404; bad ObjectId 404 not 500", async () => {
    const { saved: transactions } = await (await post(tokenA, [item(1)])).json();
    const id = transactions[0].id;
    const other = await del(tokenB, id);
    expect(other.status).toBe(404);
    expect((await other.json()).error.code).toBe("not_found");
    expect(await Tx.countDocuments({ userId: userA })).toBe(1);
    expect((await del(tokenA, id)).status).toBe(204);
    expect(await Tx.countDocuments({ userId: userA })).toBe(0);
    expect((await del(tokenA, id)).status).toBe(404);
    expect((await del(tokenA, "not-an-id")).status).toBe(404);
    expect((await del(undefined, id)).status).toBe(401);
  });
});
