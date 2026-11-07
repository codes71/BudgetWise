import { randomUUID } from "node:crypto";
import { beforeAll, afterAll } from "vitest";
import type { Collection, CollectionOptions, Db, Document } from "mongodb";
import { db, ensureIndexes, mongoClient, type MongoRecord } from "@/lib/mongodb";

type TestDb = Omit<Db, "collection"> & {
  collection<TSchema extends Document = MongoRecord>(name: string, options?: CollectionOptions): Collection<TSchema>;
};
let database: TestDb;

function assertLocalTestTarget(): void {
  const uri = process.env.MONGODB_URI;
  if (!uri || !/^mongodb:\/\/127\.0\.0\.1:\d+(?:[,/?]|$)/.test(uri)
      || !/^test-pocketpeek-[a-z0-9-]+$/.test(process.env.MONGODB_DB ?? "")) {
    throw new Error("Tests require their owned loopback Mongo replica set and test-pocketpeek- database");
  }
}

beforeAll(async () => {
  assertLocalTestTarget();
  database = await db() as TestDb;
  await ensureIndexes();
});
afterAll(async () => { await (await mongoClient()).close(); });

export function testDb(): TestDb {
  assertLocalTestTarget();
  if (!database) throw new Error("Test database has not initialized");
  return database;
}

/** Delete only this run's synthetic records, preserving indexes for concurrency checks. */
export async function clearDb(): Promise<void> {
  const database = testDb();
  for (const { name } of await database.listCollections({}, { nameOnly: true }).toArray()) {
    await database.collection(name).deleteMany({});
  }
}

export const newUserId = (): string => randomUUID();

export async function seedAccount(userId: string, email = `${userId}@example.com`): Promise<void> {
  await testDb().collection("users").insertOne({ _id: userId, email, emailLower: email.toLowerCase() });
}
