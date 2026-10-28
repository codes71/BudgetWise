export interface TransactionDto {
  id: string;
  clientId: string;
  type: "expense" | "income";
  amount: number;
  category: string;
  description: string;
  date: string;
  source: "voice" | "manual" | "web";
  transcript?: string;
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
  reference?: string;
  clientId?: string;
  items?: { name: string; amount: number }[];
}

export interface ScanResultDto {
  kind: "receipt" | "payment" | "history" | "unknown";
  merchant?: string;
  date?: string;
  total?: number;
  entries: ScanEntryDto[];
}

export interface LeanTransaction {
  _id: unknown;
  date: string;
  description: string;
  amount: number;
  category: string;
  type: "income" | "expense";
  clientId?: string;
  source?: "voice" | "manual" | "web";
  transcript?: string;
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
    createdAt: iso(doc.createdAt),
    updatedAt: iso(doc.updatedAt),
  };
}
