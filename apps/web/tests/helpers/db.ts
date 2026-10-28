import mongoose from "mongoose";
import { MongoMemoryServer } from "mongodb-memory-server";

let server: MongoMemoryServer | null = null;

// Starts the in-memory server and sets MONGODB_URI but does NOT open a mongoose
// connection, to reproduce a cold-started process.
export async function startMongoServerOnly(): Promise<void> {
  if (server) return;
  server = await MongoMemoryServer.create();
  // Must be set before src/lib/db.ts is imported (it reads the env at load).
  process.env.MONGODB_URI = server.getUri("budgetwise_test");
}

export async function startMongo(): Promise<void> {
  await startMongoServerOnly();
  await mongoose.connect(process.env.MONGODB_URI!);
}

export async function stopMongo(): Promise<void> {
  await mongoose.disconnect();
  if (server) {
    await server.stop();
    server = null;
  }
}

export async function clearDb(): Promise<void> {
  const collections = await mongoose.connection.db!.collections();
  await Promise.all(collections.map((c) => c.deleteMany({})));
}
