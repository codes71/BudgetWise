import { beforeEach, describe, expect, it, vi } from "vitest";
import * as z from "zod/v4";

vi.mock("@/ai/claude", () => ({ claudeStructured: vi.fn() }));
vi.mock("@/ai/openai", () => ({ openaiStructured: vi.fn(), openaiConfigured: vi.fn() }));
import { claudeStructured } from "@/ai/claude";
import { generateStructured } from "@/ai/generate";
import { openaiConfigured, openaiStructured } from "@/ai/openai";
import { RefusalError } from "@/ai/structured";

const claude = vi.mocked(claudeStructured);
const openai = vi.mocked(openaiStructured);
const configured = vi.mocked(openaiConfigured);
const request = { schema: z.object({ category: z.string() }), content: "x", effort: "low" as const };

beforeEach(() => {
  claude.mockReset();
  openai.mockReset();
  configured.mockReset().mockReturnValue(true);
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

describe("generateStructured", () => {
  it("uses Claude's answer and never calls OpenAI when Claude works", async () => {
    claude.mockResolvedValue({ category: "Food" });
    await expect(generateStructured(request)).resolves.toEqual({ category: "Food" });
    expect(openai).not.toHaveBeenCalled();
  });

  it("sends the same request to OpenAI when Claude fails", async () => {
    claude.mockRejectedValue(Object.assign(new Error("Overloaded"), { status: 529 }));
    openai.mockResolvedValue({ category: "Transport" });
    await expect(generateStructured(request)).resolves.toEqual({ category: "Transport" });
    expect(openai).toHaveBeenCalledWith(request);
    expect(String(vi.mocked(console.warn).mock.calls[0][0])).toContain("529 Error: Overloaded");
  });

  it("does not move a refusal to OpenAI", async () => {
    claude.mockRejectedValue(new RefusalError("Claude declined this request."));
    await expect(generateStructured(request)).rejects.toBeInstanceOf(RefusalError);
    expect(openai).not.toHaveBeenCalled();
  });

  it("throws Claude's error when OpenAI is not configured", async () => {
    configured.mockReturnValue(false);
    const err = new Error("connection refused");
    claude.mockRejectedValue(err);
    await expect(generateStructured(request)).rejects.toBe(err);
    expect(openai).not.toHaveBeenCalled();
  });

  it("names both failures when OpenAI fails too", async () => {
    claude.mockRejectedValue(new Error("timed out"));
    openai.mockRejectedValue(Object.assign(new Error("rate limited"), { status: 429 }));
    await expect(generateStructured(request)).rejects.toThrow(
      "Claude failed (Error: timed out); OpenAI failed (429 Error: rate limited)",
    );
  });
});
