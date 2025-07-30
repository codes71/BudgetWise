import mongoose, { Schema, Document, models, Model } from 'mongoose';

export interface IUser extends Document {
  email: string;
  password?: string; // Password is not always present, e.g., for Google sign-in in the future
}

const UserSchema = new Schema({
  email: { type: String, required: true, unique: true },
  password: { type: String, required: false },
});

const UserModel = (models.User || 
  mongoose.model('User', UserSchema)) as Model<IUser>;

export default UserModel;
