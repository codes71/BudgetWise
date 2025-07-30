import mongoose, { Schema, Document, models, Model } from 'mongoose';
import type { Budget } from '@/lib/types';

const BudgetSchema = new Schema({
  category: { type: String, required: true, unique: true },
  limit: { type: Number, required: true },
});

const BudgetModel = (models.Budget || 
  mongoose.model('Budget', BudgetSchema)) as Model<Budget & Document>;

export default BudgetModel;