import { defineConfig } from "vitest/config";
import fs from "node:fs";
import path from "node:path";

// The Mongo copy script test uses mongodb-memory-server: prefer a local mongod
// when one is available; otherwise let the environment or the package decide.
const HOMEBREW_MONGOD = "/opt/homebrew/bin/mongod";
const systemBinary =
  process.env.MONGOMS_SYSTEM_BINARY ?? (fs.existsSync(HOMEBREW_MONGOD) ? HOMEBREW_MONGOD : undefined);

// Separate test runs can share the emulator without clearing the dev app's
// database. The override is deliberately restricted to emulator-only projects.
const testProjectId = process.env.BUDGETWISE_TEST_PROJECT_ID ?? "demo-budgetwise";
if (!/^demo-[a-z0-9-]+$/.test(testProjectId)) {
  throw new Error("BUDGETWISE_TEST_PROJECT_ID must be an emulator-only demo- project");
}

export default defineConfig({
  esbuild: { jsx: "automatic" },
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  test: {
    environment: "node",
    include: ["tests/**/*.test.ts"],
    // Starts the Firestore emulator once for the run (or reuses a running one).
    globalSetup: ["tests/global-setup.ts"],
    // Every file shares one emulator database and clears it between tests.
    fileParallelism: false,
    testTimeout: 30000,
    hookTimeout: 60000,
    env: {
      JWT_SECRET: "test-only-jwt-secret-0123456789abcdef-0123456789",
      FIRESTORE_EMULATOR_HOST: "127.0.0.1:8085",
      FIREBASE_PROJECT_ID: testProjectId,
      ...(systemBinary ? { MONGOMS_SYSTEM_BINARY: systemBinary } : {}),
    },
  },
});
