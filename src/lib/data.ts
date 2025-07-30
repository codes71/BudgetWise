import type { Transaction, Budget } from './types';

export const mockTransactions: Transaction[] = [
  { id: '1', date: '2024-07-20', description: 'Grocery Store', amount: 75.5, category: 'Groceries', type: 'expense' },
  { id: '2', date: '2024-07-20', description: 'Electricity Bill', amount: 120.0, category: 'Utilities', type: 'expense' },
  { id: '3', date: '2024-07-19', description: 'Movie Tickets', amount: 30.0, category: 'Entertainment', type: 'expense' },
  { id: '4', date: '2024-07-18', description: 'Gasoline', amount: 50.25, category: 'Transport', type: 'expense' },
  { id: '5', date: '2024-07-18', description: 'Rent', amount: 1200.0, category: 'Housing', type: 'expense' },
  { id: '6', date: '2024-07-17', description: 'Pharmacy', amount: 25.0, category: 'Health', type: 'expense' },
  { id: '7', date: '2024-07-15', description: 'Salary', amount: 3000.0, category: 'Income', type: 'income' },
  { id: '8', date: '2024-07-15', description: 'Restaurant', amount: 60.0, category: 'Entertainment', type: 'expense' },
];

export const mockBudgets: Budget[] = [
  { category: 'Groceries', limit: 400 },
  { category: 'Utilities', limit: 150 },
  { category: 'Entertainment', limit: 200 },
  { category: 'Transport', limit: 150 },
  { category: 'Housing', limit: 1200 },
  { category: 'Health', limit: 100 },
  { category: 'Other', limit: 50 },
];
