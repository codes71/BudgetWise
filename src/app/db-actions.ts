'use server';

import dbConnect from '@/lib/db';
import TransactionModel from '@/lib/models/transaction';
import BudgetModel from '@/lib/models/budget';
import CategoryModel from '@/lib/models/category'; // New import
import type { Transaction, Budget } from '@/lib/types';
import { revalidatePath } from 'next/cache';
import { verifySession } from '@/lib/auth';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { logger } from '@/lib/logger';

async function getUserId(): Promise<string> {
  const user = await verifySession();
  if (!user || !user.userId) {
    throw new Error('You must be logged in to perform this action.');
  }
  return user.userId;
}

export async function getTransactions(): Promise<Transaction[]> {
  const userId = await getUserId();
  console.log('Fetching transactions for user:', userId);
  await dbConnect();
  const transactions = await TransactionModel.find({ userId }).sort({
    date: -1,
  });
  return JSON.parse(JSON.stringify(transactions));
}

export async function getBudgets(): Promise<Budget[]> {
  const userId = await getUserId();
  await dbConnect();
  const budgets = await BudgetModel.find({ userId });
  return JSON.parse(JSON.stringify(budgets));
}

// New function to get categories
export async function getCategories(): Promise<string[]> {
  const userId = await getUserId(); // Categories can be user-specific or global
  await dbConnect();
  // For now, fetch all categories. Later, can filter by userId if needed.
  const categories = await CategoryModel.find({
    $or: [{ userId: userId }, { userId: { $exists: false } }],
  });
  console.log('Fetched categories');
  return categories.map((cat) => cat.name);
}

export async function addTransaction(
  transaction: Omit<Transaction, 'id' | '_id' | 'userId'>
): Promise<Transaction | null> {
  const userId = await getUserId();
  await dbConnect();
  try {
    const newTransaction = new TransactionModel({ ...transaction, userId });
    await newTransaction.save();
    revalidatePath('/');
    return JSON.parse(JSON.stringify(newTransaction));
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
    await dbConnect();
    
    const updatedBudget = await BudgetModel.findOneAndUpdate(
      { category: budget.category, userId },
      { limit: budget.limit },
      { new: true, upsert: true, runValidators: true }
    );

    revalidatePath('/');
    revalidatePath('/budgets');
    
    return JSON.parse(JSON.stringify(updatedBudget));
  } catch (error) {
    logger.error('Error setting budget', error as Error);
    return null;
  }
}

export async function deleteTransaction(transactionId: string): Promise<{ success: boolean, error?: string }> {
  try {
    const userId = await getUserId();
    await dbConnect();

    const result = await TransactionModel.deleteOne({ _id: transactionId, userId });

    if (result.deletedCount === 0) {
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