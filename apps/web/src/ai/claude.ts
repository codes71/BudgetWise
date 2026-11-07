import Anthropic from '@anthropic-ai/sdk';
import { betaZodOutputFormat } from '@anthropic-ai/sdk/helpers/beta/zod';
import type * as z from 'zod/v4';
import {
  CLAUDE_TIMEOUT_MS,
  RefusalError,
  requestSignal,
  type Content,
  type StructuredRequest,
} from '@/ai/structured';
import { logger } from '@/lib/logger';

// Claude, the first choice for every AI flow. Reads ANTHROPIC_API_KEY from the environment (.env.local).
export const CLAUDE_MODEL = 'claude-opus-5-5';

let client: Anthropic | undefined;

// Created lazily so pages still load when no key is configured; only the AI actions fail.
function getClient() {
  client ??= new Anthropic();
  return client;
}

export function toClaudeContent(content: Content): string | Anthropic.Beta.BetaContentBlockParam[] {
  if (typeof content === 'string') return content;
  return content.map((part) =>
    part.type === 'text'
      ? { type: 'text', text: part.text }
      : { type: 'image', source: { type: 'base64', media_type: part.mediaType, data: part.data } },
  );
}

export async function claudeStructured<Schema extends z.ZodType>({
  schema,
  content,
  effort,
  timeoutMs = CLAUDE_TIMEOUT_MS,
  signal,
  maxRetries,
  flow,
}: StructuredRequest<Schema>): Promise<z.infer<Schema>> {
  const started = Date.now();
  const response = await getClient().beta.messages.parse(
    {
      model: CLAUDE_MODEL,
      max_tokens: 16000,
      // If Claude declines on a safety classifier, the API retries on a fallback model in the same call.
      betas: ['server-side-fallback-2026-07-01'],
      fallbacks: 'default',
      output_config: { effort, format: betaZodOutputFormat(schema) },
      messages: [{ role: 'user', content: toClaudeContent(content) }],
    },
    { signal: requestSignal(timeoutMs, signal), ...(maxRetries === undefined ? {} : { maxRetries }) },
  );
  // Timings, fixed stop reasons and token counts only, never content or untrusted provider strings.
  logger.info('Claude answered', {
    flow,
    ms: Date.now() - started,
    stopReason: response.stop_reason,
    inputTokens: response.usage?.input_tokens,
    outputTokens: response.usage?.output_tokens,
  });

  if (response.stop_reason === 'refusal') {
    throw new RefusalError('Claude declined this request.');
  }
  if (!response.parsed_output) {
    throw new Error(`Claude response did not match the expected format (stop_reason: ${response.stop_reason}).`);
  }
  return response.parsed_output as z.infer<Schema>;
}
