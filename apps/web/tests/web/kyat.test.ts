import { describe, expect, it } from "vitest";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { WHOLE_KYAT_MESSAGE, formatKyat, formatLakh, wholeKyatField } from "@/lib/kyat";

describe("wholeKyatField", () => {
  it("accepts whole numbers typed into the form", () => {
    expect(wholeKyatField.parse("12500")).toBe(12500);
  });

  it("refuses decimals with the whole number message", () => {
    const res = wholeKyatField.safeParse("12.50");
    expect(res.success).toBe(false);
    expect(res.error?.issues[0].message).toBe(WHOLE_KYAT_MESSAGE);
  });

  it.each(["0", "-5", "", "abc", String(2 ** 53)])("refuses %j", (input) => {
    expect(wholeKyatField.safeParse(input).success).toBe(false);
  });
});

describe("formatKyat and formatLakh", () => {
  it("groups the digits", () => {
    expect(formatKyat(2280000)).toBe("2,280,000 Ks");
    expect(formatKyat(0)).toBe("0 Ks");
  });

  it.each([
    [2280000, "22.8 lakh"],
    [2000000, "20 lakh"],
    [2250000, "22.5 lakh"],
    [50000, "0.5 lakh"],
    [123400000, "1,234 lakh"],
    [2245000, "22.5 lakh"],
    [2244999, "22.4 lakh"],
    [4999, "0 lakh"],
  ])("%d is %s", (kyat, text) => {
    expect(formatLakh(kyat)).toBe(text);
  });
});

// Exercise the actual field schema through the upgraded browser-form resolver.
describe("whole-kyat form resolver", () => {
  const resolve = zodResolver(z.object({ amount: wholeKyatField }));
  const options = { fields: {}, shouldUseNativeValidation: false };

  it("submits the coerced numeric value rather than the input text", async () => {
    const result = await resolve({ amount: "12500" }, undefined, options);
    expect(result.values).toEqual({ amount: 12500 });
    expect(result.errors).toEqual({});
  });

  it("keeps the existing decimal error and does not submit an invalid value", async () => {
    const result = await resolve({ amount: "12.50" }, undefined, options);
    expect(result.values).toEqual({});
    expect(result.errors).toMatchObject({ amount: { message: WHOLE_KYAT_MESSAGE } });
  });
});
