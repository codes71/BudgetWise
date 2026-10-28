import mongoose, { Schema, Document, models, Model } from "mongoose";
import type { Transaction } from "@/lib/types";

const TransactionSchema = new Schema(
  {
    userId: { type: String, required: true },
    date: { type: String, required: true },
    description: { type: String, required: true },
    amount: { type: Number, required: true },
    category: { type: String, required: true },
    type: { type: String, enum: ["income", "expense"], required: true },
    clientId: { type: String },
    source: { type: String, enum: ["voice", "manual", "web"], default: "web" },
    transcript: { type: String, maxlength: 2000 },
  },
  { timestamps: true }
);

// Partial so legacy web rows (no clientId) stay valid. Partial indexes cannot
// use $ne, hence $type. https://www.mongodb.com/docs/manual/core/index-partial/
TransactionSchema.index(
  { userId: 1, clientId: 1 },
  { unique: true, partialFilterExpression: { clientId: { $type: "string" } } }
);
TransactionSchema.index({ userId: 1, updatedAt: 1 });

const TransactionModel = (models.Transaction ||
  mongoose.model("Transaction", TransactionSchema)) as Model<
  Transaction & Document
>;

export default TransactionModel;
