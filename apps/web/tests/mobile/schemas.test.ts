import { expect, test } from "vitest";
import {
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
