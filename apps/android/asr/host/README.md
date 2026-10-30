# :asr host tools (Mac)

- `export/freococo_onnx.py` exports freococo/myanmar_asr to `freococo.onnx` (16 kHz audio in, syllable ids out).
- `onnx/ort_reference.py` runs both ONNX models with Python onnxruntime, with the same session settings and the
  same pre- and post-processing as the phone (`engine/OnnxEngines.kt`, `engine/CtcDecoding.kt`). It writes the
  device test goldens and the JVM decode fixtures, and can measure peak memory.
- `spike/` is the October Raj bake-off (scripts, results and the CER/SER scorer in `run_spike.py`), kept as evidence.

## Environment

onnxruntime must be the version in `apps/android/gradle/libs.versions.toml`. Use a separate venv; do not upgrade
the shared `~/.cache/budgetwise-eval/venv-asr`:

```
cd ~/.cache/budgetwise-eval
uv venv --python 3.12 venv-ort-1.31.1
VIRTUAL_ENV=$PWD/venv-ort-1.31.1 uv pip install "onnxruntime==1.31.1" "numpy<3" soundfile
```

## Regenerating goldens and fixtures

From `apps/android/asr` (defaults: the eval cache models and `my-voice/audio/wav/r*.wav`):

```
PY=~/.cache/budgetwise-eval/venv-ort-1.31.1/bin/python
W=~/.cache/budgetwise-eval/my-voice/audio/wav
$PY -I host/onnx/ort_reference.py transcribe --out src/androidTest/assets/voice/goldens.json
$PY -I host/onnx/ort_reference.py fixtures --out src/test/resources/voice/decode-fixtures.json \
    $W/r01.wav $W/r02.wav $W/r10.wav $W/r16.wav
```

## Why these session settings

The int8 Omnilingual model quantizes activations on the fly, so any rounding difference flips int8 values and
changes the transcript. Measured on the Mac (M5 Pro, onnxruntime 1.31.1), Omnilingual only; freococo gave the
PyTorch transcript at every setting on all 48 clips.

| Setting (KleidiAI off) | user clips: same as 1 thread | FLEURS: same as 1 thread | FLEURS CER | FLEURS SER |
|---|---|---|---|---|
| no optimization, 2 threads (shipped) | 18 / 18 | 30 / 30 | 7.9 % | 17.8 % |
| no optimization, 4 threads | 7 / 18 | 13 / 30 | 8.1 % | 18.4 % |
| all optimizations, 2 threads | 17 / 18 | 28 / 30 | 8.2 % | 18.0 % |
| all optimizations, 4 threads | 11 / 18 | 17 / 30 | 8.1 % | 17.9 % |

- KleidiAI on (the Mac's SME kernels, which a Snapdragon 8 Gen 3 lacks) changed 8 to 12 of the 18 user transcripts
  against the same setting with KleidiAI off, so both the Mac and the phone turn it off and run MLAS's NEON kernels.
- On FLEURS every setting is within noise of sherpa-onnx 1.13.8 (CER 8.1 %, SER 18.3 %). The shipped setting is
  the only one where 1 and 2 threads agreed on all 48 clips, which is what lets the Mac stand in for the phone.
- On the user's clips the shipped setting matches the sherpa-onnx transcripts the bake-off was scored on for 8 of
  18 clips, 3.0 % of characters apart; most differences are one or two characters, but the quiet clip r16 loses
  its last word. With all optimizations, 4 threads and KleidiAI on, r16 loses most of its words.
- Weight prepacking, the memory arena and memory patterns are off; that changed none of the 18 transcripts.

Peak RSS on the Mac for Omnilingual alone (Python adds about 50 MB), shipped settings:

| | after load | after a 7 s clip | after a 20 s clip |
|---|---|---|---|
| prepacking off (shipped) | 1.26 GB | 1.49 GB | 1.99 GB |
| prepacking on | 1.58 GB | 1.80 GB | 2.13 GB |

freococo adds about 0.15 GB. Long segments are the risk for the old 1.6 GB limit: `SpeechSegmenter` allows 20 s.
