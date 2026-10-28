import { beforeEach, expect, test } from "vitest";
import { clearDb, testDb } from "./helpers/db";

beforeEach(async () => {
  await clearDb();
});

test("connects to the emulator and round-trips a transaction doc", async () => {
  const col = testDb().collection("users").doc("u1").collection("transactions");
  await col.add({
    userId: "u1",
    date: "2026-10-21",
    description: "coffee",
    amount: 4,
    category: "Food",
    type: "expense",
  });
  const rows = await col.get();
  expect(rows.size).toBe(1);
  expect(rows.docs[0].get("description")).toBe("coffee");
});
