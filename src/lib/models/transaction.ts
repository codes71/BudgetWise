import mongoose, { Schema, Document, models, Model } from 'mongoose';
import type { Transaction } from '@/lib/types';

const TransactionSchema = new Schema({
  date: { type: String, required: true },
  description: { type: String, required: true },
  amount: { type: Number, required: true },
  category: { type: String, required: true },
  type: { type: String, enum: ['income', 'expense'], required: true },
});

// Use a type assertion to avoid conflicts with the imported Transaction type
const TransactionModel = (models.Transaction ||
