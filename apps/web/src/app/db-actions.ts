'use server';

import { z } from 'zod';
import { listBudgets, setBudgetLimit } from '@/lib/budgets';
import type { LeanTransaction } from '@/lib/mobile/dto';
import { getCategoriesForUser } from '@/lib/mobile/reference-data';
import { removeOne, toLeanTransaction, transactionsCol } from '@/lib/mobile/transactions-service';
import type { Transaction, Budget } from '@/lib/types';
import { revalidatePath } from 'next/cache';
import { verifySession } from '@/lib/auth';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { logger } from '@/lib/logger';
import { isWholeKyat } from '@/lib/kyat';

async function getUserId(): Promise<string> {
  const user = await verifySession();
  if (!user || !user.userId) {
    throw new Error('You must be logged in to perform this action.');
  }
  return user.userId;
}

// The web form's fields; anything else a caller sends is dropped.
const webTransactionFields = z.object({
  date: z.string().min(1),
  description: z.string().min(1),
  amount: z.number(),
  category: z.string().min(1),
  type: z.enum(['income', 'expense']),
});

const isoOrUndefined = (value: Date | string | undefined) =>
  value === undefined ? undefined : new Date(value).toISOString();

// The UI keys rows by `_id`, which is the Firestore doc id.
function toWebTransaction(userId: string, row: LeanTransaction): Transaction {
  return {
    _id: String(row._id),
    userId,
    date: row.date,
    description: row.description,
    amount: row.amount,
    category: row.category,
    type: row.type,
    ...(row.clientId ? { clientId: row.clientId } : {}),
    source: row.source ?? 'web',
    ...(row.transcript ? { transcript: row.transcript } : {}),
    createdAt: isoOrUndefined(row.createdAt),
    updatedAt: isoOrUndefined(row.updatedAt),
  };
}

export async function getTransactions(): Promise<Transaction[]> {
  const userId = await getUserId();
  console.log('Fetching transactions for user:', userId);
  const snap = await transactionsCol(userId).get();
  // Newest date first.
  return snap.docs
    .map((doc) => toWebTransaction(userId, toLeanTransaction(doc)))
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
}

export async function getBudgets(): Promise<Budget[]> {
  const userId = await getUserId();
  return listBudgets(userId);
}

export async function getCategories(): Promise<string[]> {
  const userId = await getUserId(); // Categories can be user-specific or global
  console.log('Fetched categories');
  return getCategoriesForUser(userId);
}

export async function addTransaction(
  transaction: Omit<Transaction, 'id' | '_id' | 'userId'>
): Promise<Transaction | null> {
  const userId = await getUserId();
  // The form already refuses decimals; this covers callers that skip it.
  if (!isWholeKyat(transaction.amount)) return null;
  try {
    // Mobile-only fields (clientId, transcript) are dropped and source is
    // always web, so the client cannot pose as the phone app.
    const fields = webTransactionFields.safeParse(transaction);
    if (!fields.success) return null;
    const now = new Date();
    const data = { ...fields.data, userId, source: 'web' as const, createdAt: now, updatedAt: now };
    const ref = await transactionsCol(userId).add(data);
    revalidatePath('/');
    return { ...data, _id: ref.id, createdAt: now.toISOString(), updatedAt: now.toISOString() };
  } catch (error) {
    logger.error('Error adding transaction', error as Error);
    return null;
  }
}

export async function setBudget(
  budget: Omit<Budget, 'userId' | '_id'>
): Promise<Budget | null> {
  try {
    const userId = await getUserId();
    if (!isWholeKyat(budget.limit)) return null;
    if (typeof budget.category !== 'string' || !budget.category) return null;

    const updatedBudget = await setBudgetLimit(userId, budget.category, budget.limit);

    revalidatePath('/');
    revalidatePath('/budgets');

    return updatedBudget;
  } catch (error) {
    logger.error('Error setting budget', error as Error);
    return null;
  }
}

export async function deleteTransaction(transactionId: string): Promise<{ success: boolean, error?: string }> {
  try {
    const userId = await getUserId();

    if (!(await removeOne(userId, transactionId))) {
      return { success: false, error: 'Transaction not found or you do not have permission to delete it.' };
    }

    revalidatePath('/');
    return { success: true };
  } catch (error) {
    logger.error('Error deleting transaction', error as Error);
    return { success: false, error: 'Failed to delete transaction' };
  }
}

export async function signOut() {
  const cookieStore = await Promise.resolve(cookies());
  await cookieStore.set('session', '', { expires: new Date(0) });
  redirect('/login');
}