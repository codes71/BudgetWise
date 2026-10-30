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

## Still open

- `OPENAI_API_KEY` (and optionally `OPENAI_MODEL`) in the deployed environment; without it there is no fallback.
- The phone's `cloudParseEnabled` setting (default off) predates the online-only decision; the voice UI must not
  depend on it, or it should default on.

- Public host for `freococo.onnx` and `vocab.json` (`-Pbudgetwise.freococoModelBaseUrl`); until then the download
  fails with "voice model host not configured".
- Memory: on the Mac a 20 s segment peaks near 2 GB; short phrases near 1.5 GB. Measure on the phone, then pick
  `maxSegmentMs`.
- 64-bit ARM only (`abiFilters`), kept from the Raj build. Cheap 32-bit phones cannot install the app.

## Work, in order

1. Device gate: run both models on the Mi 14 from files on disk, with the runtime the app will ship. Check
   transcripts against the Mac on the user's 18 clips, latency, and peak memory (the Raj limit was 1.6 GB).
   Decide plain ONNX Runtime for both versus sherpa-onnx for Omnilingual from this result.
2. `:asr`: replace the Raj/llama.cpp engine with the two ONNX engines behind the existing `SpeechToText`
   (returning both transcripts), keep `SpeechSegmenter`, and point `ModelManager` and the download worker at the
   new files with checksums. Drop the llama.cpp submodule and native build.
3. Model hosting: Omnilingual from its Hugging Face repo at a pinned revision; freococo's ONNX export needs a
   public home (a Hugging Face repo or a public GitHub release). Both are free.
4. Understanding: done (Claude, OpenAI fallback); needs `OPENAI_API_KEY` in the deployed environment.
5. `:feature:voice`: download button and progress, mic button, review screen for entries, answers for questions.
6. Licenses screen entries for both models (freococo is Apache-2.0; check Omnilingual's).
