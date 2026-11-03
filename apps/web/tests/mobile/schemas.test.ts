import { describe, expect, test } from "vitest";
import {
  endCheckInputSchema,
  monthCount,
  monthPlanInputSchema,
  monthRangeSchema,
  monthSchema,
  loginSchema,
  transactionInputSchema,
  transactionBatchSchema,
  voiceParseRequestSchema,
  updatedSinceSchema,
} from "@/lib/mobile/schemas";

const valid = {
  clientId: "3b241101-e2bb-4255-8caf-4136c566a962",
  type: "expense" as const,
  amount: 450,
  category: "Food and Drink Item",
  description: "coffee",
  date: "2026-10-19",
  source: "voice" as const,
  transcript: "coffee four fifty",
};

test("accepts valid sample", () => {
  expect(transactionInputSchema.safeParse(valid).success).toBe(true);
  expect(transactionInputSchema.safeParse({ ...valid, transcript: undefined }).success).toBe(true);
});

test.each([0, -1, 1.5])("rejects amount %s", (amount) => {
  expect(transactionInputSchema.safeParse({ ...valid, amount }).success).toBe(false);
});

test("rejects 2026-02-31", () => {
  expect(transactionInputSchema.safeParse({ ...valid, date: "2026-02-31" }).success).toBe(false);
  expect(transactionInputSchema.safeParse({ ...valid, date: "2028-02-29" }).success).toBe(true);
});

test("rejects source web", () => {
  expect(transactionInputSchema.safeParse({ ...valid, source: "web" }).success).toBe(false);
});

test("rejects non-v4 clientId and blank description", () => {
  expect(transactionInputSchema.safeParse({ ...valid, clientId: "abc" }).success).toBe(false);
  expect(transactionInputSchema.safeParse({ ...valid, description: "   " }).success).toBe(false);
});

describe("receipt fields", () => {
  const scanned = {
    ...valid,
    source: "scan" as const,
    transcript: undefined,
    receiptId: "9f1c2b3a-4d5e-4f60-8a7b-1c2d3e4f5a6b",
    merchant: "  City Mart  ",
    items: [
      { name: " Milk ", quantity: 2, unitPrice: 1250 },
      { name: "Bag", quantity: 1, unitPrice: 0 },
    ],
  };
  const parse = (over: Record<string, unknown>) => transactionInputSchema.safeParse({ ...scanned, ...over });

  test("accepts source scan with a receipt id, merchant and items, trimming the text", () => {
    const out = transactionInputSchema.parse(scanned);
    expect(out).toMatchObject({
      source: "scan",
      receiptId: scanned.receiptId,
      merchant: "City Mart",
      items: [
        { name: "Milk", quantity: 2, unitPrice: 1250 },
        { name: "Bag", quantity: 1, unitPrice: 0 },
      ],
    });
  });

  test("does not require the items to add up to the amount", () => {
    // 2 x 1250 + 0 is 2500, the entry says 450 (a discount or a tax line can do that).
    expect(parse({}).success).toBe(true);
  });

  test("an old client's entry without the new fields still parses, without them", () => {
    const out = transactionInputSchema.parse(valid);
    expect(out).not.toHaveProperty("receiptId");
    expect(out).not.toHaveProperty("merchant");
    expect(out).not.toHaveProperty("items");
  });

  test("rejects a receipt id that is not a v4 uuid", () => {
    expect(parse({ receiptId: "abc" }).success).toBe(false);
    expect(parse({ receiptId: "9f1c2b3a-4d5e-1f60-8a7b-1c2d3e4f5a6b" }).success).toBe(false);
  });

  test("merchant is 1..100 characters after trimming", () => {
    expect(parse({ merchant: "   " }).success).toBe(false);
    expect(parse({ merchant: "x".repeat(100) }).success).toBe(true);
    expect(parse({ merchant: "x".repeat(101) }).success).toBe(false);
  });

  test("at most 100 items; an empty list is allowed", () => {
    const line = { name: "x", quantity: 1, unitPrice: 1 };
    expect(parse({ items: Array(100).fill(line) }).success).toBe(true);
    expect(parse({ items: Array(101).fill(line) }).success).toBe(false);
    expect(parse({ items: [] }).success).toBe(true);
  });

  test.each([
    ["blank name", { name: "  " }],
    ["name over 120", { name: "x".repeat(121) }],
    ["quantity 0", { quantity: 0 }],
    ["quantity 1000", { quantity: 1000 }],
    ["quantity 1.5", { quantity: 1.5 }],
    ["negative unit price", { unitPrice: -1 }],
    ["decimal unit price", { unitPrice: 2.5 }],
    ["unsafe unit price", { unitPrice: 2 ** 53 }],
    ["missing quantity", { quantity: undefined }],
    ["missing unit price", { unitPrice: undefined }],
  ])("rejects an item with %s", (_, over) => {
    expect(parse({ items: [{ name: "Milk", quantity: 1, unitPrice: 100, ...over }] }).success).toBe(false);
  });

  test("an item name of 120 characters and a count of 999 are allowed", () => {
    expect(parse({ items: [{ name: "x".repeat(120), quantity: 999, unitPrice: 1 }] }).success).toBe(true);
  });
});

test("batch bounds 1..50", () => {
  const mk = (n: number) => ({ transactions: Array.from({ length: n }, () => valid) });
  expect(transactionBatchSchema.safeParse(mk(50)).success).toBe(true);
  expect(transactionBatchSchema.safeParse(mk(51)).success).toBe(false);
  expect(transactionBatchSchema.safeParse(mk(0)).success).toBe(false);
});

test("login, voiceParseRequest, updatedSince", () => {
  expect(loginSchema.safeParse({ email: "a@b.co", password: "x" }).success).toBe(true);
  expect(loginSchema.safeParse({ email: "  ", password: "x" }).success).toBe(false);
  expect(voiceParseRequestSchema.safeParse({ transcripts: ["hi", " "] }).data).toEqual({ transcripts: ["hi"] });
  expect(voiceParseRequestSchema.safeParse({ transcripts: [" ", ""] }).success).toBe(false);
  expect(voiceParseRequestSchema.safeParse({ transcripts: Array(4).fill("hi") }).success).toBe(false);
  expect(updatedSinceSchema.safeParse("2026-10-19T00:00:00.000Z").success).toBe(true);
  expect(updatedSinceSchema.safeParse("yesterday").success).toBe(false);
});

describe("month plans", () => {
  const plan = { newMoney: 2000000, carriedOver: 0, shares: [{ category: "Food", limit: 500000 }] };

  test("monthSchema takes YYYY-MM only", () => {
    for (const ok of ["2026-11", "2026-01", "2026-12", "1999-06"]) expect(monthSchema.safeParse(ok).success).toBe(true);
    for (const bad of ["2026-13", "2026-00", "2026-1", "26-11", "2026-11-01", " 2026-11", ""]) {
      expect(monthSchema.safeParse(bad).success).toBe(false);
    }
  });

  test("monthCount counts both ends", () => {
    expect(monthCount("2026-11", "2026-11")).toBe(1);
    expect(monthCount("2026-01", "2026-12")).toBe(12);
    expect(monthCount("2025-11", "2026-02")).toBe(4);
  });

  test("monthRangeSchema: from <= to, at most 24 months", () => {
    expect(monthRangeSchema.safeParse({ from: "2025-01", to: "2026-12" }).success).toBe(true);
    expect(monthRangeSchema.safeParse({ from: "2025-01", to: "2027-01" }).success).toBe(false);
    expect(monthRangeSchema.safeParse({ from: "2026-03", to: "2026-02" }).success).toBe(false);
    expect(monthRangeSchema.safeParse({ from: null, to: "2026-02" }).success).toBe(false);
  });

  test("a plan needs a total above 0 and whole amounts", () => {
    expect(monthPlanInputSchema.safeParse(plan).success).toBe(true);
    expect(monthPlanInputSchema.safeParse({ ...plan, newMoney: 0, carriedOver: 5, shares: [] }).success).toBe(true);
    expect(monthPlanInputSchema.safeParse({ ...plan, newMoney: 0, carriedOver: 0 }).success).toBe(false);
    expect(monthPlanInputSchema.safeParse({ ...plan, newMoney: 10.5 }).success).toBe(false);
    expect(monthPlanInputSchema.safeParse({ ...plan, carriedOver: -1 }).success).toBe(false);
    expect(monthPlanInputSchema.safeParse({ ...plan, newMoney: Number.MAX_SAFE_INTEGER, carriedOver: 1 }).success).toBe(false);
  });

  test("shares: limit above 0, each category once, at most 50", () => {
    const share = (category: string, limit = 1) => ({ category, limit });
    expect(monthPlanInputSchema.safeParse({ ...plan, shares: [share("Food", 0)] }).success).toBe(false);
    expect(monthPlanInputSchema.safeParse({ ...plan, shares: [share("Food"), share("Food")] }).success).toBe(false);
    expect(monthPlanInputSchema.safeParse({ ...plan, shares: [share("")] }).success).toBe(false);
    const fifty = Array.from({ length: 50 }, (_, i) => share(`C${i}`));
    expect(monthPlanInputSchema.safeParse({ ...plan, shares: fifty }).success).toBe(true);
    expect(monthPlanInputSchema.safeParse({ ...plan, shares: [...fifty, share("C50")] }).success).toBe(false);
  });

  test("the split is not checked here: it has its own error", () => {
    expect(monthPlanInputSchema.safeParse({ ...plan, shares: [{ category: "Food", limit: 9000000 }] }).success).toBe(true);
  });

  test("endCheckInputSchema takes 0 and whole kyat", () => {
    expect(endCheckInputSchema.safeParse({ onHand: 0 }).success).toBe(true);
    expect(endCheckInputSchema.safeParse({ onHand: 280000 }).success).toBe(true);
    for (const onHand of [-1, 1.5, "1", undefined]) expect(endCheckInputSchema.safeParse({ onHand }).success).toBe(false);
  });
});
