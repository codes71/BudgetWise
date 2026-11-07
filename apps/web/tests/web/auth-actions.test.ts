import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { clearDb, testDb } from "../helpers/db";

const { jar } = vi.hoisted(() => ({ jar: new Map<string, string>() }));

vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => ({
    get: (name: string) => (jar.has(name) ? { name, value: jar.get(name)! } : undefined),
    set: (name: string, value: string) => void jar.set(name, value),
  })),
}));
vi.mock("next/cache", () => ({ revalidatePath: vi.fn() }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

let actions: typeof import("@/app/actions");

const form = (fields: Record<string, string>) => {
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.set(k, v);
  return fd;
};

beforeAll(async () => {
  actions = await import("@/app/actions");
});
beforeEach(async () => {
  await clearDb();
  jar.clear();
});

describe("signUp and signIn", () => {
  it("creates the user, claims the email and signs in", async () => {
    const res = await actions.signUp(form({ email: "  Dana@Example.com ", password: "pw-123456" }));
    if (!("user" in res)) throw new Error(res.error);
    expect(res.user).toMatchObject({ email: "Dana@Example.com", fullName: "Dana" });
    const user = await testDb().collection("users").findOne({ _id: res.user.userId });
    expect(user).toMatchObject({ email: "Dana@Example.com", emailLower: "dana@example.com" });
    expect(user!.password).toMatch(/^\$2[aby]\$10\$/);
    const emails = await testDb().collection("userEmails").find({  }).toArray();
    expect(emails.map((d) => ({ userId: d.userId }))).toEqual([{ userId: res.user.userId }]);
    expect(jar.get("session")).toBeTruthy();
  });

  it("rejects a case-variant duplicate email", async () => {
    await actions.signUp(form({ email: "dana@example.com", password: "pw-1" }));
    const res = await actions.signUp(form({ email: "DANA@Example.COM", password: "pw-2" }));
    expect(res).toEqual({ error: "User with this email already exists." });
    expect((await testDb().collection("users").find({  }).toArray()).length).toBe(1);
  });

  it("concurrent signups for one email create one account", async () => {
    const results = await Promise.all(
      ["race@example.com", "Race@example.com", "RACE@example.com"].map((email) =>
        actions.signUp(form({ email, password: "pw" })),
      ),
    );
    expect(results.filter((r) => "user" in r)).toHaveLength(1);
    expect((await testDb().collection("users").find({  }).toArray()).length).toBe(1);
  });

  it("signs in case-insensitively and refuses a wrong password", async () => {
    await actions.signUp(form({ email: "erin@example.com", password: "right" }));
    jar.clear();
    const ok = await actions.signIn(form({ email: "ERIN@example.com", password: "right" }));
    expect("user" in ok && ok.user.email).toBe("erin@example.com");
    expect(await actions.signIn(form({ email: "erin@example.com", password: "wrong" }))).toEqual({
      error: "Invalid email or password.",
    });
    expect(await actions.signIn(form({ email: "nobody@example.com", password: "right" }))).toEqual({
      error: "Invalid email or password.",
    });
  });
});

describe("getUserDetails and updateUser", () => {
  it("returns the signed-in user without the password and updates the profile", async () => {
    const res = await actions.signUp(form({ email: "fay@example.com", password: "pw" }));
    if (!("user" in res)) throw new Error(res.error);
    const id = res.user.userId;

    const details = await actions.getUserDetails();
    expect(details).toMatchObject({ _id: id, userId: id, email: "fay@example.com", fullName: "fay" });
    expect(details).not.toHaveProperty("password");
    expect(typeof details.createdAt).toBe("string");

    const updated = await actions.updateUser(
      form({ fullName: "Fay F", phoneNumber: "+95 9 123", profilePhotoUrl: "https://placehold.co/64" }),
    );
    expect(updated).toMatchObject({ success: true, user: { _id: id, fullName: "Fay F", phoneNumber: "+95 9 123" } });
    expect(updated.user).not.toHaveProperty("password");
    expect((await testDb().collection("users").findOne({ _id: id }))!.fullName).toBe("Fay F");
    // The refreshed session carries the new name.
    expect(await actions.getUserDetails()).toMatchObject({ fullName: "Fay F", profilePhotoUrl: "https://placehold.co/64" });
  });

  it("returns null for a session whose user is gone", async () => {
    const res = await actions.signUp(form({ email: "gone@example.com", password: "pw" }));
    if (!("user" in res)) throw new Error(res.error);
    await testDb().collection("users").deleteOne({ _id: res.user.userId });
    expect(await actions.getUserDetails()).toBeNull();
    expect(await actions.updateUser(form({ fullName: "x" }))).toEqual({ error: "User not found" });
  });
});
