import { createHash } from "node:crypto";
import bcrypt from "bcryptjs";
import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";
import { loginSchema } from "@/lib/mobile/schemas";
import { signMobileToken } from "@/lib/mobile/token";
import { findUserByEmail } from "@/lib/users";

export const dynamic = "force-dynamic";

const WINDOW_SEC = 15 * 60;
// Precomputed bcrypt hash (cost 10, same as web signup) of a random string.
// Compared against when the user is missing so response time does not reveal it.
const DUMMY_HASH = "$2a$10$vLjJiV2q4BmEOHfVG2glG.QeAjpjs4IEt0FJ2gGTGOgRJ12WKjdU6";

function clientIp(req: Request): string {
  return req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

const invalid = () => new ApiError(401, "invalid_credentials", "Invalid email or password");

export const POST = handle(async (req) => {
  const body = await readJson(req);
  // loginSchema's email check rejects surrounding whitespace, so trim first.
  const { email, password } = loginSchema.parse(
    body && typeof body === "object" && typeof (body as any).email === "string"
      ? { ...body, email: (body as any).email.trim() }
      : body,
  );
  const emailHash = createHash("sha256").update(email.toLowerCase()).digest("hex");

  await rateLimit(`auth:ip:${clientIp(req)}`, { limit: 30, windowSec: WINDOW_SEC });
  await rateLimit(`auth:email:${emailHash}`, { limit: 10, windowSec: WINDOW_SEC });

  // Emails are unique case-insensitively (userEmails), so one lookup on the
  // lowercased email finds the account; it runs even when there is none.
  const user = await findUserByEmail(email);

  const hash = user?.password || DUMMY_HASH;
  const ok = await bcrypt.compare(password, hash);
  if (!user || !user.password || !ok) throw invalid();

  const id = user.id;
  const { token, expiresAt } = await signMobileToken({ id, email: user.email });
  return Response.json({
    token,
    expiresAt,
    user: { id, email: user.email, fullName: user.fullName ?? "" },
  });
});
