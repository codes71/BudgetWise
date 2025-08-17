import mongoose, { Schema, Document, models, Model } from "mongoose";

export interface IUser extends Document {
  email: string;
  password?: string;
  fullName?: string;
  phoneNumber?: string;
  profilePhotoUrl?: string;
  createdAt: Date;
}

const UserSchema = new Schema({
  email: { type: String, required: true, unique: true },
  password: { type: String, required: false },
  fullName: { type: String, required: false },
  phoneNumber: { type: String, required: false },
  profilePhotoUrl: { type: String, required: false },
}, { timestamps: true });

const UserModel = (models.User ||
  mongoose.model("User", UserSchema)) as Model<IUser>;

export default UserModel;
