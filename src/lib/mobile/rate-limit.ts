import dbConnect from "@/lib/db";
import RateLimit from "@/lib/models/rate-limit";
import { ApiError } from "./errors";

export interface RateLimitOptions {
  limit: number;
  windowSec: number;
}

// One atomic pipeline upsert per call: if the stored window is still live the
// count is incremented, otherwise the doc is reset to a fresh window. Two
// racing upserts on a missing key can hit E11000 on the unique key; the loser
// retries once and then takes the update path.
async function hit(key: string, windowSec: number) {
  const now = new Date();
  const live = { $gt: ["$expiresAt", now] };
  return RateLimit.findOneAndUpdate(
    { key },
    [
      {
        $set: {
          count: { $cond: [live, { $add: [{ $ifNull: ["$count", 0] }, 1] }, 1] },
          expiresAt: {
            $cond: [live, "$expiresAt", new Date(now.getTime() + windowSec * 1000)],
          },
        },
      },
    ],
    { upsert: true, new: true },
  ).lean();
}

export async function rateLimit(key: string, opts: RateLimitOptions): Promise<void> {
  // init() waits for a connection that nothing else opens on a cold process.
  await dbConnect();
  await RateLimit.init();
  let doc;
  try {
    doc = await hit(key, opts.windowSec);
  } catch (err: any) {
    if (err?.code !== 11000) throw err;
    doc = await hit(key, opts.windowSec);
  }
  if (doc && doc.count > opts.limit) {
    const retryAfterSec = Math.max(1, Math.ceil((doc.expiresAt.getTime() - Date.now()) / 1000));
    throw new ApiError(429, "rate_limited", "Too many requests", retryAfterSec);
  }
}
