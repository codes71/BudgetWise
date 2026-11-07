import { ApiError, handle } from "@/lib/mobile/errors";
import { loginSchema } from "@/lib/mobile/schemas";
import { signMobileToken } from "@/lib/mobile/token";
import { authClientIp, authenticatePassword, limitSignIn, readAuthJson } from "@/lib/auth-credentials";

export const dynamic = "force-dynamic";

const invalid = () => new ApiError(401, "invalid_credentials", "Invalid email or password");

export const POST = handle(async (req) => {
  const { email, password } = loginSchema.parse(await readAuthJson(req));
  await limitSignIn(email, authClientIp(req.headers));
  const user = await authenticatePassword(email, password, { allowDeletionRetry: true });
  if (!user) throw invalid();

  const id = user.id;
  const { token, expiresAt } = await signMobileToken({ id, email: user.email });
  return Response.json({
    token,
    expiresAt,
    user: { id, email: user.email, fullName: user.fullName ?? "" },
  });
});
