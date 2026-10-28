import { describe, expect, it } from "vitest";
import type { ScanOutput } from "@/ai/flows/scan-image";
import { ApiError } from "@/lib/mobile/errors";
import {
  MAX_IMAGE_BYTES,
  decodeImage,
  expenseCategories,
  kpayClientId,
  normalizeScan,
  yangonDate,
} from "@/lib/mobile/scan";
import { transactionInputSchema } from "@/lib/mobile/schemas";

const b64 = (bytes: number[], pad = 32) => Buffer.from([...bytes, ...Array(pad).fill(0)]).toString("base64");
const JPEG = b64([0xff, 0xd8, 0xff, 0xe0]);
const PNG = b64([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const WEBP = Buffer.concat([Buffer.from("RIFF"), Buffer.alloc(4), Buffer.from("WEBP"), Buffer.alloc(32)]).toString("base64");

const decodeError = (image: string, mediaType: "image/jpeg" | "image/png" | "image/webp") => {
  try {
    decodeImage(image, mediaType);
  } catch (err) {
    return err as ApiError;
  }
  throw new Error("expected decodeImage to throw");
};

describe("decodeImage", () => {
  it("accepts each supported type by its bytes", () => {
    expect(decodeImage(JPEG, "image/jpeg")).toBe(JPEG);
    expect(decodeImage(PNG, "image/png")).toBe(PNG);
    expect(decodeImage(WEBP, "image/webp")).toBe(WEBP);
  });

  it("drops the line breaks Android's Base64.DEFAULT adds", () => {
    const wrapped = JPEG.replace(/(.{8})/g, "$1\n");
    expect(decodeImage(wrapped, "image/jpeg")).toBe(JPEG);
  });

  it("rejects bytes that do not match the declared type", () => {
    expect(decodeError(PNG, "image/jpeg")).toMatchObject({ status: 422, code: "invalid_image" });
    expect(decodeError(JPEG, "image/webp")).toMatchObject({ status: 422, code: "invalid_image" });
  });

  it("rejects data URLs and text that is not base64", () => {
    expect(decodeError(`data:image/jpeg;base64,${JPEG}`, "image/jpeg")).toMatchObject({ status: 422 });
    expect(decodeError("not base64!", "image/jpeg")).toMatchObject({ status: 422 });
    expect(decodeError(JPEG.slice(0, -1), "image/jpeg")).toMatchObject({ status: 422 });
  });

  it("allows exactly 6 MB and rejects one byte more with 413", () => {
    const image = (size: number) => {
      const buf = Buffer.alloc(size);
      buf.set([0xff, 0xd8, 0xff]);
      return buf.toString("base64");
    };
    expect(() => decodeImage(image(MAX_IMAGE_BYTES), "image/jpeg")).not.toThrow();
    expect(decodeError(image(MAX_IMAGE_BYTES + 1), "image/jpeg")).toMatchObject({
      status: 413,
      code: "image_too_large",
    });
  });
});

describe("kpayClientId", () => {
  it("is sha256('kpay:' + reference) shaped as a UUID v4", () => {
    expect(kpayClientId("01003456789012345678")).toBe("8757c8d9-1367-4ee0-b09b-80b464aba7a0");
    expect(kpayClientId("01003456789012345679")).toBe("032fbd02-6beb-4d08-b75e-9f9c1b964ac0");
  });

  it("is a clientId the batch save accepts", () => {
    const clientId = kpayClientId("01003456789012345678");
    const parsed = transactionInputSchema.safeParse({
      clientId,
      type: "expense",
      amount: 5000,
      category: "Other",
      description: "Shop",
      date: "2026-10-01",
      source: "manual",
    });
    expect(parsed.success).toBe(true);
  });
});

describe("yangonDate", () => {
  it("is UTC+06:30", () => {
    expect(yangonDate(new Date("2026-10-28T17:29:00Z"))).toBe("2026-10-28");
    expect(yangonDate(new Date("2026-10-28T17:30:00Z"))).toBe("2026-10-29");
  });
});

describe("expenseCategories", () => {
  it("drops Income, dedupes by case and always offers Other", () => {
    expect(expenseCategories(["Food", "income", "food", "Rent"])).toEqual(["Food", "Rent", "Other"]);
    expect(expenseCategories(["Food", "other"])).toEqual(["Food", "other"]);
  });
});

describe("normalizeScan", () => {
  const ctx = { categories: ["Groceries", "Transport", "Other"], today: "2026-10-29" };
  const entry = (over: Partial<ScanOutput["entries"][number]> = {}): ScanOutput["entries"][number] => ({
    type: "expense",
    amount: 1000,
    category: "Groceries",
    description: "City Mart",
    date: "2026-10-20",
    ...over,
  });
  const history = (entries: ScanOutput["entries"]) => normalizeScan({ kind: "history", entries }, ctx).entries;

  it("rounds to whole kyat, makes amounts positive and drops zero or missing ones", () => {
    const out = history([
      entry({ amount: 1500.5 }),
      entry({ amount: -2000 }),
      entry({ amount: 0.4 }),
      entry({ amount: Number.NaN }),
    ]);
    expect(out.map((e) => e.amount)).toEqual([1501, 2000]);
  });

  it("drops impossible, very old and future dates instead of guessing", () => {
    const out = history([
      entry({ date: "2026-02-30" }),
      entry({ date: "26-10-20" }),
      entry({ date: "1999-12-31" }),
      entry({ date: "2026-10-31" }),
      entry({ date: "2026-10-30" }),
      entry({ date: " 2026-10-29 " }),
    ]);
    expect(out.map((e) => e.date)).toEqual(["2026-10-30", "2026-10-29"]);
  });

  it("an entry without a date takes the scan's date, else it is dropped", () => {
    expect(normalizeScan({ kind: "payment", date: "2026-10-21", entries: [entry({ date: undefined })] }, ctx).entries[0].date)
      .toBe("2026-10-21");
    expect(normalizeScan({ kind: "payment", entries: [entry({ date: undefined })] }, ctx).entries).toEqual([]);
  });

  it("keeps Burmese text as written and falls back to the merchant for a blank description", () => {
    const out = normalizeScan(
      { kind: "payment", merchant: "  ဒေါ်အေး  ", entries: [entry({ description: "   " })] },
      ctx,
    );
    expect(out.merchant).toBe("ဒေါ်အေး");
    expect(out.entries[0].description).toBe("ဒေါ်အေး");
    expect(history([entry({ description: "ကော်ဖီ ဆိုင်" })])[0].description).toBe("ကော်ဖီ ဆိုင်");
  });

  it("normalizes a reference before deriving the clientId and ignores unreadable ones", () => {
    const out = history([
      entry({ reference: "0100 3456 7890 1234 5678" }),
      entry({ reference: "၀၁၀၀၃၄၅၆၇၈၉၀၁၂၃၄၅၆၇၉" }),
      entry({ reference: "n/a" }),
    ]);
    expect(out[0]).toMatchObject({ reference: "01003456789012345678", clientId: kpayClientId("01003456789012345678") });
    expect(out[1]).toMatchObject({ reference: "01003456789012345679", clientId: kpayClientId("01003456789012345679") });
    expect(out[2].reference).toBeUndefined();
    expect(out[2].clientId).toBeUndefined();
  });

  it("lists a transaction repeated in one history screenshot once", () => {
    const out = history([entry({ reference: "01003456789012345678" }), entry({ reference: "01003456789012345678" }), entry()]);
    expect(out).toHaveLength(2);
  });

  it("does not attach references to receipts", () => {
    const out = normalizeScan({ kind: "receipt", date: "2026-10-20", entries: [entry({ reference: "INV12345678" })] }, ctx);
    expect(out.entries[0].reference).toBeUndefined();
    expect(out.entries[0].clientId).toBeUndefined();
  });

  it("keeps only the first entry of a payment slip and drops items outside receipts", () => {
    const out = normalizeScan(
      { kind: "payment", entries: [entry({ items: [{ name: "x", amount: 1 }] }), entry({ amount: 7 })] },
      ctx,
    );
    expect(out.entries).toHaveLength(1);
    expect(out.entries[0].items).toBeUndefined();
  });
});
