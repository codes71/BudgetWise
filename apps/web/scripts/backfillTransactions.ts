// Backfill legacy transactions with createdAt, updatedAt and source.
//
// Dry run by default. Pass --apply to write.
//   npm run backfill:transactions            (dry run, prints what would change)
//   npm run backfill:transactions -- --apply (writes)
//
// Only adds missing createdAt/updatedAt/source. Never touches date, amount or
// clientId, and never overwrites a value that already exists, so it is
// idempotent. Rollback: the fields are additive, so
//   db.transactions.updateMany({source: "web"}, {$unset: {createdAt: "", updatedAt: "", source: ""}})
// would undo it for rows this script filled (only if you accept losing the
// same fields on rows the app created as source "web").
import mongoose from "mongoose";

const FILTER = {
  $or: [{ updatedAt: { $exists: false } }, { source: { $exists: false } }],
};

export async function backfillTransactions(opts: {
  apply: boolean;
}): Promise<{ matched: number; modified: number }> {
  const col = mongoose.connection.db!.collection("transactions");
  const matched = await col.countDocuments(FILTER);

  const samples = await col
    .find(FILTER, { projection: { date: 1, createdAt: 1, updatedAt: 1, source: 1 } })
    .limit(3)
    .toArray();
  console.log(`${matched} transaction(s) need backfill.`);
  for (const s of samples) {
    console.log(
      `  sample ${s._id}: date=${JSON.stringify(s.date)} would set createdAt=${
        s.createdAt ?? s._id.getTimestamp().toISOString()
      } updatedAt=${s.updatedAt ?? s._id.getTimestamp().toISOString()} source=${
        s.source ?? "web"
      }`
    );
  }

  if (!opts.apply) {
    console.log("Dry run, nothing written. Re-run with --apply to write.");
    return { matched, modified: 0 };
  }

  const res = await col.updateMany(FILTER, [
    {
      $set: {
        createdAt: { $ifNull: ["$createdAt", { $toDate: "$_id" }] },
        updatedAt: { $ifNull: ["$updatedAt", { $toDate: "$_id" }] },
        source: { $ifNull: ["$source", "web"] },
      },
    },
  ]);
  console.log(`Applied: matched ${res.matchedCount}, modified ${res.modifiedCount}.`);
  return { matched, modified: res.modifiedCount };
}

async function main() {
  const apply = process.argv.includes("--apply");
  const uri = process.env.MONGODB_URI;
  if (!uri) {
    throw new Error("MONGODB_URI is not set");
  }
  await mongoose.connect(uri);
  console.log(`Connected to database "${mongoose.connection.name}". Mode: ${apply ? "APPLY" : "dry run"}.`);
  try {
    await backfillTransactions({ apply });
  } finally {
    await mongoose.disconnect();
  }
}

if (process.argv[1] && /backfillTransactions\.[cm]?[jt]s$/.test(process.argv[1])) {
  main().catch((err) => {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
  });
}
