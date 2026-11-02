import { beforeEach, expect, test } from "vitest";
import { clearDb, testDb } from "../helpers/db";
import { DEFAULT_CATEGORIES, seedCategories } from "../../scripts/seedCategories";

beforeEach(async () => {
  await clearDb();
});

test("creates the 8 defaults under slug ids with their order", async () => {
  expect(await seedCategories(testDb())).toEqual({ created: 8, existing: 0 });
  const snap = await testDb().collection("categories").get();
  expect(snap.docs.map((d) => d.id).sort()).toEqual(
    ["entertainment", "food-and-drink-item", "groceries", "health", "housing", "other", "transport", "utilities"],
  );
  expect((await testDb().collection("categories").doc("food-and-drink-item").get()).data()).toEqual({
    name: "Food and Drink Item",
    sort: DEFAULT_CATEGORIES.indexOf("Food and Drink Item"),
  });
});

test("is create-only: a rerun changes nothing and keeps edited docs", async () => {
  await seedCategories(testDb());
  await testDb().collection("categories").doc("other").update({ sort: 99 });
  expect(await seedCategories(testDb())).toEqual({ created: 0, existing: 8 });
  expect((await testDb().collection("categories").doc("other").get()).get("sort")).toBe(99);
});
