# WS5 Quality, Integration and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the voice-to-transaction pipeline is accurate enough to trust with money, keep it that way with CI, wire WS1-WS4 together on a milestone ladder, and ship a signed APK to the user first.

**Architecture:** A host-side Python eval harness (`eval/voice/`) records ground truth (reference transcript + expected drafts), runs Raj via llama.cpp on a Mac, shells out to a small JVM CLI that wraps the WS4 parser, and prints a scorecard with go/no-go gates. Two GitHub Actions workflows (web, android) run only deterministic, model-free checks. Release is a tag-triggered workflow that builds a signed APK onto GitHub Releases.

**Tech Stack:** Python 3.12 (pytest, jiwer-free custom metrics), llama.cpp (`llama-cli` or `llama-server`), Kotlin/JVM CLI, GitHub Actions, Vitest, Gradle, Firebase Crashlytics or Sentry (decision below).

**Spec:** `docs/superpowers/plans/2026-10-19-android-voice-budget/README.md` (shared contract)

## Decisions for the user

1. **Raw audio storage.** Recommend: keep `eval/voice/clips/` out of git (gitignored), store clips in a private Google Drive folder, commit only `manifest.jsonl` (transcripts + expected drafts, no audio) and a `clips.sha256` file. Why: friends' voices are biometric-ish personal data, git history is permanent, and ~100 short 16 kHz WAVs are well under 100 MB, not worth an LFS quota. Alternative: Git LFS in a private repo. Which?
2. **Backend hosting for off-network use.** Recommend: Vercel (Hobby) + MongoDB Atlas M0. Why: `vercel.json` already exists, Next.js is first-class, Atlas free tier is enough for one user. Firebase App Hosting is the other candidate (`apphosting.yaml` exists, `maxInstances: 1`), but needs Blaze billing. Hobby plan is non-commercial only. Which?
3. **Distribution first step.** Recommend: sideloaded signed APK from GitHub Releases (private repo, release asset) for months 1-2; start Play closed testing in parallel only if you want a Play listing, because a new personal account needs 12 testers opted in for 14 continuous days before production access (see Task 11). Which?
4. **Crash reporting.** Recommend: Sentry free tier (no Google Play Services dependency, works for sideloaded builds, stack traces only, no audio, no transcripts). Alternative: Firebase Crashlytics. Which?
5. **Ship thresholds.** The numbers in Task 6 (amount exact >= 95%, statement >= 85%, SER <= 15%) are proposals. Do you accept them, or want stricter amount accuracy?
6. **Which Raj variant gates release.** Recommend gating on RAJ_0_6B (lower end phone) and treating RAJ_1_7B as an opt-in quality upgrade evaluated separately.

## Global Constraints

- No em dashes in code, docs or commit messages.
- Raw audio never committed, never uploaded by CI, never sent by the app by default.
- CI never downloads the 1-2 GB Raj model and never runs ASR. ASR is evaluated on the developer Mac only.
- Nothing in WS5 edits WS1-WS4 source, except adding files WS5 owns (workflows, `eval/`, release docs). Where WS5 needs a hook in another workstream it is listed under Contract problems and requested via SendMessage.
- No commit, push, tag or release without the user's explicit approval at execution time.
- Eval numbers are only comparable for a fixed (model variant, quantization, llama.cpp commit, parser git SHA). The scorecard records all four.
- GitHub account for this repo: `codes71` (personal), repo-local git identity only.

## Review Focus

Five failure modes no per-task happy-path test covers, each pinned to a test:

1. **Audio leaves the device by accident** (e.g. a debug logger or multipart upload of the PCM buffer). Test: Task 8 `NoAudioEgressTest` captures every HTTP request body in a full draft flow and asserts none exceeds the transcript size and none starts with RIFF/PCM magic.
2. **Eval set silently enters git history** (wrong glob, new clip dir). Test: Task 3 `test_no_audio_tracked` fails if any `*.wav|*.m4a|*.flac` is in `git ls-files` or the `.gitignore` rule is missing.
3. **Inflated accuracy from metric choices** (digits or English words counted as free syllables; Burmese numerals vs Arabic digits). Test: Task 4 `test_ser_counts_latin_tokens_as_errors_when_missed` and `test_numeral_normalization_is_reference_side_only`.
4. **Amount "close enough" masking wrong-money bugs** (e.g. 3000 vs 30000). Test: Task 5 `test_amount_match_is_exact_integer_kyat`, plus Task 6 gate test that a single off-by-10x fails the amount gate even when statement accuracy is high.
5. **Release APK signed with the debug key or keystore committed.** Test: Task 10 `test_release_workflow_requires_secrets_and_no_keystore_tracked` and a post-build `apksigner verify --print-certs` check that rejects the Android debug certificate.

## Milestones

| Milestone | Depends on | Owner workstreams | Demo / exit criteria |
|---|---|---|---|
| M0 Spike go/no-go | none | WS3, WS5 | Raj 0.6B GGUF passes WS3 gates G1 (Mac, 20 budget phrases) and G2 (phone over adb); see the master README milestones for the numbers. If it fails: fall back to cloud ASR behind opt-in, or smaller scope (typed entry only). |
| M1 API + shell sync | M0 not required | WS1, WS2, WS5 (CI) | Manual entry on phone appears in web app after sync (and vice versa), over `adb reverse tcp:9002 tcp:9002`. Web + Android CI green. |
| M2 On-device transcript | M0, M1 shell | WS3, WS2 | Hold mic, speak, see Burmese transcript with latency shown; model download resumable. |
| M3 Voice to ledger E2E | M1, M2, WS4 corpus | WS1, WS2, WS3, WS4, WS5 | Speak, see draft transactions, confirm in review sheet, row syncs to web. Task 12 smoke script passes. |
| M4 Thresholds met | M3, eval set recorded | WS5 (WS4, WS3 tune) | `make eval` scorecard passes all Task 6 gates on the held-out split. |
| M5 Release | M4, hosting decided | WS5, WS1 | Signed release APK from GitHub Releases installs on user's phone, syncs to hosted backend off home Wi-Fi, Sentry receives a test crash. |

Order of work: Tasks 1-2 (CI, unblocks everyone) and Task 3-4 (eval scaffolding) first; Task 5-6 after WS4 parser compiles; Tasks 7-9 during M3; Tasks 10-13 for M5.

## File Structure

```
.github/workflows/web.yml
.github/workflows/android.yml
.github/workflows/release.yml
vitest.config.ts                      (web test config, small)
src/lib/__tests__/smoke.test.ts       (proves vitest runs)
eval/voice/README.md                  (recording protocol, consent text)
eval/voice/manifest.jsonl             (committed, no audio)
eval/voice/clips/                     (gitignored, *.wav)
eval/voice/clips.sha256               (committed)
eval/voice/budgetwise_eval/__init__.py
eval/voice/budgetwise_eval/manifest.py
eval/voice/budgetwise_eval/syllables.py
eval/voice/budgetwise_eval/metrics.py
eval/voice/budgetwise_eval/asr_llamacpp.py
eval/voice/budgetwise_eval/nlu_bridge.py
eval/voice/budgetwise_eval/scorecard.py
eval/voice/run_eval.py
eval/voice/gates.toml
eval/voice/tests/test_*.py
android/eval-cli/build.gradle.kts, src/main/kotlin/eval/ParseCli.kt
docs/release/RELEASING.md
docs/release/privacy.md               (data safety answers, retention)
docs/release/hosting.md
scripts/ci/check-no-model-download.sh
scripts/ci/check-no-secrets-tracked.sh
scripts/smoke/e2e-voice.sh
```

## Tasks

### Task 1: Web CI (lint, typecheck, vitest)

**Depends on:** WS1 Task 1, which owns the Vitest harness (`vitest.config.ts`, `tests/helpers/*`, the `test` script). This task does not add Vitest again.
**Files:** `.github/workflows/web.yml`, `package.json` (script `typecheck`)
**Interfaces:** npm scripts `lint` (exists: `next lint`), `typecheck` = `tsc --noEmit`, `test` = `vitest run` (from WS1 Task 1). Repo has both `package-lock.json` and `pnpm-lock.yaml`; CI uses npm (`npm ci`) because `vercel.json` uses `npm run build`.

- [ ] Write failing check: `npm run typecheck`. Expected FAIL: `missing script: typecheck`.
- [ ] Implement: add script `typecheck`. `tsc --noEmit` reported 33 existing errors on 2026-10-19 (in `src/app`, `src/lib/guest-data.ts`, `src/lib/db.ts`, `src/lib/auth.ts`, login/signup/guest pages); gate CI on that baseline count, do not fix unrelated code here.
- [ ] Run `npm test && npm run typecheck && npm run lint`. Expected PASS against the baseline.
- [ ] Write `web.yml`: trigger `pull_request` and `push` to master; `actions/checkout`, `actions/setup-node` with `node-version-file` or `22` and `cache: npm`, then `npm ci`, `npm run lint`, `npm run typecheck`, `npm test`. Pin each action to the current major after checking its releases page (UNVERIFIED for 2026-10: `actions/checkout` v4/v5, `actions/setup-node` v4/v5; confirm at https://github.com/actions/checkout/releases and https://github.com/actions/setup-node/releases).
- [ ] Commit: `ci(web): add lint, typecheck and vitest workflow` (after user approval).

### Task 2: Android CI and "what not to run" guard

**Files:** `.github/workflows/android.yml`, `scripts/ci/check-no-model-download.sh`
**Interfaces:** Gradle tasks `:app:lintDebug`, `:app:assembleDebug`, `:nlu:test` (golden tests included), `testDebugUnitTest` for all modules. Paths filter `android/**`.

- [ ] Write failing check `scripts/ci/check-no-model-download.sh`: greps `.github/workflows/*.yml` and `android/**/build.gradle.kts` for `huggingface.co`, `hf download`, `.gguf`, `Raj-` and exits 1 on any hit.
- [ ] Run it before `android.yml` exists with a test fixture workflow containing `huggingface.co`. Expected FAIL (exit 1). Then run against real tree. Expected PASS (exit 0) once the workflow is written without such strings.
- [ ] Implement `android.yml`: `actions/checkout` (with `submodules: recursive` only if WS3 vendors llama.cpp as a submodule, UNVERIFIED, ask WS3), `actions/setup-java` with `temurin` 17 (match AGP requirement from WS2), `gradle/actions/setup-gradle` for caching and wrapper validation (v4 series documented at https://community.gradle.org/github-actions/docs/setup-gradle ; v5 exists with a runner version requirement, check https://github.com/gradle/actions/releases before pinning), NDK via `sdkmanager "ndk;<version WS3 pins>"` (the ubuntu-latest image already has the Android SDK; confirm NDK version list at https://github.com/actions/runner-images, UNVERIFIED), cache CMake outputs only if build time > 10 min.
- [ ] Steps: `./gradlew :nlu:test testDebugUnitTest lintDebug assembleDebug --stacktrace` in `android/`; upload `app-debug.apk` and test reports with `actions/upload-artifact` (retention 7 days).
- [ ] Add a CI job step running `check-no-model-download.sh`. Not run in CI: ASR decode, model download, instrumented tests, eval harness (all need the model or an emulator).
- [ ] Verify locally with `act` is not required; instead push a draft PR branch after user approval and read the Actions run.
- [ ] Commit: `ci(android): add build, unit test, golden test and lint workflow`.

### Task 3: Eval set layout, consent protocol, privacy guard

**Files:** `eval/voice/README.md`, `eval/voice/manifest.jsonl`, `eval/voice/budgetwise_eval/manifest.py`, `eval/voice/tests/test_manifest.py`, `.gitignore` (append), `eval/voice/clips.sha256`
**Interfaces:** `Clip` dataclass, `load_manifest(path) -> list[Clip]`, `validate(clips, clips_dir) -> list[str]` (errors).

Manifest line schema (JSON per line): `id` (`c001`), `file` (`clips/c001.wav`), `speaker` (`s1` pseudonym), `split` (`dev` | `heldout`), `reference` (Burmese transcript, Unicode NFC), `today` (ISO date used for relative dates), `expected` (array of `{amount_kyat:int, type:"expense|income", category:str, date:ISO}`), `tags` (`codeswitch`, `numerals`, `relative_date`, `multi_item`, `noisy`).

Recording protocol (goes in README): 100 clips, 16 kHz mono 16-bit WAV, 3-12 s each; at least 3 speakers (user plus two friends who gave written consent: what is recorded, kept on the user's Mac and private Drive, used only to test this app, deletable on request); mix of quiet room, street noise, and one-handed phone-in-pocket-distance; cover amounts in spoken Burmese words, Arabic digits read aloud, English loanwords (e.g. "Grab", "KBZPay"); 30% held out, never used for tuning WS4 rules. Held-out split is frozen after M3.

- [ ] Write failing tests in `test_manifest.py`: `test_validate_flags_missing_file`, `test_validate_flags_bad_split`, `test_no_audio_tracked` (runs `git ls-files` and asserts no `.wav|.m4a|.flac|.mp3`, and that `.gitignore` contains `eval/voice/clips/`), `test_sha256_matches_when_clips_present` (skips if dir empty).
- [ ] Run `cd eval/voice && python -m pytest tests/test_manifest.py`. Expected FAIL: module not found.
- [ ] Implement `manifest.py` and append `eval/voice/clips/` plus `*.wav` under `eval/` to `.gitignore`.
- [ ] Run tests. Expected PASS.
- [ ] Record the first 10 clips (M0 spike set) and commit manifest rows only. Commit: `feat(eval): add voice eval manifest schema and privacy guard`.

### Task 4: Metrics (syllable error rate, statement accuracy)

**Files:** `eval/voice/budgetwise_eval/syllables.py`, `eval/voice/budgetwise_eval/metrics.py`, `eval/voice/tests/test_metrics.py`
**Interfaces:** `syllabify(text: str) -> list[str]`, `ser(ref: str, hyp: str) -> float` (edit distance over syllable tokens / ref length), `cer(ref, hyp)`, `statement_correct(expected, got) -> StatementResult(amount, type, category, date, all)`.

Why syllable error rate: Burmese has no orthographic word boundaries so WER depends on a segmenter, and CER is biased because syllables use a variable number of code points. A Burmese ASR model card makes the same argument and reports SER (https://huggingface.co/freococo/myanmar_asr, project page not peer reviewed); an LREC 2024 Burmese corpus paper reports CER/WER with syllable segmentation (https://aclanthology.org/2024.lrec-main.1051). No community-wide standard was found, so we report SER as primary and CER as secondary. Syllable segmentation: regex rule-based break (sylbreak style, by Ye Kyaw Thu; reference implementation URL UNVERIFIED, implement from the published rule: break before a consonant not followed by asat/virama and not a stacked consonant). Latin runs and digit runs are each one token.

- [ ] Write failing tests: `test_syllabify_known_word` (hand-checked example from user, 3 Burmese strings with expected token lists), `test_ser_identical_is_zero`, `test_ser_counts_latin_tokens_as_errors_when_missed` (ref has "Grab", hyp omits it, SER > 0), `test_numeral_normalization_is_reference_side_only` (Burmese digits in reference normalized to ASCII for amount comparison, hyp is never silently "fixed"), `test_statement_requires_all_four_fields`.
- [ ] Run pytest. Expected FAIL: ImportError.
- [ ] Implement functions above; edit distance via a small DP (no external dependency).
- [ ] Run pytest. Expected PASS.
- [ ] Commit: `feat(eval): add syllable error rate and statement accuracy metrics`.

### Task 5: Host eval runner (Raj via llama.cpp + parser bridge)

**Files:** `eval/voice/budgetwise_eval/asr_llamacpp.py`, `eval/voice/budgetwise_eval/nlu_bridge.py`, `android/eval-cli/build.gradle.kts`, `android/eval-cli/src/main/kotlin/eval/ParseCli.kt`, `eval/voice/run_eval.py`, `eval/voice/tests/test_runner.py`
**Interfaces:**
- Python: `transcribe(wav: Path, model: Path) -> AsrOut(text, decode_ms, audio_ms)`; `parse_batch(rows: list[dict]) -> list[list[dict]]` (calls `./gradlew -q :eval-cli:run --args=...` with JSONL stdin of `{id, transcript, today, categories}`, JSONL stdout of drafts).
- Kotlin: `fun main(args)` reading JSONL, calling `BurmeseBudgetParser.parse(transcript, today, categories)` and printing `{id, drafts:[{amountKyat,type,category,date}]}`.
- CLI: `python run_eval.py --split heldout --model <gguf> --variant RAJ_0_6B --out scorecard.json`.

Notes: Raj is Qwen3-ASR based. Whether stock `llama-cli` or `llama-server` can decode Qwen3-ASR audio GGUFs is UNVERIFIED, WS3 owns the answer (M0 spike). `asr_llamacpp.py` therefore takes the decode command as a template in `eval/voice/gates.toml` (`[asr] cmd = "..."`) so it can follow whatever WS3 proves. Also supports `--transcripts-from <jsonl>` to skip ASR and test the NLU on reference transcripts (isolates ASR error from parser error). Model files live in `~/.cache/budgetwise-eval/` (outside the repo).

- [ ] Write failing tests in `test_runner.py`: `test_amount_match_is_exact_integer_kyat` (3000 vs 30000 is a miss; 3000 vs 3000.0 hit), `test_runner_uses_reference_transcripts_mode` with a fake parser bridge (monkeypatch) asserting scorecard keys `ser`, `cer`, `amount_exact`, `statement_acc`, `p50_decode_ms`, `p95_decode_ms`, `rtf`, `meta.{variant,quant,llama_cpp_sha,parser_sha}`, `test_runner_fails_loudly_when_parser_returns_wrong_row_count`.
- [ ] Run pytest. Expected FAIL.
- [ ] Implement runner, bridge, and `ParseCli.kt` (JVM-only module; requires `:nlu` to be pure Kotlin/JVM, see Contract problems).
- [ ] Run pytest. Expected PASS. Then run `python run_eval.py --split dev --transcripts-from eval/voice/manifest.jsonl` as an NLU-only dry run. Expected: scorecard printed, statement accuracy reflects parser only.
- [ ] Commit: `feat(eval): add host eval runner with llama.cpp ASR and parser bridge`.

### Task 6: Scorecard and go/no-go gates

**Files:** `eval/voice/budgetwise_eval/scorecard.py`, `eval/voice/gates.toml`, `eval/voice/tests/test_gates.py`
**Interfaces:** `render(card: dict) -> str` (table), `check_gates(card, gates) -> list[Failure]`; `run_eval.py` exits 1 on any failure.

Proposed gates (held-out split, RAJ_0_6B; user to confirm, Decision 5):

| Metric | Gate | Reasoning |
|---|---|---|
| Amount exact-match | >= 95% | Wrong money is the worst failure; review sheet is the backstop, not the plan |
| Type (income/expense) | >= 97% | Sign errors are easy to miss in a list |
| Category | >= 85% | Cheap to fix in the review sheet |
| Date | >= 95% | Relative dates are rule-based |
| Statement accuracy (all four) | >= 85% | End-to-end usability |
| SER (ASR only) | <= 15% | Informational; statement accuracy is the binding gate |
| p95 end-to-end latency (release-to-draft) on the user's phone | <= 6 s for 8 s clip | Hold-to-talk feel |
| Crash-free sessions in dogfood | >= 99% over 50 voice entries | Stability |

- [ ] Write failing tests: `test_gate_fails_on_single_10x_amount_error` (card with 19/20 amounts right = 95% passes; 18/20 fails), `test_high_statement_acc_cannot_mask_low_amount_acc`, `test_render_includes_all_meta_fields`.
- [ ] Run pytest. Expected FAIL.
- [ ] Implement scorecard and `gates.toml` (thresholds are data, not code).
- [ ] Run pytest. Expected PASS.
- [ ] Commit: `feat(eval): add scorecard rendering and ship gates`.

### Task 7: Golden corpus linkage

**Files:** `eval/voice/tests/test_golden_link.py`, `eval/voice/budgetwise_eval/golden.py`
**Interfaces:** `load_golden(path = android/nlu/src/test/resources/golden/burmese-budget-corpus.json) -> list[dict]`.

Purpose: WS4's golden corpus is text only; the voice manifest `reference` fields must never drift from it silently, and spoken clips should reuse golden utterances where possible so ASR and NLU errors can be separated.

- [ ] Write failing test: every manifest row with tag `from_golden` has a `reference` that appears verbatim in `burmese-budget-corpus.json` with identical expected drafts; at least 40% of clips carry the tag.
- [ ] Run `python -m pytest tests/test_golden_link.py`. Expected FAIL (module missing; later skip with clear message if WS4 file absent, then FAIL until WS4 lands).
- [ ] Implement `golden.py`; field names read from the WS4 file (shape UNVERIFIED, see Contract problems).
- [ ] Run. Expected PASS.
- [ ] Commit: `test(eval): link voice manifest to WS4 golden corpus`.

### Task 8: Privacy tests (no audio egress, cloud fallback off by default)

**Files:** `android/app/src/test/kotlin/privacy/NoAudioEgressTest.kt`, `android/app/src/test/kotlin/privacy/CloudFallbackDefaultOffTest.kt`, `docs/release/privacy.md`
**Interfaces:** consumes WS2 `ApiClient` (OkHttp) and WS3 `SpeechToText`; uses OkHttp `MockWebServer`.

Policy to document: audio is captured into memory, decoded on-device, and discarded; no audio file is written (verify `cacheDir`/`filesDir` empty after a run); only the transcript text and confirmed transactions leave the device, to the user's own backend; `voice/parse` (WS1) receives text only and is an opt-in "cloud assist" off by default; no analytics SDK; Sentry scrubs breadcrumbs and `beforeSend` drops request bodies.

- [ ] Write failing tests: `NoAudioEgressTest` runs a fake `SpeechToText` returning a transcript for a 2 s `FloatArray`, drives record -> parse -> confirm -> sync against `MockWebServer`, asserts every request body is JSON, each smaller than 8 KB, none contain RIFF header or base64 of the sample buffer, and `filesDir`/`cacheDir` contain no `.wav/.pcm`. `CloudFallbackDefaultOffTest` asserts the default settings have `cloudParseEnabled == false` and that no request hits `/api/v1/voice/parse` unless enabled.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests 'privacy.*'`. Expected FAIL (classes/flags missing or assertion fails).
- [ ] Implement only test scaffolding; if the setting flag is missing request it from WS2 (do not edit WS2 code).
- [ ] Run. Expected PASS.
- [ ] Write `privacy.md` with: RECORD_AUDIO justification text ("Used only while the mic button is held, to turn speech into a draft transaction on your phone. Audio is not stored or uploaded."); token storage (access/refresh JWT in EncryptedSharedPreferences or DataStore with Android Keystore key; never logged; `android:allowBackup="false"` or excluded via data extraction rules); data deletion (in-app "Delete my data" calls backend delete then clears Room and keystore token); Play Data safety draft answers: Financial info collected (transactions) yes, shared no, encrypted in transit yes, deletion available yes; Audio not collected (processed on device only, ephemeral); App activity none; Crash logs collected by Sentry yes (diagnostics, not linked to identity). Verify wording against the live form at https://support.google.com/googleplay/android-developer/answer/10787469 (UNVERIFIED, URL from memory).
- [ ] Commit: `test(privacy): assert no audio egress and cloud fallback default off`.

### Task 9: Account and data deletion contract check

**Files:** `scripts/smoke/delete-account.sh`, `src/app/api/v1/__tests__/delete-account.contract.test.ts` (WS5-owned contract test only)
**Interfaces:** expects `DELETE /api/v1/account` (NOT in the shared contract list, see Contract problems) returning 204 and removing the user's transactions, categories, budgets.

- [ ] Write failing contract test using Vitest + `fetch` against `BASE_URL` (skipped unless set): create user via `auth/token`, post one transaction, call DELETE, assert 204, assert subsequent GET returns 401, assert Mongo has no documents for that user id.
- [ ] Run `BASE_URL=http://localhost:9002 npm test -- delete-account`. Expected FAIL: 404 on the route.
- [ ] Implement: ask WS1 to add the endpoint. Nothing for WS5 to implement beyond the test.
- [ ] Run. Expected PASS once WS1 lands it.
- [ ] Commit: `test(api): contract test for account deletion`.

### Task 10: Signing, versioning and GitHub Releases workflow

**Files:** `.github/workflows/release.yml`, `docs/release/RELEASING.md`, `scripts/ci/check-no-secrets-tracked.sh`, `android/app/build.gradle.kts` (WS2-owned; WS5 requests the versioning block)
**Interfaces:** tag `android-v0.1.0` triggers the workflow; `versionName` = tag without prefix; `versionCode` = `git rev-list --count HEAD` (monotonic, simple for one developer); signing config reads env `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` from GitHub Secrets.

- [ ] Write failing check `check-no-secrets-tracked.sh`: fails if `git ls-files` includes `*.jks`, `*.keystore`, `keystore.properties`, `.env*` (except `.env.example`) or `google-services.json` with real keys. Add `test_release_workflow_requires_secrets_and_no_keystore_tracked` as a shell self-test that greps `release.yml` for the four secrets and for `apksigner verify`.
- [ ] Run. Expected FAIL: `release.yml` missing. Note: the repo already has a tracked-looking `.env.local` and `env` file at root; the script must confirm they are gitignored (check `git check-ignore`) and report, never print contents.
- [ ] Implement `release.yml`: checkout, setup-java 17, setup-gradle, decode keystore to `$RUNNER_TEMP`, `./gradlew :app:assembleRelease`, run `apksigner verify --print-certs` and fail if the cert subject contains `Android Debug`, `actions/upload-artifact`, then `gh release create` with the APK and a SHA-256 file. Release permissions: `contents: write` only on this job.
- [ ] Generate the upload keystore locally (user runs, passwords in a password manager, backup copy offline). Document in `RELEASING.md` that losing it means a new app identity for sideloaded installs unless Play App Signing is used.
- [ ] Run check script. Expected PASS. Dry-run the workflow with `workflow_dispatch` on a branch after user approval.
- [ ] Commit: `ci(release): signed APK to GitHub Releases on android-v* tags`.

### Task 11: Distribution, app size, hosting and crash reporting decisions

**Files:** `docs/release/RELEASING.md`, `docs/release/hosting.md`, `scripts/ci/check-apk-size.sh`
**Interfaces:** `check-apk-size.sh <apk> <max_mb>` exits 1 if the APK exceeds the budget.

- App size budget: release APK (arm64-v8a only via `abiFilters`) <= 60 MB including llama.cpp native libs; the Raj GGUF (roughly 1-2 GB, size per WS3, UNVERIFIED) is downloaded post-install by WS3 `ModelManager` with resume and SHA-256 check, shown with a Wi-Fi-only default. Play AAB base limit is 200 MB, so this is not a store constraint, it is a sideload convenience limit.
- Play path (only if Decision 3 chooses it): a newly created personal developer account must run a closed test with at least 12 testers opted in for 14 continuous days before applying for production access (https://support.google.com/googleplay/android-developer/answer/14151465). Reported as applying to accounts created after 13 Nov 2023, was 20 testers before Dec 2024 (third-party summary, https://extendsclass.com/blog/google-plays-closed-testing-requirement-what-developers-need-to-know-in-2026). A reset-on-dip behavior is only third-party claimed, UNVERIFIED. Internal testing track has no such requirement for up to 100 testers (UNVERIFIED, check Play Console help). Recommend sideload first.
- Hosting: Vercel Hobby + Atlas M0 requires env vars `MONGODB_URI`, JWT secret, and a CORS/allowlist review for the mobile client (mobile does not need CORS but rate limiting on `auth/token` is needed). Firebase App Hosting alternative: `apphosting.yaml` has `maxInstances: 1`, stays within free quota only on Blaze with budget alerts. Local-only fallback: `adb reverse tcp:9002 tcp:9002`. Hobby plan is for non-commercial use (https://vercel.com/docs/plans/hobby, UNVERIFIED).
- Crash reporting: Sentry Android SDK with `sendDefaultPii=false`, `beforeSend` strips transcripts and request bodies; disabled in debug builds.

- [ ] Write failing check: `check-apk-size.sh` against a 70 MB dummy file with max 60. Expected FAIL (exit 1), and against a 10 MB dummy, PASS.
- [ ] Implement script, wire into `release.yml` and `android.yml` (debug budget 120 MB).
- [ ] Write `hosting.md` recording the user's decision from Decisions 2-4 and the exact env var list (names only, never values).
- [ ] Run script on the real release APK at M5. Expected PASS.
- [ ] Commit: `docs(release): distribution, hosting and size budget`.

### Task 12: End-to-end smoke script (M3 checkpoint)

**Files:** `scripts/smoke/e2e-voice.sh`, `docs/release/m3-checklist.md`
**Interfaces:** takes `BASE_URL`, runs against local dev server (`npm run dev` on port 9002 with local MongoDB).

- [ ] Write failing script: obtains a token from `/api/v1/auth/token`, POSTs a transaction twice with the same `clientId` and asserts one row (idempotency), POSTs `voice/parse` with a golden transcript (if enabled) and asserts amounts, DELETEs the row and asserts it is gone, prints PASS/FAIL per step.
- [ ] Run against a server without `/api/v1`. Expected FAIL at step 1.
- [ ] Implement checklist for the phone side: install debug APK, `adb reverse tcp:9002 tcp:9002`, speak 5 golden phrases, confirm, check web dashboard.
- [ ] Run after WS1 lands. Expected PASS.
- [ ] Commit: `test(smoke): add M3 end-to-end voice smoke script`.

### Task 13: M4/M5 gate run and release checklist

**Files:** `eval/voice/results/YYYY-MM-DD-<variant>.json` (committed, no audio), `docs/release/RELEASING.md`
**Interfaces:** none new.

- [ ] Failing check: `python eval/voice/run_eval.py --split heldout --variant RAJ_0_6B --model <gguf>` run on the full 100 clips. Expected FAIL on at least one gate on the first run (if it passes first time, re-inspect that held-out clips were not used for tuning).
- [ ] Iterate with WS3 and WS4 (quantization, prompt/language hints, parser rules, dev split only) until gates pass. Each change re-runs the scorecard; commit results files.
- [ ] Run final scorecard on frozen held-out split. Expected PASS all gates.
- [ ] Release checklist in `RELEASING.md`: tag, workflow green, `apksigner` cert check, install on phone, off-Wi-Fi sync to hosted backend, Sentry test event, rollback plan (previous release APK kept; backend API is additive-only so old clients keep working).
- [ ] Commit: `chore(release): M4 scorecard and release checklist`.

## Contract problems

- WS1 has no account deletion endpoint in the shared list; Task 9 and the Data safety answer need `DELETE /api/v1/account`.
- `:nlu` must be pure Kotlin/JVM (no Android imports) for Task 5's `eval-cli`; confirm with WS4.
- WS4 golden `burmese-budget-corpus.json` schema and the `DraftTransaction` field names (`amountKyat`? type enum values? date format) are not fixed; Tasks 5 and 7 assume `amountKyat:Int`, `type: expense|income`, `category`, `date: ISO`.
- WS3 must state whether stock llama.cpp decodes Raj (Qwen3-ASR) audio, and the exact decode command for `gates.toml`.
- WS2 must expose a `cloudParseEnabled` setting (default false) and own the `versionCode/versionName` and signing config block.
