import { beforeEach, expect, test } from "vitest";
import { clearDb, testDb } from "./helpers/db";
import { scopedId } from "@/lib/mongodb";

beforeEach(clearDb);

test("connects to the isolated Mongo replica set and round-trips a transaction", async () => {
  const col = testDb().collection("transactions");
  await col.insertOne({
    _id: scopedId("u1", "entry"), userId: "u1", date: "2026-10-21", description: "coffee",
    amount: 4, category: "Food", type: "expense",
  });
  const rows = await col.find({ userId: "u1" }).toArray();
  expect(rows).toHaveLength(1);
  expect(rows[0].description).toBe("coffee");
});
