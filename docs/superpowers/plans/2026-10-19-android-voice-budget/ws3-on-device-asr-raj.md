# WS3 On-Device Burmese ASR (Raj) Implementation Plan
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a 16 kHz mono `FloatArray` into Burmese (plus English code-switch) text fully on the phone with the Raj model, behind the shared `SpeechToText` contract, with verified model download, a JNI bridge to llama.cpp, and a Mac host harness whose output matches the phone.

**Architecture:** One C++ core (`raj_engine` + `raj_text`) over llama.cpp `libllama` + `libmtmd`, compiled twice: into `libraj_jni.so` for Android (arm64-v8a, CPU backends loaded at runtime per SoC) and into the `raj-host` CLI on macOS. Kotlin wraps it with a VAD segmenter, a mutex-guarded `RajSpeechToText`, and a `ModelManager` that downloads pinned Hugging Face revisions with resume and sha256 verification. Two go/no-go gates (Mac accuracy G1, phone latency G2) run before any app code.

**Tech Stack:** Kotlin, Coroutines, OkHttp, WorkManager, Robolectric, MockWebServer, C++17, CMake 3.31.6, NDK 29.0.13113456, llama.cpp `fb34fc262c1b43f1832c7472429fb2247d650493` (git submodule), `com.github.gkonovalov.android-vad:webrtc:2.0.10`, Python 3 (stdlib only) for the spike.

**Spec:** `docs/superpowers/plans/2026-10-19-android-voice-budget/README.md` (shared contract)

## Research Findings

All checks done 2026-10-19. "VERIFIED" means read from the primary source (HF API, raw repo files, HTTP headers, llama.cpp source at the pinned SHA or master). Estimates are labelled ESTIMATE.

### Q1. Files, sizes, licenses (VERIFIED: HF API `?blobs=true`, `SHA256SUMS`, HEAD `x-linked-etag`)

| Repo @ pinned commit | File | Bytes | sha256 |
|---|---|---|---|
| Raj-0.6B @ `92b89bafb8b48fe47857665ca834390b7303c7d8` | `gguf/Raj-0.6B-Q8_0.gguf` | 639,442,816 | `eb8b09623dcd0e6d135c7f119e8504b66edbeeacd5f961c014ac76974fc3b70a` |
| | `gguf/mmproj-Raj-0.6B-Q8_0.gguf` | 207,941,216 | `a940a9645e849d1456c0c6e6297e902907794d8422014759f3d28593904fcab9` |
| Raj-1.7B @ `7dbed379408543ec4b88b6a28f2435422b83c5fe` | `gguf/Raj-1.7B-Q8_0.gguf` | 1,834,422,144 | `7888844eb4b5f7a7d6123d07255cf93b6f35671abd2d4da13a557fc125ffd180` |
| | `gguf/mmproj-Raj-1.7B-Q8_0.gguf` | 348,336,480 | `1345a70dda8fc0b4d7984961d6c5ad868d7d47cf91d3f7f963b30f218fbf16ba` |
| Raj-0 @ `afddf336c0fd45d3daac65de32fca4a80c1bdec4` | `raj-0-q8_0.gguf` (NeMo-Speech.cpp format, GGUF arch `asr`) | 723,985,024 | `fae02ea2f3dd338f4e2b7e2f1a574f9a79253e267e5aff4e181b8362af993f98` |
| | `raj-0.nemo` (full NeMo archive) | 2,506,813,440 | `c7770c1d55e6730a797686745898b74a116e3c803d9af1a91e60b401b0d713df` |

- Raj-0.6B/1.7B also ship 8-bit MLX (`mlx/model.safetensors` 1,000,926,425 / 2,500,516,700 bytes, runtime `mlx-qwen3-asr` 0.4.4, Apple only) and tokenizer files. Only Q8_0 GGUF is published: no F16/BF16 GGUF, no Q4/Q5, no ONNX for any Raj model.
- GGUF metadata (HF API): arch `qwen3vl`, context 65536, plain ChatML chat template. `gguf/release.json` pins runtime llama.cpp revision `fb34fc262c1b43f1832c7472429fb2247d650493`.
- Upstream Qwen3-ASR `support_languages` (in Raj `mlx/config.json`) has 30 languages and no Burmese; Burmese comes from the fine-tune. Text decoder: 28 layers, 8 KV heads, head_dim 128 in both sizes.
- No WER/CER is published for 0.6B/1.7B. Raj-0 reports CER 11.02% on 18.02 h of internal, auto-transcribed validation audio (not human verified). Cards warn 1.7B is slower and long recordings can miss or repeat passages.
- Licenses: Raj-0.6B/1.7B Apache-2.0 with `NOTICE` (Copyright (c) 2026 Aung Zay Thant; base Qwen3-ASR Apache-2.0, Copyright 2026 Alibaba Cloud, `UPSTREAM-LICENSE.txt`). Raj-0 weights Apache-2.0, base model OpenMDW-1.1 (redistribution must keep the license copy and notices), runtime NeMo-Speech.cpp Apache-2.0.
- The README has no usage commands. The `llama serve -hf ...`, Ollama and Lemonade lines are HF's auto-generated "Use this model" snippets (same snippets appear on `ggml-org/Qwen3-ASR-0.6B-GGUF`).
- Sources: https://huggingface.co/api/models/aungzaythant/Raj-0.6B?blobs=true , https://huggingface.co/api/models/aungzaythant/Raj-1.7B?blobs=true , https://huggingface.co/api/models/aungzaythant/Raj-0?blobs=true , https://huggingface.co/aungzaythant/Raj-1.7B/raw/main/NOTICE , https://huggingface.co/aungzaythant/Raj-1.7B/raw/main/gguf/release.json , https://huggingface.co/aungzaythant/Raj-0/raw/main/INFERENCE.md , https://huggingface.co/aungzaythant/Raj-0/raw/main/NOTICE

### Q2. llama.cpp support, audio format, prompt (VERIFIED unless marked)

- Support: PR #19441 "mtmd: qwen3 audio support (qwen3-omni and qwen3-asr)" merged 2026-04-12 (https://github.com/ggml-org/llama.cpp/pull/19441). PR #23073 "mtmd: add chunks and fix preproc for qwen3a" merged 2026-05-15 fixes the repetition-loop bug of issue #22357 (https://github.com/ggml-org/llama.cpp/pull/23073). The Raj-pinned SHA `fb34fc2` (2026-09-21) is 1924 commits ahead of the #23073 merge (GitHub compare API) and contains `models/qwen3a.cpp` and `mtmd_audio_preprocessor_qwen3a`. Build tag numbers (b8769, b9173) come from third-party notes: UNVERIFIED.
- Tools: `llama-mtmd-cli`, `llama-server` (OpenAI `/v1/chat/completions` with `input_audio`, and raw `/completion` with `{prompt_string, multimodal_data}`), libmtmd C API (`docs/multimodal.md`, `tools/server/README.md` at the pin). ggml-org publishes `Qwen3-ASR-0.6B-GGUF` and `-1.7B-GGUF` under audio models.
- Audio input: PCM F32 mono via `mtmd_bitmap_init_from_audio(n_samples, const float*)`; `hparams.audio_sample_rate = 16000`, n_fft 400, hop 160, 128 mel bins, Whisper-style log10 normalization (`tools/mtmd/clip.cpp`, `mtmd-audio.cpp`). Mel is split into 800-frame (8 s) encoder windows padded to multiples of 100 frames, 13 audio tokens per 100 frames (about 13 tokens per second). No hard max length in llama.cpp; PR #23073 shows a 2-minute clip transcribed. Upstream `qwen_asr` caps chunks at 1200 s and pads clips under 0.5 s (`MAX_ASR_INPUT_SECONDS`, `MIN_ASR_INPUT_SECONDS` in `qwen_asr/inference/utils.py`).
- No streaming for Qwen3-ASR: `mtmd_support_audio_stream()` is true only for `PROJECTOR_TYPE_VOXTRAL_RT` (`tools/mtmd/mtmd.cpp`).
- Prompt: mtmd wraps audio as `<|audio_start|>` embeddings `<|audio_end|>` and replaces the marker `<__media__>`. Upstream Qwen3-ASR prompt is `<|im_start|>system\n{context}<|im_end|>\n<|im_start|>user\n{audio}<|im_end|>\n<|im_start|>assistant\n`, output `language <Name><asr_text><transcript>`; forcing a language appends `language X<asr_text>` to the assistant prefix (`qwen_asr/inference/qwen3_asr.py`, `_build_text_prompt`). Upstream also post-processes with `detect_and_fix_repetitions(threshold=20)`. Raj's exact Burmese label and number spelling: UNVERIFIED, measured in Task 1.
- `llama-mtmd-cli` runs single-turn only when `-p` is non-empty; `-p "<__media__>"` makes the user turn audio-only (`tools/mtmd/mtmd-cli.cpp`, `is_single_turn`).
- Sources: https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/multimodal.md , https://github.com/ggml-org/llama.cpp/blob/fb34fc262c1b43f1832c7472429fb2247d650493/tools/mtmd/mtmd.cpp , https://github.com/ggml-org/llama.cpp/issues/22357 , https://github.com/QwenLM/Qwen3-ASR/blob/main/qwen_asr/inference/utils.py

### Q3. Android runtime paths, ranked

| Rank | Path | For | Against |
|---|---|---|---|
| 1 PRIMARY | llama.cpp + libmtmd via JNI, Raj-0.6B GGUF, CPU backends | Exact runtime Raj was released against (pinned SHA); code-switching model; `examples/llama.android` proves NDK build with `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, KleidiAI, NDK 29.0.13113456 (VERIFIED, `lib/build.gradle.kts`); standalone `LLAMA_BUILD_MTMD=ON` exists at the pin | Autoregressive decode is the bottleneck for Burmese (Q4); no streaming partials; llama.android is text-only, so the mtmd glue is ours |
| 2 FALLBACK | Raj-0 GGUF via NeMo-Speech.cpp C SDK (ggml), NDK build | Streaming RNNT with a 4096-token Burmese tokenizer, far fewer decode steps; card says it "ran comfortably on a mid-range Android phone" | Burmese only, English written phonetically; needs `native-runtime.patch` on commit `5be7bfb`; Android build not documented upstream (README lists Linux/macOS/Windows); OpenMDW notice duties |
| 3 | Raj-0 via sherpa-onnx after a custom ONNX export of `raj-0.nemo` | Mature Android AAR, built-in VAD, streaming; sherpa-onnx already re-exported base `nemotron-3.5-asr-streaming-0.6b` (k2-fsa/sherpa-onnx PR #3732, merged 2026-07-08) | Raj-0 is prompt-aware (prompt index 63) with its own tokenizer; export path UNVERIFIED and the most custom work |
| later | OpenCL (Adreno) or Vulkan backend on top of path 1 | Faster decode on Adreno 7xx/8xx | `docs/backend/OPENCL.md`: "A6x GPUs in phones are likely not supported"; Vulkan unmeasured |
| no | MLX, vLLM/transformers | | Apple-only or server-class |

ggml's Android CPU variants are `android_armv8.0_1`, `armv8.2_1` (dotprod), `armv8.2_2` (+fp16), `armv8.6_1` (+i8mm), `armv9.0_1`, `armv9.2_1/2` (VERIFIED, `ggml/src/CMakeLists.txt`), so one APK serves ARMv8.0 phones without SIGILL. Loading them needs `ggml_backend_load_all_from_path(nativeLibraryDir)` and extracted native libs (llama.android sets `android:extractNativeLibs="true"`, VERIFIED).

### Q4. Memory and latency (ESTIMATE unless marked)

- Token rate (measured offline): replaying Raj's own `vocab.json` + `merges.txt` byte-level BPE on four budget phrases gives 1.6 to 1.7 tokens per Burmese code point; "ဒီနေ့ မနက် ကော်ဖီ သုံးထောင် ပေးခဲ့တယ်" is about 56 tokens. Approximate (simplified pre-tokenizer). So a 4 s phrase needs about 60 decode steps and decode speed dominates.
- KV cache: 28 layers x 2 x 8 heads x 128 x 2 bytes = 112 KiB per token, so `n_ctx = 1024` costs 112 MiB (arithmetic from config, VERIFIED inputs).

| Config | Download | RAM (weights + KV + compute) | Decode tok/s, mid-range CPU | 4 s phrase wall time |
|---|---|---|---|---|
| 0.6B Q8_0 | 847 MB | 1.1 to 1.3 GB | 8 to 15 | 5 to 9 s |
| 0.6B Q4_K_M (requant) | about 0.6 GB | 0.8 to 1.0 GB | 13 to 22 | 3.5 to 6 s |
| 1.7B Q8_0 | 2.18 GB | 2.5 to 2.8 GB | 3 to 6 | 12 to 22 s |
| 1.7B Q4_K_M (requant) | about 1.4 GB | 1.7 to 2.0 GB | 6 to 10 | 7 to 12 s |

- Basis: decode is memory-bandwidth bound (LPDDR4X class, 8 to 12 GB/s effective). Cores without dotprod (Cortex-A73/A53 class, for example Snapdragon 680) will be slower still (core lists from memory, UNVERIFIED; Task 2 reads `/proc/cpuinfo`). 1.7B Q8_0 does not fit the headroom of a 4 GB phone; 0.6B does.
- Quants: Q4/Q5 can only be made by `llama-quantize --allow-requantize` from Q8_0 (flag VERIFIED in `tools/quantize/quantize.cpp`), a double quantization whose Burmese accuracy cost is UNVERIFIED; Task 1 measures it. mmproj stays Q8_0. Cleaner path: ask the author for BF16 weights (Decision 2).

### Q5. Model delivery (VERIFIED unless marked)

- HF `resolve/<commit>/<path>` returns 302 to a signed CDN URL (`us.aws.cdn.hf.co/xet-bridge-us/...`, has `Expires`), CDN answers `accept-ranges: bytes`; the 302 carries `x-linked-size` and `x-linked-etag` equal to the sha256, and `x-repo-commit` (HEAD requests, 2026-10-19). Resume = request the resolve URL again (fresh signature) with `Range: bytes=N-`.
- Play: individual asset packs max 1.5 GB compressed; install-time total 4 GB; fast-follow/on-demand total 30 GB (https://support.google.com/googleplay/android-developer/answer/9859372). Play for On-device AI "AI packs" are beta, 1.5 GB per pack, device targeting by RAM/SoC (https://developer.android.com/google/play/on-device-ai). Raj-1.7B's 1.83 GB GGUF would need splitting; 0.6B fits. WS5 ships a GitHub Releases APK, so runtime download is the default (Decision 4).
- Storage: `context.noBackupFilesDir/models/<variant>/<revision>/` (excluded from Auto Backup, app-private), `.part` files, atomic rename after sha256.
- 16 KB pages: Play blocks non-compliant updates of apps targeting API 35+ from 2027-02-01; NDK r28+ aligns by default (https://developer.android.com/guide/practices/page-sizes).
- Licenses: Apache-2.0 4(d) requires passing NOTICE text when redistributing; if we mirror or requantize, 4(b) requires stating the modification. Ship Raj `LICENSE`, `NOTICE`, `UPSTREAM-LICENSE.txt` and llama.cpp MIT text in-app regardless (Task 12).
- Hugging Face reachability from Myanmar networks: UNVERIFIED (Myanmar blocks VPNs and some sites; https://www.internetinmyanmar.com/articles/myanmar-digital-repression-2026). Hence a configurable mirror base URL (Decision 3).

### Q6. Long audio, VAD, streaming

- Push-to-talk, final result only, no streaming partials (not supported for Qwen3-ASR in llama.cpp, Q2).
- WS2 caps recordings at 60 s (`capsAtSixtySeconds` in ws2 plan). WS3 trims silence and splits anything over 20 s at the longest pause inside each 12 to 20 s window (hard cut at 20 s if none), each segment decoded with a cleared KV cache, joined with one space. Segments under 0.5 s are zero-padded (upstream minimum).
- VAD: WebRTC VAD from `gkonovalov/android-vad` 2.0.10 (MIT, about 158 KB, 16 kHz frames of 160/320/480 samples, 16-bit PCM) for v1; the same library's Silero variant (ONNX Runtime) only if noise tests demand it (https://github.com/gkonovalov/android-vad, version currency UNVERIFIED).
- Max new tokens per segment = `ceil(seconds x K) + 16`, K = 1.5 x the tokens-per-audio-second measured in Task 1 (expected 15 to 20), plus a repetition fix, so silence or loops cannot emit runaway text.

## Decisions for the user

1. **Default variant.** Recommend Raj-0.6B as default and Raj-1.7B shown only on phones with at least 8 GB RAM that pass G2. Reason: Q4 estimates put 1.7B at 12 to 22 s for a 4 s phrase on mid-range CPUs. Confirm?
2. **Quantization.** Recommend shipping 0.6B Q8_0 as published unless Task 1 shows Q4_K_M within 3 SER points; Q4_K_M means we host modified weights (Decision 3). Also ask Aung Zay Thant for BF16 weights so we quantize cleanly. Who contacts the author?
3. **Hosting.** Recommend HF pinned revisions as primary plus a built-in mirror base URL (GitHub Release asset, 2 GB per file limit, or Cloudflare R2) filled only if HF is unreliable in Myanmar or we requantize. Mirroring makes us a redistributor (NOTICE duties, Task 12).
4. **Delivery channel.** Recommend first-run download over Play asset or AI packs, because WS5 releases an APK on GitHub and AI packs are beta. Revisit if the app moves to Play.
5. **If G1 or G2 fails.** Recommend (a) a Raj-0 spike via NeMo-Speech.cpp first, then (b) opt-in cloud ASR, then (c) typed entry only. Adding `RAJ_0` to `RajVariant` happens only after (a) passes.
6. **Spike audio.** Who records the 20 budget phrases (at least 2 native speakers, 3 or more with English words such as Grab, taxi, KBZPay), and is FLEURS `my_mm` (CC-BY-4.0, possibly in Raj's training data) acceptable as the general set?

## Global Constraints

- Audio never leaves the device; no cloud ASR; never log or persist PCM on Android (host harness only reads local WAVs).
- llama.cpp is a git submodule at `android/asr/third_party/llama.cpp` pinned to `fb34fc262c1b43f1832c7472429fb2247d650493`. Bumping it requires re-running Task 1 parity and G1.
- Model URLs use the pinned HF commits and sha256 values from Q1, never `main`.
- arm64-v8a only, NDK `29.0.13113456`, CMake `3.31.6`, CPU backends only in v1 (`GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_CPU_KLEIDIAI=ON`, `GGML_NATIVE=OFF`, `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF`). Every `.so` 16 KB aligned.
- Greedy decoding (temperature 0) everywhere so host and device outputs are comparable.
- No commit, push, tag or model upload without the user's explicit approval at execution time. GitHub account for this repo is `codes71`, repo-local identity only.
- Contract deltas (additive, flagged to the orchestrator): `ModelManager` is an interface (same name) with `DefaultModelManager` as impl so WS2 can fake it, adding `activeVariant: StateFlow<RajVariant?>`, `delete(variant)`, `filesFor(variant)` and `allowMetered: Boolean = false` on `ensureDownloaded`; `ModelState.Ready.path` is the variant directory holding two GGUFs; `RajVariant` gains `displayName`, `downloadBytes`, `modelId` (no `RAJ_0`); `transcribe` returns `text = ""` for no speech, throws `AsrException`, and coroutine cancellation aborts native decode; `:app` must set `packaging { jniLibs { useLegacyPackaging = true } }`.

## Review Focus

1. **Wrong CPU variant or unextracted libs on low-end SoCs** (SIGILL or "no backend" on ARMv8.0 phones). Test: Task 3 `NativeLoadTest.cpuBackendRegisteredOnDevice`, run on the lowest-feature phone P1, plus Task 2 G2 on the same phone.
2. **Truncated, corrupted or silently updated model marked Ready.** Test: Task 8 `resumedDownloadWithCorruptedTailFailsSha` and `urlsUsePinnedRevisionNotMain`; Task 6 `loadFailsCleanlyOnTruncatedFile`.
3. **Silence or repetition loop turns into bogus amounts** (for example "သုံးထောင်" repeated and summed by the parser). Test: Task 4 `collapses_repeated_phrase`; Task 10 `silenceReturnsEmptyTextWithoutOpeningEngine` and `maxTokensScalesWithAudio`.
4. **Low-memory kill on 4 GB phones** (1.7B selected, engine held in background). Test: Task 11 `variantEligibilityBlocks17BBelow7_5GB` and `releasesEngineOnTrimMemory`.
5. **Host and device diverge, so Mac eval scores do not predict the phone.** Test: Task 5 `test_host_matches_server_golden` and Task 6 `transcribesGoldenClip` (device text equals host CPU text).

## File Structure

```
.gitmodules                                   (add llama.cpp submodule)
android/asr/
  build.gradle.kts  consumer-rules.pro  src/main/AndroidManifest.xml
  third_party/llama.cpp/                      (submodule @ fb34fc2)
  src/main/cpp/CMakeLists.txt  raj_text.h  raj_text.cpp  raj_engine.h  raj_engine.cpp  raj_jni.cpp
  src/main/java/app/budgetwise/asr/
    SpeechToText.kt (SpeechToText, AsrResult)  AsrException.kt  ModelState.kt  RajVariant.kt
    ModelManager.kt  DefaultModelManager.kt  RajSpeechToText.kt  DeviceProfile.kt  MemoryGuard.kt  AsrLicenses.kt
    engine/RajEngine.kt  engine/NativeRajEngine.kt  engine/RajNative.kt
    audio/SpeechSegmenter.kt  audio/WebRtcFrameClassifier.kt
    download/ModelSource.kt  download/ModelDownloader.kt  download/ModelDownloadWorker.kt  download/StorageCheck.kt
  src/main/assets/licenses/{raj-0.6b,raj-1.7b,llama.cpp,android-vad}/...
  src/test/java/app/budgetwise/asr/...        (JVM + Robolectric)
  src/androidTest/java/app/budgetwise/asr/... (device, models pushed via adb)
  host/CMakeLists.txt  host/raj_host_main.cpp  host/tests/raj_text_test.cpp  host/tests/parity_test.py  host/tests/prompt_forced.txt
  host/spike/build_llamacpp.sh  run_spike.py  test_run_spike.py  build_android_cli.sh  bench_phone.sh  RESULTS.md  golden/*.jsonl
  scripts/check_16kb_alignment.sh
```

Clips and model files live outside the repo (`~/.cache/budgetwise-eval/`), golden files hold text only.

## Tasks

### Task 1: Mac spike and gate G1 (no Android code)

**Files:** `android/asr/host/spike/build_llamacpp.sh`, `run_spike.py`, `test_run_spike.py`, `RESULTS.md`, `golden/<label>-<mode>.jsonl`
**Interfaces:**
- `build_llamacpp.sh <dest>`: clone llama.cpp, checkout `fb34fc262c1b43f1832c7472429fb2247d650493`, build `llama-server llama-mtmd-cli llama-quantize`.
- `run_spike.py --server http://127.0.0.1:8089 --clips clips.jsonl --mode auto|forced --label raj-0.6b-q8_0 --out golden/...jsonl`; clip rows `{id, wav, ref, kind: budget|fleurs|silence}`; output rows `{id, raw, text, language, audio_ms, prompt_ms, predicted_ms, predicted_n}` plus summary `{ser, cer, amount_hits, loops, tokens_per_audio_s, p50_ms}`.
- `build_prompt(forced: str | None) -> str`, `parse_raw(raw: str) -> tuple[str, str]`, `syllables(text: str) -> list[str]`, `ser(ref: str, hyp: str) -> float`, `amount_hit(ref: str, hyp: str) -> bool`.

- [ ] Write failing `test_run_spike.py`: `test_parse_raw_strips_language_prefix` (`parse_raw("language Burmese<asr_text>ကော်ဖီ") == ("Burmese", "ကော်ဖီ")`), `test_parse_raw_language_none_is_empty`, `test_build_prompt_forced_appends_tag` (ends with `<|im_start|>assistant\nlanguage Burmese<asr_text>`, exactly one `<__media__>`), `test_ser_identical_is_zero`, `test_amount_hit_requires_exact_number_phrase` (ref သုံးထောင်, hyp သုံးသောင်း -> False).
- [ ] Run `python3 -m pytest android/asr/host/spike/test_run_spike.py`. Expected FAIL (module missing).
- [ ] Implement `run_spike.py` with stdlib only: POST `/completion` with `{"prompt": {"prompt_string": build_prompt(f), "multimodal_data": [b64wav]}, "temperature": 0, "n_predict": 512, "cache_prompt": false}`; minimal sylbreak-style syllable split (WS5 `syllables.py` replaces it later).
- [ ] Run the same pytest. Expected PASS.
- [ ] Build llama.cpp at the pin; `hf download aungzaythant/Raj-0.6B --revision 92b89bafb8b48fe47857665ca834390b7303c7d8 --include "gguf/*" --local-dir ~/.cache/budgetwise-eval/raj-0.6b` (same for 1.7B at `7dbed379408543ec4b88b6a28f2435422b83c5fe`); `shasum -a 256` must match Q1.
- [ ] Clips (16 kHz mono s16 WAV via `ffmpeg -ac 1 -ar 16000 -sample_fmt s16`): 20 budget phrases (Decision 6), 30 FLEURS `my_mm` test utterances, 3 silence/room-noise clips of 5 s.
- [ ] Requantize: `llama-quantize --allow-requantize Raj-0.6B-Q8_0.gguf Raj-0.6B-Q4_K_M.gguf Q4_K_M` (and Q5_K_M). Serve each with `llama-server -m <gguf> --mmproj <mmproj> -c 4096 --temp 0 --port 8089`; run matrix {0.6B Q8_0, 0.6B Q5_K_M, 0.6B Q4_K_M, 1.7B Q8_0} x {auto, forced}. Sanity: `llama-mtmd-cli -m <gguf> --mmproj <mmproj> --audio <wav> -p "<__media__>" --temp 0 -n 256` gives the same text as the server for 3 clips.
- [ ] Write `RESULTS.md`: per-config metrics, Mac latency, tokens per audio second, the literal language label, 5 examples of number spelling (words, Burmese digits, ASCII) and English-word script. SendMessage number form and label to ws4, and the server command to ws5 for `gates.toml [asr] cmd`.
- [ ] Gate G1, GO only if the chosen 0.6B quant on budget clips has: SER < 40% (WS5 M0), amount phrase exact in at least 16 of 20, no repetition loop, and empty or near-empty text on all 3 silence clips. Q4_K_M is chosen only if within 3 SER points of Q8_0 with at most 1 fewer amount hit. NO-GO: stop and take Decision 5 to the user.
- [ ] Commit after user approval: `chore(asr): add Raj Mac spike harness and G1 results`.

### Task 2: Phone CLI benchmark and gate G2 (no app code)

**Files:** `android/asr/host/spike/build_android_cli.sh`, `bench_phone.sh`, `RESULTS.md`
**Interfaces:** `bench_phone.sh <serial> <label> <gguf> <mmproj> <clipdir> <threads>` prints CSV `id,audio_ms,wall_ms,rtf,vmhwm_kb`; `bench_phone.sh --self-test`.

- [ ] Write failing self-test: canned `llama-mtmd-cli` log fixture must yield header `id,audio_ms,wall_ms,rtf,vmhwm_kb` and `rtf = wall_ms / audio_ms` to 2 decimals.
- [ ] Run `bash android/asr/host/spike/bench_phone.sh --self-test`. Expected FAIL (script missing).
- [ ] Implement: NDK cmake per `docs/android.md` (`-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF`) plus `-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_CPU_KLEIDIAI=ON`; push to `/data/local/tmp/raj`; run `LD_LIBRARY_PATH=lib ./bin/llama-mtmd-cli ... -p "<__media__>" --temp 0 -t N`; poll `/proc/<pid>/status` VmHWM every 200 ms.
- [ ] Run self-test. Expected PASS.
- [ ] Benchmark P1 (4 GB, Helio G85/G99 or Snapdragon 680 class) and P2 (6 to 8 GB, Snapdragon 7-series class) at 2 and 4 threads on 10 budget clips; record `getprop ro.soc.model`, `/proc/cpuinfo` Features (`asimddp`, `i8mm`) and the loaded CPU variant name.
- [ ] Gate G2: on P1 the chosen 0.6B quant has p50 RTF <= 1.0 (WS5 M0), p95 wall <= 8 s for clips <= 8 s, VmHWM <= 1.6 GB, no SIGILL. 1.7B is offered only if on P2 p50 RTF <= 1.5 and VmHWM <= 3.2 GB. If P1 fails, retry Q4_K_M and 2 threads, then NO-GO (Decision 5).
- [ ] Commit after approval: `chore(asr): add phone CLI benchmark and G2 results`.

### Task 3: `:asr` skeleton, contract types, native build

**Files:** `.gitmodules`, `android/asr/build.gradle.kts`, `AndroidManifest.xml`, `consumer-rules.pro`, `src/main/cpp/CMakeLists.txt`, `src/main/cpp/raj_jni.cpp`, `SpeechToText.kt`, `ModelState.kt`, `RajVariant.kt` (constants only), `AsrException.kt`, `engine/RajNative.kt`; tests `src/test/.../ContractTypesTest.kt`, `src/androidTest/.../NativeLoadTest.kt`
**Interfaces:** shared contract verbatim, plus
```kotlin
sealed class AsrException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class ModelNotReady(val state: ModelState) : AsrException("model not ready: $state")
  class EngineFailure(val code: Int, detail: String) : AsrException(detail)
}
internal object RajNative { external fun initBackends(nativeLibDir: String): String }
```

- [ ] Write failing tests: `ContractTypesTest.modelStateWhenIsExhaustive`, `asrResultEquality`; `NativeLoadTest.cpuBackendRegisteredOnDevice` (returned description contains `CPU` and an `android_armv` variant name).
- [ ] Run `cd android && ./gradlew :asr:testDebugUnitTest`. Expected FAIL (unresolved symbols).
- [ ] Implement: library namespace `app.budgetwise.asr`, `ndkVersion = "29.0.13113456"`, CMake 3.31.6, `abiFilters += "arm64-v8a"`, CMake args as in Global Constraints plus `-DBUILD_SHARED_LIBS=ON -DLLAMA_BUILD_MTMD=ON -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_APP=OFF` (verify option names at the pin); `raj_jni.cpp` calls `ggml_backend_load_all_from_path` and returns registered backend names.
- [ ] Run unit tests (PASS) and `./gradlew :asr:connectedDebugAndroidTest` on P1 (PASS, needs `useLegacyPackaging = true` in the test APK and in `:app`). `llvm-objdump -p` shows `align 2**14` for every `.so`.
- [ ] Commit after approval: `feat(asr): add asr module skeleton with pinned llama.cpp build`.

### Task 4: Text post-processing in C++ (`raj_text`)

**Files:** `android/asr/src/main/cpp/raj_text.h`, `raj_text.cpp`, `android/asr/host/CMakeLists.txt`, `host/tests/raj_text_test.cpp`, `host/tests/prompt_forced.txt`
**Interfaces:**
```cpp
std::string raj_build_prompt(const std::string & context, const std::string & forced_language); // ChatML + <__media__>
std::string raj_strip_asr_prefix(const std::string & raw, std::string * language_out);
std::string raj_fix_repetitions(const std::string & s, int threshold = 20); // port of qwen_asr detect_and_fix_repetitions
std::string raj_postprocess(const std::string & raw); // strip prefix, fix repeats, drop U+200B, collapse spaces, trim
```

- [ ] Write failing tests (plain asserts): `strips_language_prefix`, `language_none_is_empty`, `no_tag_returns_whole_text`, `collapses_char_run_over_threshold`, `collapses_repeated_phrase` ("ကော်ဖီ " x 25 -> "ကော်ဖီ"), `keeps_short_repeats` ("၅၅၅" unchanged), `prompt_matches_spike` (equals `prompt_forced.txt` written from Task 1 `build_prompt("Burmese")`).
- [ ] Run `cmake -S android/asr/host -B android/asr/host/build -DGGML_METAL=OFF && cmake --build android/asr/host/build --target raj_text_test && ctest --test-dir android/asr/host/build -R raj_text`. Expected FAIL (target missing).
- [ ] Implement UTF-8 aware functions operating on code points.
- [ ] Run the same command. Expected PASS.
- [ ] Commit after approval: `feat(asr): add Raj transcript post-processing`.

### Task 5: Engine core and host CLI `raj-host`

**Files:** `android/asr/src/main/cpp/raj_engine.h`, `raj_engine.cpp`, `android/asr/host/raj_host_main.cpp`, `host/tests/parity_test.py`
**Interfaces:**
```cpp
struct RajParams { int n_threads = 4; int n_ctx = 1024; std::string forced_language; };
struct RajResult { int status; std::string text, raw; int n_audio_tokens, n_gen_tokens; int64_t encode_ms, decode_ms; };
class RajEngine {
 public:
  static std::unique_ptr<RajEngine> load(const std::string & model, const std::string & mmproj, const RajParams & p, std::string * err);
  RajResult transcribe(const float * pcm16k, size_t n_samples, int max_new_tokens);
  void request_abort();
  ~RajEngine();
};
```
CLI: `raj-host --model M --mmproj P --clips clips.jsonl [--forced Burmese] [--threads 4] --out out.jsonl` (Task 1 row schema plus `model_id`, `llama_cpp_sha`).

- [ ] Write failing `parity_test.py::test_host_matches_server_golden`: for 5 clips, host text equals a CPU-only server run (`-ngl 0 --no-mmproj-offload`) exactly, and SER versus the Task 1 Metal golden is <= 0.02.
- [ ] Run `python3 -m pytest android/asr/host/tests/parity_test.py`. Expected FAIL (`raj-host` missing).
- [ ] Implement: `mtmd_init_from_file` (`use_gpu=false`, `warmup=false`), `mtmd_bitmap_init_from_audio`, `mtmd_tokenize` on `raj_build_prompt`, `mtmd_helper_eval_chunks`, greedy loop via `llama_sampler_init_greedy` until EOG or limit, `llama_memory_clear` per call, abort through `llama_set_abort_callback` plus a per-token flag, `raj_postprocess`. Host CLI decodes WAV with `mtmd_helper_bitmap_init_from_file`.
- [ ] Run the same pytest. Expected PASS. SendMessage ws5 the `raj-host` command for `gates.toml`.
- [ ] Commit after approval: `feat(asr): add Raj engine core and host CLI`.

### Task 6: JNI bridge and `NativeRajEngine`

**Files:** `android/asr/src/main/cpp/raj_jni.cpp`, `engine/RajEngine.kt`, `engine/NativeRajEngine.kt`, `engine/RajNative.kt`; test `src/androidTest/.../NativeEngineTest.kt`
**Interfaces:**
```kotlin
data class ModelFiles(val model: File, val mmproj: File)
data class EngineOutput(val text: String, val raw: String, val genTokens: Int, val encodeMs: Long, val decodeMs: Long)
interface RajEngine : Closeable { fun transcribe(pcm: FloatArray, maxNewTokens: Int): EngineOutput; fun abort() }
fun interface RajEngineFactory { fun open(files: ModelFiles, threads: Int): RajEngine }
class NativeRajEngineFactory(context: Context, private val forcedLanguage: String?) : RajEngineFactory
internal object RajNative {
  external fun load(model: String, mmproj: String, threads: Int, nCtx: Int, forced: String?): Long // 0 = failure
  external fun transcribe(handle: Long, pcm: FloatArray, maxNewTokens: Int): EngineOutput?
  external fun abort(handle: Long); external fun free(handle: Long); external fun lastError(handle: Long): String
}
```

- [ ] Write failing instrumented tests (`assumeTrue` on files pushed with `adb push` to `getExternalFilesDir("raj")`): `transcribesGoldenClip` (equals host CPU text for `budget_01`), `abortStopsWithin500ms`, `transcribeAfterAbortSucceeds`, `loadFailsCleanlyOnTruncatedFile` (first 10 MB copy -> handle 0, no crash), `freeTwiceIsSafe`.
- [ ] Run `./gradlew :asr:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.budgetwise.asr.NativeEngineTest`. Expected FAIL.
- [ ] Implement: handle wraps `RajEngine*`; copy samples with `GetFloatArrayRegion`; `free` nulls the pointer under a mutex; failures set `lastError`.
- [ ] Run the same command. Expected PASS.
- [ ] Commit after approval: `feat(asr): add JNI bridge for Raj engine`.

### Task 7: VAD trim and segmentation

**Files:** `audio/SpeechSegmenter.kt`, `audio/WebRtcFrameClassifier.kt`; test `src/test/.../SpeechSegmenterTest.kt`
**Interfaces:**
```kotlin
fun interface FrameClassifier { fun isSpeech(frame: ShortArray): Boolean } // 30 ms = 480 samples at 16 kHz
class SpeechSegmenter(private val classifier: FrameClassifier, val maxSegmentMs: Int = 20_000, val minSpeechMs: Int = 300, val padMs: Int = 200) {
  fun segments(audio: FloatArray): List<FloatArray> // empty list = no speech
}
class WebRtcFrameClassifier : FrameClassifier, Closeable // android-vad webrtc 2.0.10, Mode.VERY_AGGRESSIVE
```

- [ ] Write failing JVM tests with a scripted classifier: `silenceOnlyReturnsEmpty`, `trimsSilenceKeepingPad`, `blipShorterThanMinSpeechIsDropped`, `splitsAtLongestPauseUnderMax` (45 s, pauses at 14 s and 31 s -> 3 segments, each <= 20 s), `hardSplitsWithoutPause` (25 s -> 20 s + 5 s), `shortSegmentPaddedTo500ms`.
- [ ] Run `./gradlew :asr:testDebugUnitTest --tests '*SpeechSegmenterTest'`. Expected FAIL.
- [ ] Implement: float to 16-bit frames, speech mask, pad, split by pause search in [12 s, 20 s].
- [ ] Run the same command. Expected PASS.
- [ ] Commit after approval: `feat(asr): add VAD-based speech segmentation`.

### Task 8: Variant catalog and verified resumable downloader

**Files:** `RajVariant.kt`, `download/ModelSource.kt`, `download/ModelDownloader.kt`; test `src/test/.../ModelDownloaderTest.kt` (MockWebServer)
**Interfaces:**
```kotlin
data class ModelFile(val path: String, val bytes: Long, val sha256: String)
enum class RajVariant(val displayName: String, val repo: String, val revision: String, val model: ModelFile, val mmproj: ModelFile) {
  RAJ_0_6B(...), RAJ_1_7B(...); // values from Q1
  val downloadBytes: Long get() = model.bytes + mmproj.bytes
  val modelId: String get() // "raj-0.6b-q8_0@92b89ba"
}
class ModelSource(val baseUrl: HttpUrl = "https://huggingface.co/".toHttpUrl()) {
  fun urlFor(v: RajVariant, f: ModelFile): HttpUrl // {base}{repo}/resolve/{revision}/{path}
}
class ModelDownloader(private val client: OkHttpClient, private val source: ModelSource) {
  suspend fun fetch(v: RajVariant, f: ModelFile, dest: File, onBytes: (Long) -> Unit)
}
sealed class DownloadException(msg: String) : Exception(msg) { class HashMismatch(val file: String) : DownloadException(file); class Http(val code: Int) : DownloadException("http $code") }
```
If Task 1 selects Q4_K_M, `RAJ_0_6B.model` points at the mirrored requantized file and its sha256 (Decisions 2 and 3).

- [ ] Write failing tests: `urlsUsePinnedRevisionNotMain`, `followsRedirectToCdn`, `resumesWithRangeHeader` (1000-byte `.part` -> `Range: bytes=1000-`, 206 appended, hash OK), `restartsWhenServerIgnoresRange` (200 -> rewrite), `hashMismatchDeletesPartAndThrows`, `resumedDownloadWithCorruptedTailFailsSha`, `cancellationKeepsPartFile`.
- [ ] Run `./gradlew :asr:testDebugUnitTest --tests '*ModelDownloaderTest'`. Expected FAIL.
- [ ] Implement: hash the existing `.part`, then stream through `DigestOutputStream`, `ensureActive()` every 64 KB, rename only after sha256 and size match.
- [ ] Run the same command. Expected PASS.
- [ ] Commit after approval: `feat(asr): add pinned verified model downloader`.

### Task 9: `DefaultModelManager` and download worker

**Files:** `ModelManager.kt`, `DefaultModelManager.kt`, `download/ModelDownloadWorker.kt`, `download/StorageCheck.kt`, manifest permissions; test `src/test/.../DefaultModelManagerTest.kt` (Robolectric, `work-testing`)
**Interfaces:**
```kotlin
interface ModelManager {
  val state: StateFlow<ModelState>            // state of activeVariant
  val activeVariant: StateFlow<RajVariant?>
  suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean = false)
  suspend fun delete(variant: RajVariant)
  fun filesFor(variant: RajVariant): ModelFiles? // non-null only when verified
}
class DefaultModelManager(context: Context, workManager: WorkManager, storage: StorageCheck = StorageCheck(context)) : ModelManager
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) // foreground, dataSync type
class StorageCheck(context: Context) { fun hasRoomFor(bytes: Long): Boolean } // bytes x 1.1 + 200 MB free
```

- [ ] Write failing tests: `missingWhenNoFiles`, `readyAfterVerifiedMarkerPresent` (`verified.json` lists sha256s; `Ready.path` is the variant dir), `enqueuesUniqueWorkUnmeteredByDefault`, `allowMeteredUsesConnected`, `insufficientStorageFailsBeforeNetwork`, `progressMapsToDownloadingFraction`, `deleteRemovesDirAndReturnsMissing`, `switchingVariantKeepsOtherFiles`.
- [ ] Run `./gradlew :asr:testDebugUnitTest --tests '*DefaultModelManagerTest'`. Expected FAIL.
- [ ] Implement: unique work per variant, worker calls `ModelDownloader` for both files into `noBackupFilesDir/models/<variant>/<revision>/`, writes `verified.json` last; permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (runtime `POST_NOTIFICATIONS` asked by WS2).
- [ ] Run the same command. Expected PASS.
- [ ] Commit after approval: `feat(asr): add model manager with background download`.

### Task 10: `RajSpeechToText`

**Files:** `RajSpeechToText.kt`; test `src/test/.../RajSpeechToTextTest.kt` (fake engine and manager, `kotlinx-coroutines-test`)
**Interfaces:**
```kotlin
class RajSpeechToText(
  private val modelManager: ModelManager,
  private val engineFactory: RajEngineFactory,
  private val segmenter: SpeechSegmenter,
  private val device: DeviceProfile,
  private val clock: () -> Long = { SystemClock.elapsedRealtime() },
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeechToText, Closeable {
  override suspend fun transcribe(audio: FloatArray): AsrResult
  suspend fun warmUp()
  fun release()
  override fun close()
  companion object {
    fun create(context: Context, modelManager: ModelManager): RajSpeechToText // for WS2 Hilt binding
    fun maxNewTokens(samples: Int): Int // ceil(sec x K) + 16, K from Task 1
  }
}
```

- [ ] Write failing tests: `notReadyThrowsModelNotReady`, `silenceReturnsEmptyTextWithoutOpeningEngine`, `joinsSegmentsWithSingleSpace`, `audioMsFrom16kSamples` (32_000 -> 2000), `concurrentCallsAreSerialized`, `cancellationCallsAbortAndNextCallWorks`, `engineReusedAcrossCalls`, `variantSwitchReopensEngine`, `maxTokensScalesWithAudio`, `modelIdIncludesRevisionAndLlamaSha` (`raj-0.6b-q8_0@92b89ba+llama.cpp@fb34fc2`).
- [ ] Run `./gradlew :asr:testDebugUnitTest --tests '*RajSpeechToTextTest'`. Expected FAIL.
- [ ] Implement: `Mutex` around the whole call, `withContext(dispatcher)`, segments loop, `try/finally` calling `engine.abort()` on cancellation, engine cached per variant, `decodeMs` = wall time.
- [ ] Run the same command. Expected PASS.
- [ ] Commit after approval: `feat(asr): add RajSpeechToText`.

### Task 11: Device profile, memory guard, on-device benchmark

**Files:** `DeviceProfile.kt`, `MemoryGuard.kt`; tests `src/test/.../DeviceProfileTest.kt`, `src/androidTest/.../OnDeviceBenchmarkTest.kt`
**Interfaces:**
```kotlin
data class DeviceProfile(val totalRamBytes: Long, val coreMaxKhz: List<Int>, val socModel: String) {
  fun threads(): Int                   // cores >= 0.8 x fastest, clamped 2..4 (G2 may override)
  fun eligible(v: RajVariant): Boolean // RAJ_1_7B needs >= 7.5 GB total RAM and G2 pass
  companion object { fun detect(context: Context): DeviceProfile }
}
class MemoryGuard(private val stt: RajSpeechToText) : ComponentCallbacks2 // onTrimMemory(>= TRIM_MEMORY_BACKGROUND) -> stt.release()
```

- [ ] Write failing tests: `threadsCountsBigCores` (2 x 2.2 GHz + 6 x 1.8 GHz -> 2; 1 + 3 + 4 layout -> 4), `threadsNeverBelowTwo`, `variantEligibilityBlocks17BBelow7_5GB` (4 and 6 GB false, 8 GB true), `releasesEngineOnTrimMemory`; instrumented `benchmarkGoldenClips` (logs RTF and VmHWM, asserts p50 RTF <= 1.0 for 0.6B on P1, `assumeTrue` model present).
- [ ] Run `./gradlew :asr:testDebugUnitTest --tests '*DeviceProfileTest'`. Expected FAIL.
- [ ] Implement: `ActivityManager.MemoryInfo.totalMem`, `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`, `Build.SOC_MODEL` (API 31+, else `ro.board.platform`).
- [ ] Run unit and instrumented tests. Expected PASS.
- [ ] Commit after approval: `feat(asr): add device profile and memory guard`.

### Task 12: Licenses, NOTICE assets, 16 KB check

**Files:** `src/main/assets/licenses/raj-0.6b/{LICENSE,NOTICE,UPSTREAM-LICENSE.txt}`, `raj-1.7b/...`, `llama.cpp/LICENSE`, `android-vad/LICENSE`, `AsrLicenses.kt`; test `src/test/.../AsrLicensesTest.kt`; `android/asr/scripts/check_16kb_alignment.sh`
**Interfaces:**
```kotlin
data class LicenseEntry(val component: String, val license: String, val text: String)
object AsrLicenses { fun all(context: Context): List<LicenseEntry> } // WS2 renders under Settings, Open-source licenses
```

- [ ] Write failing tests: `includesRajNoticeVerbatim` (contains "Aung Zay Thant" and "Qwen/Qwen3-ASR"), `includesLlamaCppMit`, `everyEntryNonEmpty`, `mirroredVariantDeclaresModification` (only if Decision 2 picks requantized weights).
- [ ] Run `./gradlew :asr:testDebugUnitTest --tests '*AsrLicensesTest'`. Expected FAIL.
- [ ] Implement: copy files from the pinned revisions and check them against each repo's `SHA256SUMS` (0.6B NOTICE `7b0a84b206927d4fadc081d42cd9379bf5c2c98859171af297bc9f071463f9c4`, 1.7B NOTICE `09c4d5678695a8d10f594aa181cdbac9a41f8acafa843111e70e472eb3eb0db6`); script runs `zipalign -v -c -P 16 4 <apk>` and fails on any `.so` LOAD segment below `2**14`.
- [ ] Run tests and `bash android/asr/scripts/check_16kb_alignment.sh app/build/outputs/apk/debug/app-debug.apk`. Expected PASS ("Verification successful").
- [ ] Commit after approval: `feat(asr): ship model and runtime license notices`.
