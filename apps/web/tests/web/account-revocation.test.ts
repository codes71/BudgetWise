import { beforeEach, describe, expect, it, vi } from "vitest";
import { SignJWT } from "jose";
import { clearDb, seedAccount, testDb } from "../helpers/db";

const { jar } = vi.hoisted(() => ({ jar: new Map<string, string>() }));
vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => ({ get: (name: string) => jar.has(name) ? { value: jar.get(name) } : undefined })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));

beforeEach(async () => { await clearDb(); jar.clear(); });

async function browserSession(userId: string) {
  const token = await new SignJWT({ userId, email: "session@example.com" })
    .setProtectedHeader({ alg: "HS256" }).setExpirationTime("1h")
    .sign(new TextEncoder().encode(process.env.JWT_SECRET!));
  jar.set("session", token);
  return token;
}

describe("account revocation", () => {
  it("keeps edge claims separate while Node authorization denies deleted and deleting accounts", async () => {
    const { verifySessionToken } = await import("@/lib/session-claims");
    const { verifySession } = await import("@/lib/auth");
    await seedAccount("revoked", "session@example.com");
    const token = await browserSession("revoked");
    expect(await verifySession()).toMatchObject({ userId: "revoked" });
    await testDb().collection("users").doc("revoked").update({ deletionStartedAt: new Date() });
    expect(await verifySessionToken(token)).toMatchObject({ userId: "revoked" });
    expect(await verifySession()).toBeNull();
    await testDb().collection("users").doc("revoked").delete();
    expect(await verifySession()).toBeNull();
  });

  it("a still-valid deleted account cookie cannot recreate cloud financial records through web actions", async () => {
    const actions = await import("@/app/db-actions");
    await seedAccount("gone", "session@example.com");
    await browserSession("gone");
    await testDb().collection("users").doc("gone").delete();
    await expect(actions.addTransaction({ amount: 10, category: "Food", description: "late", type: "expense", date: "2026-11-01" }))
      .rejects.toThrow("logged in");
    expect(await actions.setBudget({ category: "Food", limit: 100 })).toBeNull();
    expect(await testDb().collection("users").doc("gone").listCollections()).toHaveLength(0);
  });
});
