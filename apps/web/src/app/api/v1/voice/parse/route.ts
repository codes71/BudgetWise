import { parseVoice, type VoiceOutput } from "@/ai/flows/parse-voice";
import { logger } from "@/lib/logger";
import { requireUser } from "@/lib/mobile/bearer";
import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";
import { getCategoriesForUser } from "@/lib/mobile/reference-data";
import { expenseCategories, yangonDate } from "@/lib/mobile/scan";
import { voiceParseRequestSchema } from "@/lib/mobile/schemas";
import { normalizeVoice } from "@/lib/mobile/voice";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

// Turns what the phone's speech models heard into entries to review or a spending
// question. Nothing is saved or answered here: the phone saves through POST /transactions
// and answers questions from its own data.
export const POST = handle(async (req) => {
  const { userId } = await requireUser(req);
  const { transcripts } = voiceParseRequestSchema.parse(await readJson(req));

  // Counted only for valid requests, since each one is a paid AI call.
  await rateLimit(`voice:user:${userId}`, { limit: 60, windowSec: 60 * 60 });

  const categories = expenseCategories(await getCategoriesForUser(userId));
  const today = yangonDate(new Date());
  let raw: VoiceOutput;
  try {
    raw = await parseVoice({ transcripts, categories, today });
  } catch (err) {
    logger.error("Voice parse failed", err instanceof Error ? err : new Error(String(err)));
    throw new ApiError(502, "parse_failed", "Could not understand that, please try again");
  }
  return Response.json(normalizeVoice(raw, { categories, today }));
});
