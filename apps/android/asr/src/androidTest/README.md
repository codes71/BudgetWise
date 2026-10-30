# :asr device tests

`OnDeviceVoiceTest` runs both ONNX engines on the user's 18 recordings on a USB-connected phone. Inputs stay out of
git: the four pack files and `r01.wav` .. `r18.wav` are pushed into the test APK's external files dir.

Never use `connectedDebugAndroidTest`: it uninstalls the test APK afterwards, which deletes the pushed files.

From `apps/android`:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :asr:installDebugAndroidTest

C=~/.cache/budgetwise-eval
D=/sdcard/Android/data/app.budgetwise.asr.test/files/voice
adb shell mkdir -p $D
adb push $C/models/freococo-onnx/freococo.onnx $C/models/freococo-myanmar-asr/vocab.json $D/
adb push $C/models/omni1b-int8/model.int8.onnx $C/models/omni1b-int8/tokens.txt $D/
adb push $C/my-voice/audio/wav/r*.wav $D/

R=app.budgetwise.asr.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -c
adb shell am instrument -w -e class 'app.budgetwise.asr.OnDeviceVoiceTest#parityWithMacGoldens' $R
adb shell am instrument -w -e class 'app.budgetwise.asr.OnDeviceVoiceTest#benchmarkFullPath' $R
adb shell am instrument -w -e class 'app.budgetwise.asr.OnDeviceVoiceTest#onnxRuntimeTelemetryProviderIsRemoved' $R
adb logcat -d -s OnDeviceVoice
```

Each `am instrument` call is its own process, so the `vmhwm_kb` columns are that test's peak alone. Accept a run
only when logcat shows its `RAN <test>` line; missing inputs skip the test.

- `parityWithMacGoldens`: both engines on each whole clip (no silence trimming), compared with
  `assets/voice/goldens.json`. freococo must match on every clip; Omnilingual within 5 % of the golden characters.
  Logs load time per model, per-clip latency per model and VmHWM.
- `benchmarkFullPath`: `OnnxSpeechToText` with WebRTC VAD, as the app runs it. Logs load time, per-clip latency per
  model, both transcripts and VmHWM. Fails above p50 RTF 1.0 or VmHWM 1.6 GB (the old G2 limits).

The goldens come from `asr/host/onnx/ort_reference.py transcribe` (see `asr/host/README.md`). Regenerate them
whenever the onnxruntime version or `OnnxSessions` changes; the test refuses goldens made with another version.
