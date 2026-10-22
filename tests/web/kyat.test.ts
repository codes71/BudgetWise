import { describe, expect, it } from "vitest";
import { WHOLE_KYAT_MESSAGE, wholeKyatField } from "@/lib/kyat";

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
