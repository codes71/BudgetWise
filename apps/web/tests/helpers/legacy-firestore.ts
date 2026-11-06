import { db } from "@/lib/firestore";

export function legacyFirestore() {
  const project = process.env.FIREBASE_PROJECT_ID;
  if (process.env.FIRESTORE_EMULATOR_HOST !== "127.0.0.1:8085" || !project?.startsWith("demo-pocketpeek-legacy-")) {
    throw new Error("Historical copy tests require the isolated demo-pocketpeek-legacy- emulator namespace");
  }
  return db();
}

export async function clearLegacyFirestore(): Promise<void> {
  legacyFirestore();
  const res = await fetch(`http://127.0.0.1:8085/emulator/v1/projects/${process.env.FIREBASE_PROJECT_ID}/databases/(default)/documents`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Clearing legacy test namespace failed: ${res.status}`);
}
