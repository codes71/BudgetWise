import type * as z from 'zod/v4';
import { claudeStructured } from '@/ai/claude';
import { openaiConfigured, openaiStructured } from '@/ai/openai';
import { RefusalError, type StructuredRequest } from '@/ai/structured';
import { logger } from '@/lib/logger';

export type { Content, Effort } from '@/ai/structured';

function describe(err: unknown): string {
  const status = (err as { status?: unknown })?.status;
  const message = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
  return `${typeof status === 'number' ? `${status} ` : ''}${message}`.slice(0, 300);
}

// Every AI flow comes through here. Claude answers first; when it is down, overloaded, slow,
// not configured or answers in the wrong shape, the same request goes to OpenAI.
// A refusal is final and never moves to the other provider.
export async function generateStructured<Schema extends z.ZodType>(
  request: StructuredRequest<Schema>,
): Promise<z.infer<Schema>> {
  try {
    return await claudeStructured(request);
  } catch (claudeError) {
    if (claudeError instanceof RefusalError || !openaiConfigured()) throw claudeError;
    logger.warn('Claude failed, trying OpenAI', { error: describe(claudeError) });
    try {
      return await openaiStructured(request);
    } catch (openaiError) {
      if (openaiError instanceof RefusalError) throw openaiError;
      throw new Error(`Claude failed (${describe(claudeError)}); OpenAI failed (${describe(openaiError)})`, {
        cause: openaiError,
      });
    }
  }
}
