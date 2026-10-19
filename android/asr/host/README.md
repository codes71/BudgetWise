# :asr host tools (Mac)

CPU-only host build of the `:asr` native code: `raj_text` (prompt and transcript cleanup), `raj_engine`
(Raj through llama.cpp + libmtmd, the code the Android JNI wraps) and the `raj-host` CLI. llama.cpp comes
from the `third_party/llama.cpp` submodule with the Android option set, minus Metal, BLAS and llamafile.

Build and run the native tests (from the repo root):

```
BIN=~/Library/Android/sdk/cmake/3.31.6/bin
$BIN/cmake -S android/asr/host -B android/asr/host/build -DCMAKE_BUILD_TYPE=Release
$BIN/cmake --build android/asr/host/build -j
$BIN/ctest --test-dir android/asr/host/build --output-on-failure
```

`raj_engine` needs the Raj files under `~/.cache/budgetwise-eval` (or `RAJ_MODEL`, `RAJ_MMPROJ`, `RAJ_WAV`) and
is skipped without them.

Transcribe a clips file (rows in the `spike/run_spike.py` schema plus `n_audio_tokens`, `model_id`, `llama_cpp_sha`;
no summary line; `predicted_n` excludes the end-of-generation token, llama-server counts it):

```
C=~/.cache/budgetwise-eval
android/asr/host/build/raj-host --model $C/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf \
  --mmproj $C/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf \
  --clips $C/clips/clips.jsonl --threads 4 --out raj-host-auto.jsonl
```

Options: `--forced Burmese` (default auto, as in production), `--max-new-tokens 512`, `--model-id ID`
(default the model file name), `--verbose` (llama.cpp info logs).

Parity with llama-server (needs the build above):

```
~/.cache/budgetwise-eval/venv/bin/python -m pytest android/asr/host/tests/parity_test.py
```
