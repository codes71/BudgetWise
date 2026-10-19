# Raj Mac spike results (Task 1, gate G1 PROVISIONAL)

Run 2026-10-19 on Apple Silicon (M-series, 15 cores, 24 GB, Metal), llama.cpp `fb34fc262c1b43f1832c7472429fb2247d650493`
(built by `build_llamacpp.sh`), greedy decoding (`--temp 0`, `n_predict` 512, `cache_prompt` false).
Model files match the Q1 sha256 values (all four GGUFs). Q5_K_M and Q4_K_M are `llama-quantize --allow-requantize`
from Q8_0 (mmproj stays Q8_0); no published mirror exists, so the shipped default stays the published Q8_0.

## Clip set (provisional)

No recorded budget phrases exist yet (user Decision 6 open). Used instead:
30 FLEURS `my_mm` test utterances (google/fleurs, CC-BY-4.0, 3 to 15 s, evenly spaced, built by `make_clips.py`,
mean 12.3 s) plus 3 generated 5 s clips (digital silence, low pink noise, louder brown-noise hum).
FLEURS may overlap Raj's training data, so SER is optimistic. Audio is not in git; `golden/*.jsonl` hold text only
(model output for FLEURS clips; the last line of each file is the summary object).
SER is syllable error rate (minimal sylbreak split, Burmese punctuation stripped), pooled over all FLEURS clips.
Refs spell numbers as words in some clips and digits in others, so SER includes style mismatches.

## Matrix

| Config | Mode | SER | CER | Loops | Silence non-empty (of 3) | Mean tokens / audio s | p50 wall ms (12.3 s mean clip) |
|---|---|---|---|---|---|---|---|
| 0.6B Q8_0 | auto | 19.9% | 12.2% | 0 | 0 | 16.6 | 841 |
| 0.6B Q8_0 | forced | 20.4% | 12.2% | 0 | 3 | 16.4 | 870 |
| 0.6B Q5_K_M | auto | 22.3% | 13.4% | 0 | 0 | 16.6 | 770 |
| 0.6B Q5_K_M | forced | 22.1% | 13.3% | 0 | 3 | 16.4 | 767 |
| 0.6B Q4_K_M | auto | 25.8% | 16.2% | 0 | 0 | 16.4 | 729 |
| 0.6B Q4_K_M | forced | 26.1% | 16.1% | 0 | 3 | 16.2 | 689 |
| 1.7B Q8_0 | auto | 18.4% | 11.8% | 0 | 0 | 16.4 | 1720 |
| 1.7B Q8_0 | forced | 18.4% | 11.4% | 0 | 3 | 16.2 | 1925 |

Latency is Mac Metal only (prompt about 65 ms, decode about 250 tok/s for 0.6B Q8_0, about 120 tok/s for 1.7B); it says nothing
about the phone (G2 measures that). Budget-phrase columns (amount hits) are PENDING.

Quant verdict: Q4_K_M is 5.9 SER points worse than Q8_0 (limit 3), so it is rejected. Q5_K_M is 2.4 points worse, within the 3 point
limit, but we have no hosting for modified weights, so Q8_0 stays the default. 1.7B gains only 1.5 SER points for 2x latency.

## Silence behaviour

- auto: all 3 silence clips return `language None<asr_text>` with empty text (4 tokens), for every config.
- forced `language Burmese`: all 3 clips hallucinate a short word (0.6B Q8_0 returns `အဲ့ဒါ` three times; Q4 returns `အဲ့ဒီ`/`အာရုံ`; 1.7B returns `မြန်မာ`).
- Recommendation: use **auto**, and keep the WS3 VAD pre-trim so silence never reaches the decoder.

## Facts for later tasks

- **Literal language label.** In auto mode every output starts `language None<asr_text>` (the model has no name for Burmese, all 33 clips
  gave `None`). `parse_raw` returns `("None", text)`. To force, append `language Burmese<asr_text>` to the assistant prefix; it works
  (SER equal to auto) but is not needed. When forced, the server returns only the text after the tag.
- **Stop/format.** Output ends with the Burmese full stop `။` on most sentences. Strip `။ ၊` before scoring or parsing.
- **Tokens per audio second.** Decoded tokens / audio second over the 30 FLEURS clips: mean 16.6 (0.6B Q8_0 auto), per-clip min 8.6, max 26.8
  (read speech of long sentences). Budget phrases are shorter and likely slower speech, but unmeasured. Recommended **K = 30** for
  `max_new_tokens = ceil(seconds x K) + 16` (above the observed max; the context's 1.5 x mean = 25 would have cut the densest clip).
  Observed max tokens for a 14 s clip: 376 (limit 512 never reached, 0 loops in 66 runs).
- **Prompt.** `<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<MARKER><|im_end|>\n<|im_start|>assistant\n` (+ `language Burmese<asr_text>` when forced).
  **llama-server randomizes the media marker per process**: read `media_marker` from `GET /props` (here `<__media_...__>`), a literal
  `<__media__>` gives HTTP 400 "number of media markers in text (0) does not match". `mtmd_default_marker()` is `<__media__>` for in-process use
  (libmtmd, `llama-mtmd-cli -p "<__media__>"`). `run_spike.build_prompt(forced, marker="<__media__>")` takes the marker as an argument.
- **Number spelling** (0.6B Q8_0 auto, FLEURS clips; ref -> model):
  1. `ကိုး` (word) -> `၉` (Burmese digit), in "၉ ဦးပါသော"
  2. `၂၀ ရာခိုင်နှုန်း` -> `၂၀ ရာခိုင်နှုန်း` (digits; a second recording gave `၂၀%`, ASCII percent sign)
  3. `နှစ်ဆယ့်တစ်ရာစု` (words) -> `၂၁ ရာစု` (digits)
  4. `ည ၁၁၃၅` -> `ည ၁၁ နာရီ ၃၅` or `ည ၁၁ ၃၅` (digits, split by hour)
  5. `M၁၆` -> `M ၁၆`; `၂၀၀၂` -> `၂၀၀၀` (digits, with errors)
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
- **`llama-mtmd-cli` vs server.** Same text on all 3 clips tried (fleurs_23, fleurs_13, fleurs_17; `-p "<__media__>" --temp 0 -n 256`), byte for byte.

## Gate G1 (PROVISIONAL)

Measured on FLEURS, not budget phrases, for 0.6B Q8_0 auto:
- SER 19.9% (< 40%): pass on FLEURS.
- Repetition loops: 0 of 33 clips: pass.
- Silence: empty text on 3 of 3 clips (auto): pass.
- Amount phrase exact in at least 16 of 20 budget clips: **PENDING user voice testing** (Decision 6).

Provisional GO to continue with Task 2 using 0.6B Q8_0, auto mode. Final G1 needs the 20 recorded budget phrases.

## Reproduce

```
android/asr/host/spike/build_llamacpp.sh ~/.cache/budgetwise-eval/llama.cpp
python3 -I android/asr/host/spike/make_clips.py          # needs fleurs/test.tsv + test.tar.gz
android/asr/host/spike/run_matrix.sh ~/.cache/budgetwise-eval/llama.cpp/build/bin ~/.cache/budgetwise-eval ~/.cache/budgetwise-eval/clips/clips.jsonl
python3 -m pytest android/asr/host/spike/test_run_spike.py
```
