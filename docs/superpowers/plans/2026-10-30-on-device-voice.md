# On-device Burmese voice (plan, 2026-10-30)

Branch `feat/voice-on-device`, worktree `/Users/code/Projects/BudgetWise-voice`, cut from `chore/monorepo` at 9580bb7.
Committed and opened as a pull request into `chore/monorepo`; not merged or deployed.

## What the user decided

- Speech recognition runs on the phone, forever. No speech server, no paid speech API.
- Both speech models are used: freococo/myanmar_asr and Meta Omnilingual CTC 1B. They won the test on the user's
  own voice (`~/.cache/budgetwise-eval/my-voice/results.md`): 14 of 14 amounts and 4 of 4 questions when an LLM
  reads both transcripts.
- The models do not ship inside the app. The app has a "download voice" button; the download runs in the
  background with visible progress (about 1.1 GB).
- OpenAI is out for voice: it got 3 to 6 of 14 amounts, and its audio models invented amounts.

- Understanding (user: "do whatever to get the best transcription"): the phone sends only the transcripts (no
  audio, no transactions) to `POST /api/v1/voice/parse`, which asks Claude Opus 5.5 at low effort; the phone answers
  questions from its own Room data. Live check on the user's recordings: 14 of 14 amounts, 4 of 4 questions,
  about 4 s per request.
- No offline mode for voice or receipt scan (user, 2026-10-30): both need the network. Small on-device models
  scored far lower (best: Gemma 4 E4B, 5 GB, 10 of 14 amounts, 3 of 4 questions, 5 invented amounts).
- If Claude is down, the server asks OpenAI instead (user, 2026-10-30). `apps/web/src/ai/generate.ts`: Claude
  first with a 25 s limit; on any failure except a refusal, the same request goes to OpenAI GPT-6.1 Sol (30 s).
  Live check with Claude forced down: voice 14 of 14 amounts and 4 of 4 questions (median about 5 s, the same as
  Claude), a test receipt read exactly; a hung Claude switched over and answered in 33 s.

## Findings so far

- freococo exports to one 78 MB ONNX file (`apps/android/asr/host/export/freococo_onnx.py`) that takes 16 kHz
  audio and returns syllable ids; the log-mel front end is inside the graph. Its transcripts are identical to the
  original PyTorch model on all 65 test clips (18 of the user's, 47 FLEURS). The app only collapses repeated ids,
  drops blank id 0 and looks ids up in `vocab.json`. Clips under 0.5 s return "" in the original; keep that.
- Omnilingual takes raw 16 kHz audio normalized to zero mean and unit variance (epsilon 1e-5, variance from
  centered values), returns logits; greedy CTC with blank id 0, `▁` becomes a space.
- Risk: the int8 Omnilingual model is numerically fragile. On the same clip (r16, a quiet recording, peak 0.05)
  ONNX Runtime 1.23 gives garbage with full graph optimization, a near-copy of sherpa-onnx's text with
  optimization off, and sherpa-onnx 1.13.8 gives the readable text. Only 7 of 18 clips were character-identical
  between the two runtimes. The phone result must be checked with the exact runtime and settings the app ships.
- sherpa-onnx 2.0.1 (2026-10-26) has a Kotlin API for Omnilingual CTC (`OfflineOmnilingualAsrCtcModelConfig`)
  and ships as GitHub release AARs only; Maven Central has no official artifact. A static-link-onnxruntime AAR
  exists, so it could sit next to `onnxruntime-android` without a duplicate `libonnxruntime.so`.
- Google Play: install-time asset packs up to 1.5 GB each, 4 GB total (not used, since the user chose a download).

## Done (on this branch)

- `apps/web`: `POST /api/v1/voice/parse` (route, flow, normalization, tests) and the OpenAI fallback for every AI
  flow. 166 of 166 web tests pass; tsc at the 31-error baseline. The unused M1 `voiceParseSchema` was removed.
- `apps/android/asr`: Raj and llama.cpp removed; freococo and Omnilingual run on onnxruntime-android 1.31.1 behind
  `SpeechToText` (one transcript per model); one download pack with progress. Session settings and the evidence
  for them are in `OnnxEngines.kt` and `asr/host/README.md`; ONNX Runtime telemetry is off. Device steps:
  `asr/src/androidTest/README.md`. The CI task list (now with `:asr`) passes locally.

## Voice UI (branch `feat/voice-ui`, worktree `BudgetWise-voice-ui`)

Built on `feat/onboarding-v2` plus this branch. Designs: Paper file "Budget Wise", page "Redesign: full app", row
"Voice" (VO1 to VO14), approved by the user on 2026-10-30, with onboarding getting a voice step (user, 2026-10-30).

- Entry points: "Say it" on Add entry and on the first-day home. Before the models are on the phone it opens the setup
  card (1.1 GB, waits for Wi-Fi, "Use mobile data" while waiting), then the progress card; voice opens when the
  download finishes with the card open. The worker posts "Voice is ready" (Android 13+ asks for notifications first).
- Onboarding is now three steps: create account, first income, then "Say what you spend" (Download or Not now). A
  phone that already has voice skips the step.
- `ui/voice`: listening (20 s limit, level rings), working (on the phone, then the server), problem cards (no amount,
  nothing heard, no internet, server failure, signed out, microphone denied or busy, voice turned off), check what you
  said (edit sheet per entry, saved with source `voice` and what was heard), and answers worked out on the phone from
  Room (total, list, over budget for the month).
- Settings has a Voice section: Download, Cancel or Remove, and the "Understand what I say" switch (`cloudParseEnabled`).
- `:feature:voice` is the microphone (`AudioRecordMicrophone`, audio in memory only). `POST /voice/parse` gets a 65 s
  read timeout (`@SlowCall`), since the server may try OpenAI after Claude.
- No "Scan a receipt" button yet: the phone has no scan screen.
- Low-memory phones (user, 2026-10-31): voice is off where Android reports under 3.3 GB of total memory (advertised
  3 GB phones) or the phone calls itself low-RAM (`VoiceRequirements`). Nothing offers voice there: no Say it, no
  download, two onboarding steps. Where the voice step would be, a small notice says why; Settings says the same.
  Cloud speech recognition was ruled out: OpenAI's best model got 4 of 14 amounts (rerun 2026-10-31).

## Device gate (Xiaomi 14, Snapdragon 8 Gen 3, 12 GB; 2026-10-31)

Run with `asr/src/androidTest` (README there), the shipped ONNX Runtime 1.31.1 and settings:

- Parity: on all 18 of the user's recordings, both models' transcripts are character-identical to the Mac goldens.
  Plain ONNX Runtime stays for Omnilingual; sherpa-onnx is not needed.
- Full path (VAD, both models): load 3.3 to 4.2 s, p50 RTF 0.61 (about 2.5 s for a 4 to 7 s phrase), peak VmHWM
  1.55 GB.
- A 20 s recording (the listening limit): 17.6 s to transcribe (RTF 0.88), peak 1.76 GB.
- ONNX Runtime's telemetry provider is removed from the merged manifest.
- A 4 GB emulator (arm64, 16 KB pages, Mac CPU so its speed means nothing): peak 1.39 GB for short phrases and
  1.69 GB for 20 s; Android killed only background apps, never the test. The emulator image would not go below
  4 GB, so 3 GB phones are untested.

## Still open

- `OPENAI_API_KEY` was added in Vercel by the user (2026-10-31). It applies from the next deployment of each
  environment; production gets it when `chore/monorepo` is merged and deployed.
- The phone's `cloudParseEnabled` setting defaults on (user, 2026-10-30); its switch is in Settings on `feat/voice-ui`.

- Done 2026-10-31 (user chose Hugging Face): `freococo.onnx` and `vocab.json` are public at
  huggingface.co/thura2/freococo-myanmar-asr-onnx, commit e5041e6, Apache-2.0 with a model card; the app's default
  host pins that commit. Downloaded copies match the pinned sha256.
- Memory: measured on the phone (above): 1.55 GB for short phrases, 1.76 GB for 20 s. 4 GB (emulator) works; 3 GB
  phones and a real low-end CPU are untested.
- 64-bit ARM only (`abiFilters`), kept from the Raj build. Cheap 32-bit phones cannot install the app.

## Work, in order

1. Device gate: done on the Mi 14 (see above). Plain ONNX Runtime for both models.
2. `:asr`: replace the Raj/llama.cpp engine with the two ONNX engines behind the existing `SpeechToText`
   (returning both transcripts), keep `SpeechSegmenter`, and point `ModelManager` and the download worker at the
   new files with checksums. Drop the llama.cpp submodule and native build.
3. Model hosting: done. Both models come from Hugging Face at pinned revisions.
4. Understanding: done (Claude, OpenAI fallback); `OPENAI_API_KEY` is set in Vercel.
5. Voice UI: built on `feat/voice-ui` (see above); not yet tried on a phone.
6. Licenses screen entries for both models (freococo is Apache-2.0; check Omnilingual's).
