import { beforeEach, describe, expect, it, vi } from "vitest";
import * as z from "zod/v4";

vi.mock("@/ai/claude", () => ({ claudeStructured: vi.fn() }));
vi.mock("@/ai/openai", () => ({ openaiStructured: vi.fn(), openaiConfigured: vi.fn() }));
import { claudeStructured } from "@/ai/claude";
import { generateStructured } from "@/ai/generate";
import { openaiConfigured, openaiStructured } from "@/ai/openai";
import { DeadlineError, RefusalError } from "@/ai/structured";

const claude = vi.mocked(claudeStructured);
const openai = vi.mocked(openaiStructured);
const configured = vi.mocked(openaiConfigured);
const request = { schema: z.object({ category: z.string() }), content: "x", effort: "low" as const };

beforeEach(() => {
  vi.useRealTimers();
  claude.mockReset();
  openai.mockReset();
  configured.mockReset().mockReturnValue(true);
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

// The timeout each provider was called with.
const timeoutOf = (fn: typeof claude | typeof openai) => (fn.mock.calls[0][0] as { timeoutMs?: number }).timeoutMs;

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

  it("logs how long Claude took and whether it timed out before trying OpenAI", async () => {
    claude.mockRejectedValue(new Error("Overloaded"));
    openai.mockResolvedValue({ category: "Transport" });
    await generateStructured({ ...request, flow: "scan" });
    const line = JSON.parse(String(vi.mocked(console.warn).mock.calls[0][0]));
    expect(line.context).toMatchObject({ flow: "scan", ms: expect.any(Number), timedOut: false });
  });

  it("names both failures when OpenAI fails too", async () => {
    claude.mockRejectedValue(new Error("timed out"));
    openai.mockRejectedValue(Object.assign(new Error("rate limited"), { status: 429 }));
    await expect(generateStructured(request)).rejects.toThrow(
      "Claude failed (Error: timed out); OpenAI failed (429 Error: rate limited)",
    );
  });
});

describe("generateStructured with a deadline", () => {
  const within = (ms: number) => ({ ...request, deadlineAt: Date.now() + ms });

  it("leaves a request with only timeoutMs exactly as it was, for each provider", async () => {
    const slow = { ...request, timeoutMs: 90_000 };
    claude.mockRejectedValue(new Error("timed out"));
    openai.mockResolvedValue({ category: "Food" });
    await generateStructured(slow);
    expect(claude).toHaveBeenCalledWith(slow);
    expect(openai).toHaveBeenCalledWith(slow);
  });

  it("gives Claude at most 25 s, cut to what the deadline leaves, and no retries when OpenAI can take over", async () => {
    claude.mockResolvedValue({ category: "Food" });
    await generateStructured(within(50_000));
    expect(timeoutOf(claude)).toBe(25_000);
    expect(claude.mock.calls[0][0]).toMatchObject({ maxRetries: 0 });

    claude.mockClear();
    await generateStructured(within(10_000));
    expect(timeoutOf(claude)).toBeLessThanOrEqual(8_000);
    expect(timeoutOf(claude)).toBeGreaterThan(7_000);

    claude.mockClear();
    configured.mockReturnValue(false);
    await generateStructured(within(50_000));
    expect(claude.mock.calls[0][0]).not.toHaveProperty("maxRetries");
  });

  it("caps OpenAI's time by the deadline", async () => {
    claude.mockRejectedValue(new Error("Overloaded"));
    openai.mockResolvedValue({ category: "Transport" });
    await expect(generateStructured(within(20_000))).resolves.toEqual({ category: "Transport" });
    expect(timeoutOf(openai)).toBeLessThanOrEqual(18_000);
    expect(timeoutOf(openai)).toBeGreaterThan(17_000);

    openai.mockClear();
    await generateStructured(within(50_000));
    expect(timeoutOf(openai)).toBe(30_000);
  });

  it("skips OpenAI with a DeadlineError when less than 8 s is left after Claude fails", async () => {
    const err = new Error("timed out");
    claude.mockRejectedValue(err);
    const out = generateStructured(within(7_000));
    await expect(out).rejects.toBeInstanceOf(DeadlineError);
    await expect(out).rejects.toMatchObject({ cause: err });
    expect(openai).not.toHaveBeenCalled();
  });

  it("calls no provider when the deadline has already passed", async () => {
    await expect(generateStructured(within(1_000))).rejects.toBeInstanceOf(DeadlineError);
    expect(claude).not.toHaveBeenCalled();
  });

  it("is a DeadlineError when OpenAI runs out of the time the deadline left it", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    claude.mockRejectedValue(new Error("Overloaded"));
    openai.mockImplementation(async (r) => {
      vi.setSystemTime(Date.now() + (r.timeoutMs ?? 0));
      throw new Error("Request was aborted.");
    });
    await expect(generateStructured(within(20_000))).rejects.toBeInstanceOf(DeadlineError);

    // Failing early, with time to spare, is an ordinary failure.
    openai.mockRejectedValue(new Error("rate limited"));
    const out = generateStructured(within(20_000));
    await expect(out).rejects.toThrow("OpenAI failed");
    await expect(out).rejects.not.toBeInstanceOf(DeadlineError);
  });

  it("does not move a request the caller gave up on to OpenAI", async () => {
    const gone = new AbortController();
    gone.abort();
    const err = new Error("Request was aborted.");
    claude.mockRejectedValue(err);
    await expect(generateStructured({ ...within(50_000), signal: gone.signal })).rejects.toBe(err);
    expect(openai).not.toHaveBeenCalled();
    expect(console.warn).not.toHaveBeenCalled();
  });
});
