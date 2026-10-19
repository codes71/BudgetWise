import Anthropic from '@anthropic-ai/sdk';
import { betaZodOutputFormat } from '@anthropic-ai/sdk/helpers/beta/zod';
import * as z from 'zod/v4';

// Shared Claude client for the AI flows. Reads ANTHROPIC_API_KEY from the environment (.env.local).
export const CLAUDE_MODEL = 'claude-opus-5-5';

let client: Anthropic | undefined;

// Created lazily so pages still load when no key is configured; only the AI actions fail.
function getClient() {
  client ??= new Anthropic();
  return client;
}

type Effort = 'low' | 'medium' | 'high';

export async function generateStructured<Schema extends z.ZodType>({
  schema,
  content,
  effort,
}: {
  schema: Schema;
  content: string | Anthropic.Beta.BetaContentBlockParam[];
  effort: Effort;
}): Promise<z.infer<Schema>> {
  const response = await getClient().beta.messages.parse({
    model: CLAUDE_MODEL,
    max_tokens: 16000,
    // If Claude declines on a safety classifier, the API retries on a fallback model in the same call.
    betas: ['server-side-fallback-2026-07-01'],
    fallbacks: 'default',
    output_config: { effort, format: betaZodOutputFormat(schema) },
    messages: [{ role: 'user', content }],
  });

  if (response.stop_reason === 'refusal') {
    throw new Error('Claude declined this request.');
  }
  if (!response.parsed_output) {
    throw new Error(`Claude response did not match the expected format (stop_reason: ${response.stop_reason}).`);
  }
  return response.parsed_output as z.infer<Schema>;
}
