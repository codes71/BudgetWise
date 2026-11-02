import type { DocumentSnapshot } from "firebase-admin/firestore";
import { ALREADY_EXISTS, NOT_FOUND, db, errorCode, isDocId, sha256Hex, toDate, userDoc } from "@/lib/firestore";
import type { UserPayload } from "@/lib/types";

export interface UserRecord {
  id: string;
  email: string;
  emailLower: string;
  password?: string;
  fullName?: string | null;
  phoneNumber?: string | null;
  profilePhotoUrl?: string | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export function normalizeEmail(raw: string): { email: string; emailLower: string } {
  const email = raw.trim();
  return { email, emailLower: email.toLowerCase() };
}

/** userEmails/{id}: one doc per lowercased email, which makes emails unique regardless of case. */
export const emailDocId = (emailLower: string): string => sha256Hex(emailLower);

function fromSnapshot(snap: DocumentSnapshot): UserRecord {
  const d = snap.data()!;
  return {
    id: snap.id,
    email: d.email,
    emailLower: d.emailLower,
    password: d.password,
    fullName: d.fullName,
    phoneNumber: d.phoneNumber,
    profilePhotoUrl: d.profilePhotoUrl,
    createdAt: toDate(d.createdAt),
    updatedAt: toDate(d.updatedAt),
  };
}

// Always exactly one query, found or not, so login timing does not reveal accounts.
export async function findUserByEmail(rawEmail: string): Promise<UserRecord | null> {
  const { emailLower } = normalizeEmail(rawEmail);
  const snap = await db().collection("users").where("emailLower", "==", emailLower).limit(1).get();
  return snap.empty ? null : fromSnapshot(snap.docs[0]);
}

export async function getUserById(userId: string): Promise<UserRecord | null> {
  if (!isDocId(userId)) return null;
  const snap = await userDoc(userId).get();
  return snap.exists ? fromSnapshot(snap) : null;
}

/** Creates the user and claims its email in one transaction. Null when the email is taken in any case. */
export async function createUser(input: {
  email: string;
  passwordHash?: string;
  fullName?: string;
}): Promise<UserRecord | null> {
  const { email, emailLower } = normalizeEmail(input.email);
  const emailRef = db().collection("userEmails").doc(emailDocId(emailLower));
  const userRef = db().collection("users").doc();
  const now = new Date();
  const data = {
    email,
    emailLower,
    password: input.passwordHash,
    fullName: input.fullName,
    createdAt: now,
    updatedAt: now,
  };
  try {
    const created = await db().runTransaction(async (tx) => {
      if ((await tx.get(emailRef)).exists) return false;
      tx.create(userRef, data);
      tx.create(emailRef, { userId: userRef.id });
      return true;
    });
    return created ? { id: userRef.id, ...data } : null;
  } catch (err) {
    // A concurrent signup for the same email committed first.
    if (errorCode(err) === ALREADY_EXISTS) return null;
    throw err;
  }
}

export async function updateUserProfile(
  userId: string,
  fields: Pick<UserRecord, "fullName" | "phoneNumber" | "profilePhotoUrl">,
): Promise<UserRecord | null> {
  if (!isDocId(userId)) return null;
  try {
    await userDoc(userId).update({ ...fields, updatedAt: new Date() });
  } catch (err) {
    if (errorCode(err) === NOT_FOUND) return null;
    throw err;
  }
  return getUserById(userId);
}

/** What the web UI gets: never the password hash. `_id` stays for older client code. */
export function toPublicUser(user: UserRecord): UserPayload & { _id: string; updatedAt?: string } {
  return {
    _id: user.id,
    userId: user.id,
    email: user.email,
    fullName: user.fullName,
    phoneNumber: user.phoneNumber,
    profilePhotoUrl: user.profilePhotoUrl,
    createdAt: user.createdAt?.toISOString(),
    updatedAt: user.updatedAt?.toISOString(),
  };
}
