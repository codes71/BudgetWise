import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { requireUser } from "@/lib/mobile/bearer";
import { saveEndCheck, yangonMonth } from "@/lib/budgets";
import { toMonthPlanDto } from "@/lib/mobile/dto";
import { endCheckInputSchema, monthSchema } from "@/lib/mobile/schemas";

export const dynamic = "force-dynamic";

export const PUT = handle(async (req, { params }: { params: Promise<{ month: string }> }) => {
  const { userId } = await requireUser(req);
  const month = monthSchema.safeParse((await params).month);
  if (!month.success) throw new ApiError(400, "invalid_month", "Month must be YYYY-MM");
  const { onHand } = endCheckInputSchema.parse(await readJson(req));

  // "YYYY-MM" sorts like dates, so this month and later ones are not over.
  if (month.data >= yangonMonth(new Date())) throw new ApiError(422, "month_not_over", "This month is not over yet");
  const plan = await saveEndCheck(userId, month.data, onHand);
  if (!plan) throw new ApiError(404, "not_found", "This month has no budget");
  return Response.json({ month: toMonthPlanDto(plan) });
});
