import { ApiError, handle } from "@/lib/mobile/errors";
import { requireUser } from "@/lib/mobile/bearer";
import { removeOne } from "@/lib/mobile/transactions-service";

export const dynamic = "force-dynamic";

export const DELETE = handle(async (req, { params }: { params: Promise<{ id: string }> }) => {
  const { userId } = await requireUser(req);
  const { id } = await params;
  if (!(await removeOne(userId, id))) throw new ApiError(404, "not_found", "Transaction not found");
  return new Response(null, { status: 204 });
});
