import OpenAI from 'openai';
import * as z from 'zod/v4';
import { RefusalError, type Content, type StructuredRequest } from '@/ai/structured';

// OpenAI, used only when Claude fails (generate.ts). Reads OPENAI_API_KEY from the environment (.env.local).
// GPT-6.1 Sol matched Claude on the Burmese voice check (14 of 14 amounts, 4 of 4 questions, about 5 s)
// and on a test receipt; GPT-5.5 matched too but took about 12 s. OPENAI_MODEL in the environment swaps it.
export const OPENAI_MODEL = process.env.OPENAI_MODEL || 'gpt-6.1-sol';

// Claude's 25 s plus this stay inside the phone routes' 60 s.
const DEADLINE_MS = 30_000;

let client: OpenAI | undefined;

function getClient() {
  client ??= new OpenAI({ maxRetries: 1 });
  return client;
}

export function openaiConfigured(): boolean {
  return Boolean(process.env.OPENAI_API_KEY);
}

type JsonSchema = { [key: string]: unknown };

export function toOpenAIContent(content: Content): OpenAI.Responses.ResponseInputMessageContentList {
  if (typeof content === 'string') return [{ type: 'input_text', text: content }];
  return content.map((part) =>
    part.type === 'text'
      ? { type: 'input_text', text: part.text }
      : // Full resolution: receipts carry small print.
        { type: 'input_image', image_url: `data:${part.mediaType};base64,${part.data}`, detail: 'original' },
  );
}

// OpenAI's strict mode needs every property in `required`, so a field the schema lets the model
// leave out becomes "may be null" here, and withoutNulls() turns those nulls back into missing fields.
export function strictSchema(node: JsonSchema): JsonSchema {
  const out: JsonSchema = { ...node };
  delete out.$schema;
  if (node.properties) {
    const required = new Set((node.required as string[] | undefined) ?? []);
    const properties = node.properties as Record<string, JsonSchema>;
    out.properties = Object.fromEntries(
      Object.entries(properties).map(([key, prop]) => [
        key,
        required.has(key) ? strictSchema(prop) : { anyOf: [strictSchema(prop), { type: 'null' }] },
      ]),
    );
    out.required = Object.keys(properties);
    out.additionalProperties = false;
  }
  if (node.items) out.items = strictSchema(node.items as JsonSchema);
  if (node.anyOf) out.anyOf = (node.anyOf as JsonSchema[]).map(strictSchema);
  return out;
}

// `node` is the schema before strictSchema(): it still says which fields may be left out.
export function withoutNulls(value: unknown, node: JsonSchema): unknown {
  // A nullable object or array is an anyOf; follow the branch that matches the value.
  const branch = (want: 'properties' | 'items') =>
    node[want] ? node : (node.anyOf as JsonSchema[] | undefined)?.find((n) => n[want]);
  if (Array.isArray(value)) {
    const items = branch('items')?.items as JsonSchema | undefined;
    return items ? value.map((v) => withoutNulls(v, items)) : value;
  }
  if (value !== null && typeof value === 'object') {
    const objectNode = branch('properties');
    if (!objectNode) return value;
    const required = new Set((objectNode.required as string[] | undefined) ?? []);
    const properties = objectNode.properties as Record<string, JsonSchema>;
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key, v]) => v !== null || required.has(key))
        .map(([key, v]) => [key, properties[key] ? withoutNulls(v, properties[key]) : v]),
    );
  }
  return value;
}

export async function openaiStructured<Schema extends z.ZodType>({
  schema,
  content,
  effort,
  timeoutMs = DEADLINE_MS,
}: StructuredRequest<Schema>): Promise<z.infer<Schema>> {
  const json = z.toJSONSchema(schema, { target: 'draft-7' }) as JsonSchema;
  const response = await getClient().responses.create(
    {
      model: OPENAI_MODEL,
      reasoning: { effort },
      max_output_tokens: 16000,
      // Receipts and what people say about their money are not kept on OpenAI's side.
      store: false,
      input: [{ role: 'user', content: toOpenAIContent(content) }],
      text: { format: { type: 'json_schema', name: 'output', strict: true, schema: strictSchema(json) } },
    },
    { signal: AbortSignal.timeout(timeoutMs) },
  );

  const parts = response.output.flatMap((item) => (item.type === 'message' ? item.content : []));
  if (parts.some((part) => part.type === 'refusal')) {
    throw new RefusalError('OpenAI declined this request.');
  }
  if (response.status !== 'completed') {
    throw new Error(`OpenAI response is incomplete (${response.incomplete_details?.reason ?? response.status}).`);
  }
  return schema.parse(withoutNulls(JSON.parse(response.output_text), json));
}
