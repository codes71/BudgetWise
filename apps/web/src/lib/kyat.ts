import { z } from "zod";

// Amounts are whole kyat: the phone app stores whole numbers, so the website
// refuses decimals instead of letting the two show different totals.
export function isWholeKyat(n: unknown): n is number {
  return typeof n === "number" && Number.isSafeInteger(n) && n > 0;
}

export const WHOLE_KYAT_MESSAGE = "Enter a whole number, no decimals";

// Form field: coerces the input's text, then applies the same rule as isWholeKyat.
export const wholeKyatField = z.coerce
  .number()
  .positive("Amount must be positive")
  .int(WHOLE_KYAT_MESSAGE)
  .safe("Amount is too large");
