import { SignJWT, jwtVerify } from "jose";

const AUDIENCE = "budgetwise-mobile";
const TTL_SEC = 30 * 24 * 3600;
const MIN_SECRET_LEN = 32;

/** Thrown when the server is misconfigured; callers must map it to a 500, never a 401. */
export class TokenConfigError extends Error {
  constructor() {
    super(`JWT_SECRET must be set and at least ${MIN_SECRET_LEN} characters`);
    this.name = "TokenConfigError";
  }
}

// Read per call (not at module load) and never fall back to a default secret.
function getKey(): Uint8Array {
  const secret = process.env.JWT_SECRET;
  if (!secret || secret.length < MIN_SECRET_LEN) throw new TokenConfigError();
  return new TextEncoder().encode(secret);
}

export async function signMobileToken(user: {
  id: string;
  email: string;
}): Promise<{ token: string; expiresAt: string }> {
  const key = getKey();
  const iat = Math.floor(Date.now() / 1000);
  const exp = iat + TTL_SEC;
  const token = await new SignJWT({ email: user.email })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(user.id)
    .setAudience(AUDIENCE)
    .setIssuedAt(iat)
    .setExpirationTime(exp)
    .sign(key);
  return { token, expiresAt: new Date(exp * 1000).toISOString() };
}

export async function verifyMobileToken(
  token: string,
): Promise<{ userId: string; email: string }> {
  const key = getKey();
  const { payload } = await jwtVerify(token, key, {
    audience: AUDIENCE,
    algorithms: ["HS256"],
    requiredClaims: ["sub", "exp"],
  });
  if (typeof payload.sub !== "string" || !payload.sub || typeof payload.email !== "string" || !payload.email) {
    throw new Error("Invalid mobile token claims");
  }
  return { userId: payload.sub, email: payload.email };
}
