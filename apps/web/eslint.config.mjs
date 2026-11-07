import js from "@eslint/js";
import next from "@next/eslint-plugin-next";
import tsParser from "@typescript-eslint/parser";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";

// Next15 supports its plugin directly in a custom ESLint config. Its older aggregate config pins ESLint9,
// which is EOL. Use currently supported ESLint10 with parsers/plugins that declare compatible peers.
// This gate targets framework/correctness/hooks issues; the separate typecheck gate handles semantic types.
const eslintConfig = [
  { ignores: [".next/**", "out/**", "coverage/**", "next-env.d.ts"] },
  {
    files: ["**/*.{js,mjs,cjs}"],
    ...js.configs.recommended,
  },
  {
    files: ["**/*.{js,mjs,cjs,ts,tsx}"],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaFeatures: { jsx: true } },
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { "@next/next": next, "react-hooks": reactHooks },
    rules: {
      ...next.configs.recommended.rules,
      ...next.configs["core-web-vitals"].rules,
      "no-async-promise-executor": "error",
      "no-constant-binary-expression": "error",
      "no-dupe-args": "error",
      "no-dupe-else-if": "error",
      "no-dupe-keys": "error",
      "no-loss-of-precision": "error",
      "no-unreachable": "error",
      "no-unsafe-finally": "error",
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "warn",
    },
  },
];

export default eslintConfig;
