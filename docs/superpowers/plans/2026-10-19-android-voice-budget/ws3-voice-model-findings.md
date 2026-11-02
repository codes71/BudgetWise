# Burmese voice model findings (WS3)

Status on 2026-10-21: the on-device speech pipeline works on the user's phone, but Raj-0.6B is not accurate enough.
The user tested it by voice and judged it inaccurate, so the model is being replaced. The voice work is paused while
the Android app is built with typed entry. Pick it up again from "Next steps" at the end of this file.

## Verdict

- The pipeline works end to end on a real phone. Mic capture, silence trimming, the llama.cpp engine, the model
  download code, cancellation and memory release all pass their tests on a Xiaomi Mi 14.
- Raj-0.6B gets about 1 in 5 Burmese syllables wrong on clean read news (FLEURS), and worse on the user's own speech.
- Two free models beat it on the same clips: freococo/myanmar_asr (best syllable score, tiny) and Meta Omnilingual
  CTC 1B (best character score). Neither has been tried on budget phrases yet.
- No candidate has been scored on real budget phrases with amounts (ထောင်, သောင်း, သိန်း). Gate G1 was meant to do that
  with 20 recorded phrases and was never run, because no such recordings exist.

## Test set and metrics

- 30 FLEURS my_mm test utterances (3 to 15 s, mean 12.3 s, read news, CC-BY-4.0), built by
  `android/asr/host/spike/make_clips.py`. Audio is outside git in `~/.cache/budgetwise-eval/clips/wav/`.
- SER is syllable error rate, CER is character error rate, both pooled over the 30 clips with the scorers in
  `android/asr/host/spike/run_spike.py` (Burmese punctuation stripped, spaces ignored).
- FLEURS may be in some models' training data, so every FLEURS number here may be optimistic. With 30 clips, gaps of
  2 to 3 points are noise.

## Accuracy on the 30 FLEURS clips

All rows are on the same clips with the same scorer.

| Model | Where it ran | SER | CER | Size on disk | Notes |
|---|---|---|---|---|---|
| Raj-0.6B Q8_0 (current) | Mac, Metal server | 19.8% | 12.2% | 850 MB | Task 1 golden |
| Raj-0.6B Q8_0 | Mac, CPU engine, no VAD | 20.2% | 12.4% | 850 MB | same C++ engine as the phone |
| Raj-0.6B Q8_0 | Mi 14, full app path with VAD | 21.4% | n/a | 850 MB | trimming costs about 1 point |
| Raj-1.7B Q8_0 | Mac, Metal server | 18.6% | 11.8% | 2.2 GB | about twice as slow |
| Raj-0.6B Q5_K_M (requant) | Mac | 22.0% | 13.4% | 650 MB | within 3 points, no hosting |
| Raj-0.6B Q4_K_M (requant) | Mac | 25.6% | 16.2% | 600 MB | rejected |
| freococo/myanmar_asr | Mac CPU | 13.7% | 9.1% | 91 MB | 24M params, 30 clips in 3.4 s |
| Omnilingual CTC 1B int8 (v1) | Mac CPU, sherpa-onnx | 18.3% | 8.1% | 985 MB | 30 clips in 45 s |
| Omnilingual CTC 1B int8 (v2) | Mac CPU, sherpa-onnx | 20.1% | 8.9% | 999 MB | |
| Omnilingual CTC 300M int8 | Mac CPU, sherpa-onnx | 28.1% | 12.2% | 349 MB | worse than Raj |
| Omnilingual CTC 300M fp32 (v2) | Mac CPU, sherpa-onnx | 27.4% | 12.2% | 1.2 GB | int8 v2 repo was empty |

Silence: Raj in auto mode returns empty text on all 3 silence and noise clips. Forced `language Burmese` mode
hallucinates a short word on all 3, so auto mode plus the VAD pre-trim stays.

## How each model writes numbers and English words

This matters more than raw accuracy for a budget app.

- Raj: Burmese digits, sometimes wrong (`၂၀၀၂` came out as `၂၀၀၀`). Small counts stay as words. One ASCII `%`.
  English names come out in Latin script or transliterated to Burmese, inconsistently.
- freococo: spells every number as Burmese words (`M၁၆` came out as `အမ် ဆယ့် ခြောက်`). No digits and no Latin
  letters ever. Output is space-separated syllables.
- Omnilingual v1: never writes digits. It dropped a spoken year entirely and leaked stray Latin letters into Burmese
  words (`မာaလီ`).
- Omnilingual v2: writes Burmese digits, sometimes wrongly (`၉ိုဦး` for `ကိုး ဦး`).
- Kyat magnitude words (`ထောင်`, `သောင်း`, `သိန်း`) have not been tested with any model. The WS4 parser must accept words,
  Burmese digits and ASCII digits.

## Published numbers (not on our clips)

Test sets and normalization differ, so these only point at what to try. Sources are in the research notes listed
under "Where the evidence lives".

- Meta Omnilingual LLM 7B: Burmese CER 4.4 on Meta's own sets, and 8.27 on FLEURS plus Common Voice. Server only.
- Meta Omnilingual 1B: Burmese CER 11.14 on FLEURS plus Common Voice (BuzzASR paper).
- Gemini 2.5 Flash, used through its API: FLEURS Burmese CER about 13%, the best of the models in SEA-SpeechBench.
  It costs about $0.002 per minute. Gemini 3.1 Pro is about 9% in another paper.
- ElevenLabs Scribe v2 puts Burmese in its 10 to 20% WER band. That is the vendor's own figure.
- Google Chirp 3 lists my-MM as Preview with no published accuracy. Azure and Amazon list my-MM, also with no published accuracy.
- Ruled out:
  - Whisper and its Burmese fine-tunes: over 50% WER, and over 100% CER zero-shot.
  - Qwen3-ASR base: has no Burmese.
  - MMS and SeamlessM4T v2: non-commercial licenses.
  - Deepgram, Soniox and Speechmatics: no Burmese.
  - AssemblyAI: over 50% WER.
  - Claude: has no audio input, so it can only post-process transcript text.

The takeaway: on clean read speech, Raj is not far from the best published cloud numbers. The gap the user hears is
likely real speech versus read news. Only a test on the user's own phrases can pick the winner.

## Phone results (Xiaomi Mi 14, Snapdragon 8 Gen 3, 11.7 GB, Android 16)

Gate G2 was measured with `OnDeviceBenchmarkTest` on all 30 clips at 4 threads, the warm path. The CPU backend was
`libggml-cpu-android_armv8.6_1.so`.

| Check | Limit | Measured |
|---|---|---|
| Median real-time factor | at most 1.0 | 0.537 (decodes about twice as fast as real time) |
| Slowest clip of 8 s or less | at most 8 s | 7.4 s |
| Peak memory (VmHWM) | at most 1.6 GB | 1.44 GB after the fix below (1.90 GB before) |
| Crash from unsupported CPU instructions | none | none |
| Model load | not gated | 8.4 s (5.5 s with mmap) |

- The phone and the Mac produce identical text on the same clip without VAD.
- Latency varies run to run: the same 6.7 s clip took 4.0 s in one run and 7.4 s in another. Thread placement on the
  big and little cores is the likely cause (deferred item M3 in the ledger).
- This is a flagship phone. G2 was meant for a 4 GB phone, which has not been tested.

## Bugs found on the phone and fixed

Both fixes are pushed to `feat/ws3-raj-asr`.

1. `a6a24e8`: calling `abort()` after `close()` returned silently instead of throwing as documented. Caught by
   `NativeEngineTest.transcribeAfterCloseThrows`.
2. `74d9dcb`: peak memory was 1.90 GB, over the 1.6 GB limit. The mapped GGUF file pages stayed resident next to the
   copy the CPU backend repacks the weights into. Loading without mmap (`LLAMA_LOAD_MODE_NONE`) brought it to 1.44 GB
   with identical transcripts, at the cost of about 3 s more load time.

## What was ruled out as the cause of the bad results

- Mic capture: logcat confirms 16 kHz mono 16-bit PCM from the `VOICE_RECOGNITION` source, as intended.
- Silence trimming (WebRTC VAD, very aggressive mode, 200 ms padding): costs about 1 point of SER on clean audio
  (20.2% without, 21.4% with).
- The engine: it matches llama-server exactly on the Mac and matches the Mac on the phone.
- The cause that remains is the model itself on real speech.

## Device and tooling gotchas

- Wireless debugging failed while the phone's VPN was on: the phone showed a 100.64.x address and pairing hit a
  protocol fault. USB debugging works.
- Gradle needs `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Device tests:
  - Use `installDebugAndroidTest`, then `adb push` the inputs, then `am instrument`.
  - Never use `connectedDebugAndroidTest`: it uninstalls the test app and deletes the pushed files.
  - Accept a run only when logcat shows the `RAN <test>` lines.
- Files copied on the device with `adb shell cp` into another app's `Android/data` folder end up owned by the shell with
  mode 0660, so the app gets EACCES. `adb push` keeps mode 0644 and works.
- The demo app was given the model without downloading it: `run-as` writes into `no_backup/models/RAJ_0_6B/<revision>/`
  plus a `verified.json` marker and the `active_variant` preference. The in-app Download button has not been tried on
  the phone.

## Next steps when voice work resumes

1. Record about 20 budget phrases the way the user really speaks, amounts included, with the phone's Recorder app.
   Keep them outside git.
2. Score Raj-0.6B, Raj-1.7B, freococo/myanmar_asr and Omnilingual CTC 1B on those phrases. Add Gemini Flash only if
   cloud audio is acceptable: that needs a paid Google AI key and breaks the plan's rule that audio never leaves the phone.
3. Switch `:asr` to the winner.
   - freococo needs an ONNX export plus a log-mel front end that matches its training.
   - Omnilingual has a ready sherpa-onnx Android path.
   - The `SpeechToText` interface stays the same, so the app does not change.
4. Re-run G2 for the new model, and run it on a 4 GB phone if one is available.

## Where the evidence lives

- Ledger with every ruling and raw number: `.superpowers/sdd/ws3-on-device-asr-raj/progress.md` (git-ignored).
- Mac spike results and G2 table: `android/asr/host/spike/RESULTS.md`.
- Bake-off models: `~/.cache/budgetwise-eval/models/`, outside git. The runner scripts and transcripts were in the
  session scratchpad and are temporary. The table above is the durable copy.
