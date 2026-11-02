/** One receipt line: `quantity` of `name` at `unitPrice` whole kyat each. */
export interface EntryItemDto {
  name: string;
  quantity: number;
  unitPrice: number;
}

/** "web" is only ever read: rows added on the website, and legacy rows without a source. */
export type TransactionSource = "voice" | "manual" | "scan" | "web";

export interface TransactionDto {
  id: string;
  clientId: string;
  type: "expense" | "income";
  amount: number;
  category: string;
  description: string;
  date: string;
  source: TransactionSource;
  transcript?: string;
  receiptId?: string;
  merchant?: string;
  items?: EntryItemDto[];
  createdAt: string;
  updatedAt: string;
}

export type TransactionInput = Omit<TransactionDto, "id" | "createdAt" | "updatedAt">;

export interface DraftTransactionDto {
  type: "expense" | "income";
  amount: number;
  category: string;
  description: string;
  date: string;
  confidence: number;
}

export interface ScanEntryDto {
  type: "expense" | "income";
  amount: number;
  category: string;
  description: string;
  date: string;
  /** Set when a receipt or slip showed no readable date and `date` is today in Yangon. */
  dateGuessed?: true;
  reference?: string;
  clientId?: string;
  items?: ScanItemDto[];
}

/** A receipt line as read: `amount` is the line total, always `quantity * unitPrice`. */
export interface ScanItemDto extends EntryItemDto {
  amount: number;
}

/**
 * Why a scan gave kind "unknown": not a receipt or KPay screen at all, one that cannot be read, several receipts
 * in one photo, another wallet or bank app's screen, or amounts in another currency (`currency` then says which).
 */
export type ScanReason = "not_money" | "unclear" | "multiple" | "unsupported" | "foreign_currency";

export interface ScanResultDto {
  kind: "receipt" | "payment" | "history" | "unknown";
  /** Only with kind "unknown", which always has one. */
  reason?: ScanReason;
  /** Only with reason "foreign_currency": the currency printed, as the model read it (USD, THB). */
  currency?: string;
  merchant?: string;
  date?: string;
  total?: number;
  entries: ScanEntryDto[];
}

export interface VoiceEntryDto {
  type: "expense" | "income";
  amount: number;
  category: string;
  description: string;
  date: string;
  /** Set when nothing said when it happened and `date` is today in Yangon. */
  dateGuessed?: true;
}

export interface VoiceQuestionDto {
  metric: "list" | "total" | "over_budget";
  category: string | null;
  from: string;
  to: string;
}

export interface VoiceParseDto {
  intent: "add" | "question" | "other";
  heard: string;
  entries: VoiceEntryDto[];
  question: VoiceQuestionDto | null;
}

export interface LeanTransaction {
  _id: unknown;
  date: string;
  description: string;
  amount: number;
  category: string;
  type: "income" | "expense";
  clientId?: string;
  source?: TransactionSource;
  transcript?: string;
  receiptId?: string;
  merchant?: string;
  items?: EntryItemDto[];
  createdAt?: Date | string;
  updatedAt?: Date | string;
}

const iso = (v: Date | string | undefined): string =>
  v === undefined ? new Date(0).toISOString() : new Date(v).toISOString();

export function toTransactionDto(doc: LeanTransaction): TransactionDto {
  const id = String(doc._id);
  return {
    id,
    // Legacy web rows have no clientId; the contract requires a string, so synthesize from _id.
    clientId: doc.clientId ?? id,
    type: doc.type,
    amount: doc.amount, // as stored, no rounding
    category: doc.category,
    description: doc.description,
    date: doc.date.slice(0, 10),
    source: doc.source ?? "web",
    ...(doc.transcript ? { transcript: doc.transcript } : {}),
    ...(doc.receiptId ? { receiptId: doc.receiptId } : {}),
    ...(doc.merchant ? { merchant: doc.merchant } : {}),
    ...(doc.items?.length ? { items: doc.items } : {}),
    createdAt: iso(doc.createdAt),
    updatedAt: iso(doc.updatedAt),
  };
}
