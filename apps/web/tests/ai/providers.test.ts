import { beforeEach, describe, expect, it, vi } from "vitest";
import * as z from "zod/v4";

// Both clients are replaced: responses.create and beta.messages.parse answer with what the test sets.
const create = vi.fn();
const parse = vi.fn();
vi.mock("openai", () => ({ default: class { responses = { create }; } }));
vi.mock("@anthropic-ai/sdk", () => ({ default: class { beta = { messages: { parse } }; } }));
import { claudeStructured, toClaudeContent } from "@/ai/claude";
import { ScanOutputSchema } from "@/ai/flows/scan-image";
import { VoiceOutputSchema } from "@/ai/flows/parse-voice";
import { openaiStructured, strictSchema, toOpenAIContent, withoutNulls } from "@/ai/openai";
import { RefusalError, type Content } from "@/ai/structured";

type Node = { [key: string]: any };

// Every object in a strict schema lists all its properties as required and allows no others.
function assertStrict(node: Node) {
  if (node.properties) {
    expect(node.required).toEqual(Object.keys(node.properties));
    expect(node.additionalProperties).toBe(false);
    Object.values(node.properties).forEach((p) => assertStrict(p as Node));
  }
  if (node.items) assertStrict(node.items);
  (node.anyOf ?? []).forEach(assertStrict);
}

const json = (schema: z.ZodType) => z.toJSONSchema(schema, { target: "draft-7" }) as Node;
const content: Content = [
  { type: "image", mediaType: "image/png", data: "iVBORw0K" },
  { type: "text", text: "read it" },
];

describe("strictSchema", () => {
  it("makes the scan and voice schemas strict, with left-out fields as nullable", () => {
    for (const schema of [ScanOutputSchema, VoiceOutputSchema]) assertStrict(strictSchema(json(schema)));
    const scan = strictSchema(json(ScanOutputSchema)) as Node;
    expect(scan.$schema).toBeUndefined();
    expect(scan.properties.merchant).toEqual({ anyOf: [{ type: "string" }, { type: "null" }] });
    expect(scan.properties.kind).toEqual({ type: "string", enum: ["receipt", "payment", "history", "unknown"] });
    const entry = scan.properties.entries.items;
    expect(entry.properties.date.anyOf[1]).toEqual({ type: "null" });
    expect(entry.properties.amount.type).toBe("number");
    const item = entry.properties.items.anyOf[0].items;
    expect(item.properties.quantity).toMatchObject({ anyOf: [{ type: "number" }, { type: "null" }] });
    expect(item.properties.unitPrice).toMatchObject({ anyOf: [{ type: "number" }, { type: "null" }] });
  });
});

describe("withoutNulls", () => {
  it("drops nulls for fields that may be left out and keeps required nulls", () => {
    const raw = {
      kind: "receipt", merchant: null, date: "2026-10-30", total: null,
      entries: [{ type: "expense", amount: 3000, category: "Food", description: "ကော်ဖီ", date: null,
        reference: null, items: [{ name: "ကော်ဖီ", amount: 3000, quantity: null, unitPrice: null }] }],
    };
    expect(ScanOutputSchema.parse(withoutNulls(raw, json(ScanOutputSchema)))).toEqual({
      kind: "receipt", date: "2026-10-30",
      entries: [{ type: "expense", amount: 3000, category: "Food", description: "ကော်ဖီ",
        items: [{ name: "ကော်ဖီ", amount: 3000 }] }],
    });
    const voice = { intent: "question", heard: "x", entries: [],
      question: { metric: "total", category: null, from: "2026-10-26", to: "2026-10-30" } };
    expect(VoiceOutputSchema.parse(withoutNulls(voice, json(VoiceOutputSchema)))).toEqual(voice);
    expect(withoutNulls({ ...voice, question: null }, json(VoiceOutputSchema))).toEqual({ ...voice, question: null });
  });
});

describe("content", () => {
  it("passes images to Claude as base64 blocks and to OpenAI as full-resolution data URLs", () => {
    expect(toClaudeContent(content)).toEqual([
      { type: "image", source: { type: "base64", media_type: "image/png", data: "iVBORw0K" } },
      { type: "text", text: "read it" },
    ]);
    expect(toOpenAIContent(content)).toEqual([
      { type: "input_image", image_url: "data:image/png;base64,iVBORw0K", detail: "original" },
      { type: "input_text", text: "read it" },
    ]);
    expect(toClaudeContent("hi")).toBe("hi");
    expect(toOpenAIContent("hi")).toEqual([{ type: "input_text", text: "hi" }]);
  });
});

describe("openaiStructured", () => {
  const schema = z.object({ category: z.string(), note: z.string().optional() });
  const message = (part: object) => ({ type: "message", content: [part] });
  beforeEach(() => create.mockReset());

  it("asks for a strict schema, keeps nothing on OpenAI and parses the answer", async () => {
    create.mockResolvedValue({ status: "completed", output: [message({ type: "output_text" })],
      output_text: '{"category":"Food","note":null}' });
    await expect(openaiStructured({ schema, content: "x", effort: "low" })).resolves.toEqual({ category: "Food" });
    const [body, options] = create.mock.calls[0];
    expect(body).toMatchObject({ model: "gpt-6.1-sol", reasoning: { effort: "low" }, store: false });
    expect(body.text.format).toMatchObject({ type: "json_schema", strict: true });
    assertStrict(body.text.format.schema);
    expect(options.signal).toBeInstanceOf(AbortSignal);
  });

  it("throws a RefusalError when OpenAI declines", async () => {
    create.mockResolvedValue({ status: "completed", output: [message({ type: "refusal", refusal: "no" })], output_text: "" });
    await expect(openaiStructured({ schema, content: "x", effort: "low" })).rejects.toBeInstanceOf(RefusalError);
  });

  it("throws when the answer was cut off or does not match the schema", async () => {
    create.mockResolvedValue({ status: "incomplete", incomplete_details: { reason: "max_output_tokens" }, output: [], output_text: "" });
    await expect(openaiStructured({ schema, content: "x", effort: "low" })).rejects.toThrow("max_output_tokens");
    create.mockResolvedValue({ status: "completed", output: [], output_text: '{"category":7}' });
    await expect(openaiStructured({ schema, content: "x", effort: "low" })).rejects.toThrow();
  });
});

// The info lines a provider wrote, parsed.
function infoLines() {
  return vi.mocked(console.log).mock.calls.map(([line]) => JSON.parse(String(line)));
}

describe("claudeStructured", () => {
  const schema = z.object({ category: z.string() });
  const answer = (over: object = {}) => ({
    model: "claude-opus-5-5", stop_reason: "end_turn", usage: { input_tokens: 1200, output_tokens: 80 },
    parsed_output: { category: "Food" }, ...over,
  });
  beforeEach(() => {
    parse.mockReset();
    vi.spyOn(console, "log").mockImplementation(() => {}).mockClear();
  });

  it("passes maxRetries and a signal that also ends when the caller's does", async () => {
    parse.mockResolvedValue(answer());
    const caller = new AbortController();
    await expect(
      claudeStructured({ schema, content: "x", effort: "low", maxRetries: 0, signal: caller.signal }),
    ).resolves.toEqual({ category: "Food" });
    const [, options] = parse.mock.calls[0];
    expect(options.maxRetries).toBe(0);
    expect(options.signal.aborted).toBe(false);
    caller.abort();
    expect(options.signal.aborted).toBe(true);

    parse.mockClear();
    await claudeStructured({ schema, content: "x", effort: "low" });
    expect(parse.mock.calls[0][1]).not.toHaveProperty("maxRetries");
  });

  it("logs the model, stop reason, tokens and time, but not the content", async () => {
    parse.mockResolvedValue(answer());
    await claudeStructured({ schema, content: "secret receipt text", effort: "low", flow: "scan" });
    const [line] = infoLines();
    expect(line.message).toBe("Claude answered");
    expect(line.context).toMatchObject({
      flow: "scan", ms: expect.any(Number), model: "claude-opus-5-5", stopReason: "end_turn",
      inputTokens: 1200, outputTokens: 80,
    });
    expect(JSON.stringify(line)).not.toContain("secret");
    expect(JSON.stringify(line)).not.toContain("Food");
  });

  it("throws a RefusalError when Claude declines, after logging the stop reason", async () => {
    parse.mockResolvedValue(answer({ stop_reason: "refusal", parsed_output: null }));
    await expect(claudeStructured({ schema, content: "x", effort: "low" })).rejects.toBeInstanceOf(RefusalError);
    expect(infoLines()[0].context.stopReason).toBe("refusal");
  });
});

describe("openaiStructured signals and logs", () => {
  const schema = z.object({ category: z.string() });
  beforeEach(() => {
    create.mockReset();
    vi.spyOn(console, "log").mockImplementation(() => {}).mockClear();
  });

  it("ends with the caller's signal and logs status and tokens, not the content", async () => {
    create.mockResolvedValue({ status: "completed", model: "gpt-6.1-sol", output: [], output_text: '{"category":"Food"}',
      usage: { input_tokens: 900, output_tokens: 40 } });
    const caller = new AbortController();
    await openaiStructured({ schema, content: "secret", effort: "low", signal: caller.signal, flow: "voice" });
    const [, options] = create.mock.calls[0];
    caller.abort();
    expect(options.signal.aborted).toBe(true);
    const [line] = infoLines();
    expect(line).toMatchObject({ message: "OpenAI answered", context: { flow: "voice", status: "completed",
      inputTokens: 900, outputTokens: 40 } });
    expect(JSON.stringify(line)).not.toContain("secret");
  });
});
