import mongoose, { Schema, Document } from "mongoose";

export interface IRateLimit extends Document {
  key: string;
  count: number;
  expiresAt: Date;
}

const RateLimitSchema: Schema = new Schema({
  key: { type: String, required: true, unique: true },
  count: { type: Number, required: true },
  expiresAt: { type: Date, required: true },
});

// Mongo's TTL monitor deletes expired docs (lag up to ~60s), so the limiter
// also checks expiresAt itself.
RateLimitSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

const RateLimitModel: mongoose.Model<IRateLimit> =
  (mongoose.models.RateLimit as mongoose.Model<IRateLimit>) ||
  mongoose.model<IRateLimit>("RateLimit", RateLimitSchema);

export default RateLimitModel;
