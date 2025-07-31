'use server';

import dbConnect from '@/lib/db';
import TransactionModel from '@/lib/models/transaction';
import BudgetModel from '@/lib/models/budget';
import type { Transaction, Budget } from '@/lib/types';
import { revalidatePath } from 'next/cache';
import { verifySession } from '@/lib/auth';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';

async function getUserId(): Promise<string> {
  const user = await verifySession();
  if (!user || !user.userId) {
    throw new Error('You must be logged in to perform this action.');
  }
  return user.userId;
}

export async function getTransactions(): Promise<Transaction[]> {
  const userId = await getUserId();
  await dbConnect();
  const transactions = await TransactionModel.find({ userId }).sort({ date: -1 });
  return JSON.parse(JSON.stringify(transactions));
}

export async function getBudgets(): Promise<Budget[]> {
  const userId = await getUserId();
  await dbConnect();
  const budgets = await BudgetModel.find({ userId });
  return JSON.parse(JSON.stringify(budgets));
}

export async function addTransaction(transaction: Omit<Transaction, 'id' | '_id' | 'userId'>): Promise<Transaction | null> {
  const userId = await getUserId();
  await dbConnect();
  try {
    const newTransaction = new TransactionModel({ ...transaction, userId });
    await newTransaction.save();
    revalidatePath('/');
    return JSON.parse(JSON.stringify(newTransaction));
  } catch (error) {
    console.error('Error adding transaction:', error);
    return null;
  }
}

export async function setBudget(budget: Omit<Budget, 'userId' | '_id'>): Promise<void> {
  const userId = await getUserId();
  await dbConnect();
  await BudgetModel.updateOne({ category: budget.category, userId }, { limit: budget.limit }, { upsert: true });
  revalidatePath('/');
  revalidatePath('/budgets');
}

export async function signOut() {
  await cookies().set('session', '', { expires: new Date(0) });
  redirect('/login');
}
