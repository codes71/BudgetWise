import { createHash } from "node:crypto";
import type { ScanOutput } from "@/ai/flows/scan-image";
import { isWholeKyat } from "@/lib/kyat";
import type { ScanEntryDto, ScanResultDto } from "./dto";
import { ApiError } from "./errors";
import { transactionInputSchema, type ScanMediaType } from "./schemas";

export const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
// Base64 is 4/3 of the bytes; the rest leaves room for line breaks and the JSON around it.
export const MAX_BODY_BYTES = 2 * MAX_IMAGE_BYTES;

export const OTHER = "Other";
export const INCOME = "Income";
// The most one batch save accepts.
const MAX_ENTRIES = 50;
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
  if ((data.length / 4) * 3 - padding > MAX_IMAGE_BYTES) {
    throw new ApiError(413, "image_too_large", "Image must be 6 MB or smaller");
  }
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

const categoryKey = (name: string) => name.normalize("NFC").trim().toLowerCase();

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

function text(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const chars = Array.from(value.normalize("NFC").replace(/\s+/g, " ").trim());
  return chars.slice(0, MAX_TEXT).join("").trim() || undefined;
}

function kyat(value: number | undefined): number | undefined {
  if (value === undefined || !Number.isFinite(value)) return undefined;
  const n = Math.round(Math.abs(value));
  return isWholeKyat(n) ? n : undefined;
}

function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

// A real calendar date (the save rule), not before 2000 and not after tomorrow in Yangon.
function validDate(value: string, today: string): string | undefined {
  const s = value.trim();
  if (!transactionInputSchema.shape.date.safeParse(s).success) return undefined;
  return s >= MIN_DATE && s <= addDays(today, 1) ? s : undefined;
}

const BURMESE_DIGITS = /[၀-၉]/g;

function reference(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const s = value.replace(/\s+/g, "").replace(BURMESE_DIGITS, (c) => String(c.charCodeAt(0) - 0x1040));
  return /^[A-Za-z0-9-]{6,64}$/.test(s) ? s : undefined;
}

/**
 * Turns Claude's reading into entries the phone can review and save: amounts in
 * whole kyat, real dates, known categories. Anything that does not pass is
 * dropped, never filled in.
 */
export function normalizeScan(
  raw: ScanOutput,
  { categories, today }: { categories: string[]; today: string },
): ScanResultDto {
  if (raw.kind === "unknown") return { kind: "unknown", entries: [] };

  const known = new Map(categories.map((name) => [categoryKey(name), name]));
  const merchant = text(raw.merchant);
  const date = raw.date === undefined ? undefined : validDate(raw.date, today);
  const total = kyat(raw.total);

  let entries: ScanEntryDto[] = [];
  for (const e of raw.entries) {
    const amount = kyat(e.amount);
    // An entry without its own date takes the receipt's; a date that is there but unreadable drops it.
    const entryDate = e.date === undefined ? date : validDate(e.date, today);
    if (amount === undefined || entryDate === undefined) continue;
    const category =
      e.type === "income" ? INCOME : (known.get(categoryKey(e.category)) ?? OTHER);
    const entry: ScanEntryDto = {
      type: e.type,
      amount,
      category,
      description: text(e.description) ?? merchant ?? category,
      date: entryDate,
    };
    if (raw.kind === "receipt") {
      const items = (e.items ?? []).flatMap((item) => {
        const name = text(item.name);
        const itemAmount = kyat(item.amount);
        return name && itemAmount ? [{ name, amount: itemAmount }] : [];
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

  if (raw.kind === "receipt") entries = groupByCategory(entries);
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

  return {
    kind: raw.kind,
    ...(merchant ? { merchant } : {}),
    ...(date ? { date } : {}),
    ...(total ? { total } : {}),
    entries: entries.slice(0, MAX_ENTRIES),
  };
}

// One entry per category, in the order the categories first appear.
function groupByCategory(entries: ScanEntryDto[]): ScanEntryDto[] {
  const groups = new Map<string, ScanEntryDto>();
  for (const e of entries) {
    const key = `${e.type}:${e.category}`;
    const group = groups.get(key);
    if (!group) {
      groups.set(key, { ...e, ...(e.items ? { items: [...e.items] } : {}) });
      continue;
    }
    group.amount += e.amount;
    if (e.items) group.items = [...(group.items ?? []), ...e.items];
  }
  return [...groups.values()];
}
