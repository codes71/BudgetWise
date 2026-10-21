import * as z from "zod/v4";

const isRealDate = (s: string): boolean => {
  const [y, m, d] = s.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
};

export const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
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
  source: z.enum(["voice", "manual"]),
  transcript: z.string().max(2000).optional(),
});

export const transactionBatchSchema = z.object({
  transactions: z.array(transactionInputSchema).min(1).max(50),
});

export const voiceParseSchema = z.object({
  transcript: z.string().min(1).max(2000),
  categories: z.array(z.string().max(60)).max(50).optional(),
});

export const updatedSinceSchema = z.iso.datetime();

export type LoginInput = z.infer<typeof loginSchema>;
export type TransactionInputParsed = z.infer<typeof transactionInputSchema>;
