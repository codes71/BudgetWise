import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { evaluateTypecheck, parseDiagnostics, validateBaseline } from "../../scripts/checkTypes.mjs";

const diagnostic = { file: "src/old.ts", code: 2339, message: "Property 'old' does not exist on type 'Account'.", count: 2 };
const baseline = { version: 1, inheritedDiagnostics: 2, diagnostics: [diagnostic] };
const oldError = (line = 1) => `src/old.ts(${line},3): error TS2339: ${diagnostic.message}\n`;

describe("TypeScript diagnostic baseline gate", () => {
  it("matches complete normalized diagnostics while ignoring moved line positions", () => {
    const result = evaluateTypecheck(oldError(30) + oldError(50), 2, baseline);
    expect(result.added).toEqual([]);
    expect(result.diagnostics).toHaveLength(2);
    expect(parseDiagnostics(".\\src\\old.ts(1,2): error TS2339: Property 'old' does not exist\n  on type 'Account'.\n"))
      .toEqual([{ file: "src/old.ts", code: 2339, message: diagnostic.message }]);
  });

  it("rejects same-count replacements with a different file, TS code, or message", () => {
    for (const replacement of [
      "src/new.ts(1,3): error TS2339: " + diagnostic.message,
      "src/old.ts(1,3): error TS2345: " + diagnostic.message,
      "src/old.ts(1,3): error TS2339: Property 'new' does not exist on type 'Account'.",
    ]) {
      const result = evaluateTypecheck(oldError() + replacement, 2, baseline);
      expect(result.diagnostics).toHaveLength(2);
      expect(result.added).toHaveLength(1);
    }
  });

  it("allows debt removal but rejects extra occurrences of an inherited diagnostic", () => {
    expect(evaluateTypecheck(oldError(), 2, baseline).added).toEqual([]);
    expect(evaluateTypecheck("", 0, baseline).added).toEqual([]);
    expect(evaluateTypecheck(oldError() + oldError() + oldError(), 2, baseline).added).toHaveLength(1);
  });

  it("fails closed for malformed output, compiler failure and inconsistent exit statuses", () => {
    for (const [output, status] of [
      ["unknown compiler output", 2],
      ["error TS18003: No inputs were found", 2],
      ["", 2],
      [oldError(), 0],
      ["", null],
      ["", 137],
    ] as const) expect(() => evaluateTypecheck(output, status, baseline)).toThrow();
  });

  it("fails closed for malformed, duplicate or miscounted baseline data", () => {
    for (const invalid of [
      {},
      { ...baseline, inheritedDiagnostics: 3 },
      { ...baseline, diagnostics: [{ ...diagnostic, count: 0 }] },
      { ...baseline, diagnostics: [diagnostic, diagnostic] },
    ]) expect(() => validateBaseline(invalid)).toThrow();
  });

  it("retains the verified 31 inherited diagnostics without an update command", () => {
    const inherited = validateBaseline(JSON.parse(readFileSync("scripts/typecheck-baseline.json", "utf8")));
    expect(inherited.inheritedDiagnostics).toBe(31);
    expect(inherited.diagnostics.reduce((sum: number, item: { count: number }) => sum + item.count, 0)).toBe(31);
  });
});
