import { db, isDocId, isDuplicateKey, leafId, scopedId, toDate, withTransaction, type MongoRecord } from "@/lib/mongodb";
import { toTransactionDto, type LeanTransaction, type TransactionDto, type TransactionInput } from "./dto";
import { requireActiveAccount } from "@/lib/account-access";

export const transactionsCol = async () => (await db()).collection<MongoRecord>("transactions");

/** Storage keys include the owner; API ids remain the original document ids. */
export function toLeanTransaction(doc: MongoRecord): LeanTransaction {
  return {
    ...(doc as unknown as Omit<LeanTransaction, "_id" | "createdAt" | "updatedAt">),
    _id: leafId(doc._id),
    createdAt: toDate(doc.createdAt),
    updatedAt: toDate(doc.updatedAt),
  };
}

// Rows are immutable once stored: existing clientIds are returned as stored,
// only missing ones are created, all in one transaction.
async function createMissing(userId: string, items: TransactionInput[]): Promise<Map<string, LeanTransaction>> {
  const col = await transactionsCol();
  // A batch that repeats a clientId stores its first occurrence.
  const byClientId = new Map<string, TransactionInput>();
  for (const item of items) if (!byClientId.has(item.clientId)) byClientId.set(item.clientId, item);
  const clientIds = [...byClientId.keys()];

  return withTransaction(async (session) => {
    // The account fence conflicts with a concurrent deletion claim.
    await requireActiveAccount(userId, session);
    const found = new Map<string, LeanTransaction>();
    const direct = await col.find({ userId, _id: { $in: clientIds.map((id) => scopedId(userId, id)) } }, { session }).toArray();
    for (const doc of direct) found.set(leafId(doc._id), toLeanTransaction(doc));
    // Earlier Mongo rows retained their original ids when copied into Firestore.
    // Match their clientId field too, without replacing an existing stored row.
    const missingIds = clientIds.filter((id) => !found.has(id));
    if (missingIds.length) {
      const legacy = await col.find({ userId, clientId: { $in: missingIds } }, { session }).sort({ _id: 1 }).toArray();
      for (const doc of legacy) if (!found.has(doc.clientId)) found.set(doc.clientId, toLeanTransaction(doc));
    }
    const now = new Date();
    for (const id of clientIds) {
      if (found.has(id)) continue;
      const { clientId, type, amount, category, description, date, source, transcript, receiptId, merchant, items: lines } =
        byClientId.get(id)!;
      const data = {
        userId, clientId, type, amount, category, description, date, source,
        ...(transcript !== undefined ? { transcript } : {}),
        ...(receiptId !== undefined ? { receiptId } : {}),
        ...(merchant !== undefined ? { merchant } : {}),
        ...(lines?.length ? { items: lines.map(({ name, quantity, unitPrice }) => ({ name, quantity, unitPrice })) } : {}),
        createdAt: now, updatedAt: now,
      };
      await col.insertOne({ _id: scopedId(userId, id), ...data }, { session });
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
    // Retry the complete transaction after a competing insert wins a unique key.
    if (!isDuplicateKey(err)) throw err;
    rows = await createMissing(userId, items);
  }
  return items.map((item) => toTransactionDto(rows.get(item.clientId)!));
}

const updatedMs = (row: LeanTransaction): number =>
  row.updatedAt instanceof Date ? row.updatedAt.getTime() : 0;

export async function listSince(userId: string, since?: Date): Promise<TransactionDto[]> {
  const col = await transactionsCol();
  const docs = await col.find({ userId, ...(since ? { updatedAt: { $gte: since } } : {}) }).toArray();
  // Full reads retain legacy rows without updatedAt.
  const rows = docs.map(toLeanTransaction).sort((a, b) => {
    const [ia, ib] = [String(a._id), String(b._id)];
    return updatedMs(a) - updatedMs(b) || (ia < ib ? -1 : ia > ib ? 1 : 0);
  });
  return rows.map(toTransactionDto);
}

export async function removeOne(userId: string, id: string): Promise<boolean> {
  if (!isDocId(id)) return false;
  const col = await transactionsCol();
  return withTransaction(async (session) => {
    await requireActiveAccount(userId, session);
    return (await col.deleteOne({ _id: scopedId(userId, id), userId }, { session })).deletedCount === 1;
  });
}
