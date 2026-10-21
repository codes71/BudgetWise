import { defineConfig } from "vitest/config";
import path from "node:path";

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
      MONGOMS_SYSTEM_BINARY: "/opt/homebrew/bin/mongod",
    },
  },
});
