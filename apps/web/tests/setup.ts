import { inject } from "vitest";

process.env.MONGODB_URI = inject("mongoUri");
process.env.MONGODB_DB = inject("mongoDatabase");
// Only the historical Mongo->Firestore copy tests use these. Their namespace is unique to this run.
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8085";
process.env.FIREBASE_PROJECT_ID = inject("legacyFirestoreProject");
