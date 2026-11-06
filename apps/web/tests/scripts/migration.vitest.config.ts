import { defineConfig } from "vitest/config";

// Deliberately no global setup, credentials, Firestore emulator, or MongoDB process.
export default defineConfig({ test: { environment: "node", include: ["tests/scripts/firestore-to-mongo.test.ts", "tests/scripts/encrypted-snapshot.test.ts", "tests/scripts/live-source-import.test.ts"] } });
