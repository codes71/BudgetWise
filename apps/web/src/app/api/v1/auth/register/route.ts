import bcrypt from "bcryptjs";
import { ApiError, handle } from "@/lib/mobile/errors";
import { authClientIp, limitSignUp, readAuthJson } from "@/lib/auth-credentials";
import { registerSchema } from "@/lib/mobile/schemas";
import { signMobileToken } from "@/lib/mobile/token";
import { createUser } from "@/lib/users";

export const dynamic = "force-dynamic";

/**
 * The phone's create account: the same account the web signup makes (bcrypt cost 10, name defaults to the email's
 * local part), then signed in like POST /auth/token. A taken email is 409 so the form can offer sign in instead.
 */
export const POST = handle(async (req) => {
  const { email, password, fullName } = registerSchema.parse(await readAuthJson(req));
  await limitSignUp(authClientIp(req.headers));

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
