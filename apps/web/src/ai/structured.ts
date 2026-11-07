import type * as z from 'zod/v4';

// Shared by the AI flows and eligible providers. generate.ts enforces the server-side policy and order.
export type Effort = 'low' | 'medium' | 'high';

export type ImageType = 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp';

// Plain text, or text and base64 images in the order the model should see them.
export type Content = string | ({ type: 'text'; text: string } | { type: 'image'; mediaType: ImageType; data: string })[];

// How long each provider gets, its own retries included, when the request sets no timeoutMs.
export const CLAUDE_TIMEOUT_MS = 25_000;
export const OPENAI_TIMEOUT_MS = 30_000;

// The phone routes (maxDuration 60 s) give their AI work this long from the moment the request arrives,
// so even Claude timing out and OpenAI taking over ends in a JSON reply before the platform cuts it off.
export const ROUTE_DEADLINE_MS = 50_000;

export type StructuredRequest<Schema extends z.ZodType> = {
  schema: Schema;
  content: Content;
  effort: Effort;
  // How long each permitted provider gets, retries included: Claude CLAUDE_TIMEOUT_MS or OpenAI OPENAI_TIMEOUT_MS
  // when left out.
  timeoutMs?: number;
  // When the whole request must be done (epoch ms), both providers included. generate.ts fits each provider's
  // time inside it and skips OpenAI when too little is left.
  deadlineAt?: number;
  // Aborts the provider calls, for example when the phone gives up on the request.
  signal?: AbortSignal;
  // How many times the provider's SDK retries a failed call; the SDK's default when left out.
  maxRetries?: number;
  // Names the flow in the logs, for example "scan" or "voice".
  flow?: string;
};

// The model declined to answer. Final: the request does not move to the other provider.
export class RefusalError extends Error {
  override name = 'RefusalError';
}

// The request's deadlineAt left no time for another try. The phone routes answer it with a 504.
export class DeadlineError extends Error {
  override name = 'DeadlineError';
}

// What a provider call is aborted by: its own timeout and, when given, the caller's signal.
export function requestSignal(timeoutMs: number, signal?: AbortSignal): AbortSignal {
  const timeout = AbortSignal.timeout(timeoutMs);
  return signal ? AbortSignal.any([signal, timeout]) : timeout;
}
