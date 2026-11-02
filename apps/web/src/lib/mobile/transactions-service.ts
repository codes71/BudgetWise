import type { DocumentSnapshot } from "firebase-admin/firestore";
import { ALREADY_EXISTS, NOT_FOUND, db, errorCode, isDocId, toDate, userDoc } from "@/lib/firestore";
import { toTransactionDto, type LeanTransaction, type TransactionDto, type TransactionInput } from "./dto";

// Firestore caps `in` filters at 30 values.
const IN_LIMIT = 30;

export const transactionsCol = (userId: string) => userDoc(userId).collection("transactions");

/** The doc as toTransactionDto and the web UI read it, with Timestamps as Dates. */
export function toLeanTransaction(snap: DocumentSnapshot): LeanTransaction {
  const d = snap.data()!;
  return {
    ...(d as Omit<LeanTransaction, "_id" | "createdAt" | "updatedAt">),
    _id: snap.id,
    createdAt: toDate(d.createdAt),
    updatedAt: toDate(d.updatedAt),
  };
}

function chunks<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

// Rows are immutable once stored: existing clientIds are returned as stored,
// only missing ones are created, all in one transaction.
async function createMissing(userId: string, items: TransactionInput[]): Promise<Map<string, LeanTransaction>> {
  const col = transactionsCol(userId);
  // A batch that repeats a clientId stores its first occurrence.
  const byClientId = new Map<string, TransactionInput>();
  for (const item of items) if (!byClientId.has(item.clientId)) byClientId.set(item.clientId, item);
  const clientIds = [...byClientId.keys()];

  return db().runTransaction(async (tx) => {
    const found = new Map<string, LeanTransaction>();
    for (const snap of await tx.getAll(...clientIds.map((id) => col.doc(id)))) {
      if (snap.exists) found.set(snap.id, toLeanTransaction(snap));
    }
    // Rows copied from Mongo keep their ObjectId as doc id, so also match the clientId field.
    for (const ids of chunks(clientIds.filter((id) => !found.has(id)), IN_LIMIT)) {
      for (const doc of (await tx.get(col.where("clientId", "in", ids))).docs) {
        const clientId = doc.get("clientId");
        if (!found.has(clientId)) found.set(clientId, toLeanTransaction(doc));
      }
    }
    const now = new Date();
    for (const id of clientIds) {
      if (found.has(id)) continue;
      const { clientId, type, amount, category, description, date, source, transcript } = byClientId.get(id)!;
      const data = { userId, clientId, type, amount, category, description, date, source, transcript, createdAt: now, updatedAt: now };
      tx.create(col.doc(id), data);
      found.set(id, { _id: id, ...data });
    }
    return found;
  });
}

export async function saveBatch(userId: string, items: TransactionInput[]): Promise<TransactionDto[]> {
  let rows: Map<string, LeanTransaction>;
  try {
    rows = await createMissing(userId, items);
  } catch (err) {
    // A concurrent identical batch can commit first; the retry then finds its rows.
    if (errorCode(err) !== ALREADY_EXISTS) throw err;
    rows = await createMissing(userId, items);
  }
  return items.map((item) => toTransactionDto(rows.get(item.clientId)!));
}

const updatedMs = (row: LeanTransaction): number =>
  row.updatedAt instanceof Date ? row.updatedAt.getTime() : 0;

export async function listSince(userId: string, since?: Date): Promise<TransactionDto[]> {
  const col = transactionsCol(userId);
  const snap = await (since ? col.where("updatedAt", ">=", since) : col).get();
  // Sorted here rather than with orderBy, which would drop legacy rows without updatedAt.
  const rows = snap.docs.map(toLeanTransaction).sort((a, b) => {
    const [ia, ib] = [String(a._id), String(b._id)];
    return updatedMs(a) - updatedMs(b) || (ia < ib ? -1 : ia > ib ? 1 : 0);
  });
  return rows.map(toTransactionDto);
}

export async function removeOne(userId: string, id: string): Promise<boolean> {
  if (!isDocId(id)) return false;
  try {
    await transactionsCol(userId).doc(id).delete({ exists: true });
    return true;
  } catch (err) {
    if (errorCode(err) === NOT_FOUND) return false;
    throw err;
  }
}
