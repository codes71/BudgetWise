import { emulatorIsUp, startFirestoreEmulator } from "../scripts/firestoreEmulator";

/** Historical copy utility coverage only. Reused emulators are never stopped by this run. */
export default async function setup() {
  if (await emulatorIsUp()) return;
  const emulator = await startFirestoreEmulator();
  return () => emulator.stop();
}
