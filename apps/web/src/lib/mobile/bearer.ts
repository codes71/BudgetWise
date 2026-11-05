import { ApiError } from "./errors";
import { TokenConfigError, verifyMobileToken } from "./token";
import { requireActiveAccount } from "@/lib/account-access";

/** Cryptographic identity only, used by the retryable deletion endpoint. */
export async function requireIdentity(req: Request): Promise<{ userId: string; email: string }> {
  const header = req.headers.get("authorization");
  const match = header ? /^Bearer +(\S+)$/i.exec(header.trim()) : null;
  if (!match) throw new ApiError(401, "unauthorized", "Authentication required");
  try {
    return await verifyMobileToken(match[1]);
  } catch (err) {
    if (err instanceof TokenConfigError) throw err;
    throw new ApiError(401, "unauthorized", "Authentication required");
  }
}

export async function requireUser(req: Request): Promise<{ userId: string; email: string }> {
  const identity = await requireIdentity(req);
  await requireActiveAccount(identity.userId);
  return identity;
}
