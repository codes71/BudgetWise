// src/lib/guest-data.ts
import type { Budget, Transaction } from '@/lib/types';

export const guestCategories = [
  "Groceries", "Salary", "Utilities", "Rent", "Entertainment", "Transportation", "Dining Out", "Shopping"
];

export const guestTransactions: Transaction[] = [
  { _id: '1', type: 'income', amount: 3000, description: 'Monthly Salary', category: 'Salary', date: new Date(new Date().setDate(1)).toISOString() },
  { _id: '2', type: 'expense', amount: 1500, description: 'Apartment Rent', category: 'Rent', date: new Date(new Date().setDate(2)).toISOString() },
  { _id: '3', type: 'expense', amount: 150, description: 'Electricity Bill', category: 'Utilities', date: new Date(new Date().setDate(5)).toISOString() },
  { _id: '4', type: 'expense', amount: 250, description: 'Weekly Groceries', category: 'Groceries', date: new Date(new Date().setDate(7)).toISOString() },
  { _id: '5', type: 'expense', amount: 75, description: 'Movie Night', category: 'Entertainment', date: new Date(new Date().setDate(10)).toISOString() },
  { _id: '6', type: 'expense', amount: 60, description: 'Dinner with friends', category: 'Dining Out', date: new Date(new Date().setDate(12)).toISOString() },
  { _id: '7', type: 'expense', amount: 120, description: 'New Shirt', category: 'Shopping', date: new Date(new Date().setDate(15)).toISOString() },
];

export const guestBudgets: Budget[] = [
  { _id: '1', category: 'Groceries', limit: 400, spent: 250 },
  { _id: '2', category: 'Entertainment', limit: 150, spent: 75 },
  { _id: '3', category: 'Shopping', limit: 200, spent: 120 },
  { _id: '4', category: 'Dining Out', limit: 250, spent: 60 },
];