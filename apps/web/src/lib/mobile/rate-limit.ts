import { Timestamp } from "firebase-admin/firestore";
import { db, sha256Hex } from "@/lib/firestore";
import { ApiError } from "./errors";

export interface RateLimitOptions {
  limit: number;
  windowSec: number;
}

// One transaction per call: read the window, then either count into it or
// start a fresh one. Racing calls on the same key retry inside the
// transaction, so no hit is lost. Expired docs are simply reset on the next
// hit; nothing has to delete them.
async function hit(key: string, windowSec: number): Promise<{ count: number; expiresAtMs: number }> {
  const ref = db().collection("rateLimits").doc(sha256Hex(key));
  return db().runTransaction(
    async (tx) => {
      const snap = await tx.get(ref);
      const now = Date.now();
      const prev = snap.data();
      const prevExpiresMs = prev?.expiresAt instanceof Timestamp ? prev.expiresAt.toMillis() : 0;
      const live = prevExpiresMs > now;
      const count = live ? (Number(prev?.count) || 0) + 1 : 1;
      const expiresAtMs = live ? prevExpiresMs : now + windowSec * 1000;
      tx.set(ref, { key, count, expiresAt: Timestamp.fromMillis(expiresAtMs) });
      return { count, expiresAtMs };
    },
    { maxAttempts: 10 },
  );
}

export async function rateLimit(key: string, opts: RateLimitOptions): Promise<void> {
  const { count, expiresAtMs } = await hit(key, opts.windowSec);
  if (count > opts.limit) {
    const retryAfterSec = Math.max(1, Math.ceil((expiresAtMs - Date.now()) / 1000));
    throw new ApiError(429, "rate_limited", "Too many requests", retryAfterSec);
  }
}
