import { emulatorIsUp, startFirestoreEmulator } from "../scripts/firestoreEmulator";

export default async function setup() {
  // An emulator that is already running (for example `npm run emulator`) is reused and left up.
  if (await emulatorIsUp()) return;
  const emulator = await startFirestoreEmulator();
  return () => emulator.stop();
}
