import type * as z from 'zod/v4';
import { claudeStructured } from '@/ai/claude';
import { openaiConfigured, openaiStructured } from '@/ai/openai';
import {
  CLAUDE_TIMEOUT_MS,
  DeadlineError,
  OPENAI_TIMEOUT_MS,
  RefusalError,
  type StructuredRequest,
} from '@/ai/structured';
import { logger } from '@/lib/logger';
import { errorDiagnostics, errorSummary } from '@/lib/error-diagnostics';

export type { Content, Effort } from '@/ai/structured';

// Kept back from a request's deadline so the reply still reaches the phone.
const RESERVE_MS = 2_000;
// The least time left worth handing to OpenAI after Claude fails (it answers in about 5 s).
const OPENAI_MIN_MS = 8_000;

// Every AI flow comes through here. Claude answers first; when it is down, overloaded, slow,
// not configured or answers in the wrong shape, the same request goes to OpenAI.
// A refusal is final and never moves to the other provider.
// With a deadlineAt, each provider's time is cut to fit it, Claude hands an overload straight to OpenAI
// instead of retrying, and OpenAI runs only when enough time is left; otherwise a DeadlineError.
export async function generateStructured<Schema extends z.ZodType>(
  request: StructuredRequest<Schema>,
): Promise<z.infer<Schema>> {
  const { deadlineAt } = request;
  const remaining = () => (deadlineAt === undefined ? Infinity : deadlineAt - Date.now());

  const claudeMs = Math.min(request.timeoutMs ?? CLAUDE_TIMEOUT_MS, remaining() - RESERVE_MS);
  if (claudeMs <= 0) throw new DeadlineError('No time left for the AI before the deadline.');
  const started = Date.now();
  try {
    return await claudeStructured(
      deadlineAt === undefined
        ? request
        : { ...request, timeoutMs: claudeMs, ...(openaiConfigured() ? { maxRetries: 0 } : {}) },
    );
  } catch (claudeError) {
    // A request the caller gave up on is not a Claude failure: nobody is waiting for OpenAI's answer.
    // Framework reporting for directly callable web actions can serialize SDK errors too.
    // Never propagate their content-bearing messages or nested causes across this boundary.
    if (claudeError instanceof RefusalError) throw new RefusalError('AI provider declined this request.');
    if (request.signal?.aborted) throw new Error('AI request cancelled.');
    if (!openaiConfigured()) throw new Error(`Claude failed (${errorSummary(claudeError)})`);
    const ms = Date.now() - started;
    if (remaining() < OPENAI_MIN_MS) {
      throw new DeadlineError(`Claude failed (${errorSummary(claudeError)}) with no time left for OpenAI.`);
    }
    // timedOut: Claude used its whole time, so it was slow rather than down.
    logger.warn('Claude failed, trying OpenAI', {
      flow: request.flow,
      ms,
      timedOut: ms >= claudeMs,
      status: errorDiagnostics(claudeError).status,
    });
    try {
      return await openaiStructured(
        deadlineAt === undefined
          ? request
          : { ...request, timeoutMs: Math.min(request.timeoutMs ?? OPENAI_TIMEOUT_MS, remaining() - RESERVE_MS) },
      );
    } catch (openaiError) {
      if (openaiError instanceof RefusalError) throw new RefusalError('AI provider declined this request.');
      if (request.signal?.aborted) throw new Error('AI request cancelled.');
      // OpenAI's time was cut to the deadline and used up: the request ran out of time.
      if (remaining() <= RESERVE_MS) {
        throw new DeadlineError(`Claude failed (${errorSummary(claudeError)}); OpenAI ran out of time.`);
      }
      throw new Error(`Claude failed (${errorSummary(claudeError)}); OpenAI failed (${errorSummary(openaiError)})`);
    }
  }
}
