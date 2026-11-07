import { defineConfig } from "vitest/config";
import { resolve } from "node:path";

// Deliberately no global setup, credentials, Firestore emulator, or MongoDB process.
export default defineConfig({
  resolve: { alias: { "@": resolve(__dirname, "../../src") } },
  test: { environment: "node", include: ["tests/scripts/firestore-to-mongo.test.ts", "tests/scripts/encrypted-snapshot.test.ts", "tests/scripts/live-source-import.test.ts", "tests/scripts/recovery-write-safety.test.ts", "tests/scripts/recovery-suppression.test.ts"] },
});
