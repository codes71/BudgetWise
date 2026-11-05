import { jwtVerify } from "jose";
import type { UserPayload } from "@/lib/types";

/** Edge-safe cookie claim verification. It is routing information, never account authorization. */
export async function verifySessionToken(token: string | undefined): Promise<UserPayload | null> {
  const secret = process.env.JWT_SECRET;
  if (!token || !secret || secret.length < 32) return null;
  try {
    const { payload } = await jwtVerify(token, new TextEncoder().encode(secret), {
      algorithms: ["HS256"], requiredClaims: ["exp"],
    });
    if (typeof payload.userId !== "string" || typeof payload.email !== "string") return null;
    return {
      userId: payload.userId,
      email: payload.email,
      fullName: typeof payload.fullName === "string" ? payload.fullName : null,
      phoneNumber: typeof payload.phoneNumber === "string" ? payload.phoneNumber : null,
      profilePhotoUrl: typeof payload.profilePhotoUrl === "string" ? payload.profilePhotoUrl : null,
      ...(payload.isGuest === true ? { isGuest: true } : {}),
    } as UserPayload;
  } catch {
    return null;
  }
}
