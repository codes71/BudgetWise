import mongoose from "mongoose";
import dbConnect from "@/lib/db";
import BudgetModel from "@/lib/models/budget";
import CategoryModel from "@/lib/models/category";

export async function getCategoriesForUser(userId: string): Promise<string[]> {
  await dbConnect();

  let query: any = { userId: { $exists: false } }; // Start with global categories

  if (userId) {
    if (mongoose.Types.ObjectId.isValid(userId)) {
      // If it's a valid ObjectId, query by userId
      query = { $or: [{ userId: new mongoose.Types.ObjectId(userId) }, { userId: { $exists: false } }] };
    } else {
      // If it's not a valid ObjectId (e.g., guest ID), only fetch global categories
      query = { userId: { $exists: false } };
    }
  }

  const categories = await CategoryModel.find(query);
  return categories.map((cat) => cat.name);
}

export async function getBudgetsForUser(userId: string): Promise<{ category: string; limit: number }[]> {
  await dbConnect();
  const rows = await BudgetModel.find({ userId }).lean();
  return rows.map((b) => ({ category: b.category, limit: b.limit }));
}
