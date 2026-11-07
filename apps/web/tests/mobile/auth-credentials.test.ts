import { beforeEach, describe, expect, it } from "vitest";
import bcrypt from "bcryptjs";
import { clearDb } from "../helpers/db";
import { authenticatePassword, authClientIp, MAX_AUTH_BODY_BYTES, readAuthJson } from "@/lib/auth-credentials";
import { createUser } from "@/lib/users";

beforeEach(clearDb);

describe("bcrypt legacy password compatibility", () => {
  it("preserves the exact first 72 UTF-8 bytes when bounding comparison input", async () => {
    const passwords = [
      "x".repeat(300),
      "x".repeat(71) + "😀" + "tail".repeat(100),
      "x".repeat(70) + "😀" + "tail".repeat(100),
      "x".repeat(72) + "😀" + "tail".repeat(100),
      "မ".repeat(40),
      "😀".repeat(40),
      "x".repeat(71) + "é" + "tail".repeat(100),
    ];
    for (const [i, password] of passwords.entries()) {
      expect(Buffer.from(password.slice(0, 73)).subarray(0, 72)).toEqual(Buffer.from(password).subarray(0, 72));
      const email = `legacy${i}@example.com`;
      const user = await createUser({ email, passwordHash: await bcrypt.hash(password, 10) });
      expect(await authenticatePassword(email, password)).toMatchObject({ id: user!.id });
      expect(await authenticatePassword(email, "different" + password)).toBeNull();
    }
  });
});

describe("bounded auth requests", () => {
  it("uses the first forwarded IP and a stable fallback", () => {
    expect(authClientIp(new Headers({ "x-forwarded-for": " 198.51.100.7 , 10.1.1.1" }))).toBe("198.51.100.7");
    expect(authClientIp(new Headers())).toBe("unknown");
  });

  it("accepts valid JSON and maps empty or malformed bodies to the existing public error", async () => {
    const request = (body?: string) => new Request("http://localhost/auth", { method: "POST", body });
    await expect(readAuthJson(request('{"email":"test@localhost"}'))).resolves.toEqual({ email: "test@localhost" });
    for (const body of [undefined, "", "{nope"]) {
      await expect(readAuthJson(request(body))).rejects.toMatchObject({ status: 400, code: "invalid_json" });
    }
  });

  it("rejects declared and streamed oversize bodies by bytes", async () => {
    const declared = new Request("http://localhost/auth", { method: "POST", headers: { "content-length": String(MAX_AUTH_BODY_BYTES + 1) }, body: "{}" });
    await expect(readAuthJson(declared)).rejects.toMatchObject({ status: 413, code: "payload_too_large" });
    let cancelled = false;
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('"' + "မ".repeat(6000)));
      },
      cancel() { cancelled = true; },
    });
    const streamed = new Request("http://localhost/auth", { method: "POST", body: stream, duplex: "half" } as RequestInit);
    await expect(readAuthJson(streamed)).rejects.toMatchObject({ status: 413, code: "payload_too_large" });
    expect(cancelled).toBe(true);
  });
});
