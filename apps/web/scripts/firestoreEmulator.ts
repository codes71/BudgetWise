// Runs the local Firestore emulator through the firebase-tools devDependency,
// with project demo-budgetwise on 127.0.0.1:8085 (ports from firebase.json).
// demo- project ids are emulator-only, so this never reaches a real Firebase
// project. Used by the vitest globalSetup and by `npm run emulator`.
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

export const EMULATOR_HOST = "127.0.0.1:8085";
export const DEMO_PROJECT_ID = "demo-budgetwise";

const ROOT = path.resolve(__dirname, "..");
const FIREBASE_BIN = path.join(ROOT, "node_modules", ".bin", "firebase");
const ANDROID_STUDIO_JBR = "/Applications/Android Studio.app/Contents/jbr/Contents/Home";

// firebase-tools 15 needs Java 21 or newer. /usr/bin/java is only a stub on
// macOS without a JDK, so look for a real one.
export function findJavaHome(): string | undefined {
  if (process.env.JAVA_HOME) return process.env.JAVA_HOME;
  try {
    const out = execFileSync("/usr/libexec/java_home", ["-v", "21+"], { stdio: ["ignore", "pipe", "ignore"] })
      .toString()
      .trim();
    if (out) return out;
  } catch {
    // no JDK 21+ registered with macOS
  }
  if (fs.existsSync(path.join(ANDROID_STUDIO_JBR, "bin", "java"))) return ANDROID_STUDIO_JBR;
  return undefined;
}

function emulatorEnv(): NodeJS.ProcessEnv {
  const javaHome = findJavaHome();
  if (!javaHome) {
    throw new Error("No Java runtime found. Set JAVA_HOME; the Firestore emulator needs Java 21 or newer.");
  }
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    JAVA_HOME: javaHome,
    PATH: `${path.join(javaHome, "bin")}${path.delimiter}${process.env.PATH ?? ""}`,
  };
  // The emulator needs no credentials; keep real ones out of its environment.
  for (const name of ["GOOGLE_APPLICATION_CREDENTIALS", "FIREBASE_CLIENT_EMAIL", "FIREBASE_PRIVATE_KEY", "FIREBASE_TOKEN"]) {
    delete env[name];
  }
  return env;
}

const ARGS = ["emulators:start", "--only", "firestore", "--project", DEMO_PROJECT_ID];

export async function emulatorIsUp(): Promise<boolean> {
  try {
    const res = await fetch(`http://${EMULATOR_HOST}/`);
    return res.ok && (await res.text()).trim() === "Ok";
  } catch {
    return false;
  }
}

async function stop(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = new Promise((resolve) => child.once("exit", resolve));
  // firebase-tools stops its Java child on SIGINT; kill the whole group if it hangs.
  child.kill("SIGINT");
  const timer = setTimeout(() => {
    try {
      process.kill(-child.pid!, "SIGKILL");
    } catch {
      // already gone
    }
  }, 15_000);
  await exited;
  clearTimeout(timer);
}

export async function startFirestoreEmulator({ timeoutMs = 180_000 } = {}): Promise<{ stop(): Promise<void> }> {
  const child = spawn(FIREBASE_BIN, ARGS, {
    cwd: ROOT,
    env: emulatorEnv(),
    stdio: ["ignore", "pipe", "pipe"],
    detached: true,
  });
  let output = "";
  const keep = (chunk: Buffer) => {
    output = (output + chunk.toString()).slice(-4000);
  };
  child.stdout!.on("data", keep);
  child.stderr!.on("data", keep);

  const deadline = Date.now() + timeoutMs;
  while (!(await emulatorIsUp())) {
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(`Firestore emulator exited before it was ready:\n${output}`);
    }
    if (Date.now() > deadline) {
      await stop(child);
      throw new Error(`Firestore emulator was not ready after ${timeoutMs} ms:\n${output}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  return { stop: () => stop(child) };
}

// `npm run emulator`: run in the foreground until Ctrl+C.
if (process.argv[1] && /firestoreEmulator\.[cm]?[jt]s$/.test(process.argv[1])) {
  try {
    const child = spawn(FIREBASE_BIN, ARGS, { cwd: ROOT, env: emulatorEnv(), stdio: "inherit" });
    child.on("exit", (code) => process.exit(code ?? 0));
  } catch (err) {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
  }
}
