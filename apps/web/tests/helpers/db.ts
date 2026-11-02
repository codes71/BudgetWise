import { db } from "@/lib/firestore";

// Tests must only ever talk to the local emulator.
function emulatorTarget(): { host: string; projectId: string } {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  const projectId = process.env.FIREBASE_PROJECT_ID;
  if (host !== "127.0.0.1:8085" || !projectId?.startsWith("demo-")) {
    throw new Error("Tests need FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 and a demo- FIREBASE_PROJECT_ID");
  }
  return { host, projectId };
}

export function testDb() {
  emulatorTarget();
  return db();
}

export async function clearDb(): Promise<void> {
  const { host, projectId } = emulatorTarget();
  const res = await fetch(`http://${host}/emulator/v1/projects/${projectId}/databases/(default)/documents`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`Clearing the emulator failed with ${res.status}`);
}

/** A fresh user id shaped like a Firestore auto id. */
export const newUserId = (): string => testDb().collection("users").doc().id;
