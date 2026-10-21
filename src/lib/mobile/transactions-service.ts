import mongoose from "mongoose";
import dbConnect from "@/lib/db";
import TransactionModel from "@/lib/models/transaction";
import { toTransactionDto, type LeanTransaction, type TransactionDto, type TransactionInput } from "./dto";

async function upsertAll(userId: string, items: TransactionInput[]): Promise<void> {
  await TransactionModel.bulkWrite(
    items.map((item) => ({
      updateOne: {
        filter: { userId, clientId: item.clientId },
        // $setOnInsert never overwrites an existing row (rows are immutable).
        update: { $setOnInsert: { userId, ...item } },
        upsert: true,
      },
    })),
    { ordered: false },
  );
}

const isDuplicateKey = (err: unknown): boolean =>
  typeof err === "object" && err !== null && (err as { code?: number }).code === 11000;

export async function saveBatch(userId: string, items: TransactionInput[]): Promise<TransactionDto[]> {
  await dbConnect();
  try {
    await upsertAll(userId, items);
  } catch (err) {
    // A concurrent identical batch can win the upsert race and surface E11000; the retry takes the match path.
    if (!isDuplicateKey(err)) throw err;
    await upsertAll(userId, items);
  }
  const rows = await TransactionModel.find({
    userId,
    clientId: { $in: items.map((i) => i.clientId) },
  }).lean<LeanTransaction[]>();
  const byClientId = new Map(rows.map((r) => [r.clientId, r]));
  return items.map((i) => toTransactionDto(byClientId.get(i.clientId)!));
}

export async function listSince(userId: string, since?: Date): Promise<TransactionDto[]> {
  await dbConnect();
  const filter = since ? { userId, updatedAt: { $gte: since } } : { userId };
  const rows = await TransactionModel.find(filter)
    .sort({ updatedAt: 1, _id: 1 })
    .lean<LeanTransaction[]>();
  return rows.map(toTransactionDto);
}

export async function removeOne(userId: string, id: string): Promise<boolean> {
  if (!mongoose.isValidObjectId(id)) return false;
  await dbConnect();
  const res = await TransactionModel.deleteOne({ _id: id, userId });
  return res.deletedCount === 1;
}
