import { handle, readJson } from "@/lib/mobile/errors";
import { requireUser } from "@/lib/mobile/bearer";
import { transactionBatchSchema, updatedSinceSchema } from "@/lib/mobile/schemas";
import type { TransactionInput } from "@/lib/mobile/dto";
import { listSince, saveBatch } from "@/lib/mobile/transactions-service";

export const dynamic = "force-dynamic";

export const GET = handle(async (req) => {
  const { userId } = await requireUser(req);
  const raw = new URL(req.url).searchParams.get("updatedSince");
  const since = raw === null ? undefined : new Date(updatedSinceSchema.parse(raw));
  return Response.json({ transactions: await listSince(userId, since) });
});

export const POST = handle(async (req) => {
  const { userId } = await requireUser(req);
  const { transactions } = transactionBatchSchema.parse(await readJson(req));
  // Compile-time check: the zod output must stay assignable to the DTO input type.
  const items: TransactionInput[] = transactions;
  return Response.json({ saved: await saveBatch(userId, items) });
});
