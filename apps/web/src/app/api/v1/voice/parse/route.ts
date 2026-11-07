import { parseVoice, type VoiceOutput } from "@/ai/flows/parse-voice";
import { DeadlineError, RefusalError, ROUTE_DEADLINE_MS } from "@/ai/structured";
import { logger } from "@/lib/logger";
import { requireUser } from "@/lib/mobile/bearer";
import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";
import { getCategoriesForUser } from "@/lib/mobile/reference-data";
import { expenseCategories, yangonDate } from "@/lib/mobile/scan";
import { voiceParseRequestSchema } from "@/lib/mobile/schemas";
import { normalizeVoice } from "@/lib/mobile/voice";

export const dynamic = "force-dynamic";
// The AI work has ROUTE_DEADLINE_MS from arrival, Claude and the OpenAI fallback together.
export const maxDuration = 60;

// Turns what the phone's speech models heard into entries to review or a spending
// question. Nothing is saved or answered here: the phone saves through POST /transactions
// and answers questions from its own data.
export const POST = handle(async (req) => {
  const started = Date.now();
  const deadlineAt = started + ROUTE_DEADLINE_MS;
  const { userId } = await requireUser(req);
  const { transcripts } = voiceParseRequestSchema.parse(await readJson(req));

  // Counted only for valid requests, since each one is a paid AI call.
  await rateLimit(`voice:user:${userId}`, { limit: 60, windowSec: 60 * 60 });

  const categories = expenseCategories(await getCategoriesForUser(userId));
  const today = yangonDate(new Date());
  let raw: VoiceOutput;
  try {
    raw = await parseVoice({ transcripts, categories, today, deadlineAt, signal: req.signal });
  } catch (err) {
    const ms = Date.now() - started;
    if (req.signal.aborted) {
      // The phone gave up: nobody reads this reply, and it is not a provider failure.
      logger.info("Voice parse cancelled", { ms });
      throw new ApiError(502, "parse_failed", "Could not understand that, please try again");
    }
    if (err instanceof RefusalError) {
      // Final: the same words are declined again, so the phone is not told to resend them.
      logger.warn("Voice parse declined by the model", { ms });
      throw new ApiError(422, "parse_declined", "That could not be understood");
    }
    if (err instanceof DeadlineError) {
      logger.error("Voice parse timed out", err, { ms });
      throw new ApiError(504, "parse_timeout", "Understanding that took too long, please try again");
    }
    logger.error("Voice parse failed", err instanceof Error ? err : new Error(String(err)), { ms });
    throw new ApiError(502, "parse_failed", "Could not understand that, please try again");
  }
  return Response.json(normalizeVoice(raw, { categories, today }));
});
