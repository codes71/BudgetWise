# :asr

On-device Burmese speech recognition with Raj (Qwen3-ASR based) through llama.cpp. arm64-v8a only.

## Building

A clean build downloads KleidiAI v1.24.0 from GitHub during CMake configure (the archive is MD5-pinned by
llama.cpp), so CI and fresh checkouts need network access. The llama.cpp submodule must be initialized:
`git submodule update --init android/asr/third_party/llama.cpp`.

## Using :asr from the app

- Bind the concrete `RajSpeechToText` as well as `SpeechToText`. `warmUp()`, `release()` and `close()` live on
  the class, not on the interface.
- `warmUp()` after `close()` is a silent no-op.
- `AsrResult.decodeMs` includes model load time when the engine was not warmed up first. Call `warmUp()` when the
  model is Ready (and again on foreground) before measuring latency.
- Loading reads both model files into memory (no mmap, so the repacked weights are not doubled by mapped file
  pages). It takes about 8 s on a Snapdragon 8 Gen 3 and peaks at about 1.44 GB RSS, so show a loading state.
- `AsrResult.truncatedSegments` counts segments that stopped at the token limit; their text may be cut short.
- Register `MemoryGuard` as a component callback so the engine is released under memory pressure.
- `ModelFiles` (what a fake `ModelManager.filesFor` returns) is in `app.budgetwise.asr.engine`.
- `AsrLicenses.all(context)` reads assets synchronously. Call it off the main thread.
- The app module needs `ndkVersion = "29.0.13113456"` (otherwise native libraries ship unstripped, about 138 MB
  extracted instead of about 19 MB), `ndk { abiFilters += "arm64-v8a" }` and
  `packaging { jniLibs { useLegacyPackaging = true } }` (ggml loads its CPU variants from the native library dir).
- After `ModelManager.delete`, call `release()` so the engine unmaps the deleted files right away. Otherwise the
  cached engine is closed only on the next `transcribe`/`warmUp` that finds `filesFor` null, and a Download in
  between reuses the engine that still maps the deleted files.
- `ensureDownloaded` with a different `allowMetered` replaces a download that is waiting for its network, but not
  one that is already running.
