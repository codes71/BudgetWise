# Pocket Peek

Pocket Peek is an Android and web app for recording income and expenses, planning monthly budgets, and seeing where money goes. Optional receipt scanning and voice parsing help prepare entries for the user to review.

## Current migration status

This branch replaces server-side Firestore persistence with MongoDB Atlas and rebrands the Android app as **Pocket Peek**, package `app.pocketpeek`. The source changes do not establish that the production migration, deployment, or Play release has completed. Keep the source Firestore database intact until a private snapshot, destination parity checks, account checks, and the production cutover are verified.

The repository name and existing production API host remain unchanged. The new Android package installs separately from legacy `app.budgetwise`; it does not automatically inherit the old app's local-only entries, session, or downloaded models. Signing in restores data that the existing account has synced to the backend.

## Repository layout

- `apps/web/`: the Next.js web app and `/api/v1` mobile API, deployed on Vercel. Run npm commands below from this directory.
- `apps/android/`: the Android Gradle project. Run `./gradlew` here.
- `brand/`: logo and icon sources shared by both apps.
- `docs/`: release guidance, architecture notes, plans, and historical evidence.
- `.github/workflows/`: CI, deployment, and Android release workflows.

See [docs/repo-layout.md](docs/repo-layout.md) for the layout and workflow inventory.

## Architecture

Android keeps an account-scoped Room cache and calls the HTTPS backend API. The API and web server use the MongoDB Node.js driver through `apps/web/src/lib/mongodb.ts`; database credentials stay on the server and never belong in the Android binary or browser bundle.

- **Data:** MongoDB collections store users, email claims, transactions, budgets, monthly plans, categories, and rate-limit windows.
- **Authentication:** the application's own email/password flow uses bcrypt password hashes and Jose-signed session/bearer tokens. Firebase Authentication and Google sign-in are not implemented. Either can be evaluated separately without changing the database again.
- **Account safety:** account-owned mutations run in MongoDB transactions. Each touches the account root so a concurrent deletion invalidates the writer. Password-confirmed deletion locks the account, removes its owned runtime and migrated companion records, and releases only its own email claims.
- **AI:** optional scan, voice, categorization, and spending-suggestion flows call Claude first, with the configured OpenAI fallback. Database choice does not select an AI provider. Android recognizes audio locally and sends recognized text for parsing.
- **UI:** Next.js App Router, React, TypeScript, Tailwind, Radix/Shadcn components, and Recharts. Android uses Jetpack Compose.
- **Historical tooling:** Firebase packages and Firestore scripts remain for snapshot/export, migration verification, and historical tests. Their presence does not mean the active runtime uses Firebase Authentication or Firestore listeners.

## Getting started

Use Node.js and npm supported by the installed dependencies. Node 22 or 24 is suitable for the retained Firebase tooling. MongoDB must support multi-document transactions, so use Atlas or a local replica set, not a standalone `mongod`.

```bash
git clone https://github.com/codes71/BudgetWise.git
cd BudgetWise/apps/web
npm ci
```

### Runtime environment

Create an ignored `apps/web/.env.local` using private values supplied through the established credential workflow. Never put real credentials in commands, documentation, source control, or public artifacts.

```dotenv
MONGODB_URI=<private Atlas connection URI or local replica-set URI>
MONGODB_DB=pocketpeek_dev
JWT_SECRET=<existing secret of at least 32 characters>
ANTHROPIC_API_KEY=<optional private AI key>
OPENAI_API_KEY=<optional private fallback key>
# OPENAI_MODEL=<optional fallback model>
```

`MONGODB_DB` is explicit; the runtime does not silently select a database from a URI or fall back to Firestore. Preserve the existing `JWT_SECRET` during migration so existing sessions and bearer tokens retain their signature identity. Preserve account IDs and bcrypt hashes as well.

The driver reuses a bounded connection pool in warm Vercel functions. Index creation is an explicit setup step using `ensureIndexes()` from `src/lib/mongodb.ts`, after migration parity checks and before enabling traffic. It is not an automatic runtime migration. Duplicate email or transaction-client identities must stop the cutover for review rather than discard records.

AI requests ask Claude first. On provider failure, timeout, missing configuration, or an invalid response shape, the same request may go to OpenAI when configured and enough time remains. Refusals are final. OpenAI requests set `store: false`. The phone AI routes have a 50-second request budget. See `src/ai/generate.ts`; do not treat an API option as proof of every provider's retention policy.

### MongoDB data layout

Existing Firestore document IDs remain strings, including accounts originally copied from MongoDB. New accounts use UUIDs. Do not convert existing IDs to fresh MongoDB ObjectIds.

| Collection | Identity and contents |
|---|---|
| `users` | `_id` is the existing account ID; email, normalized email, bcrypt hash, profile, timestamps, and any deletion lock |
| `userEmails` | `_id` is `sha256(emailLower)` and `userId` owns the claim |
| `transactions` | `_id` is `userId/originalRecordId`; `userId` scopes access, while the API keeps the original leaf ID |
| `budgets`, `budgetMonths`, `userCategories` | account-scoped `_id` and `userId`; monthly plan IDs keep `YYYY-MM` |
| `categories` | shared category IDs/slugs |
| `rateLimits` | `_id` is `sha256(key)`; expired windows reset on the next request, with no automatic TTL deletion |
| `_firestoreArchive`, `_firestoreTypes` | lossless migration companions for unmapped documents and Firestore-specific type precision; account-owned companions carry `ownerId` |

### Development and verification

```bash
npm run dev
```

This starts Next.js on port 9002. It does not start a Genkit server. Check whether another worktree owns the port before starting a server. Genkit scripts and packages are retained legacy tooling, not a required production build step.

`npm run seed:categories` inserts missing shared categories and preserves existing ones. Read the script and verify the destination database before running it; production seeding is a separate write operation.

```bash
npm test
npx tsc --noEmit
npm run build
```

`npm test` runs the MongoDB runtime and historical Firestore-copy Vitest projects. Runtime tests start an owned MongoDB replica set on dynamic ports with a unique `test-pocketpeek-*` database, and stop it afterward. `npx vitest run --project mongo` runs only native runtime tests. The legacy-copy project uses a separate emulator-only Firestore project and preserves any shared emulator. Never point cleanup at Atlas or a shared development database. TypeScript has an existing diagnostic baseline; compare new diagnostics rather than claiming the repository is clean.

For a production build, `npm run build` does not need a database connection. `npm start` runs the built app and its database-backed routes need the private runtime configuration.

### Firestore migration and rollback

The source Firestore client in `src/lib/firestore.ts`, Firebase dependencies, and `scripts/copyMongoToFirestore.ts` are retained for historical export/verification. The old copy direction is MongoDB to Firestore; it is not the current Atlas migration or a live synchronization mechanism. Do not run its `--write` mode as part of this migration.

The production migration needs an immutable private source snapshot, stable account/record IDs and hashes, source-to-destination counts and content parity, required indexes, an interval with writes quiesced for the final copy, and independent API/account verification after cutover. MongoDB must be verified before the active deployment changes. Keep Firestore intact as the rollback source. Once MongoDB accepts new writes, switching back to the old source alone would lose those new writes; reconcile them before a rollback.

### Phone API: create account
`POST /api/v1/auth/register` makes the same account as the web signup and signs it in, so the phone can create an account without the web.

- Body: `{ "email", "password", "fullName"? }`. Password at least 6 characters; the name defaults to the part of the email before `@`.
- 201 returns `{ token, expiresAt, user: { id, email, fullName } }`, the same token as `POST /api/v1/auth/token`.
- Errors: 409 `email_taken` (any letter case), 422 `validation_failed`, 429 `rate_limited` after 20 sign-ups per IP per hour.

### Phone API: scan
`POST /api/v1/scan` reads a shop receipt, a KBZPay (KPay) payment slip or a screenshot of the KPay history list, and returns the entries in it. Nothing is saved: the phone shows the entries for review, then saves the chosen ones with `POST /api/v1/transactions` (adding `source`, and a new `clientId` for entries that have none).

- Auth: `Authorization: Bearer <token>` from `POST /api/v1/auth/token`. 20 scans per user per hour.
- Body: `{ "image": "<base64>", "mediaType": "image/jpeg" | "image/png" | "image/webp" }`. Plain base64 without a `data:` prefix (line breaks are ignored), at most 3 MB decoded, which keeps the request under Vercel's 4.5 MB body limit. The phone shrinks photos first (2000 px on the long edge, JPEG quality 90).
- Errors, in the usual `{ "error": { "code", "message" } }` shape: 401 `unauthorized`, 413 `image_too_large`, 422 `validation_failed` (missing field or another media type) or `invalid_image` (not base64, or the bytes are not the declared type), 429 `rate_limited` with `Retry-After`, 422 `scan_declined` when the model declines the image (final, so the phone should not resend it), 502 `scan_failed` when neither Claude nor the OpenAI fallback can read it, 504 `scan_timeout` when the 50 s ran out.
- `kind` is `receipt`, `payment`, `history` or `unknown`. `unknown` has no entries and a `reason`: `not_money` (not a receipt or KPay screen at all, such as a photo of a computer screen), `unclear` (one that cannot be read), `multiple` (several receipts in one photo), `unsupported` (another wallet or bank app, such as WavePay) or `foreign_currency` (with `currency`, for example `"THB"`; amounts are never passed on as kyat). A receipt, slip or history with nothing usable left is `unknown` with `unclear`, except a receipt whose total was read: it comes back with its `total` and no entries. A receipt gives one expense per category, with its line items in `items`; a slip gives one entry; a history screenshot gives one entry per transaction (received is income, paid or sent is expense).
- Android shows separate cards for several receipts, unsupported wallets, foreign currency and a readable total without items. A total-only receipt can be reviewed as one expense, assigned a category and explicitly saved, with no invented items; manual entry remains available to correct the amount. Connection failures retry the kept photo, while retaking drops the old result. Foreign amounts are never offered as kyat.
- Amounts are whole kyat. On a receipt, a line read as negative (a discount) is left out, and a negative entry (a discount on the whole bill) comes off the largest entry. Entries over the printed total are brought down only by exactly a left-out discount line or one tax line the prices already included. Categories are the user's own, else `Other`; income is always `Income`. Entries whose amount cannot be read are left out, not guessed.
- Dates: a receipt or slip with no readable date gets today's date in Myanmar and `"dateGuessed": true`, so the review screen can point it out. A history row without a readable date is left out; one the model put in this year in a later month than now (late December rows scanned in January) is moved to last year.
- An entry with a KPay transaction id gets `reference` and a fixed `clientId` (sha256 of `kpay:` + reference, shaped as a UUID v4), so saving the same transaction twice stores it once.

```json
{
  "kind": "history",
  "entries": [
    { "type": "income", "amount": 150000, "category": "Income", "description": "ဦးမောင်မောင်", "date": "2026-10-25",
      "reference": "01003456789012345678", "clientId": "8757c8d9-1367-4ee0-b09b-80b464aba7a0" },
    { "type": "expense", "amount": 2500, "category": "Other", "description": "Yangon Bakehouse", "date": "2026-10-23" }
  ]
}
```

### Phone API: voice
`POST /api/v1/voice/parse` turns what the phone's on-device speech models heard into entries to review, or a question about the user's spending. Nothing is saved or answered here: the phone saves the chosen entries with `POST /api/v1/transactions` and answers questions from its own data. No audio is sent.

- Auth: `Authorization: Bearer <token>`. 60 requests per user per hour, counted only for valid requests.
- Body: `{ "transcripts": ["...", "..."] }`, 1 to 3 transcripts of the same utterance from different speech models (each at most 500 characters after trimming, at least one non-empty). The AI compares them, since each model makes different mistakes.
- Errors: 401 `unauthorized`, 422 `validation_failed`, 429 `rate_limited` with `Retry-After`, 422 `parse_declined` when the model declines, 502 `parse_failed` when neither Claude nor the OpenAI fallback answers usefully, 504 `parse_timeout` when the 50 s ran out.
- Response: `intent` is `add`, `question` or `other`; `heard` is what the AI understood, in Burmese. `add` carries `entries` shaped like the scan route's (whole kyat, the user's categories else `Other`, income is `Income`, `dateGuessed: true` with today's date when no date was said, no `items`, `reference` or `clientId`). An `add` with no valid entry becomes `other`.
- `question` is `{ "metric": "list" | "total" | "over_budget", "category": string | null, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }` with `entries: []`. A question with an impossible period (not a real date, `from` after `to`, before 2000, after tomorrow) or an unknown category is dropped and the intent becomes `other`.

```json
{
  "intent": "add",
  "heard": "ဒီနေ့ ကော်ဖီ သုံးထောင်",
  "entries": [{ "type": "expense", "amount": 3000, "category": "Food", "description": "ကော်ဖီ", "date": "2026-10-30" }],
  "question": null
}
```

### Deployment

Vercel hosts `apps/web`. Configure `MONGODB_URI`, `MONGODB_DB`, and the existing `JWT_SECRET` on the server, plus optional AI keys. Keep preview and disposable-test credentials separate from production. A source change or successful local build is not a deployment readback.

For this migration, verify the actual Vercel project, database, source snapshot, candidate deployment, and write-cutover plan before promotion. No separate `genkit:build` step is required for the active Claude/OpenAI flows.

## Contributing

Use an isolated worktree, preserve unrelated changes, and follow `CLAUDE.md` for verification and release boundaries. The GitHub repository remains `codes71/BudgetWise`.

## License

MIT License. See [LICENSE](LICENSE).
