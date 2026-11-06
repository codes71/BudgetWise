import { createHash } from "node:crypto";
import { MongoClient, type ClientSession, type Db, type Document } from "mongodb";

/** IDs are strings so accounts and all client-visible IDs survive the Firestore migration unchanged. */
export type MongoRecord = Document & { _id: string };

export class MongoConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MongoConfigError";
  }
}

interface Connection {
  key: string;
  client: Promise<MongoClient>;
}

const cache = globalThis as typeof globalThis & { __pocketPeekMongo?: Connection };

function configuration(): { uri: string; name: string } {
  const uri = process.env.MONGODB_URI;
  const name = process.env.MONGODB_DB;
  if (!uri || !/^mongodb(?:\+srv)?:\/\//.test(uri)) {
    throw new MongoConfigError("Set MONGODB_URI to the MongoDB connection URI.");
  }
  if (!name || !/^[A-Za-z0-9_-]{1,63}$/.test(name)) {
    throw new MongoConfigError("Set MONGODB_DB to an explicit application database name.");
  }
  return { uri, name };
}

/** Reuse a bounded pool in warm Vercel functions. Never log the URI or include it in application errors. */
export async function mongoClient(): Promise<MongoClient> {
  const { uri, name } = configuration();
  const key = `${uri}\0${name}`;
  if (cache.__pocketPeekMongo?.key !== key) {
    const connection: Connection = {
      key,
      client: new MongoClient(uri, {
        maxPoolSize: 10,
        minPoolSize: 0,
        maxIdleTimeMS: 60_000,
        serverSelectionTimeoutMS: 10_000,
        waitQueueTimeoutMS: 10_000,
        ignoreUndefined: true,
      }).connect(),
    };
    cache.__pocketPeekMongo = connection;
    void connection.client.catch(() => {
      if (cache.__pocketPeekMongo === connection) delete cache.__pocketPeekMongo;
    });
  }
  return cache.__pocketPeekMongo!.client;
}

/** Lazy: importing a server module or running next build never opens a database connection. */
export async function db(): Promise<Db> {
  const { name } = configuration();
  return (await mongoClient()).db(name);
}

export async function withTransaction<T>(run: (session: ClientSession) => Promise<T>): Promise<T> {
  const session = (await mongoClient()).startSession();
  try {
    return await session.withTransaction(run, {
      readConcern: { level: "snapshot" },
      writeConcern: { w: "majority" },
      readPreference: "primary",
      maxCommitTimeMS: 15_000,
    });
  } finally {
    await session.endSession();
  }
}

export const sha256Hex = (value: string): string => createHash("sha256").update(value).digest("hex");
const DOC_ID = /^[A-Za-z0-9_-]{1,128}$/;
export const isDocId = (id: unknown): id is string =>
  typeof id === "string" && DOC_ID.test(id) && !/^__.*__$/.test(id);

export function scopedId(userId: string, id: string): string {
  if (!isDocId(userId) || !isDocId(id)) throw new Error("Invalid record id");
  return `${userId}/${id}`;
}

export function leafId(id: string): string {
  return id.slice(id.lastIndexOf("/") + 1);
}

export const toDate = (value: unknown): Date | undefined => value instanceof Date ? value : undefined;

export function categorySlug(name: string): string {
  return name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 100)
    || `category-${sha256Hex(name).slice(0, 16)}`;
}

export const isDuplicateKey = (error: unknown): boolean =>
  (error as { code?: unknown } | null)?.code === 11000;

/** Explicit setup after migration verification, never a hidden runtime migration or a TTL deletion policy. */
export async function ensureIndexes(): Promise<void> {
  const database = await db();
  await database.collection<MongoRecord>("users").createIndex(
    { emailLower: 1 }, { unique: true, partialFilterExpression: { emailLower: { $type: "string" } } },
  );
  await database.collection<MongoRecord>("userEmails").createIndex({ userId: 1 });
  await database.collection<MongoRecord>("transactions").createIndex(
    { userId: 1, clientId: 1 }, { unique: true, partialFilterExpression: { clientId: { $type: "string" } } },
  );
  await database.collection<MongoRecord>("transactions").createIndex({ userId: 1, updatedAt: 1 });
  for (const name of ["budgets", "budgetMonths", "userCategories"]) {
    await database.collection<MongoRecord>(name).createIndex({ userId: 1 });
  }
  await database.collection<MongoRecord>("budgetMonths").createIndex({ userId: 1, month: 1 });
  for (const name of ["_firestoreArchive", "_firestoreTypes"]) {
    await database.collection<MongoRecord>(name).createIndex({ ownerId: 1 });
  }
  await database.collection<MongoRecord>("rateLimits").createIndex({ expiresAt: 1 });
}
