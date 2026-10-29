import { scanImage, type ScanOutput } from "@/ai/flows/scan-image";
import { logger } from "@/lib/logger";
import { requireUser } from "@/lib/mobile/bearer";
import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";
import { getCategoriesForUser } from "@/lib/mobile/reference-data";
import {
  MAX_BODY_BYTES,
  decodeImage,
  expenseCategories,
  imageTooLarge,
  normalizeScan,
  yangonDate,
} from "@/lib/mobile/scan";
import { scanRequestSchema } from "@/lib/mobile/schemas";

export const dynamic = "force-dynamic";
// Reading one image takes Claude a few seconds; leave room for a slow call.
export const maxDuration = 60;

// Returns the entries found in a receipt, KPay slip or KPay history screenshot.
// Nothing is saved: the phone shows them for review and saves through POST /transactions.
export const POST = handle(async (req) => {
  const { userId } = await requireUser(req);
  if (Number(req.headers.get("content-length")) > MAX_BODY_BYTES) throw imageTooLarge();
  const { image, mediaType } = scanRequestSchema.parse(await readJson(req));
  const data = decodeImage(image, mediaType);

  // Counted only for valid images, since each one is a paid Claude call.
  await rateLimit(`scan:user:${userId}`, { limit: 20, windowSec: 60 * 60 });

  const categories = expenseCategories(await getCategoriesForUser(userId));
  const today = yangonDate(new Date());
  let raw: ScanOutput;
  try {
    raw = await scanImage({ data, mediaType, categories, today });
  } catch (err) {
    logger.error("Scan failed", err instanceof Error ? err : new Error(String(err)));
    throw new ApiError(502, "scan_failed", "Could not read the image, please try again");
  }
  return Response.json(normalizeScan(raw, { categories, today }));
});
