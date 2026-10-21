import { handle } from "@/lib/mobile/errors";
import { requireUser } from "@/lib/mobile/bearer";
import { getCategoriesForUser } from "@/lib/mobile/reference-data";

export const dynamic = "force-dynamic";

export const GET = handle(async (req) => {
  const { userId } = await requireUser(req);
  return Response.json({ categories: await getCategoriesForUser(userId) });
});
