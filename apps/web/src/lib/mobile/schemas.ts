import * as z from "zod/v4";

const isRealDate = (s: string): boolean => {
  const [y, m, d] = s.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
};

export const loginSchema = z.object({
  // Web signup only enforces HTML type="email", so `a@b` is a real account; let the lookup decide.
  email: z.string().trim().min(1).max(254),
  password: z.string().min(1),
});

/** The phone's create account form. The web signup's rules: HTML type="email" and at least 6 characters. */
export const registerSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1)
    .max(254)
    .regex(/^[^\s@]+@[^\s@]+$/),
  password: z.string().min(6).max(200),
  fullName: z.string().trim().max(100).optional(),
});

// The most line items one entry carries, and the largest count of one item.
export const MAX_ITEMS = 100;
export const MAX_QUANTITY = 999;

// One receipt line: `quantity` of `name` at `unitPrice` kyat each.
export const entryItemSchema = z.object({
  name: z.string().trim().min(1).max(120),
  quantity: z.number().int().min(1).max(MAX_QUANTITY),
  unitPrice: z.number().int().nonnegative().safe(),
});

export const transactionInputSchema = z.object({
  clientId: z.uuid({ version: "v4" }),
  type: z.enum(["expense", "income"]),
  amount: z.number().int().positive().safe(),
  category: z.string().min(1).max(60),
  description: z.string().trim().min(1).max(200),
  date: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/)
    .refine(isRealDate, "invalid calendar date"),
  source: z.enum(["voice", "manual", "scan"]),
  transcript: z.string().max(2000).optional(),
  // The entries saved from one scanned receipt share it.
  receiptId: z.uuid({ version: "v4" }).optional(),
  merchant: z.string().trim().min(1).max(100).optional(),
  // Not checked against amount: discounts and tax lines make the two differ.
  items: z.array(entryItemSchema).max(MAX_ITEMS).optional(),
});

export const transactionBatchSchema = z.object({
  transactions: z.array(transactionInputSchema).min(1).max(50),
});

export const updatedSinceSchema = z.iso.datetime();

export const SCAN_MEDIA_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;

// The size and the bytes are checked by decodeImage, so an oversized image gets its own error.
export const scanRequestSchema = z.object({
  image: z.string().min(1),
  mediaType: z.enum(SCAN_MEDIA_TYPES),
});

// Transcripts of one utterance from different on-device speech models. Blanks are dropped; at least one must remain.
export const voiceParseRequestSchema = z.object({
  transcripts: z
    .array(z.string().trim().max(500))
    .min(1)
    .max(3)
    .transform((list) => list.filter((t) => t.length > 0))
    .pipe(z.array(z.string()).min(1)),
});

/** A calendar month, "YYYY-MM". */
export const monthSchema = z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/);

// The most months one GET /budget-months asks for, and the most shares one plan has.
export const MAX_PLAN_MONTHS = 24;
export const MAX_SHARES = 50;

/** Months from `from` to `to`, counting both: 2026-01 to 2026-12 is 12. */
export function monthCount(from: string, to: string): number {
  const index = (month: string) => {
    const [y, m] = month.split("-").map(Number);
    return y * 12 + m;
  };
  return index(to) - index(from) + 1;
}

export const monthRangeSchema = z
  .object({ from: monthSchema, to: monthSchema })
  .refine(({ from, to }) => from <= to && monthCount(from, to) <= MAX_PLAN_MONTHS);

const kyatOrZero = z.number().int().nonnegative().safe();

export const monthShareSchema = z.object({
  category: z.string().min(1).max(60),
  limit: z.number().int().positive().safe(),
});

// The split (sum of shares) is checked separately: going over the total has its own error code.
export const monthPlanInputSchema = z
  .object({
    newMoney: kyatOrZero,
    carriedOver: kyatOrZero,
    shares: z.array(monthShareSchema).max(MAX_SHARES),
  })
  .refine((p) => new Set(p.shares.map((s) => s.category)).size === p.shares.length, "a category appears at most once")
  .refine((p) => p.newMoney + p.carriedOver > 0 && Number.isSafeInteger(p.newMoney + p.carriedOver), "total must be above 0");

export const endCheckInputSchema = z.object({ onHand: kyatOrZero });

export type LoginInput = z.infer<typeof loginSchema>;
export type RegisterInput = z.infer<typeof registerSchema>;
export type TransactionInputParsed = z.infer<typeof transactionInputSchema>;
export type ScanMediaType = (typeof SCAN_MEDIA_TYPES)[number];
