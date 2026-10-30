import type * as z from 'zod/v4';

// Shared by the AI flows and both providers: Claude answers first, OpenAI only when Claude fails (generate.ts).
export type Effort = 'low' | 'medium' | 'high';

export type ImageType = 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp';

// Plain text, or text and base64 images in the order the model should see them.
export type Content = string | ({ type: 'text'; text: string } | { type: 'image'; mediaType: ImageType; data: string })[];

export type StructuredRequest<Schema extends z.ZodType> = {
  schema: Schema;
  content: Content;
  effort: Effort;
  // How long each provider gets, its own retries included. Defaults fit the phone routes' 60 s:
  // Claude 25 s, then OpenAI 30 s.
  timeoutMs?: number;
};

// The model declined to answer. Final: the request does not move to the other provider.
export class RefusalError extends Error {
  override name = 'RefusalError';
}
