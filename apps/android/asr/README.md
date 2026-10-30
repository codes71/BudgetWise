# :asr

On-device Burmese speech recognition with two models on ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`):
freococo/myanmar_asr and Meta Omnilingual ASR CTC 1B (int8). Every utterance goes through both, and the caller gets
both transcripts. Pure Kotlin plus Maven AARs: no NDK, CMake or submodule.

## Building

The voice pack (about 1.1 GB) is downloaded at runtime, never bundled. Omnilingual comes from its Hugging Face repo at
a pinned revision. freococo's ONNX export has no public host yet: pass
`-Pbudgetwise.freococoModelBaseUrl=https://<host>/<dir>/` (the directory holding `freococo.onnx` and `vocab.json`).
Without it the download fails at once with "voice model host not configured".

## Using :asr from the app

- Bind the concrete `OnnxSpeechToText` as well as `SpeechToText`. `warmUp()`, `release()` and `close()` live on the
  class, not on the interface. Build it with `OnnxSpeechToText.create(modelManager)`.
- `AsrResult.transcripts` has one `Transcript` per `VoiceModel` (freococo, then Omnilingual). For silence every text
  is "" and `isEmpty` is true; no model runs.
- `AsrResult.decodeMs` includes model load time when the engines were not warmed up first. Call `warmUp()` when the
  pack is Ready (and again on foreground) before measuring latency. Omnilingual loads first, by design.
- Omnilingual reads its whole 1 GB file into memory. Register `MemoryGuard` as a component callback so both engines
  are released under memory pressure.
- `VoiceFiles` (what a fake `ModelManager.files()` returns) is in `app.budgetwise.asr.engine`.
- `AsrLicenses.all(context)` reads assets synchronously. Call it off the main thread.
- The app module needs `ndk { abiFilters += "arm64-v8a" }` unless it wants ONNX Runtime's other three ABIs
  (about 35 MB each). No legacy JNI packaging is needed.
- This module's manifest removes ONNX Runtime's `TelemetryInitializer` provider, and `OnnxSessions` sets
  `ORT_DISABLE_TELEMETRY=1` and turns telemetry off before the runtime is first used. Keep both: since 1.29 the
  runtime otherwise sends 1DS telemetry to Microsoft from app start.
- After `ModelManager.delete`, call `release()` so the engines free the deleted files right away.
- `ensureDownloaded` deletes older packs and the Raj models of earlier builds before its storage check. With a
  different `allowMetered` it replaces a download that is waiting for its network, but not one that is running.

## Changing the runtime or session settings

The int8 Omnilingual model changes its transcripts with any change of runtime version, thread count, optimization
level or kernel family (see `host/README.md`). After any such change, regenerate the goldens and the decode fixtures
with `host/onnx/ort_reference.py` and rerun the device test (`src/androidTest/README.md`).
