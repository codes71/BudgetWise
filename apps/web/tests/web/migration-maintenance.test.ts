import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

const { verifySessionToken } = vi.hoisted(() => ({ verifySessionToken: vi.fn() }));
vi.mock("@/lib/session-claims", () => ({ verifySessionToken }));

import { config, middleware } from "@/middleware";

const request = (path: string, method = "GET", cookie?: string) => new NextRequest(`https://pocketpeek.test${path}`, {
  method,
  ...(cookie ? { headers: { cookie: `session=${cookie}` } } : {}),
});

beforeEach(() => {
  vi.stubEnv("MIGRATION_READ_ONLY", "1");
  verifySessionToken.mockReset().mockResolvedValue(null);
});
afterEach(() => { vi.unstubAllEnvs(); });

describe("temporary write freeze", () => {
  it.each([
    ["/api/v1/auth/register", "POST"],
    ["/api/v1/auth/token", "POST"],
    ["/api/v1/transactions", "POST"],
    ["/api/v1/budgets", "PUT"],
    ["/api/v1/account", "DELETE"],
    ["/dashboard", "POST"],
    ["/signup", "POST"],
    ["/myprofile", "PATCH"],
    ["/privacy", "POST"],
    ["/delete-account", "DELETE"],
    ["/api/v1/transactions", "PROPFIND"],
  ])("blocks %s %s before authentication", async (path, method) => {
    const matches = new RegExp(`^${config.matcher[0]}$`);
    expect(matches.test(path)).toBe(true);
    const response = await middleware(request(path, method, "unread-cookie"));
    expect(response.status).toBe(503);
    expect(response.headers.get("Retry-After")).toBe("60");
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(response.headers.has("location")).toBe(false);
    expect(await response.json()).toMatchObject({ error: { code: "temporarily_unavailable" } });
    expect(verifySessionToken).not.toHaveBeenCalled();
  });

  it.each(["GET", "HEAD", "OPTIONS"])("allows API %s without web-cookie verification", async (method) => {
    const response = await middleware(request("/api/v1/transactions", method, "ignored-cookie"));
    expect(response.status).toBe(200);
    expect(response.headers.get("x-middleware-next")).toBe("1");
    expect(response.headers.has("Retry-After")).toBe(false);
    expect(verifySessionToken).not.toHaveBeenCalled();
  });

  it.each(["/privacy", "/privacy/", "/delete-account", "/delete-account/"])("keeps public policy GET %s accessible", async (path) => {
    const response = await middleware(request(path, "GET", "ignored-cookie"));
    expect(response.headers.get("x-middleware-next")).toBe("1");
    expect(verifySessionToken).not.toHaveBeenCalled();
  });

  it.each([undefined, "0", "true"])("leaves ordinary writes enabled without the exact flag (%s)", async (flag) => {
    vi.stubEnv("MIGRATION_READ_ONLY", flag);
    const api = await middleware(request("/api/v1/transactions", "POST", "ignored-cookie"));
    expect(api.headers.get("x-middleware-next")).toBe("1");
    expect(verifySessionToken).not.toHaveBeenCalled();
    const web = await middleware(request("/signup", "POST"));
    expect(web.headers.get("x-middleware-next")).toBe("1");
    expect(verifySessionToken).toHaveBeenCalledOnce();
  });

  it("preserves anonymous protected-page redirects during the freeze", async () => {
    const response = await middleware(request("/dashboard"));
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("https://pocketpeek.test/login");
  });

  it("preserves authenticated home redirects without creating a stale-cookie login loop", async () => {
    verifySessionToken.mockResolvedValue({ userId: "existing-user", email: "test@example.com" });
    const home = await middleware(request("/", "GET", "valid-claims"));
    expect(home.status).toBe(307);
    expect(home.headers.get("location")).toBe("https://pocketpeek.test/dashboard");
    const login = await middleware(request("/login", "GET", "valid-claims"));
    expect(login.headers.get("x-middleware-next")).toBe("1");
    const dashboard = await middleware(request("/dashboard", "GET", "valid-claims"));
    expect(dashboard.headers.get("x-middleware-next")).toBe("1");
  });
});
