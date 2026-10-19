# WS1 Backend Mobile API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Android app a bearer-token REST API (`/api/v1/**`) for login, categories, budgets, idempotent transaction sync and Burmese transcript parsing, without changing the web cookie flow.

**Architecture:** Thin route handlers call small modules in `src/lib/mobile/` (token, bearer guard, zod schemas, error envelope, rate limiter, transaction service). Shared Mongo queries are extracted from `db-actions.ts` so web and mobile read the same data. The mobile JWT is a separate audience from the web cookie JWT, so neither is accepted in the other's place.

**Tech Stack:** Next.js 15.3.3 route handlers, Mongoose 8, jose 5 (HS256), zod 3.25 via the `zod/v4` subpath (already used by `src/ai/claude.ts`), bcryptjs, Vitest + mongodb-memory-server (new).

**Spec:** `docs/superpowers/plans/2026-10-19-android-voice-budget/README.md` (shared contract)

## Decisions for the user

1. **Delete sync.** Contract has no tombstones, so a row deleted on web never disappears from the phone via `updatedSince`. Recommend: v1 has no tombstones, the app does a full pull (no `updatedSince`) on launch and reconciles. Alternative: add `deletedAt` soft delete (touches web queries).
2. **Token revocation.** 30-day stateless JWT cannot be revoked (password change does not log the phone out). Recommend accepting this for v1; alternative is a `tokenVersion` on User checked per request (one extra read).
3. **Rate-limit store.** Recommend a Mongo TTL collection (works on any host, one atomic upsert). Alternative: Upstash Redis (new service and secret). In-memory is useless on serverless.
4. **Edits.** Contract is create/delete only; a resubmitted `clientId` returns the stored row unchanged. Recommend keeping it immutable in v1 (edit = delete + new `clientId`).
5. **Test dependency pins.** Vitest 5.0.5 and mongodb-memory-server 11.3.0 are the newest I found, but compatibility with Node/TS here is UNVERIFIED. Approve pinning after Task 1 passes, else fall back to Vitest 4.1.x.

## Global Constraints

- Web cookie auth (`verifySession`, `actions.ts`) must behave exactly as today. `src/middleware.ts` already excludes `/api`, so it is not touched.
- Mobile JWT: HS256, secret `JWT_SECRET`, claims `{sub, email, aud: "budgetwise-mobile"}`, 30d. Fail closed (throw) if `JWT_SECRET` is missing or shorter than 32 chars. Never reuse the fallback secret in `src/lib/auth.ts`.
- All data access filters by `userId` taken from the verified token, never from the body or URL.
- Every error uses `{error:{code,message}}`; never leak stack traces or whether an email exists.
- Amounts are positive integers on the mobile path. Legacy web rows may hold decimals or non-integers; the DTO mapper returns them as stored (`Math.round` is NOT applied silently).
- `date` in DTO is `YYYY-MM-DD`. Web rows were saved from a JS `Date` (`add-transaction.tsx` form field), so stored strings are probably full ISO timestamps (UNVERIFIED, check a real row in Task 3). The mapper slices the first 10 chars of an ISO string; the write path stores `YYYY-MM-DD`.
- `'use server'` files may only export async functions, so shared helpers live in plain modules under `src/lib/mobile/`.
- No git operations beyond the commit steps; no production DB access without explicit approval (backfill is run by the user).
- Package manager: npm (`package-lock.json`); ignore `pnpm-lock.yaml`.

## Review Focus

Failure modes the per-task happy-path tests would not catch. Each has a named test.

1. **Concurrent replay.** Two identical batches racing hit E11000 on the unique index; must return the same rows, not 500 or duplicates. Test `concurrent identical batches yield one row each` (Task 7).
2. **Token confusion.** A web cookie JWT sent as Bearer, or a mobile token set as the `session` cookie, must be rejected. Tests `rejects web session JWT as bearer` and `mobile token is not a valid web session` (Task 4).
3. **Cross-user clientId.** User B posting User A's `clientId` must create B's own row and never return A's data. Test `same clientId for two users stays separate` (Task 7).
4. **Legacy rows.** Rows with no `clientId`, `source`, timestamps, or an ISO `date` must neither collide on the partial unique index nor break the DTO. Tests `two legacy rows without clientId coexist` (Task 2) and `maps ISO datetime date to YYYY-MM-DD` (Task 2).
5. **Sync cursor boundary.** `updatedSince` with strict `>` drops rows written in the same millisecond as the cursor; must be `>=` (client dedupes by `id`). Test `includes rows updated exactly at the cursor` (Task 7).

## File Structure

Create:
- `vitest.config.ts`, `tests/helpers/db.ts`, `tests/helpers/request.ts`
- `src/lib/mobile/dto.ts` (wire types + `toTransactionDto`)
- `src/lib/mobile/schemas.ts` (zod request schemas)
- `src/lib/mobile/errors.ts` (`ApiError`, `apiError`, `handle`)
- `src/lib/mobile/token.ts` (`signMobileToken`, `verifyMobileToken`)
- `src/lib/mobile/bearer.ts` (`requireUser`)
- `src/lib/mobile/rate-limit.ts`, `src/lib/models/rate-limit.ts`
- `src/lib/mobile/reference-data.ts` (`getCategoriesForUser`, `getBudgetsForUser`)
- `src/lib/mobile/transactions-service.ts`
- `src/app/api/v1/auth/token/route.ts`, `categories/route.ts`, `budgets/route.ts`, `transactions/route.ts`, `transactions/[id]/route.ts`, `voice/parse/route.ts`
- `scripts/backfillTransactions.ts`
- Tests mirror under `tests/` (`tests/mobile/*.test.ts`, `tests/api/*.test.ts`)

Modify: `package.json` (scripts, devDeps), `src/lib/models/transaction.ts`, `src/lib/types.ts`, `src/app/db-actions.ts`, `.env.local` docs line in `README.md` (env table only).

## Tasks

### Task 1: Test harness

**Files:** Create `vitest.config.ts`, `tests/helpers/db.ts`, `tests/helpers/request.ts`, `tests/smoke.test.ts`; Modify `package.json`.

**Interfaces:**
- Produces `startMongo(): Promise<void>`, `stopMongo(): Promise<void>`, `clearDb(): Promise<void>` (sets `process.env.MONGODB_URI` before `src/lib/db.ts` is imported, then connects mongoose).
- Produces `req(method, path, {token?, body?, ip?}): Request`.

- [ ] Write `tests/smoke.test.ts`: `connects and round-trips a Transaction` (create via model, `find` returns 1).
- [ ] Run `npm i -D vitest@5.0.5 mongodb-memory-server@11.3.0` (versions: https://www.npmjs.com/package/vitest, https://www.npmjs.com/package/mongodb-memory-server). Add script `"test": "vitest run"`. Run `npm test`, expect FAIL (no config/helpers).
- [ ] Implement `vitest.config.ts`: `environment: "node"`, `alias: {"@": "./src"}`, `setupFiles` none, `testTimeout: 30000` (first run downloads a mongod binary; needs network, or set `MONGOMS_SYSTEM_BINARY`, see https://typegoose.github.io/mongodb-memory-server/). Set `JWT_SECRET` to a 40-char test value in `test.env`. `db.ts` sets `MONGODB_URI` from `MongoMemoryServer.create()`.
- [ ] Risk check: `src/lib/db.ts` imports `next/dist/server/config` and is marked `'use server'`. If Vitest fails to import it, alias `@/lib/db` to a test stub exporting `mongoose.connect`; record outcome in the commit message.
- [ ] Run `npm test`, expect PASS.
- [ ] Commit: `git add package.json package-lock.json vitest.config.ts tests` ; `test: add vitest and mongodb-memory-server harness`.

### Task 2: Transaction schema and DTO mapper

**Files:** Modify `src/lib/models/transaction.ts`, `src/lib/types.ts`; Create `src/lib/mobile/dto.ts`; Test `tests/mobile/dto.test.ts`, `tests/mobile/transaction-model.test.ts`.

**Interfaces:**
- Produces in `dto.ts`: `TransactionDto`, `TransactionInput`, `DraftTransactionDto` (exact contract shapes) and `toTransactionDto(doc: LeanTransaction): TransactionDto`.
- `clientId` for legacy rows: mapper returns `clientId: String(doc._id)` when absent (contract requires a string). Marked in code comment.

- [ ] Write tests:
  - `maps ISO datetime date to YYYY-MM-DD`: doc `{date:"2026-10-19T17:30:00.000Z"}` gives `"2026-10-19"`.
  - `legacy row maps source web and synthesized clientId`.
  - `two legacy rows without clientId coexist`: create two docs with no `clientId` and `await TransactionModel.init()`; expect no throw.
  - `duplicate (userId, clientId) rejects with code 11000`.
- [ ] Run `npx vitest run tests/mobile`, expect FAIL.
- [ ] Implement schema: add `clientId: String`, `source: {enum voice|manual|web, default "web"}`, `transcript: String (maxlength 2000)`, `{timestamps: true}`; indexes `{userId:1, clientId:1}` unique with `partialFilterExpression: {clientId: {$type: "string"}}` (https://www.mongodb.com/docs/manual/core/index-partial/ , partial indexes cannot use `$ne`, so `$type` is required) and `{userId:1, updatedAt:1}`. Extend `Transaction` in `types.ts` with optional `clientId, source, transcript, createdAt, updatedAt`.
- [ ] Implement `toTransactionDto` (pure function, ~15 lines).
- [ ] Run tests, expect PASS. Also `npx tsc --noEmit` clean.
- [ ] Commit: `git add src/lib tests/mobile` ; `feat(api): extend Transaction schema for mobile sync`.

### Task 3: Backfill script

**Files:** Create `scripts/backfillTransactions.ts`; Test `tests/mobile/backfill.test.ts`; Modify `package.json` (`"backfill:transactions": "tsx scripts/backfillTransactions.ts"`).

**Interfaces:**
- Produces `backfillTransactions(opts: {apply: boolean}): Promise<{matched: number; modified: number}>`. Dry run is the default; `--apply` is required to write.

- [ ] Write test `sets createdAt/updatedAt from ObjectId and source web, idempotent`: insert raw legacy docs via `collection.insertOne` (bypass mongoose), run with `apply:true` twice, second run `modified === 0`; dry run leaves docs untouched.
- [ ] Run, expect FAIL.
- [ ] Implement with one `updateMany` using an aggregation-pipeline update: `$set` of `createdAt`/`updatedAt` = `$ifNull` existing else `{$toDate:"$_id"}`, `source` = `$ifNull` existing else `"web"`. Filter `{$or:[{updatedAt:{$exists:false}},{source:{$exists:false}}]}`. Never touches `date`, `amount` or `clientId`. Rollback: fields are additive, `$unset` them.
- [ ] Run, expect PASS.
- [ ] Note for the user (Rule 10): read the script, run dry-run first against staging, production run needs explicit approval. During the dry run, print 3 sample `date` values to settle the ISO-vs-`YYYY-MM-DD` question from Global Constraints.
- [ ] Commit: `git add scripts package.json tests/mobile/backfill.test.ts` ; `feat(api): add idempotent transaction backfill script`.

### Task 4: Mobile token and bearer guard

**Files:** Create `src/lib/mobile/errors.ts`, `token.ts`, `bearer.ts`; Test `tests/mobile/token.test.ts`.

**Interfaces:**
- `errors.ts`: `class ApiError extends Error {status: number; code: string}`; `apiError(status, code, message): Response`; `handle(fn: (req: Request, ctx: any) => Promise<Response>)` wraps a handler, maps `ApiError` to the envelope, `ZodError` to 422 `validation_failed`, anything else to 500 `internal_error` (logged via `logger`, message generic).
- `token.ts`: `signMobileToken(user: {id: string; email: string}): Promise<{token: string; expiresAt: string}>`; `verifyMobileToken(token: string): Promise<{userId: string; email: string}>` (throws).
- `bearer.ts`: `requireUser(req: Request): Promise<{userId: string; email: string}>` throws `ApiError(401, "unauthorized", ...)`.

- [ ] Write tests: `round-trips claims sub/email/aud and 30d expiry`; `rejects web session JWT as bearer` (sign `{userId,email}` with same secret, no `aud`); `rejects expired and wrong-alg tokens`; `mobile token is not a valid web session` (decode with the `verifySession` payload rule: `typeof payload.userId === "string"` is false); `throws when JWT_SECRET missing or short`.
- [ ] Run, expect FAIL.
- [ ] Implement with jose `SignJWT().setSubject().setAudience("budgetwise-mobile").setIssuedAt().setExpirationTime("30d")` and `jwtVerify(token, key, {audience, algorithms:["HS256"]})` (https://github.com/panva/jose/blob/v5.x/docs/jwt/verify/functions/jwtVerify.md). Read the secret on each call, not at module load.
- [ ] Run, expect PASS.
- [ ] Commit: `git add src/lib/mobile tests/mobile/token.test.ts` ; `feat(api): mobile JWT and bearer guard`.

### Task 5: Zod schemas

**Files:** Create `src/lib/mobile/schemas.ts`; Test `tests/mobile/schemas.test.ts`.

**Interfaces:**
- Produces `loginSchema`, `transactionInputSchema`, `transactionBatchSchema` (1 to 50 items), `voiceParseSchema`, `updatedSinceSchema`. Import `* as z from "zod/v4"`.
- Rules: `clientId` UUID v4; `amount` positive safe integer; `date` regex `^\d{4}-\d{2}-\d{2}$` plus real-calendar check; `description` 1..200 trimmed; `category` 1..60; `transcript` max 2000 optional; `source` enum voice|manual (mobile cannot post `web`); `voiceParse.transcript` 1..2000, `categories` max 50 items of max 60 chars; `updatedSince` ISO datetime.

- [ ] Write tests: `rejects amount 0, -1, 1.5`; `rejects 2026-02-31`; `rejects 51 items`; `rejects source web`; `accepts valid sample`.
- [ ] Run, expect FAIL. Implement. Run, expect PASS.
- [ ] Commit: `git add src/lib/mobile/schemas.ts tests/mobile/schemas.test.ts` ; `feat(api): request schemas`.

### Task 6: Rate limiter

**Files:** Create `src/lib/models/rate-limit.ts`, `src/lib/mobile/rate-limit.ts`; Test `tests/mobile/rate-limit.test.ts`.

**Interfaces:**
- Produces `rateLimit(key: string, opts: {limit: number; windowSec: number}): Promise<void>` throwing `ApiError(429, "rate_limited", ...)`; sets no header work here, `handle` adds `Retry-After`.
- Model `RateLimit {key: string (unique), count: number, expiresAt: Date}` with TTL index `{expiresAt: 1}, {expireAfterSeconds: 0}` (https://www.mongodb.com/docs/manual/core/index-ttl/).

- [ ] Write tests: `allows limit then blocks limit+1`; `window resets after expiry` (use `vi.useFakeTimers` or write an expired doc); `keys are independent`.
- [ ] Run, expect FAIL.
- [ ] Implement with one atomic `findOneAndUpdate({key, expiresAt: {$gt: now}}, {$inc: {count: 1}}, {upsert...})`. Approach: if no live doc, upsert a fresh window via `$setOnInsert` on a filter that includes the expiry condition; handle E11000 by retrying once. TTL deletion lag (up to about 60s) is why the filter checks `expiresAt`.
- [ ] Run, expect PASS.
- [ ] Commit: `git add src/lib tests/mobile/rate-limit.test.ts` ; `feat(api): mongo-backed rate limiter`.

### Task 7: Transaction service and routes

**Files:** Create `src/lib/mobile/transactions-service.ts`, `src/app/api/v1/transactions/route.ts`, `src/app/api/v1/transactions/[id]/route.ts`; Test `tests/api/transactions.test.ts`.

**Interfaces:**
- Consumes `requireUser`, `handle`, `transactionBatchSchema`, `updatedSinceSchema`, `toTransactionDto`.
- Produces `saveBatch(userId: string, items: TransactionInput[]): Promise<TransactionDto[]>` (order preserved), `listSince(userId: string, since?: Date): Promise<TransactionDto[]>`, `removeOne(userId: string, id: string): Promise<boolean>`.
- Routes: `export const GET`, `POST` in `route.ts`; `export const DELETE = handle(async (req, {params}: {params: Promise<{id: string}>}) => ...)` (params is a Promise in Next 15, https://nextjs.org/docs/app/api-reference/file-conventions/route). Add `export const dynamic = "force-dynamic"`.

- [ ] Write tests (call handlers directly with `req(...)`, seeded user and token):
  - `POST saves and returns DTOs with ids and timestamps`
  - `resubmitting same clientId returns existing row, count stays 1`
  - `same clientId for two users stays separate`
  - `concurrent identical batches yield one row each` (`Promise.all` of two `POST`s with the same 5 items; count 5, both responses 200, equal ids)
  - `GET without updatedSince returns all, newest updatedAt last-write wins ordering stable`
  - `includes rows updated exactly at the cursor`
  - `GET excludes other users' rows`
  - `DELETE own row 204; other user's row 404; bad ObjectId 404 not 500`
  - `no token 401; 51 items 422; malformed JSON 400`
- [ ] Run, expect FAIL.
- [ ] Implement `saveBatch`: `bulkWrite` of `updateOne({userId, clientId}, {$setOnInsert: {...fields, source}}, {upsert: true})` (never overwrites), catch E11000 and retry once, then `find({userId, clientId: {$in}})` and reorder to input order. `listSince` uses `updatedAt: {$gte: since}` sorted by `updatedAt` then `_id`. Mongoose 8 adds timestamps to `bulkWrite` upserts; the first test asserts `createdAt` is set, which verifies it (UNVERIFIED until run). `removeOne` validates `mongoose.isValidObjectId` first.
- [ ] Run, expect PASS.
- [ ] Commit: `git add src/lib/mobile src/app/api/v1/transactions tests/api/transactions.test.ts` ; `feat(api): idempotent transaction sync endpoints`.

### Task 8: Categories and budgets

**Files:** Create `src/lib/mobile/reference-data.ts`, `src/app/api/v1/categories/route.ts`, `src/app/api/v1/budgets/route.ts`; Modify `src/app/db-actions.ts`; Test `tests/api/reference.test.ts`.

**Interfaces:**
- Produces `getCategoriesForUser(userId: string): Promise<string[]>` (logic moved verbatim from `getCategories`, including the non-ObjectId guest branch) and `getBudgetsForUser(userId: string): Promise<{category: string; limit: number}[]>`.
- `db-actions.getCategories` now calls `getCategoriesForUser` (web behavior unchanged).

- [ ] Write tests: `categories returns global plus user-specific, excludes other users'`; `budgets returns only caller's, as {category, limit}`; `401 without token`.
- [ ] Run, expect FAIL. Implement and refactor `getCategories`. Run all tests, expect PASS; `npx tsc --noEmit` clean.
- [ ] Commit: `git add src tests/api/reference.test.ts` ; `feat(api): categories and budgets endpoints`.

### Task 9: Auth token endpoint

**Files:** Create `src/app/api/v1/auth/token/route.ts`; Test `tests/api/auth-token.test.ts`.

**Interfaces:**
- Consumes `loginSchema`, `signMobileToken`, `rateLimit`, `UserModel`, bcryptjs.
- Produces `POST` returning `{token, expiresAt, user:{id,email,fullName}}`.

- [ ] Write tests: `valid credentials 200 with verifiable token`; `wrong password and unknown email both 401 with identical body`; `unknown email still runs bcrypt compare` (spy on `bcrypt.compare`, called once with a dummy hash); `11th attempt for same ip+email within window is 429 with Retry-After`; `user without password (OAuth-style) 401`; `email matched case-insensitively` (lowercase+trim before lookup; confirm existing signup stores lowercase, otherwise match with collation, UNVERIFIED).
- [ ] Run, expect FAIL.
- [ ] Implement: rate limit keys `auth:ip:<ip>` (30 per 15 min) and `auth:email:<sha256(email)>` (10 per 15 min). IP from first `x-forwarded-for` entry, UNVERIFIED that the deploy host strips spoofed values (`vercel.json` suggests Vercel, which sets it; confirm). Dummy-hash compare for missing user keeps timing flat.
- [ ] Run, expect PASS.
- [ ] Commit: `git add src/app/api/v1/auth tests/api/auth-token.test.ts` ; `feat(api): mobile token endpoint`.

### Task 10: Voice parse route

**Files:** Create `src/app/api/v1/voice/parse/route.ts`; Test `tests/api/voice-parse.test.ts`.

**Interfaces:**
- Consumes (owned and defined by WS4 Task 10): `parseVoiceStatement(input: {transcript: string; today: string; categories: string[]}): Promise<{drafts: VoiceDraft[]}>` exported from `@/ai/flows/parse-voice-statement` (WS4 Task 10), which wraps `generateStructured`.
- Produces `POST` returning `{drafts}`.

- [ ] Write tests with `vi.mock("@/ai/flows/parse-voice-statement")`: `returns drafts`; `401 without token`; `422 for empty or 2001-char transcript`; `31st call in an hour is 429 (per user)`; `parser throwing yields 500 with generic message, no upstream text`; `confidence outside 0..1 or amount not positive integer from parser is dropped, not forwarded` (route re-validates drafts with a zod schema).
- [ ] Run, expect FAIL.
- [ ] Implement: `requireUser`, `rateLimit("voice:"+userId, {limit: 30, windowSec: 3600})`, validate, call parser, filter drafts through `draftSchema`. Do not log the transcript (financial and personal text).
- [ ] Run, expect PASS.
- [ ] Commit: `git add src/app/api/v1/voice tests/api/voice-parse.test.ts` ; `feat(api): voice parse route`.

### Task 11: Web source tagging and regression

**Files:** Modify `src/app/db-actions.ts` (`addTransaction`); Test `tests/web/add-transaction.test.ts`, `tests/web/middleware-matcher.test.ts`.

**Interfaces:**
- `addTransaction` signature unchanged; it now sets `source: "web"` after spreading the client payload so the client cannot override it, and strips any client-supplied `clientId`, `transcript`.

- [ ] Write tests (mock `@/lib/auth` `verifySession`, `next/cache`): `addTransaction stores source web even if caller passes voice`; `addTransaction ignores caller clientId`; `middleware matcher does not match /api/v1/transactions` (compile `config.matcher[0]` with `new RegExp("^"+...+"$")`-style check against `/api/v1/transactions` and `/dashboard`).
- [ ] Run, expect FAIL for the first two. Implement. Run full `npm test`, expect PASS; run `npm run lint` and `npx tsc --noEmit`.
- [ ] Add env note to `README.md`: `JWT_SECRET` (>=32 chars, required for mobile), no new variables otherwise.
- [ ] Commit: `git add src/app/db-actions.ts README.md tests/web` ; `feat(web): tag web-created transactions with source`.
