import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { requireUser } from "@/lib/mobile/bearer";
import { saveMonthPlan, splitOf, totalOf } from "@/lib/budgets";
import { toMonthPlanDto } from "@/lib/mobile/dto";
import { getCategoriesForUser } from "@/lib/mobile/reference-data";
import { monthPlanInputSchema, monthSchema } from "@/lib/mobile/schemas";
import { formatKyat } from "@/lib/kyat";

export const dynamic = "force-dynamic";

export const PUT = handle(async (req, { params }: { params: Promise<{ month: string }> }) => {
  const { userId } = await requireUser(req);
  const month = monthSchema.safeParse((await params).month);
  if (!month.success) throw new ApiError(400, "invalid_month", "Month must be YYYY-MM");
  const plan = monthPlanInputSchema.parse(await readJson(req));

  const total = totalOf(plan);
  if (splitOf(plan.shares) > total) {
    throw new ApiError(422, "split_over_total", `The split is more than the month's total of ${formatKyat(total)}`);
  }
  const known = new Set(await getCategoriesForUser(userId));
  const unknown = plan.shares.find((s) => !known.has(s.category));
  if (unknown) throw new ApiError(422, "unknown_category", `Unknown category: ${unknown.category}`);

  return Response.json({ month: toMonthPlanDto(await saveMonthPlan(userId, month.data, plan)) });
});
