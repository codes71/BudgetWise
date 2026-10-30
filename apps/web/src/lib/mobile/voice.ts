import type { VoiceOutput } from "@/ai/flows/parse-voice";
import type { VoiceEntryDto, VoiceParseDto, VoiceQuestionDto } from "./dto";
import { INCOME, MAX_ENTRIES, OTHER, addDays, categoryKey, kyat, text, validDate } from "./scan";

const MAX_HEARD = 500;

// Real calendar dates from 2000 to tomorrow in Yangon, first day not after the last; category known or null.
function validQuestion(
  q: VoiceOutput["question"],
  known: Map<string, string>,
  today: string,
): VoiceQuestionDto | undefined {
  if (!q) return undefined;
  const from = validDate(q.from, today);
  const to = validDate(q.to, today);
  if (from === undefined || to === undefined || from > to) return undefined;
  const category = q.category === null || !q.category.trim() ? null : (known.get(categoryKey(q.category)) ?? undefined);
  if (category === undefined) return undefined;
  return { metric: q.metric, category, from, to };
}

/**
 * Turns Claude's reading of the transcripts into what the phone shows: entries
 * to review or a question to answer on the phone. Anything that does not pass
 * is dropped, never filled in.
 */
export function normalizeVoice(
  raw: VoiceOutput,
  { categories, today }: { categories: string[]; today: string },
): VoiceParseDto {
  const known = new Map(categories.map((name) => [categoryKey(name), name]));
  const heard = text(raw.heard, MAX_HEARD) ?? "";

  if (raw.intent === "question") {
    const question = validQuestion(raw.question, known, today);
    return question
      ? { intent: "question", heard, entries: [], question }
      : { intent: "other", heard, entries: [], question: null };
  }

  if (raw.intent === "add") {
    const entries: VoiceEntryDto[] = [];
    for (const e of raw.entries) {
      const amount = kyat(e.amount);
      if (amount === undefined) continue;
      const date = e.date === undefined ? undefined : validDate(e.date, today);
      const category = e.type === "income" ? INCOME : (known.get(categoryKey(e.category)) ?? OTHER);
      entries.push({
        type: e.type,
        amount,
        category,
        description: text(e.description) ?? category,
        date: date ?? today,
        ...(date === undefined ? { dateGuessed: true } : {}),
      });
    }
    if (entries.length) return { intent: "add", heard, entries: entries.slice(0, MAX_ENTRIES), question: null };
  }

  return { intent: "other", heard, entries: [], question: null };
}
