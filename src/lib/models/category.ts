import mongoose, { Schema, Document } from 'mongoose';

export interface ICategory extends Document {
  name: string;
  userId?: mongoose.Types.ObjectId; // Optional: if categories are user-specific
}

const CategorySchema: Schema = new Schema({
  name: { type: String, required: true, unique: true },
  userId: { type: Schema.Types.ObjectId, ref: 'User', required: false }, // Reference to User model
});

const CategoryModel = mongoose.models.Category || mongoose.model<ICategory>('Category', CategorySchema);

export default CategoryModel;
