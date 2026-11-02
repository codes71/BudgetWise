import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/auth", () => ({ verifySession: vi.fn(), decrypt: vi.fn() }));

describe("middleware matcher", () => {
  it("does not match /api/v1/transactions but matches /dashboard", async () => {
    const { config } = await import("@/middleware");
    const re = new RegExp("^" + config.matcher[0] + "$");
    expect(re.test("/api/v1/transactions")).toBe(false);
    expect(re.test("/dashboard")).toBe(true);
  });
});
