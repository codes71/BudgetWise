import mongoose, { Schema, Document, models, Model } from 'mongoose';
import type { Transaction } from '@/lib/types';

const TransactionSchema = new Schema({
  userId: { type: String, required: true },
  date: { type: String, required: true },
  description: { type: String, required: true },
  amount: { type: Number, required: true },
  category: { type: String, required: true },
  type: { type: String, enum: ['income', 'expense'], required: true },
});

const TransactionModel = (models.Transaction ||
  mongoose.model('Transaction', TransactionSchema)) as Model<Transaction & Document>;

export default TransactionModel;
