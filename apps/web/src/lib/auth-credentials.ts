import bcrypt from "bcryptjs";
import { ApiError } from "@/lib/mobile/errors";
import { rateLimit } from "@/lib/mobile/rate-limit";
import { sha256Hex } from "@/lib/mongodb";
import { findUserByEmail } from "@/lib/users";

// Same cost as signup. Missing, passwordless and deleting accounts still perform one compare.
const DUMMY_HASH = "$2a$10$vLjJiV2q4BmEOHfVG2glG.QeAjpjs4IEt0FJ2gGTGOgRJ12WKjdU6";
export const MAX_AUTH_BODY_BYTES = 16 * 1024;

export function authClientIp(headers: Pick<Headers, "get">): string {
  return headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

/** Web actions and mobile routes share keys so changing clients cannot bypass either counter. */
export async function limitSignIn(email: string, ip: string): Promise<void> {
  await rateLimit(`auth:ip:${ip}`, { limit: 30, windowSec: 15 * 60 });
  await rateLimit(`auth:email:${sha256Hex(email.trim().toLowerCase())}`, { limit: 10, windowSec: 15 * 60 });
}

export async function limitSignUp(ip: string): Promise<void> {
  await rateLimit(`register:ip:${ip}`, { limit: 20, windowSec: 60 * 60 });
}

export async function authenticatePassword(email: string, password: string, options: { allowDeletionRetry?: boolean } = {}) {
  const user = await findUserByEmail(email);
  // Mobile must recover a token after an interrupted deletion. Ordinary account access still requires
  // an active root; only the deletion endpoint accepts that token for a locked account.
  const activeHash = user?.password && (!user.deletionStartedAt || options.allowDeletionRetry) ? user.password : undefined;
  // bcrypt uses the first 72 UTF-8 bytes. Keep at least 73 UTF-16 units and a complete trailing
  // surrogate pair: bcryptjs rejects a high surrogate split by slicing, even past its 72-byte limit.
  // This preserves the hashed byte prefix while avoiding encoding an unbounded legacy password.
  // Do not trim, normalize, or impose signup's minimum/maximum on existing passwords.
  const lastUnit = password.charCodeAt(72);
  const prefix = password.slice(0, lastUnit >= 0xd800 && lastUnit <= 0xdbff ? 74 : 73);
  const matches = await bcrypt.compare(prefix, activeHash || DUMMY_HASH);
  return user && activeHash && matches ? user : null;
}

/** Bound unauthenticated JSON before parsing, including streamed bodies without Content-Length. */
export async function readAuthJson(req: Request): Promise<unknown> {
  const tooLarge = () => new ApiError(413, "payload_too_large", "Request body is too large");
  const length = req.headers.get("content-length");
  if (length && Number(length) > MAX_AUTH_BODY_BYTES) throw tooLarge();
  const reader = req.body?.getReader();
  if (!reader) throw new ApiError(400, "invalid_json", "Request body must be valid JSON");
  let size = 0;
  const chunks: Uint8Array[] = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_AUTH_BODY_BYTES) {
        await reader.cancel();
        throw tooLarge();
      }
      chunks.push(value);
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(400, "invalid_json", "Request body must be valid JSON");
  } finally {
    reader.releaseLock();
  }
}
