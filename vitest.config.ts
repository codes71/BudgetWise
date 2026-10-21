import { defineConfig } from "vitest/config";
import fs from "node:fs";
import path from "node:path";

// Use a local mongod when one is available; otherwise let the environment or
// mongodb-memory-server decide.
const HOMEBREW_MONGOD = "/opt/homebrew/bin/mongod";
const systemBinary =
  process.env.MONGOMS_SYSTEM_BINARY ?? (fs.existsSync(HOMEBREW_MONGOD) ? HOMEBREW_MONGOD : undefined);

export default defineConfig({
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  test: {
    environment: "node",
    include: ["tests/**/*.test.ts"],
    testTimeout: 30000,
    hookTimeout: 60000,
    env: {
      JWT_SECRET: "test-only-jwt-secret-0123456789abcdef-0123456789",
      ...(systemBinary ? { MONGOMS_SYSTEM_BINARY: systemBinary } : {}),
    },
  },
});
