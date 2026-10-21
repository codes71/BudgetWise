# BudgetWise Android Voice Budget: Master Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Each workstream file below is a self-contained plan; this README is the spec and the shared contract they all argue from.

**Goal:** An Android companion app for BudgetWise where the user speaks Burmese about money ("ဒီနေ့ မနက် ကော်ဖီ သုံးထောင် ပေးခဲ့တယ်"), the phone transcribes it on-device with the Raj model, turns it into budget entries, and saves them to BudgetWise after one tap of confirmation.

**Architecture:** A Kotlin/Compose app in `android/` inside this repo. Audio never leaves the phone by default: Raj (Burmese ASR by Aung Zay Thant, Qwen3-ASR based, GGUF via llama.cpp) runs locally, a pure-Kotlin parser turns the transcript into draft transactions, and an offline-first outbox syncs confirmed entries to a new `/api/v1` REST API on the existing Next.js backend. A server-side Claude parser exists only as an opt-in fallback.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, WorkManager, llama.cpp (NDK/JNI), Next.js 15 route handlers, Mongoose, zod, jose, Vitest, GitHub Actions.

**Spec:** this file.

**Model:** [aungzaythant/Raj-1.7B](https://huggingface.co/aungzaythant/Raj-1.7B) (Apache-2.0, fine-tuned from Qwen/Qwen3-ASR-1.7B, GGUF Q8_0 about 1.83 GB), with the smaller [Raj-0.6B](https://huggingface.co/aungzaythant/Raj-0.6B) and the earlier Nemotron-based [Raj-0](https://huggingface.co/aungzaythant/Raj-0) as candidates. WS3 picks the variant after an on-Mac spike.

## How this plan was made

Five sub-agents drafted one workstream each, in parallel, against the shared contract below. The orchestrator then cross-checked names and dependencies and wrote this README. Each workstream is sized so one agent (or one person) can own it.

| # | Workstream | Plan | Owns |
|---|---|---|---|
| WS1 | Backend mobile API | [ws1-backend-mobile-api.md](ws1-backend-mobile-api.md) | `/api/v1` route handlers, bearer JWT, schema changes, test harness |
| WS2 | Android app | [ws2-android-app.md](ws2-android-app.md) | `:app`, `:core:data`, `:feature:voice` (UI, Room, sync, mic capture, review sheet) |
| WS3 | On-device ASR (Raj) | [ws3-on-device-asr-raj.md](ws3-on-device-asr-raj.md) | `:asr` (llama.cpp JNI, model download and verify, host harness) |
| WS4 | Burmese budget NLU | [ws4-burmese-budget-nlu.md](ws4-burmese-budget-nlu.md) | `:nlu` (numbers, dates, categories, segmentation), golden corpus, server fallback flow |
| WS5 | Quality and release | [ws5-quality-release.md](ws5-quality-release.md) | eval set and scorecard, CI, milestones, privacy, release |

## User flow

1. Hold the mic button and speak (Burmese, English words mixed in are fine).
2. `:feature:voice` records 16 kHz mono audio and hands a `FloatArray` to `SpeechToText.transcribe`.
3. Raj returns Burmese text (`AsrResult`).
4. `BurmeseBudgetParser.parse` turns the text into zero or more `DraftTransaction`s.
5. A review sheet lists each draft (amount, type, category, date, description). The user edits or confirms with one tap. Auto-save for high-confidence drafts is a setting, off by default.
6. Confirmed drafts go into Room with a fresh `clientId` and an outbox row.
7. `SyncWorker` posts them to `POST /api/v1/transactions` when online. Retries are safe because the server dedupes on `clientId`.
8. The web dashboard shows the same entries.

## Shared contract

All workstreams use these exact names. A change here is a change to every plan.

### Wire types (JSON, camelCase)

- `TransactionDto { id: string; clientId: string /* UUIDv4 from device */; type: "expense" | "income"; amount: number /* positive integer kyat */; category: string; description: string; date: string /* YYYY-MM-DD */; source: "voice" | "manual" | "web"; transcript?: string; createdAt: string; updatedAt: string }`
- `TransactionInput` = `TransactionDto` without `id`, `createdAt`, `updatedAt`.
- `DraftTransactionDto { type: "expense" | "income"; amount: number; category: string; description: string; date: string; confidence: number /* 0..1 */ }`
- Errors: `{ error: { code: string, message: string } }` with 400, 401, 404, 422, 429, 500.

### REST API (WS1 builds, WS2 consumes)

Base `/api/v1`. `Authorization: Bearer <token>` on every route except `auth/token`. Token: JWT HS256 signed with `JWT_SECRET`, claims `{ sub: userId, email, aud: "budgetwise-mobile" }`, 30-day expiry. Web cookie sessions keep working unchanged.

| Method and path | Body | Response |
|---|---|---|
| `POST /auth/token` | `{ email, password }` | `{ token, expiresAt, user: { id, email, fullName } }` |
| `GET /categories` | | `{ categories: string[] }` |
| `GET /transactions?updatedSince=ISO` | | `{ transactions: TransactionDto[] }` |
| `POST /transactions` | `{ transactions: TransactionInput[] }` (max 50) | `{ saved: TransactionDto[] }`, idempotent on (userId, clientId) |
| `DELETE /transactions/:id` | | 204 |
| `GET /budgets` | | `{ budgets: { category, limit }[] }` |
| `POST /voice/parse` | `{ transcript, today, categories }` | `{ drafts: DraftTransactionDto[] }`, opt-in cloud fallback |

### Android modules (Kotlin, Gradle Kotlin DSL, in `android/`)

- `:asr` (WS3): `interface SpeechToText { suspend fun transcribe(audio: FloatArray): AsrResult }` where audio is 16 kHz mono in -1..1; `data class AsrResult(val text: String, val modelId: String, val audioMs: Long, val decodeMs: Long)`; `class RajSpeechToText : SpeechToText`; `interface ModelManager { val state: StateFlow<ModelState>; val activeVariant: StateFlow<RajVariant?>; suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean = false); suspend fun delete(variant: RajVariant); fun filesFor(variant: RajVariant) }` with `DefaultModelManager` as the implementation; `enum class RajVariant { RAJ_0_6B, RAJ_1_7B }` carrying `displayName`, `downloadBytes`, `modelId`; `sealed interface ModelState { Missing; Downloading(progress); Ready(path /* variant directory with model + mmproj GGUF */); Failed(reason) }`. `transcribe` returns `text = ""` for silence, throws `AsrException` on failure, and coroutine cancellation aborts the native decode.
- `:nlu` (WS4, pure JVM): `object BurmeseBudgetParser { fun parse(transcript: String, today: LocalDate, categories: List<String>): List<DraftTransaction> }`; `data class DraftTransaction(val type: TxType, val amountKyat: Long, val category: String, val description: String, val date: LocalDate, val confidence: Float, val sourceSpan: IntRange)`; `enum class TxType { EXPENSE, INCOME }`; `object BurmeseNumber { fun parse(text: String): Long? }`.
- `:core:data`, `:feature:voice`, `:app` (WS2): consume the two modules above through fakes until WS3 and WS4 land.

### Data facts the plans rely on

- Categories (global, exact strings): Groceries, Utilities, Entertainment, Transport, Housing, Health, Food and Drink Item, Other.
- Amounts are stored as plain numbers. For this user they are kyat. Currency is a display toggle on the web (INR/MMK).
- Existing web transactions have no `clientId`. The unique index on `{ userId, clientId }` must be partial so they stay valid.
- The web form already stores `date` as `yyyy-MM-dd` (`src/components/dashboard/add-transaction.tsx`, `format(data.date, 'yyyy-MM-dd')`), so the DTO date needs no conversion for rows created on the web.

### Contract amendment from WS3 execution

- `AsrResult` gains a fifth, defaulted field `truncatedSegments: Int = 0` (segments that stopped at the token limit, so their text may be cut short). Four-argument constructor calls still compile.

### Contract amendments from the cross-review

The five drafts were checked against each other. These resolutions win over anything a workstream file says.

1. **Deletes reaching the phone.** No tombstones in v1. The app does a full pull (no `updatedSince`) on launch and on pull-to-refresh and reconciles by `clientId`. Deleting a row that never synced just drops its outbox entry; deleting a synced row uses the server `id` from the `saved` response. (WS1 Decision 1, WS2.)
2. **Edits.** Synced rows are immutable in v1: edit means delete plus a new row with a new `clientId`. No `PUT` route. (WS1 Decision 4, WS2 Decision 6.)
3. **Server parse function.** WS4 Task 10 owns `parseVoiceStatement(input): Promise<{ drafts: VoiceDraft[] }>` in `src/ai/flows/parse-voice-statement.ts`; WS1 Task 10's route calls it.
4. **Web test harness.** WS1 Task 1 owns Vitest (config, helpers, `test` script). WS4 and WS5 reuse it and do not add it again.
5. **Golden corpus.** One file, `android/nlu/src/test/resources/golden/burmese-budget-corpus.json`, schema defined in WS4 Task 1 (`id`, `transcript`, `today`, `tags`, `expected[]` with `type`, `amountKyat`, `category`, `date`, optional `description`, `fallback`). WS5 reads it, never edits it.
6. **Draft rules.** `DraftTransaction.category` is always one of the categories passed in (else `Other`). `sourceSpan` indexes the original transcript; drafts that came from the server use `IntRange.EMPTY`.
7. **Account deletion.** Needed only if the app goes to Play (Data safety form). WS1 adds `DELETE /api/v1/account` before any Play submission; not part of the sideload milestones.
8. **Android basics.** Package root `app.budgetwise`. JUnit 4 for Android modules (Robolectric and Compose need it), JUnit 5 for pure-JVM `:nlu`. `:app` sets `packaging { jniLibs { useLegacyPackaging = true } }` for llama.cpp's runtime-loaded CPU backends. CI checks out submodules recursively and installs NDK 29.0.13113456.
9. **Cloud parse setting.** WS2 exposes `cloudParseEnabled`, default `false`. When on, only transcript text (never audio) goes to `/voice/parse`.
10. **Default branch** of this repo is `master`, not `main`.

## Key research findings (WS3)

- Raj-0.6B ships as two GGUF files (Q8_0 model 639 MB plus audio projector 208 MB); Raj-1.7B is 1.83 GB plus 348 MB. Only Q8_0 is published. Checked against the Hugging Face API on 2026-10-19.
- llama.cpp gained Qwen3-ASR audio support in [PR #19441](https://github.com/ggml-org/llama.cpp/pull/19441) (merged 2026-04-12). Raj's own `release.json` pins llama.cpp `fb34fc2`, and WS3 builds against that exact commit.
- No streaming: the user holds the button, speaks, and gets the text when they let go.
- Estimated wait for a 4 second phrase on a mid-range phone CPU: 5 to 9 s with Raj-0.6B, 12 to 22 s with Raj-1.7B. These are estimates; the spike measures them. The Qwen tokenizer spends about 1.6 tokens per Burmese character, so text generation, not listening, is the slow part.
- Fallback if Raj is too slow or inaccurate: the earlier Raj-0 (streaming, Burmese only) through NeMo-Speech.cpp.
- 2026-10-21: Raj-0.6B works on the phone but the user judged it inaccurate; the model is being replaced. Every measurement and the candidate bake-off are in [ws3-voice-model-findings.md](ws3-voice-model-findings.md).

## Milestones

| Milestone | Depends on | Owners | Demo / exit criteria |
|---|---|---|---|
| M0 Spike go/no-go | none | WS3, WS5 | Raj-0.6B decodes 20 recorded budget phrases on the Mac (G1: SER under 40%, at least 16 of 20 amounts exact, no loops, silence gives empty text) and on the user's phone over adb (G2: median real-time factor at most 1.0 on a 4 GB phone, peak memory at most 1.6 GB). |
| M1 API and app shell sync | none | WS1, WS2, WS5 (CI) | A manual entry on the phone appears on the web dashboard after sync, and the reverse, over `adb reverse tcp:9002 tcp:9002`. Web and Android CI green. |
| M2 On-device transcript | M0, M1 | WS3, WS2 | Hold the mic, speak, see the Burmese transcript with its latency. Model download resumes after interruption. |
| M3 Voice to ledger | M1, M2, WS4 corpus | all | Speak, see drafts, confirm in the review sheet, the row syncs to the web. |
| M4 Quality thresholds | M3, eval set recorded | WS5 (WS3, WS4 tune) | Scorecard passes on the held-out split: amount exact at least 95%, whole statement correct at least 85%, SER at most 15% (proposed, see Decisions). |
| M5 Release | M4, hosting decided | WS5, WS1 | Signed APK from GitHub Releases installs on the user's phone and syncs to the hosted backend away from home Wi-Fi. |

## Decisions for the user

Each workstream file lists its own decisions with a recommendation. These are the ones that change the plan's shape; the recommendation is in bold.

1. **Default voice model:** **Raj-0.6B by default, Raj-1.7B offered only on phones with at least 8 GB RAM that pass the speed gate.**
2. **Who records the spike and eval clips** (about 20 phrases for M0, about 100 for M4, at least two native speakers, some with English words like Grab, taxi, KBZPay) and **who reviews the Burmese golden corpus**: **you, plus one friend.**
3. **Backend for use away from home:** **Vercel Hobby plus MongoDB Atlas free tier** (vs Firebase App Hosting).
4. **Distribution:** **signed APK from GitHub Releases first**; Play closed testing later needs 12 testers for 14 days.
5. **Cloud fallback:** **opt-in, off by default, transcript text only, never audio.**
6. **Ship thresholds** in M4: **accept the proposed numbers, revisit after the first scorecard.**
7. **Eval audio storage:** **outside git (private Drive), only the manifest and checksums in the repo.**
8. **Quantization:** **ship the published Q8_0 unless the spike shows a Q4 requant within 3 SER points**; ask the Raj author for BF16 weights.

## Execution order

1. WS3 Task 1 (Mac spike with Raj) first. It is the go/no-go gate for the whole plan; if Raj cannot transcribe short Burmese money phrases well enough, the plan changes before any Android code is written.
2. WS1 and WS4 in parallel (no shared files). WS2 starts its skeleton with fakes at the same time.
3. WS3 Android integration once the spike passes.
4. WS5 runs alongside: CI first, eval set as soon as WS3 and WS4 have something to score.
