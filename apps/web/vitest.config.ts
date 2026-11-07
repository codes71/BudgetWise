import { defineConfig } from "vitest/config";
import fs from "node:fs";
import path from "node:path";

const HOMEBREW_MONGOD = "/opt/homebrew/bin/mongod";
const systemBinary = process.env.MONGOMS_SYSTEM_BINARY
  ?? (fs.existsSync(HOMEBREW_MONGOD) ? HOMEBREW_MONGOD : undefined);

export default defineConfig({
  esbuild: { jsx: "automatic" },
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  test: {
    environment: "node",
    globalSetup: ["tests/global-setup.ts"],
    setupFiles: ["tests/setup.ts"],
    fileParallelism: false,
    testTimeout: 30000,
    hookTimeout: 60000,
    env: {
      JWT_SECRET: "test-only-jwt-secret-0123456789abcdef-0123456789",
      ...(systemBinary ? { MONGOMS_SYSTEM_BINARY: systemBinary } : {}),
    },
    projects: [
      {
        test: {
          name: "mongo",
          include: ["tests/**/*.test.ts"],
          exclude: ["tests/scripts/copy-mongo.test.ts"],
        },
      },
      {
        test: {
          name: "legacy-firestore-copy",
          include: ["tests/scripts/copy-mongo.test.ts"],
          globalSetup: ["tests/legacy-global-setup.ts"],
        },
      },
    ],
  },
});
