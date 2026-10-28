import mongoose, { Schema, Document, models, Model } from "mongoose";
import type { Budget } from "@/lib/types";

const BudgetSchema = new Schema({
  userId: { type: String, required: true },
  category: { type: String, required: true },
  limit: { type: Number, required: true },
});

BudgetSchema.index({ userId: 1, category: 1 }, { unique: true });

const BudgetModel = (models.Budget ||
  mongoose.model("Budget", BudgetSchema)) as Model<Budget & Document>;

export default BudgetModel;
