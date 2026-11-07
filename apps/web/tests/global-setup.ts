import { existsSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { MongoMemoryReplSet } from "mongodb-memory-server";
import type { TestProject } from "vitest/node";

/** Every test run owns a temporary replica set, with dynamic ports and no Atlas credentials. */
export default async function setup(project: TestProject) {
  const systemBinary = process.env.MONGOMS_SYSTEM_BINARY
    ?? (existsSync("/opt/homebrew/bin/mongod") ? "/opt/homebrew/bin/mongod" : undefined);
  const server = await MongoMemoryReplSet.create({
    ...(systemBinary ? { binary: { systemBinary } } : {}),
    replSet: { count: 1, storageEngine: "wiredTiger" },
  });
  const suffix = `${process.pid}-${randomBytes(4).toString("hex")}`;
  project.provide("mongoUri", server.getUri());
  project.provide("mongoDatabase", `test-pocketpeek-${suffix}`);
  project.provide("legacyFirestoreProject", `demo-pocketpeek-legacy-${suffix}`);
  return () => server.stop();
}

declare module "vitest" {
  export interface ProvidedContext {
    mongoUri: string;
    mongoDatabase: string;
    legacyFirestoreProject: string;
  }
}
