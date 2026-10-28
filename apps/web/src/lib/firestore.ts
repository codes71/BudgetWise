import { createHash } from "node:crypto";
import { applicationDefault, cert, getApps, initializeApp, type App } from "firebase-admin/app";
import { getFirestore, Timestamp, type Firestore } from "firebase-admin/firestore";

const APP_NAME = "budgetwise";
const EMULATOR_PROJECT_ID = "demo-budgetwise";

export class FirestoreConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FirestoreConfigError";
  }
}

// Credentials are picked explicitly, in this order, and never fall back to
// whatever gcloud login happens to be on the machine:
// 1. FIRESTORE_EMULATOR_HOST: the local emulator (no credentials).
// 2. FIREBASE_PROJECT_ID + FIREBASE_CLIENT_EMAIL + FIREBASE_PRIVATE_KEY.
// 3. GOOGLE_APPLICATION_CREDENTIALS pointing at a key file.
function createApp(): App {
  const existing = getApps().find((app) => app.name === APP_NAME);
  if (existing) return existing;

  const { FIREBASE_PROJECT_ID: projectId, FIREBASE_CLIENT_EMAIL: clientEmail, FIREBASE_PRIVATE_KEY: privateKey } =
    process.env;

  if (process.env.FIRESTORE_EMULATOR_HOST) {
    return initializeApp({ projectId: projectId || EMULATOR_PROJECT_ID }, APP_NAME);
  }
  if (projectId && clientEmail && privateKey) {
    return initializeApp(
      {
        // Env UIs often store the PEM with literal \n sequences.
        credential: cert({ projectId, clientEmail, privateKey: privateKey.replace(/\\n/g, "\n") }),
        projectId,
      },
      APP_NAME,
    );
  }
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    return initializeApp({ credential: applicationDefault(), ...(projectId ? { projectId } : {}) }, APP_NAME);
  }
  const missing = [
    !projectId && "FIREBASE_PROJECT_ID",
    !clientEmail && "FIREBASE_CLIENT_EMAIL",
    !privateKey && "FIREBASE_PRIVATE_KEY",
  ].filter(Boolean);
  throw new FirestoreConfigError(
    `Firestore is not configured: set ${missing.join(", ")} (or GOOGLE_APPLICATION_CREDENTIALS, ` +
      "or FIRESTORE_EMULATOR_HOST for the local emulator).",
  );
}

const cache = globalThis as typeof globalThis & { __budgetwiseFirestore?: Firestore };

// Lazy so importing a module never needs Firestore config (next build, tests).
// Cached on globalThis so dev hot reloads reuse one client.
export function db(): Firestore {
  if (!cache.__budgetwiseFirestore) {
    const firestore = getFirestore(createApp());
    firestore.settings({ ignoreUndefinedProperties: true });
    cache.__budgetwiseFirestore = firestore;
  }
  return cache.__budgetwiseFirestore;
}

export const sha256Hex = (value: string): string => createHash("sha256").update(value).digest("hex");

// Ids we accept from callers: Mongo ObjectId hex, Firestore auto ids and UUIDs all fit.
// Firestore reserves ids shaped like __name__.
const DOC_ID = /^[A-Za-z0-9_-]{1,128}$/;
export const isDocId = (id: unknown): id is string =>
  typeof id === "string" && DOC_ID.test(id) && !/^__.*__$/.test(id);

/** Firestore Timestamp (or Date) to Date; anything else is treated as missing. */
export const toDate = (value: unknown): Date | undefined =>
  value instanceof Timestamp ? value.toDate() : value instanceof Date ? value : undefined;

/** users/{userId}; throws on ids that would form a different path. */
export function userDoc(userId: string) {
  if (!isDocId(userId)) throw new Error("Invalid user id");
  return db().collection("users").doc(userId);
}

/** Global category doc id: "Food and Drink Item" -> "food-and-drink-item". */
export function categorySlug(name: string): string {
  const slug = name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 100);
  return slug || `category-${sha256Hex(name).slice(0, 16)}`;
}

/** gRPC status codes the Firestore client reports on errors. */
export const ALREADY_EXISTS = 6;
export const NOT_FOUND = 5;
export const errorCode = (err: unknown): unknown => (err as { code?: unknown } | null)?.code;
