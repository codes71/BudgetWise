import { beforeEach, describe, expect, it, vi } from "vitest";
import * as z from "zod/v4";

// The OpenAI client is replaced: responses.create answers with whatever `reply` holds.
const create = vi.fn();
vi.mock("openai", () => ({ default: class { responses = { create }; } }));
import { toClaudeContent } from "@/ai/claude";
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
  });
});

describe("withoutNulls", () => {
  it("drops nulls for fields that may be left out and keeps required nulls", () => {
    const raw = {
      kind: "receipt", merchant: null, date: "2026-10-30", total: null,
      entries: [{ type: "expense", amount: 3000, category: "Food", description: "ကော်ဖီ", date: null,
        reference: null, items: [{ name: "ကော်ဖီ", amount: 3000 }] }],
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
