import { createHash } from "node:crypto";
import type { ScanOutput } from "@/ai/flows/scan-image";
import { isWholeKyat } from "@/lib/kyat";
import type { ScanEntryDto, ScanItemDto, ScanResultDto } from "./dto";
import { ApiError } from "./errors";
import { MAX_ITEMS, MAX_QUANTITY, transactionInputSchema, type ScanMediaType } from "./schemas";

// As base64 (4/3 of the bytes) inside the JSON body, 3 MB stays under Vercel's 4.5 MB request limit.
export const MAX_IMAGE_BYTES = 3 * 1024 * 1024;
// Leaves room for line breaks and the JSON around the base64.
export const MAX_BODY_BYTES = 2 * MAX_IMAGE_BYTES;

export const imageTooLarge = () => new ApiError(413, "image_too_large", "Image must be 3 MB or smaller");

export const OTHER = "Other";
export const INCOME = "Income";
// The most one batch save accepts.
export const MAX_ENTRIES = 50;
const MAX_TEXT = 100;
const MIN_DATE = "2000-01-01";

const MAGIC: Record<ScanMediaType, (b: Buffer) => boolean> = {
  "image/jpeg": (b) => b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff,
  "image/png": (b) => b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
  "image/webp": (b) => b.toString("latin1", 0, 4) === "RIFF" && b.toString("latin1", 8, 12) === "WEBP",
};

/** Plain base64 of an image of the declared type, at most MAX_IMAGE_BYTES once decoded. */
export function decodeImage(image: string, mediaType: ScanMediaType): string {
  // Android's Base64.DEFAULT wraps lines, so whitespace is dropped first.
  const data = image.replace(/\s+/g, "");
  const padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0;
  if ((data.length / 4) * 3 - padding > MAX_IMAGE_BYTES) throw imageTooLarge();
  if (data.length === 0 || data.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) {
    throw new ApiError(422, "invalid_image", "image must be plain base64, without a data: prefix");
  }
  if (!MAGIC[mediaType](Buffer.from(data.slice(0, 16), "base64"))) {
    throw new ApiError(422, "invalid_image", `image is not a ${mediaType} file`);
  }
  return data;
}

// Myanmar has no daylight saving time, so Asia/Yangon is always UTC+06:30.
export function yangonDate(now: Date): string {
  return new Date(now.getTime() + 6.5 * 3600 * 1000).toISOString().slice(0, 10);
}

/** A UUID v4-shaped id from the KPay transaction id, so the same transaction always gets the same clientId. */
export function kpayClientId(reference: string): string {
  const b = createHash("sha256").update(`kpay:${reference}`).digest().subarray(0, 16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export const categoryKey = (name: string) => name.normalize("NFC").trim().toLowerCase();

/** The categories an expense may use: the user's, without "Income", plus "Other". */
export function expenseCategories(names: string[]): string[] {
  const byKey = new Map<string, string>();
  for (const name of names) {
    const key = categoryKey(name);
    if (key && key !== categoryKey(INCOME) && !byKey.has(key)) byKey.set(key, name);
  }
  if (!byKey.has(categoryKey(OTHER))) byKey.set(categoryKey(OTHER), OTHER);
  return [...byKey.values()];
}

export function text(value: string | undefined, max = MAX_TEXT): string | undefined {
  if (value === undefined) return undefined;
  const chars = Array.from(value.normalize("NFC").replace(/\s+/g, " ").trim());
  return chars.slice(0, max).join("").trim() || undefined;
}

export function kyat(value: number | undefined): number | undefined {
  if (value === undefined || !Number.isFinite(value)) return undefined;
  const n = Math.round(Math.abs(value));
  return isWholeKyat(n) ? n : undefined;
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

// A real calendar date (the save rule), not before 2000 and not after tomorrow in Yangon.
export function validDate(value: string, today: string): string | undefined {
  const s = value.trim();
  if (!transactionInputSchema.shape.date.safeParse(s).success) return undefined;
  return s >= MIN_DATE && s <= addDays(today, 1) ? s : undefined;
}

/**
 * A line item whose quantity times unitPrice is its amount. The count and price are kept as read when they
 * make the amount; otherwise (a discount, rounding, or nothing read) the amount is split over the count when
 * it divides evenly, else it is one item at the line's amount.
 */
export function scanItem(name: string, amount: number, quantity?: number, unitPrice?: number): ScanItemDto {
  const count =
    quantity !== undefined && Number.isInteger(quantity) && quantity >= 1 && quantity <= MAX_QUANTITY ? quantity : 1;
  const each = kyat(unitPrice);
  if (each !== undefined && count * each === amount) return { name, amount, quantity: count, unitPrice: each };
  return amount % count === 0
    ? { name, amount, quantity: count, unitPrice: amount / count }
    : { name, amount, quantity: 1, unitPrice: amount };
}

const BURMESE_DIGITS = /[၀-၉]/g;

function reference(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const s = value.replace(/\s+/g, "").replace(BURMESE_DIGITS, (c) => String(c.charCodeAt(0) - 0x1040));
  return /^[A-Za-z0-9-]{6,64}$/.test(s) ? s : undefined;
}

// Nothing printed means kyat; so does any of these.
const KYAT = new Set(["MMK", "KS", "K", "KYAT", "KYATS", "ကျပ်"]);

/** The currency printed on a scan when it is not kyat, as read (USD, THB). */
function foreignCurrency(value: string | undefined): string | undefined {
  const c = text(value, 12)?.toUpperCase();
  return c && !KYAT.has(c.replace(/[.\s]/g, "")) ? c : undefined;
}

/**
 * A KPay history row's date. The prompt's year rule, applied here too: a row showing no year that the model put
 * in this year, in a later month than now, is from last year (late December rows scanned in January).
 */
export function historyDate(value: string, today: string): string | undefined {
  const s = value.trim();
  const ok = validDate(s, today);
  if (ok || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return ok;
  if (s.slice(0, 4) !== today.slice(0, 4) || s.slice(5, 7) <= today.slice(5, 7)) return undefined;
  return validDate(`${Number(s.slice(0, 4)) - 1}${s.slice(4)}`, today);
}

/**
 * Turns Claude's reading into entries the phone can review and save: amounts in
 * whole kyat, real dates, known categories. Anything that does not pass is
 * dropped, never filled in. Nothing usable left is kind "unknown" with its reason,
 * except a receipt whose total was read: the phone offers that total for review.
 */
export function normalizeScan(
  raw: ScanOutput,
  { categories, today }: { categories: string[]; today: string },
): ScanResultDto {
  // Any reason but "unclear" means there is nothing to read here, whatever kind came with it.
  if (raw.kind === "unknown" || (raw.reason !== undefined && raw.reason !== "unclear")) {
    return { kind: "unknown", reason: raw.reason ?? "unclear", entries: [] };
  }
  // BudgetWise records kyat: amounts in another currency are never passed on as kyat.
  const currency = foreignCurrency(raw.currency);
  if (currency) return { kind: "unknown", reason: "foreign_currency", currency, entries: [] };

  const receipt = raw.kind === "receipt";
  const readDate = (value: string) => (raw.kind === "history" ? historyDate(value, today) : validDate(value, today));
  const known = new Map(categories.map((name) => [categoryKey(name), name]));
  const merchant = text(raw.merchant);
  const date = raw.date === undefined ? undefined : readDate(raw.date);
  const total = kyat(raw.total);

  let entries: ScanEntryDto[] = [];
  // A receipt's discounts on the whole bill, and its discount lines read as items, which are left out.
  let billDiscount = 0;
  const discountLines: number[] = [];
  for (const e of raw.entries) {
    const amount = kyat(e.amount);
    if (amount === undefined) continue;
    // On a receipt the sign matters: a negative entry lowers what was paid, it is never something bought.
    if (receipt && e.amount < 0) {
      billDiscount += amount;
      continue;
    }
    const ownDate = e.date === undefined ? undefined : readDate(e.date);
    // A history row needs its own readable date (or the screenshot's, when the row shows none).
    // A receipt or slip without one gets today's, flagged so the review screen can point it out.
    const entryDate = raw.kind === "history" && e.date !== undefined ? ownDate : (ownDate ?? date);
    if (entryDate === undefined && raw.kind === "history") continue;
    const category =
      e.type === "income" ? INCOME : (known.get(categoryKey(e.category)) ?? OTHER);
    const entry: ScanEntryDto = {
      type: e.type,
      amount,
      category,
      description: text(e.description) ?? merchant ?? category,
      date: entryDate ?? today,
      ...(entryDate === undefined ? { dateGuessed: true } : {}),
    };
    if (receipt) {
      const items = (e.items ?? []).flatMap((item) => {
        const name = text(item.name);
        const itemAmount = kyat(item.amount);
        if (itemAmount !== undefined && item.amount < 0) {
          discountLines.push(itemAmount);
          return [];
        }
        return name && itemAmount ? [scanItem(name, itemAmount, item.quantity, item.unitPrice)] : [];
      });
      if (items.length) entry.items = items;
    } else {
      // Only KPay slips and history rows carry a KPay reference.
      const ref = reference(e.reference);
      if (ref) {
        entry.reference = ref;
        entry.clientId = kpayClientId(ref);
      }
    }
    entries.push(entry);
  }

  if (receipt) entries = fitTotal(groupByCategory(entries), total, billDiscount, discountLines);
  if (raw.kind === "payment") entries = entries.slice(0, 1);
  if (raw.kind === "history") {
    // A transaction seen twice in one screenshot is listed once.
    const seen = new Set<string>();
    entries = entries.filter((e) => {
      if (!e.clientId) return true;
      if (seen.has(e.clientId)) return false;
      seen.add(e.clientId);
      return true;
    });
  }

  if (!entries.length && !(receipt && total)) return { kind: "unknown", reason: "unclear", entries: [] };
  return {
    kind: raw.kind,
    ...(merchant ? { merchant } : {}),
    ...(date ? { date } : {}),
    ...(total ? { total } : {}),
    entries: entries.slice(0, MAX_ENTRIES),
  };
}

// Tax or a service charge, as a receipt line names it in English or Burmese.
const TAX_LINE = /\b(tax|vat|gst)\b|service charge|ခွန်|ဝန်ဆောင်ခ/i;

/**
 * Brings a receipt's entries down to its printed total, only for a cause it can name, since the total can be
 * misread too: a discount on the whole bill comes off the largest entry (with a total, only as far as the entries
 * are over it), and entries over the total by exactly a left-out discount line, or by exactly one tax line the
 * prices already included, lose that much. The largest entry never goes to 0 or below.
 */
function fitTotal(
  entries: ScanEntryDto[],
  total: number | undefined,
  billDiscount: number,
  discountLines: number[],
): ScanEntryDto[] {
  const expenses = entries.filter((e) => e.type === "expense");
  if (!expenses.length) return entries;
  const largest = expenses.reduce((a, b) => (b.amount > a.amount ? b : a));
  const over = () => (total === undefined ? 0 : expenses.reduce((sum, e) => sum + e.amount, 0) - total);

  const cut = total === undefined ? billDiscount : Math.min(billDiscount, over());
  if (cut > 0 && cut < largest.amount) largest.amount -= cut;

  const excess = over();
  if (excess <= 0 || excess >= largest.amount) return entries;
  if (discountLines.includes(excess) || discountLines.reduce((a, b) => a + b, 0) === excess) {
    largest.amount -= excess;
    return entries;
  }
  for (const e of expenses) {
    const at = e.items?.findIndex((item) => TAX_LINE.test(item.name) && item.amount === excess) ?? -1;
    if (at < 0 || e.amount <= excess) continue;
    const items = e.items!.filter((_, n) => n !== at);
    if (items.length) e.items = items;
    else delete e.items;
    e.amount -= excess;
    break;
  }
  return entries;
}

// One entry per category, in the order the categories first appear.
// Items keep their own quantity and unitPrice; an entry lists at most MAX_ITEMS, the most a save accepts.
function groupByCategory(entries: ScanEntryDto[]): ScanEntryDto[] {
  const groups = new Map<string, ScanEntryDto>();
  for (const e of entries) {
    const key = `${e.type}:${e.category}`;
    const group = groups.get(key);
    if (!group) {
      groups.set(key, { ...e, ...(e.items ? { items: e.items.slice(0, MAX_ITEMS) } : {}) });
      continue;
    }
    group.amount += e.amount;
    if (e.items) group.items = [...(group.items ?? []), ...e.items].slice(0, MAX_ITEMS);
  }
  return [...groups.values()];
}
