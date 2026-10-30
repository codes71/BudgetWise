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

export type LoginInput = z.infer<typeof loginSchema>;
export type RegisterInput = z.infer<typeof registerSchema>;
export type TransactionInputParsed = z.infer<typeof transactionInputSchema>;
export type ScanMediaType = (typeof SCAN_MEDIA_TYPES)[number];
