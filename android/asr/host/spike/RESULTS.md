# Raj Mac spike results (Task 1, gate G1 PROVISIONAL)

Re-scored and regenerated 2026-10-19 (fix round 1). Run 2026-10-19 on Apple Silicon (M-series, 15 cores, 24 GB, Metal), llama.cpp `fb34fc262c1b43f1832c7472429fb2247d650493`
(built by `build_llamacpp.sh`), greedy decoding (`--temp 0`, `n_predict` 512, `cache_prompt` false).
Model files match the Q1 sha256 values (all four GGUFs). Q5_K_M and Q4_K_M are `llama-quantize --allow-requantize`
from Q8_0 (mmproj stays Q8_0); no published mirror exists, so the shipped default stays the published Q8_0.

## Clip set (provisional)

No recorded budget phrases exist yet (user Decision 6 open). Used instead:
30 FLEURS `my_mm` test utterances (google/fleurs, CC-BY-4.0, 3 to 15 s, evenly spaced, built by `make_clips.py`,
mean 12.3 s) plus 3 generated 5 s clips (digital silence, low pink noise, louder brown-noise hum).
FLEURS may overlap Raj's training data, so SER is optimistic. Audio is not in git; `golden/*.jsonl` hold text only
(model output for FLEURS clips; the last line of each file is the summary object). Silence/noise clips use fixed ffmpeg seeds (`make_clips.py`)..
SER is syllable error rate (minimal sylbreak split, Burmese punctuation stripped), pooled over all FLEURS clips.
Refs spell numbers as words in some clips and digits in others, so SER includes style mismatches.

## Matrix

| Config | Mode | SER | CER | Loops | Silence non-empty (of 3) | Pooled tokens / audio s | p50 server ms, prompt + decode (12.3 s mean clip) |
|---|---|---|---|---|---|---|---|
| 0.6B Q8_0 | auto | 19.8% | 12.2% | 0 | 0 | 16.6 | 801 |
| 0.6B Q8_0 | forced | 20.2% | 12.2% | 0 | 3 | 16.4 | 806 |
| 0.6B Q5_K_M | auto | 22.0% | 13.4% | 0 | 0 | 16.6 | 795 |
| 0.6B Q5_K_M | forced | 21.7% | 13.3% | 0 | 3 | 16.4 | 779 |
| 0.6B Q4_K_M | auto | 25.6% | 16.2% | 0 | 0 | 16.4 | 720 |
| 0.6B Q4_K_M | forced | 25.5% | 16.1% | 0 | 3 | 16.2 | 702 |
| 1.7B Q8_0 | auto | 18.6% | 11.8% | 0 | 0 | 16.4 | 1718 |
| 1.7B Q8_0 | forced | 18.7% | 11.4% | 0 | 3 | 16.2 | 1750 |

Latency is Mac Metal only (prompt about 65 ms, decode about 250 tok/s for 0.6B Q8_0, about 120 tok/s for 1.7B); it says nothing
about the phone (G2 measures that). Amount hits are N/A on FLEURS (the summary shows 0 of 0) and PENDING on budget phrases.

Quant verdict: Q4_K_M is 5.8 SER points worse than Q8_0 (limit 3), so it is rejected. Q5_K_M is 2.2 points worse, within the 3 point
limit, but we have no hosting for modified weights, so Q8_0 stays the default. 1.7B gains only 1.2 SER points for 2x latency.

## Silence behaviour

- auto: all 3 silence clips return `language None<asr_text>` with empty text (4 tokens), for every config.
- forced `language Burmese`: all 3 clips hallucinate a short word (0.6B Q8_0 returns `အဲ့ဒါ` three times; Q4 returns `အဲ့ဒီ`/`အာရုံ`; 1.7B returns `မြန်မာ`, `အဲ့ဒါ`, `အဲ့ဒါ`).
- Recommendation: use **auto**, and keep the WS3 VAD pre-trim so silence never reaches the decoder.

## Facts for later tasks

- **Literal language label.** In auto mode every output starts `language None<asr_text>` (the model has no name for Burmese, all 33 clips
  gave `None`). `parse_raw` returns `("None", text)`. To force, append `language Burmese<asr_text>` to the assistant prefix; it works
  (SER equal to auto) but is not needed. When forced, the server returns only the text after the tag.
- **Stop/format.** Output ends with the Burmese full stop `။` on most sentences. Strip `။ ၊` before scoring or parsing.
- **Tokens per audio second.** Pooled over the 30 FLEURS clips (0.6B Q8_0 auto): 16.6 tokens per audio second. Per clip: mean 17.0, min 8.6, max 26.8
  (max across all 8 configs 27.2, Q4 auto). Recommended **K = 30** for `max_new_tokens = ceil(seconds x K) + 16` (covers every observed clip; the
  context's 1.5 x mean would be 25 and would cut the densest clip). Largest single output was 387 tokens, so `n_predict` 512 was never reached
  (0 loops in 264 runs). Budget phrases are unmeasured: short phrases carry about 4 fixed overhead tokens, so tokens per second can exceed 27;
  the `+ 16` term is what covers that, so Task 10 should keep it.
- **Prompt.** `<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<MARKER><|im_end|>\n<|im_start|>assistant\n` (+ `language Burmese<asr_text>` when forced).
  `llama-server` uses a per-process random media marker. Source at the pin: `tools/server/server-common.cpp:134-144` (`get_media_marker()`) returns
  `LLAMA_MEDIA_MARKER` from the environment if set, else `"<__media_" + random_string() + "__>"`; it is exposed as `media_marker` by `GET /props`
  (`tools/server/server-context.cpp:4623`) and passed to mtmd (`server-context.cpp:1057`). A literal `<__media__>` sent to the server therefore gives
  HTTP 400 ("number of media markers in text (0) does not match number of bitmaps (1)"). Options: read `/props` (what `run_spike.py` does) or set
  `LLAMA_MEDIA_MARKER` when starting the server. The **libmtmd C API** (what Task 5's engine calls directly) uses `mtmd_default_marker()`, which
  returns the fixed `"<__media__>"` (`tools/mtmd/mtmd.cpp:445-447`, default in `mtmd_context_params_default` at `:465`); `llama-mtmd-cli -p "<__media__>"`
  relies on it. `run_spike.build_prompt(forced, marker="<__media__>")` takes the marker as an argument.
- **Number spelling** (0.6B Q8_0 auto; every example below is in a committed golden file: 1 and 5 in `golden/raj-0.6b-q8_0-auto.jsonl`
  rows fleurs_00, fleurs_23, fleurs_25; 2 to 4 in `golden/numprobe-raj-0.6b-q8_0-auto.jsonl` rows num_01, num_07, num_11, num_12, produced by
  `probe_numbers.py` on 14 extra FLEURS clips that are not part of the G1 set). ref -> model:
  1. `ကိုး` (word) -> `၉` (Burmese digit), in "၉ ဦးပါသော"
  2. `၂၀ ရာခိုင်နှုန်း` -> `၂၀ ရာခိုင်နှုန်း` (num_00) and `၂၀%` with an ASCII percent sign (num_01)
  3. `နှစ်ဆယ့်တစ်ရာစု` (words) -> `၂၁ ရာစု` (digits, num_07)
  4. `ည ၁၁၃၅` -> `ည ၁၁ နာရီ ၃၅` (num_11) or `ည ၁၁ ၃၅` (num_12), digits split by hour
  5. `M၁၆` -> `M ၁၆` (fleurs_23); `၂၀၀၂` -> `၂၀၀၀` (fleurs_25), digits with errors
  Small counts stay as words (`နှစ်ခု`, `တစ်ပုဒ်`). Digits are Burmese (U+1040 to U+1049); ASCII digits never appeared, ASCII `%` once.
  Kyat magnitude words (`ထောင်`, `သောင်း`, `သိန်း`) with amounts are UNVERIFIED: no budget audio yet. WS4 must accept words, Burmese digits and
  ASCII digits.
- **English words.** Mixed: sometimes Latin script (`Rolan Domingo`, `CEP`, `E M`, `Hamatchek`), often transliterated to Burmese script
  (`Sintra` -> `စင်တာ`, `Kowloon` -> `ကိုလုံး`, `Schengen` -> `ရှမ်း...`). Do not rely on Latin tokens such as `Grab` or `taxi`; confirm with
  voice testing.
- **Server command for `gates.toml [asr] cmd`** (llama-server from `build_llamacpp.sh`, ready when `GET /health` returns 200):
  `llama-server -m ~/.cache/budgetwise-eval/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf --mmproj ~/.cache/budgetwise-eval/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf -c 4096 --temp 0 --port 8089`
  Request: POST `/completion` `{"prompt":{"prompt_string":<build_prompt(None, marker)>,"multimodal_data":[<b64 wav>]},"temperature":0,"n_predict":512,"cache_prompt":false}`.
  The response `content` begins `language None<asr_text>`.
- **`llama-mtmd-cli` vs server.** Identical raw text on 3 of 3 clips (fleurs_23, fleurs_13, fleurs_17) with
  `-p "<__media__>" --temp 0 -n 256` against `golden/raj-0.6b-q8_0-auto.jsonl`. Record: `parity-mtmd-cli.txt`, reproduce with `parity_cli.py`.
- **Golden files.** The Metal golden for Task 5 host parity is **`golden/raj-0.6b-q8_0-auto.jsonl`** (recommended mode). `raw` in every golden file
  is the unmodified server `content`: auto rows start `language None<asr_text>`; forced rows contain the text only (the server does not echo the
  forced prefix), and `language` is the forced label. The last line of each file is the summary object.
- **Provenance.** `checksums.txt` records the four sha256 values verified against Q1 and the pinned HF revisions.
## Gate G1 (PROVISIONAL)

Measured on FLEURS, not budget phrases, for 0.6B Q8_0 auto:
- SER 19.8% (< 40%): pass on FLEURS.
- Repetition loops: 0 of 33 clips: pass.
- Silence: empty text on 3 of 3 clips (auto): pass.
- Amount phrase exact in at least 16 of 20 budget clips: **PENDING user voice testing** (Decision 6).

Provisional GO to continue with Task 2 using 0.6B Q8_0, auto mode. Amount scoring (`run_spike.amount_hit`) compares integers: words, Burmese digits and ASCII digits are normalized, so `သုံးထောင်` equals `၃၀၀၀` and differs from `သုံးသောင်း`. Final G1 needs the 20 recorded budget phrases.

## Reproduce

```
android/asr/host/spike/build_llamacpp.sh ~/.cache/budgetwise-eval/llama.cpp
python3 -I android/asr/host/spike/make_clips.py          # needs fleurs/test.tsv + test.tar.gz
android/asr/host/spike/run_matrix.sh ~/.cache/budgetwise-eval/llama.cpp/build/bin ~/.cache/budgetwise-eval ~/.cache/budgetwise-eval/clips/clips.jsonl
python3 -m pytest android/asr/host/spike/test_run_spike.py
```

## Gate G2 (PASS on the warm path for one flagship phone; CLI bench and 1.7B PENDING)

Status: measured 2026-10-21 on the user's Xiaomi Mi 14 (23127PN0CG, SM8650 Snapdragon 8 Gen 3, 11.7 GB, Android 16) with `OnDeviceBenchmarkTest` over all 30 FLEURS clips at 4 threads, CPU variant `libggml-cpu-android_armv8.6_1.so`. The first runs failed VmHWM (1,896,380 kB: the mapped GGUF pages stayed resident next to the CPU repack copy); loading without mmap fixed it. This is a flagship, not a P1 phone, so G2 on a 4 GB phone is still unmeasured. The CLI 2 vs 4 thread runs and 1.7B are not run yet.

Phone class: SM8650, MemTotal 11.7 GB, cores by cpu_capacity 379 x2, 867 x2, 923 x3, 1024 x1. There is one phone, so G2 is evaluated against the P1 thresholds. The 1.7B criterion (P2) is "not evaluated, one phone" unless the phone has at least 7.5 GB RAM, in which case also run 1.7B Q8_0 at 4 threads.

Build evidence (arm64-v8a, NDK 29.0.13113456, pinned llama.cpp fb34fc2): `build_android_cli.sh` produced `bin/llama-mtmd-cli` (ARM aarch64 ELF) and the ggml CPU variants `android_armv8.0_1`, `armv8.2_1`, `armv8.2_2`, `armv8.6_1`, `armv9.0_1`, `armv9.2_1`, `armv9.2_2`. Install tree is 22 MB, kept outside the repo in `~/.cache/budgetwise-eval/android-cli/install`. ggml loads dlopen'd backends only from the executable's directory, so the CPU variants sit in `bin/` and the other libs in `lib/` (run with `LD_LIBRARY_PATH=lib`).

Clips (10 FLEURS my_mm, no budget-phrase audio exists): fleurs_02 (6.7 s), fleurs_10 (7.5 s), fleurs_08 (8.4 s), fleurs_17 (9.1 s), fleurs_23 (10.2 s), fleurs_03 (9.8 s), fleurs_13 (12.7 s), fleurs_00 (14.3 s), fleurs_25 (14.2 s), fleurs_20 (15.0 s). Only fleurs_02 and fleurs_10 are 8 s or shorter, so the p95 wall check on clips <= 8 s uses those two; report the wall time of the longer clips too.

Run (phone connected, USB debugging on; `SERIAL` from `adb devices`):

```
android/asr/host/spike/build_android_cli.sh
M=~/.cache/budgetwise-eval/raj-0.6b/gguf
C=~/.cache/budgetwise-eval/clips/wav
for T in 2 4; do
  android/asr/host/spike/bench_phone.sh "$SERIAL" q8_0-t$T "$M/Raj-0.6B-Q8_0.gguf" "$M/mmproj-Raj-0.6B-Q8_0.gguf" "$C" $T > g2-q8_0-t$T.csv
done
# fallback if Q8_0 fails: Q4_K_M at 2 threads
android/asr/host/spike/bench_phone.sh "$SERIAL" q4km-t2 "$M/Raj-0.6B-Q4_K_M.gguf" "$M/mmproj-Raj-0.6B-Q8_0.gguf" "$C" 2 > g2-q4km-t2.csv
```

Output is CSV `id,audio_ms,wall_ms,rtf,vmhwm_kb`. Each clip runs in a fresh process, so wall_ms and rtf include process start and model load, and the first row is also cold (`# cold: <id>` on stderr). This is a load-inclusive upper bound. By controller ruling, the G2 p50 RTF threshold is judged on the warm production path from `OnDeviceBenchmarkTest` (instrumented, Task 11b, excludes warmUp); this CLI benchmark supplies the 2 vs 4 thread comparison, SIGILL detection, the CPU variant and VmHWM. Other output: SoC, MemTotal, `asimddp`/`i8mm` features and the loaded CPU variant are printed to stderr as `#` lines. A SIGILL (exit 132 or "Illegal instruction") aborts the run with exit status 3.

G2 thresholds on P1 (4 GB, Helio G85/G99 or Snapdragon 680 class), chosen 0.6B quant:

| Check | Threshold | Measured |
|---|---|---|
| p50 RTF | <= 1.0 (WS5 M0) | 0.537 (warm, 30 clips; 0.589 with mmap) |
| p95 wall, clips <= 8 s | <= 8 s | 7.4 s (fleurs_02 7,385 ms, fleurs_10 5,838 ms; fleurs_02 took 4,003 ms in the mmap run, so latency varies run to run) |
| VmHWM | <= 1,600,000 kB | 1,439,612 kB without mmap (1,896,380 kB with mmap) |
| SIGILL | none | none (NativeLoadTest and all engine tests pass) |
| 1.7B on P2 (p50 RTF <= 1.5, VmHWM <= 3.2 GB) | only if offered | not evaluated, one phone |

Accuracy on the phone: pooled SER 21.4% on the 30 clips against 19.8% for the Mac golden (VAD trim plus CPU kernels; 3 of 30 transcripts identical). Model load without mmap: 8.4 s (5.5 s with mmap).

If P1 fails: retry Q4_K_M at 2 threads, then NO-GO (Decision 5). Self-test (no phone, NDK or models): `bash android/asr/host/spike/bench_phone.sh --self-test`.
