import { randomUUID } from "node:crypto";
import { db, isDuplicateKey, isDocId, sha256Hex, toDate, withTransaction, type MongoRecord } from "@/lib/mongodb";
import type { UserPayload } from "@/lib/types";
import { requireActiveAccount } from "@/lib/account-access";
import { hasDeletionRequest } from "@/lib/account-deletion-journal";

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
  deletionStartedAt?: Date;
}

export function normalizeEmail(raw: string): { email: string; emailLower: string } {
  const email = raw.trim();
  return { email, emailLower: email.toLowerCase() };
}

/** userEmails/{id}: one doc per lowercased email, which makes emails unique regardless of case. */
export const emailDocId = (emailLower: string): string => sha256Hex(emailLower);

function fromDocument(d: MongoRecord): UserRecord {
  return {
    id: d._id,
    email: d.email,
    emailLower: d.emailLower,
    password: d.password,
    fullName: d.fullName,
    phoneNumber: d.phoneNumber,
    profilePhotoUrl: d.profilePhotoUrl,
    createdAt: toDate(d.createdAt),
    updatedAt: toDate(d.updatedAt),
    deletionStartedAt: toDate(d.deletionStartedAt),
  };
}

// Always exactly one query, found or not, so login timing does not reveal accounts.
export async function findUserByEmail(rawEmail: string): Promise<UserRecord | null> {
  const { emailLower } = normalizeEmail(rawEmail);
  const record = await (await db()).collection<MongoRecord>("users").findOne({ emailLower });
  return record ? fromDocument(record) : null;
}

export async function getUserById(userId: string): Promise<UserRecord | null> {
  if (!isDocId(userId)) return null;
  const database = await db();
  if (await hasDeletionRequest(database, userId)) return null;
  const record = await database.collection<MongoRecord>("users").findOne({ _id: userId });
  return record ? fromDocument(record) : null;
}

/** Creates the user and claims its email in one transaction. Null when the email is taken in any case. */
export async function createUser(input: {
  email: string;
  passwordHash?: string;
  fullName?: string;
}): Promise<UserRecord | null> {
  const { email, emailLower } = normalizeEmail(input.email);
  const database = await db();
  const emails = database.collection<MongoRecord>("userEmails");
  const users = database.collection<MongoRecord>("users");
  const userId = randomUUID();
  const now = new Date();
  const data = {
    email,
    emailLower,
    ...(input.passwordHash !== undefined ? { password: input.passwordHash } : {}),
    ...(input.fullName !== undefined ? { fullName: input.fullName } : {}),
    createdAt: now,
    updatedAt: now,
  };
  try {
    const created = await withTransaction(async (session) => {
      // Checking the existing users also protects a legacy account whose email-claim document is absent.
      if (await emails.findOne({ _id: emailDocId(emailLower) }, { session })
        || await users.findOne({ emailLower }, { session })) return false;
      await users.insertOne({ _id: userId, ...data }, { session });
      await emails.insertOne({ _id: emailDocId(emailLower), userId }, { session });
      return true;
    });
    return created ? { id: userId, ...data } : null;
  } catch (err) {
    // A concurrent signup for the same email committed first.
    if (isDuplicateKey(err)) return null;
    throw err;
  }
}

export async function updateUserProfile(
  userId: string,
  fields: Pick<UserRecord, "fullName" | "phoneNumber" | "profilePhotoUrl">,
): Promise<UserRecord | null> {
  if (!isDocId(userId)) return null;
  try {
    await withTransaction(async (session) => {
      await requireActiveAccount(userId, session);
      await (await db()).collection<MongoRecord>("users").updateOne(
        { _id: userId }, { $set: { ...fields, updatedAt: new Date() } }, { session },
      );
    });
  } catch (err) {
    if ((err as { status?: number }).status === 401) return null;
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
