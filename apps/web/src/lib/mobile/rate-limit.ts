import { db, isDuplicateKey, sha256Hex, toDate, withTransaction, type MongoRecord } from "@/lib/mongodb";
import { ApiError } from "./errors";
import { requireAccountRecord, requireActiveAccount } from "@/lib/account-access";

export interface RateLimitOptions {
  limit: number;
  windowSec: number;
}

// One transaction per call: read the window, then either count into it or
// start a fresh one. Racing calls on the same key retry inside the
// transaction, so no hit is lost. Expired docs are simply reset on the next
// hit; nothing has to delete them.
async function hit(key: string, windowSec: number): Promise<{ count: number; expiresAtMs: number }> {
  const rates = (await db()).collection<MongoRecord>("rateLimits");
  const id = sha256Hex(key);
  const run = () => withTransaction(async (session) => {
      // `<purpose>:user:<uid>` keys are exclusively account-owned. Read the same root that deletion locks/removes
      // inside this transaction, so an earlier successful bearer check cannot recreate counters after deletion.
      const owner = /^[^:]+:user:(.+)$/.exec(key)?.[1];
      if (owner) {
        if (key.startsWith("delete-account:user:")) await requireAccountRecord(owner, session);
        else await requireActiveAccount(owner, session);
      }
      const prev = await rates.findOne({ _id: id }, { session });
      const now = Date.now();
      const prevExpiresMs = toDate(prev?.expiresAt)?.getTime() ?? 0;
      const live = prevExpiresMs > now;
      const count = live ? (Number(prev?.count) || 0) + 1 : 1;
      const expiresAtMs = live ? prevExpiresMs : now + windowSec * 1000;
      await rates.replaceOne({ _id: id }, { _id: id, key, count, expiresAt: new Date(expiresAtMs) }, {
        session, upsert: true,
      });
      return { count, expiresAtMs };
  });
  try {
    return await run();
  } catch (error) {
    // Initial inserts can race before the key exists. Retry the whole transaction against the winner's row.
    if (!isDuplicateKey(error)) throw error;
    return run();
  }
}

export async function rateLimit(key: string, opts: RateLimitOptions): Promise<void> {
  const { count, expiresAtMs } = await hit(key, opts.windowSec);
  if (count > opts.limit) {
    const retryAfterSec = Math.max(1, Math.ceil((expiresAtMs - Date.now()) / 1000));
    throw new ApiError(429, "rate_limited", "Too many requests", retryAfterSec);
  }
}
