import * as z from "zod/v4";
import { scanImage, type ScanOutput } from "@/ai/flows/scan-image";
import { DeadlineError, RefusalError, ROUTE_DEADLINE_MS } from "@/ai/structured";
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
// Reading one image takes a few seconds. The AI work has ROUTE_DEADLINE_MS from arrival, Claude and the
// OpenAI fallback together, so a slow read still ends in a JSON reply inside these 60 s.
export const maxDuration = 60;

async function readImage(req: Request) {
  if (Number(req.headers.get("content-length")) > MAX_BODY_BYTES) throw imageTooLarge();
  const { image, mediaType } = scanRequestSchema.parse(await readJson(req));
  return { data: decodeImage(image, mediaType), mediaType };
}

// Returns the entries found in a receipt, KPay slip or KPay history screenshot.
// Nothing is saved: the phone shows them for review and saves through POST /transactions.
// Logs carry timings, codes and counts only, never the image or what was read from it.
export const POST = handle(async (req) => {
  const started = Date.now();
  const deadlineAt = started + ROUTE_DEADLINE_MS;
  const { userId } = await requireUser(req);
  const { data, mediaType } = await readImage(req).catch((err: unknown) => {
    // The phone shows these as its couldn't-read card too, so they are logged to tell them apart.
    if (err instanceof ApiError) logger.warn("Scan rejected", { status: err.status, code: err.code });
    if (err instanceof z.ZodError) logger.warn("Scan rejected", { status: 422, code: "validation_failed" });
    throw err;
  });

  // Counted only for valid images, since each one is a paid AI call.
  await rateLimit(`scan:user:${userId}`, { limit: 20, windowSec: 60 * 60 });

  const categories = expenseCategories(await getCategoriesForUser(userId));
  const today = yangonDate(new Date());
  let raw: ScanOutput;
  try {
    raw = await scanImage({ data, mediaType, categories, today, deadlineAt, signal: req.signal });
  } catch (err) {
    const ms = Date.now() - started;
    if (req.signal.aborted) {
      // The phone gave up (Retake or Back): nobody reads this reply, and it is not a provider failure.
      logger.info("Scan cancelled", { ms });
      throw new ApiError(502, "scan_failed", "Could not read the image, please try again");
    }
    if (err instanceof RefusalError) {
      // Final: the same image is declined again, so the phone is not told to resend it.
      logger.warn("Scan declined by the model", { ms, error: err.message });
      throw new ApiError(422, "scan_declined", "This image could not be read");
    }
    if (err instanceof DeadlineError) {
      logger.error("Scan timed out", err, { ms });
      throw new ApiError(504, "scan_timeout", "Reading the image took too long, please try again");
    }
    logger.error("Scan failed", err instanceof Error ? err : new Error(String(err)), { ms });
    throw new ApiError(502, "scan_failed", "Could not read the image, please try again");
  }
  const result = normalizeScan(raw, { categories, today });
  logger.info("Scan done", {
    ms: Date.now() - started,
    mediaType,
    imageBytes: Math.floor((data.length * 3) / 4),
    kind: raw.kind,
    keptKind: result.kind,
    reason: result.reason,
    rawEntries: raw.entries.length,
    keptEntries: result.entries.length,
  });
  return Response.json(result);
});
