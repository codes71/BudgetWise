import { z } from "zod";

// Amounts are whole kyat: the phone app stores whole numbers, so the website
// refuses decimals instead of letting the two show different totals.
export function isWholeKyat(n: unknown): n is number {
  return typeof n === "number" && Number.isSafeInteger(n) && n > 0;
}

export const WHOLE_KYAT_MESSAGE = "Enter a whole number, no decimals";

const grouped = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 });

/** 2280000 -> "2,280,000 Ks". */
export const formatKyat = (kyat: number): string => `${grouped.format(kyat)} Ks`;

/**
 * 1 lakh is 100,000 Ks: 2280000 -> "22.8 lakh", 2000000 -> "20 lakh" (one decimal, no ".0"). Rounded half up to
 * the nearest 10,000 Ks in whole numbers, as the phone does, so 2245000 is "22.5" on both (float division gave 22.4).
 */
export const formatLakh = (kyat: number): string => {
  const tenths = Math.sign(kyat) * Math.floor((Math.abs(kyat) + 5_000) / 10_000);
  return `${oneDecimal.format(tenths / 10)} lakh`;
};

// Form field: coerces the input's text, then applies the same rule as isWholeKyat.
export const wholeKyatField = z.coerce
  .number<string | number>()
  .positive("Amount must be positive")
  .int(WHOLE_KYAT_MESSAGE)
  .safe("Amount is too large");
