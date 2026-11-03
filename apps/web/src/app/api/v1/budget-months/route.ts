import { ApiError, handle } from "@/lib/mobile/errors";
import { requireUser } from "@/lib/mobile/bearer";
import { listMonthPlans } from "@/lib/budgets";
import { toMonthPlanDto } from "@/lib/mobile/dto";
import { MAX_PLAN_MONTHS, monthRangeSchema } from "@/lib/mobile/schemas";

export const dynamic = "force-dynamic";

export const GET = handle(async (req) => {
  const { userId } = await requireUser(req);
  const params = new URL(req.url).searchParams;
  const range = monthRangeSchema.safeParse({ from: params.get("from"), to: params.get("to") });
  if (!range.success) {
    throw new ApiError(
      400,
      "invalid_month",
      `from and to must be months (YYYY-MM), from no later than to, at most ${MAX_PLAN_MONTHS} months`,
    );
  }
  const plans = await listMonthPlans(userId, range.data.from, range.data.to);
  return Response.json({ months: plans.map(toMonthPlanDto) });
});
