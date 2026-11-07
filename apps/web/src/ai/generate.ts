import type * as z from 'zod/v4';
import { claudeStructured } from '@/ai/claude';
import { openaiConfigured, openaiStructured } from '@/ai/openai';
import { AiUnavailableError, requestProviders } from '@/ai/provider-policy';
import { CLAUDE_TIMEOUT_MS, DeadlineError, OPENAI_TIMEOUT_MS, RefusalError, type StructuredRequest } from '@/ai/structured';
import { logger } from '@/lib/logger';
import { errorDiagnostics, errorSummary } from '@/lib/error-diagnostics';

export type { Content, Effort } from '@/ai/structured';
const RESERVE_MS = 2_000;
const FALLBACK_MIN_MS = 8_000;

// Every cloud flow resolves the same server-side eligibility policy, including directly called web
// helpers. A key is not permission. Only the explicitly permitted provider order can be attempted;
// unknown or ineligible providers are never fallback targets. Refusals remain final.
export async function generateStructured<Schema extends z.ZodType>(
  request: StructuredRequest<Schema>,
): Promise<z.infer<Schema>> {
  const providers = (await requestProviders()).filter((provider) => provider !== 'openai' || openaiConfigured());
  if (!providers.length) throw new AiUnavailableError();
  const { deadlineAt } = request;
  const remaining = () => deadlineAt === undefined ? Infinity : deadlineAt - Date.now();
  const failures: string[] = [];
  for (const [index, provider] of providers.entries()) {
    if (request.signal?.aborted) throw new Error('AI request cancelled.');
    const timeout = provider === 'anthropic' ? CLAUDE_TIMEOUT_MS : OPENAI_TIMEOUT_MS;
    const timeoutMs = Math.min(request.timeoutMs ?? timeout, remaining() - RESERVE_MS);
    if (timeoutMs <= 0 || (index > 0 && remaining() < FALLBACK_MIN_MS)) {
      throw new DeadlineError('No time left for the AI before the deadline.');
    }
    const started = Date.now();
    const invoke = provider === 'anthropic' ? claudeStructured : openaiStructured;
    try {
      return await invoke(deadlineAt === undefined ? request : {
        ...request, timeoutMs, ...(index + 1 < providers.length ? { maxRetries: 0 } : {}),
      });
    } catch (error) {
      // Raw SDK messages, stacks and nested causes may contain receipt/transcript content.
      if (error instanceof RefusalError) throw new RefusalError('AI provider declined this request.');
      if (request.signal?.aborted) throw new Error('AI request cancelled.');
      failures.push(`${provider === 'anthropic' ? 'Claude' : 'OpenAI'} failed (${errorSummary(error)})`);
      if (remaining() <= RESERVE_MS) throw new DeadlineError('AI request ran out of time.');
      if (index + 1 < providers.length) {
        if (remaining() < FALLBACK_MIN_MS) throw new DeadlineError('No time left for the permitted fallback.');
        const ms = Date.now() - started;
        logger.warn('AI provider failed, trying permitted fallback', {
          flow: request.flow, ms, timedOut: ms >= timeoutMs, status: errorDiagnostics(error).status,
        });
      }
    }
  }
  throw new Error(failures.join('; '));
}
