import * as z from "zod/v4";
import { deleteAccount } from "@/lib/account-deletion";
import { requireIdentity } from "@/lib/mobile/bearer";
import { handle, readJson } from "@/lib/mobile/errors";
import { verifySessionToken } from "@/lib/session-claims";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

const deletionBody = z.object({ password: z.string().min(1).max(1024) }).strict();

function sessionCookie(request: Request): string | undefined {
  const raw = request.headers.get("cookie")?.split(";").map((part) => part.trim())
    .find((part) => part.startsWith("session="))?.slice("session=".length);
  if (!raw) return undefined;
  try { return decodeURIComponent(raw); } catch { return undefined; }
}

/** Bearer identity chooses the target. The body cannot name an account or bypass current-password reauth. */
export const DELETE = handle(async (request) => {
  const identity = await requireIdentity(request);
  const { password } = deletionBody.parse(await readJson(request));
  await deleteAccount(identity.userId, password);
  const headers = new Headers({ "Cache-Control": "no-store" });
  const web = await verifySessionToken(sessionCookie(request));
  if (web?.userId === identity.userId) {
    headers.set("Set-Cookie", `session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax${process.env.NODE_ENV === "production" ? "; Secure" : ""}`);
  }
  return new Response(null, { status: 204, headers });
});
