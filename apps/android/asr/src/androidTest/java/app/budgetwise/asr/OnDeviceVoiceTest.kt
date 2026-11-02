package app.budgetwise.asr

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.audio.WebRtcFrameClassifier
import app.budgetwise.asr.engine.OnnxEngineFactory
import app.budgetwise.asr.engine.VoiceFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/*
 * Device gate for the two ONNX engines, on the user's 18 recordings (r01..r18.wav) and the four pack files, all
 * pushed with adb into targetContext.getExternalFilesDir("voice"). Inputs are never committed. The exact push and
 * run commands are in apps/android/asr/src/androidTest/README.md; run each test in its own `am instrument` call so
 * VmHWM is that test's own peak. Never use connectedDebugAndroidTest: it uninstalls the test APK and deletes the
 * pushed files.
 *
 * Missing inputs skip the tests (assumeTrue), so a green run is only proof if logcat (tag OnDeviceVoice) shows the
 * "RAN <test>" line.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceVoiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val inputDir: File? = context.getExternalFilesDir("voice")

    private val files = VoiceFiles(
        freococoModel = File(inputDir, VoicePack.FREOCOCO_MODEL.name),
        freococoVocab = File(inputDir, VoicePack.FREOCOCO_VOCAB.name),
        omnilingualModel = File(inputDir, VoicePack.OMNILINGUAL_MODEL.name),
        omnilingualTokens = File(inputDir, VoicePack.OMNILINGUAL_TOKENS.name),
    )

    private class ReadyManager(private val files: VoiceFiles) : ModelManager {
        override val state: MutableStateFlow<ModelState> =
            MutableStateFlow(ModelState.Ready(files.omnilingualModel.parent.orEmpty()))
        override suspend fun ensureDownloaded(allowMetered: Boolean) = Unit
        override suspend fun delete() = Unit
        override fun files(): VoiceFiles = files
    }

    private class Golden(val freococo: String, val omnilingual: String)

    /** Mac transcripts from asr/host/onnx/ort_reference.py with the shipped session settings, full clips. */
    private fun goldens(): Map<String, Golden> {
        val json = instrumentation.context.assets.open("voice/goldens.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        assertEquals("goldens were made with another onnxruntime", BuildConfig.ONNXRUNTIME_VERSION, root.getString("onnxruntime"))
        val clips = root.getJSONObject("clips")
        return clips.keys().asSequence().associateWith {
            val c = clips.getJSONObject(it)
            Golden(c.getString("freococo"), c.getString("omni"))
        }
    }

    private fun clips(): List<File> =
        inputDir?.listFiles { f -> f.name.matches(Regex("r\\d\\d\\.wav")) }.orEmpty().sortedBy { it.name }

    private fun requireInputs(): List<File> {
        val clips = clips()
        val models = listOf(files.freococoModel, files.freococoVocab, files.omnilingualModel, files.omnilingualTokens)
        assumeTrue(
            "push the four pack files and r01..r18.wav first, see androidTest/README.md",
            models.all { it.isFile } && clips.size == CLIPS,
        )
        val device = DeviceProfile.detect(context)
        Log.i(TAG, "device soc=${device.socModel} ramBytes=${device.totalRamBytes} cores=${device.coreMaxKhz}")
        return clips
    }

    /**
     * Both engines on each whole clip, no silence trimming, exactly like the Mac reference. freococo must match on
     * every clip. The int8 Omnilingual model reacts to any rounding difference, so it must stay within the spread
     * the Mac shows between its own settings (5 % of the golden characters, pooled).
     */
    @Test
    fun parityWithMacGoldens() {
        val clips = requireInputs()
        val goldens = goldens()
        val factory = OnnxEngineFactory()
        val engines = OnnxSpeechToText.LOAD_ORDER.associateWith { model ->
            val start = System.nanoTime()
            factory.open(model, files).also { Log.i(TAG, "load_ms ${model.name}=${(System.nanoTime() - start) / 1_000_000}") }
        }
        var freococoSame = 0
        var omniSame = 0
        var omniDistance = 0
        var omniChars = 0
        val worst = mutableListOf<String>()
        try {
            Log.i(TAG, "id,audio_ms,freococo_ms,omni_ms,freococo_same,omni_distance,vmhwm_kb")
            for (clip in clips) {
                val id = clip.nameWithoutExtension
                val pcm = WavReader.readMono16(clip)
                val want = goldens.getValue(id)
                val (freococo, freococoMs) = timed { engines.getValue(VoiceModel.FREOCOCO).transcribe(pcm) }
                val (omni, omniMs) = timed { engines.getValue(VoiceModel.OMNILINGUAL).transcribe(pcm) }
                val distance = editDistance(omni, want.omnilingual)
                if (freococo == want.freococo) freococoSame++
                if (distance == 0) omniSame++
                omniDistance += distance
                omniChars += want.omnilingual.codePointCount(0, want.omnilingual.length)
                Log.i(TAG, "$id,${pcm.size / 16},$freococoMs,$omniMs,${freococo == want.freococo},$distance,${vmHwmKb()}")
                if (freococo != want.freococo) Log.i(TAG, "freococo $id device: $freococo | mac: ${want.freococo}")
                if (distance > 0) {
                    Log.i(TAG, "omni $id device: $omni | mac: ${want.omnilingual}")
                    worst += id
                }
            }
        } finally {
            engines.values.forEach { it.close() }
        }
        val pooled = 100.0 * omniDistance / omniChars
        Log.i(
            TAG,
            "RAN parityWithMacGoldens n=${clips.size} freococo_identical=$freococoSame omni_identical=$omniSame " +
                "omni_pooled_cer=${"%.1f".format(pooled)}% max_vmhwm_kb=${vmHwmKb()} differs=$worst",
        )
        assertEquals("freococo transcripts that differ from the Mac", clips.size, freococoSame)
        assertTrue("Omnilingual differs from the Mac by $pooled % of characters", pooled <= OMNI_MAX_POOLED_CER)
    }

    /** The shipped path (WebRTC VAD, both models one after the other) with load time, latency and peak memory. */
    @Test
    fun benchmarkFullPath() {
        val clips = requireInputs()
        val classifier = WebRtcFrameClassifier()
        val stt = OnnxSpeechToText(ReadyManager(files), OnnxEngineFactory(), SpeechSegmenter(classifier))
        val rtfs = mutableListOf<Double>()
        try {
            runBlocking {
                val loadStart = System.nanoTime()
                stt.warmUp()
                Log.i(TAG, "load_ms=${(System.nanoTime() - loadStart) / 1_000_000} vmhwm_kb=${vmHwmKb()}")
                Log.i(TAG, "id,audio_ms,total_ms,freococo_ms,omni_ms,rtf,vmhwm_kb")
                for (clip in clips) {
                    val result = stt.transcribe(WavReader.readMono16(clip))
                    val rtf = result.decodeMs.toDouble() / result.audioMs
                    rtfs += rtf
                    val ms = result.transcripts.associate { it.model to it.decodeMs }
                    Log.i(
                        TAG,
                        "${clip.nameWithoutExtension},${result.audioMs},${result.decodeMs}," +
                            "${ms[VoiceModel.FREOCOCO]},${ms[VoiceModel.OMNILINGUAL]},${"%.3f".format(rtf)},${vmHwmKb()}",
                    )
                    for (t in result.transcripts) Log.i(TAG, "text ${clip.nameWithoutExtension} ${t.model.name}: ${t.text}")
                }
            }
        } finally {
            stt.close()
            classifier.close()
        }
        val p50 = rtfs.sorted().let { it[(ceil(it.size / 2.0).toInt() - 1).coerceIn(0, it.size - 1)] }
        val peak = vmHwmKb()
        Log.i(TAG, "RAN benchmarkFullPath n=${clips.size} p50_rtf=${"%.3f".format(p50)} max_vmhwm_kb=$peak")
        assertTrue("p50 RTF $p50 > $MAX_P50_RTF", p50 <= MAX_P50_RTF)
        assertTrue("max VmHWM $peak kB > $MAX_VMHWM_KB kB", peak <= MAX_VMHWM_KB)
    }

    /** The manifest merge must drop ONNX Runtime's telemetry provider; nothing here may phone home. */
    @Test
    fun onnxRuntimeTelemetryProviderIsRemoved() {
        val authority = "${context.packageName}.onnxruntime_telemetry_initializer"
        assertNull(context.packageManager.resolveContentProvider(authority, 0))
        Log.i(TAG, "RAN onnxRuntimeTelemetryProviderIsRemoved")
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }

    private fun editDistance(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prev = IntArray(y.size + 1) { it }
        for (i in 1..x.size) {
            val cur = IntArray(y.size + 1)
            cur[0] = i
            for (j in 1..y.size) {
                val sub = prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[y.size]
    }

    /** Peak resident set of this process in kB, from /proc/self/status. */
    private fun vmHwmKb(): Long =
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmHWM:") }
            .filter { it.isDigit() }
            .toLong()

    private companion object {
        const val TAG = "OnDeviceVoice"
        const val CLIPS = 18
        const val OMNI_MAX_POOLED_CER = 5.0

        /** The G2 limits the Raj engine was held to: no slower than real time, peak RSS within 1.6 GB. */
        const val MAX_P50_RTF = 1.0
        const val MAX_VMHWM_KB = 1_600_000L
    }
}
