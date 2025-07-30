'use server';

import dbConnect from '@/lib/db';
import TransactionModel from '@/lib/models/transaction';
import BudgetModel from '@/lib/models/budget';
import type { Transaction, Budget } from '@/lib/types';
import { revalidatePath } from 'next/cache';

export async function getTransactions(): Promise<Transaction[]> {
  await dbConnect();
  const transactions = await TransactionModel.find({}).sort({ date: -1 });
  return JSON.parse(JSON.stringify(transactions));
}

export async function getBudgets(): Promise<Budget[]> {
  await dbConnect();
  const budgets = await BudgetModel.find({});
  return JSON.parse(JSON.stringify(budgets));
}

export async function addTransaction(transaction: Omit<Transaction, 'id' | '_id'>): Promise<Transaction | null> {
  await dbConnect();
  try {
    const newTransaction = new TransactionModel(transaction);
    await newTransaction.save();
    revalidatePath('/');
    return JSON.parse(JSON.stringify(newTransaction));
  } catch (error) {
    console.error('Error adding transaction:', error);
    return null;
  }
}

export async function setBudget(budget: Budget): Promise<void> {
  await dbConnect();
  await BudgetModel.updateOne({ category: budget.category }, { limit: budget.limit }, { upsert: true });
  revalidatePath('/');
}

export async function importData(transactions: Transaction[], budgets: Budget[]): Promise<void> {
    await dbConnect();
    if (transactions.length > 0) {
        await TransactionModel.insertMany(transactions.map(({ _id, id, ...t }) => t));
    }
    if (budgets.length > 0) {
        const budgetOps = budgets.map(b => ({
            updateOne: {
                filter: { category: b.category },
                update: { $set: { limit: b.limit } },
                upsert: true,
            }
        }));
        await BudgetModel.bulkWrite(budgetOps);
    }
    revalidatePath('/');
}