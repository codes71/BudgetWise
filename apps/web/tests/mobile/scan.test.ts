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

  it("allows exactly 3 MB and rejects one byte more with 413", () => {
    const image = (size: number) => {
      const buf = Buffer.alloc(size);
      buf.set([0xff, 0xd8, 0xff]);
      return buf.toString("base64");
    };
    expect(MAX_IMAGE_BYTES).toBe(3 * 1024 * 1024);
    expect(() => decodeImage(image(MAX_IMAGE_BYTES), "image/jpeg")).not.toThrow();
    expect(decodeError(image(MAX_IMAGE_BYTES + 1), "image/jpeg")).toMatchObject({
      status: 413,
      code: "image_too_large",
      message: "Image must be 3 MB or smaller",
    });
  });

  it("keeps the largest allowed request under Vercel's 4.5 MB body limit, even with line breaks", () => {
    const wrapped = Buffer.alloc(MAX_IMAGE_BYTES).toString("base64").replace(/(.{76})/g, "$1\n");
    expect(JSON.stringify({ image: wrapped, mediaType: "image/jpeg" }).length).toBeLessThan(4.5 * 1000 * 1000);
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

  it("a receipt or slip entry takes its own date, else the scan's, else today flagged as guessed", () => {
    const first = (raw: ScanOutput) => normalizeScan(raw, ctx).entries[0];
    expect(first({ kind: "payment", date: "2026-10-21", entries: [entry({ date: undefined })] })).toMatchObject({
      date: "2026-10-21",
    });
    expect(first({ kind: "receipt", date: "2026-10-21", entries: [entry({ date: "2026-13-01" })] })).toMatchObject({
      date: "2026-10-21",
    });
    expect(first({ kind: "payment", date: "2026-10-21", entries: [entry()] }).dateGuessed).toBeUndefined();
    for (const kind of ["payment", "receipt"] as const) {
      for (const date of [undefined, "2026-10-??", "2027-01-01"]) {
        const out = normalizeScan({ kind, date, entries: [entry({ date })] }, ctx);
        expect(out.date).toBeUndefined();
        expect(out.entries[0]).toMatchObject({ date: "2026-10-29", dateGuessed: true });
      }
    }
  });

  it("drops a history row without a readable date, never guessing it", () => {
    expect(history([entry({ date: undefined })])).toEqual([]);
    expect(normalizeScan({ kind: "history", date: "2026-10-21", entries: [entry({ date: undefined })] }, ctx).entries[0])
      .toMatchObject({ date: "2026-10-21" });
    expect(normalizeScan({ kind: "history", date: "2026-10-21", entries: [entry({ date: "bad" })] }, ctx).entries)
      .toEqual([]);
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

  describe("receipt items", () => {
    type RawItem = NonNullable<ScanOutput["entries"][number]["items"]>[number];
    const items = (...lines: RawItem[]) =>
      normalizeScan({ kind: "receipt", date: "2026-10-20", entries: [entry({ items: lines })] }, ctx).entries[0].items;

    it("keeps the count and price as read when they make the line total", () => {
      expect(items({ name: "Milk", amount: 2500, quantity: 2, unitPrice: 1250 })).toEqual([
        { name: "Milk", amount: 2500, quantity: 2, unitPrice: 1250 },
      ]);
      expect(items({ name: "Bag", amount: 100, quantity: 1, unitPrice: 100 })).toEqual([
        { name: "Bag", amount: 100, quantity: 1, unitPrice: 100 },
      ]);
    });

    it("a line without a count is one item at the line total", () => {
      expect(items({ name: "Eggs", amount: 2000 })).toEqual([{ name: "Eggs", amount: 2000, quantity: 1, unitPrice: 2000 }]);
      expect(items({ name: "Eggs", amount: 2000, unitPrice: 500 })).toEqual([
        { name: "Eggs", amount: 2000, quantity: 1, unitPrice: 2000 },
      ]);
    });

    it("splits the line total over the count when the price is missing or disagrees and it divides evenly", () => {
      expect(items({ name: "Soap", amount: 3000, quantity: 3 })).toEqual([
        { name: "Soap", amount: 3000, quantity: 3, unitPrice: 1000 },
      ]);
      // A discount: 2 x 1500 printed, 2700 paid.
      expect(items({ name: "Oil", amount: 2700, quantity: 2, unitPrice: 1500 })).toEqual([
        { name: "Oil", amount: 2700, quantity: 2, unitPrice: 1350 },
      ]);
    });

    it("falls back to one item at the line total when the total does not divide by the count", () => {
      expect(items({ name: "Tea", amount: 2900, quantity: 3, unitPrice: 1000 })).toEqual([
        { name: "Tea", amount: 2900, quantity: 1, unitPrice: 2900 },
      ]);
      // Rounding: 3 x 333.4 is not 1000 once the price is whole kyat.
      expect(items({ name: "Gum", amount: 1000, quantity: 3, unitPrice: 333.4 })).toEqual([
        { name: "Gum", amount: 1000, quantity: 1, unitPrice: 1000 },
      ]);
    });

    it("treats a count that is not a whole number from 1 to 999 as missing", () => {
      for (const quantity of [0, -2, 1.5, 1000, Number.NaN]) {
        expect(items({ name: "Rice", amount: 3000, quantity, unitPrice: 2000 })).toEqual([
          { name: "Rice", amount: 3000, quantity: 1, unitPrice: 3000 },
        ]);
      }
    });

    it("rounds the line total first, so quantity times unitPrice always equals amount", () => {
      const out = items(
        { name: "Bread", amount: 1200.6 },
        { name: "Cake", amount: 3000.2, quantity: 2, unitPrice: 1500 },
        { name: "Juice", amount: 1999.5, quantity: 2, unitPrice: 1000 },
      )!;
      expect(out).toEqual([
        { name: "Bread", amount: 1201, quantity: 1, unitPrice: 1201 },
        { name: "Cake", amount: 3000, quantity: 2, unitPrice: 1500 },
        { name: "Juice", amount: 2000, quantity: 2, unitPrice: 1000 },
      ]);
      for (const i of out) expect(i.quantity * i.unitPrice).toBe(i.amount);
    });

    it("carries each item's count and price when entries of one category are merged", () => {
      const out = normalizeScan(
        {
          kind: "receipt",
          date: "2026-10-20",
          entries: [
            entry({ amount: 2500, items: [{ name: "Milk", amount: 2500, quantity: 2, unitPrice: 1250 }] }),
            entry({ amount: 900, category: "Transport", items: [{ name: "Parking", amount: 900 }] }),
            entry({ amount: 3000, category: "groceries", items: [{ name: "Soap", amount: 3000, quantity: 3 }] }),
          ],
        },
        ctx,
      ).entries;
      expect(out).toHaveLength(2);
      expect(out[0]).toMatchObject({
        category: "Groceries",
        amount: 5500,
        items: [
          { name: "Milk", amount: 2500, quantity: 2, unitPrice: 1250 },
          { name: "Soap", amount: 3000, quantity: 3, unitPrice: 1000 },
        ],
      });
      expect(out[1].items).toEqual([{ name: "Parking", amount: 900, quantity: 1, unitPrice: 900 }]);
    });

    it("lists at most 100 items per entry, the most a save accepts", () => {
      const line = (n: number) => ({ name: `item ${n}`, amount: 100 });
      const out = normalizeScan(
        {
          kind: "receipt",
          date: "2026-10-20",
          entries: [
            entry({ items: Array.from({ length: 60 }, (_, n) => line(n)) }),
            entry({ items: Array.from({ length: 60 }, (_, n) => line(60 + n)) }),
          ],
        },
        ctx,
      ).entries;
      expect(out[0].items).toHaveLength(100);
      expect(out[0].items![99].name).toBe("item 99");
    });
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
