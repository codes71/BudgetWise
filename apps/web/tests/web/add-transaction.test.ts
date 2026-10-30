import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearDb, newUserId, testDb } from "../helpers/db";

const { userId } = vi.hoisted(() => ({ userId: `web${Date.now()}` }));

vi.mock("@/lib/auth", () => ({
  verifySession: vi.fn(async () => ({ userId, email: "web@x.com" })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));

let actions: typeof import("@/app/db-actions");

const base = {
  date: "2026-10-01",
  description: "Coffee",
  amount: 4,
  category: "Food",
  type: "expense" as const,
};
const txCol = () => testDb().collection("users").doc(userId).collection("transactions");
const rows = async () => (await txCol().get()).docs;

beforeAll(async () => {
  actions = await import("@/app/db-actions");
});
beforeEach(async () => {
  await clearDb();
});

describe("addTransaction", () => {
  it("stores source web even if caller passes voice", async () => {
    const res = await actions.addTransaction({ ...base, source: "voice" } as never);
    expect(res).not.toBeNull();
    const [row] = await rows();
    expect(row.get("source")).toBe("web");
  });

  it("returns the row with _id set to the auto doc id", async () => {
    const res = await actions.addTransaction(base);
    const [row] = await rows();
    expect(res).toMatchObject({ ...base, _id: row.id, userId, source: "web" });
    expect(typeof res!.createdAt).toBe("string");
  });

  it("stores only whitelisted fields", async () => {
    await actions.addTransaction({ ...base, clientId: "evil-1", transcript: "spoofed", userId: "other", extra: 1 } as never);
    const [row] = await rows();
    expect(Object.keys(row.data()).sort()).toEqual(
      ["amount", "category", "createdAt", "date", "description", "source", "type", "updatedAt", "userId"],
    );
    expect(row.get("userId")).toBe(userId);
  });

  it("stores whole kyat amounts", async () => {
    await actions.addTransaction({ ...base, amount: 12500 });
    const [row] = await rows();
    expect(row.get("amount")).toBe(12500);
  });

  it.each([12.5, 0.3, 0, -5, 2 ** 53, Number.NaN])("refuses amount %s", async (amount) => {
    expect(await actions.addTransaction({ ...base, amount })).toBeNull();
    expect(await rows()).toHaveLength(0);
  });

  it.each([{ type: "transfer" }, { description: "" }, { category: undefined }])("refuses bad fields %j", async (over) => {
    expect(await actions.addTransaction({ ...base, ...over } as never)).toBeNull();
    expect(await rows()).toHaveLength(0);
  });
});

describe("getTransactions and deleteTransaction", () => {
  it("lists newest date first with _id, and deletes by _id", async () => {
    await actions.addTransaction({ ...base, date: "2026-10-01", description: "older" });
    await actions.addTransaction({ ...base, date: "2026-10-03", description: "newer" });
    const list = await actions.getTransactions();
    expect(list.map((t) => t.description)).toEqual(["newer", "older"]);
    expect(list[0]._id).toMatch(/^[A-Za-z0-9]{20}$/);
    expect(await actions.deleteTransaction(list[0]._id)).toEqual({ success: true });
    expect((await actions.getTransactions()).map((t) => t.description)).toEqual(["older"]);
  });

  it("refuses unknown, other users' and malformed ids", async () => {
    const other = testDb().collection("users").doc(newUserId()).collection("transactions");
    const ref = await other.add({ ...base, userId: "someone-else" });
    for (const id of [ref.id, "missing", "a/b", "__x__"]) {
      expect((await actions.deleteTransaction(id)).success).toBe(false);
    }
    expect((await ref.get()).exists).toBe(true);
  });
});
