import bcrypt from "bcryptjs";
import { ApiError, handle, readJson } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";
import { registerSchema } from "@/lib/mobile/schemas";
import { signMobileToken } from "@/lib/mobile/token";
import { createUser } from "@/lib/users";

export const dynamic = "force-dynamic";

const WINDOW_SEC = 60 * 60;

function clientIp(req: Request): string {
  return req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

/**
 * The phone's create account: the same account the web signup makes (bcrypt cost 10, name defaults to the email's
 * local part), then signed in like POST /auth/token. A taken email is 409 so the form can offer sign in instead.
 */
export const POST = handle(async (req) => {
  const body = await readJson(req);
  const { email, password, fullName } = registerSchema.parse(body);

  await rateLimit(`register:ip:${clientIp(req)}`, { limit: 20, windowSec: WINDOW_SEC });

  const user = await createUser({
    email,
    passwordHash: await bcrypt.hash(password, 10),
    fullName: fullName || email.split("@")[0],
  });
  if (!user) throw new ApiError(409, "email_taken", "An account with this email already exists");

  const { token, expiresAt } = await signMobileToken({ id: user.id, email: user.email });
  return Response.json(
    { token, expiresAt, user: { id: user.id, email: user.email, fullName: user.fullName ?? "" } },
    { status: 201 },
  );
});
