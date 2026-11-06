import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/auth", () => ({ verifySession: vi.fn(), decrypt: vi.fn() }));

describe("middleware matcher", () => {
  it("matches app and API writers while excluding static assets", async () => {
    const { config } = await import("@/middleware");
    const re = new RegExp("^" + config.matcher[0] + "$");
    expect(re.test("/api/v1/transactions")).toBe(true);
    expect(re.test("/dashboard")).toBe(true);
    expect(re.test("/privacy")).toBe(true);
    expect(re.test("/delete-account")).toBe(true);
    expect(re.test("/updates/friends.json")).toBe(false);
    expect(re.test("/_next/static/chunk.js")).toBe(false);
    expect(re.test("/_next/image")).toBe(false);
    expect(re.test("/favicon.ico")).toBe(false);
  });
});
