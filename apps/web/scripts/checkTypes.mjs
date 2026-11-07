import { readFileSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const webRoot = fileURLToPath(new URL("../", import.meta.url));
const normalizeMessage = (message) => message.trim().replace(/\s+/g, " ");
const normalizeFile = (file) => path.posix.normalize(file.replace(/\\/g, "/"));
const keyOf = ({ file, code, message }) => JSON.stringify([file, code, message]);

/** Positions may move without changing the inherited diagnostic. Preserve every message and occurrence. */
export function parseDiagnostics(output) {
  const diagnostics = [];
  for (const line of output.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const match = /^(.+)\(\d+,\d+\): error TS(\d+): (.+)$/.exec(line);
    if (match) {
      diagnostics.push({ file: normalizeFile(match[1]), code: Number(match[2]), message: normalizeMessage(match[3]) });
    } else if (/^\s+\S/.test(line) && diagnostics.length) {
      const diagnostic = diagnostics[diagnostics.length - 1];
      diagnostic.message = normalizeMessage(`${diagnostic.message} ${line}`);
    } else {
      throw new Error(`Unrecognized TypeScript output: ${line}`);
    }
  }
  return diagnostics;
}

export function validateBaseline(baseline) {
  if (baseline?.version !== 1 || !Array.isArray(baseline.diagnostics) || !baseline.diagnostics.length) {
    throw new Error("Invalid TypeScript diagnostic baseline");
  }
  const keys = new Set();
  let count = 0;
  for (const diagnostic of baseline.diagnostics) {
    if (typeof diagnostic.file !== "string" || !diagnostic.file || diagnostic.file !== normalizeFile(diagnostic.file)
      || typeof diagnostic.message !== "string" || !diagnostic.message || diagnostic.message !== normalizeMessage(diagnostic.message)
      || !Number.isSafeInteger(diagnostic.code) || diagnostic.code < 1
      || !Number.isSafeInteger(diagnostic.count) || diagnostic.count < 1 || keys.has(keyOf(diagnostic))) {
      throw new Error("Invalid TypeScript baseline diagnostic");
    }
    keys.add(keyOf(diagnostic));
    count += diagnostic.count;
  }
  if (count !== baseline.inheritedDiagnostics) throw new Error("TypeScript baseline count does not match its diagnostics");
  return baseline;
}

export function evaluateTypecheck(output, status, baseline) {
  validateBaseline(baseline);
  if (![0, 1, 2].includes(status)) throw new Error(`TypeScript compiler did not complete successfully (exit ${status})`);
  const diagnostics = parseDiagnostics(output);
  if ((status === 0) !== (diagnostics.length === 0)) throw new Error("TypeScript exit status and parsed diagnostics disagree");
  const remaining = new Map(baseline.diagnostics.map((diagnostic) => [keyOf(diagnostic), diagnostic.count]));
  const added = [];
  for (const diagnostic of diagnostics) {
    const key = keyOf(diagnostic);
    const allowance = remaining.get(key) ?? 0;
    if (allowance > 0) remaining.set(key, allowance - 1);
    else added.push(diagnostic);
  }
  return { diagnostics, added, inheritedDiagnostics: baseline.inheritedDiagnostics };
}

export function main() {
  try {
    if (process.argv.length > 2) throw new Error("checkTypes does not accept arguments or baseline update flags");
    const baseline = JSON.parse(readFileSync(new URL("./typecheck-baseline.json", import.meta.url), "utf8"));
    validateBaseline(baseline);
    const compiler = spawnSync(process.execPath, [
      path.join(webRoot, "node_modules/typescript/bin/tsc"), "--noEmit", "--pretty", "false", "--incremental", "false",
    ], { cwd: webRoot, encoding: "utf8", maxBuffer: 10 * 1024 * 1024 });
    if (compiler.error) throw compiler.error;
    const result = evaluateTypecheck(`${compiler.stdout ?? ""}${compiler.stderr ?? ""}`, compiler.status, baseline);
    if (result.added.length) {
      console.error(`TypeScript baseline gate failed: ${result.added.length} new diagnostic(s).`);
      for (const diagnostic of result.added) console.error(`${diagnostic.file}: TS${diagnostic.code}: ${diagnostic.message}`);
      return 1;
    }
    console.log(`TypeScript baseline gate passed: ${result.diagnostics.length} inherited diagnostics remain (baseline ${result.inheritedDiagnostics}); no new diagnostics. Existing type debt is still allowed.`);
    return 0;
  } catch (error) {
    console.error(`TypeScript baseline gate failed: ${error instanceof Error ? error.message : String(error)}`);
    return 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = main();
