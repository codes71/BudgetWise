package app.budgetwise.asr

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.audio.WebRtcFrameClassifier
import app.budgetwise.asr.engine.ModelFiles
import app.budgetwise.asr.engine.NativeRajEngineFactory
import app.budgetwise.asr.engine.RajNative
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/*
 * G2 benchmark: the full RajSpeechToText path (WebRTC VAD, real native engine, DeviceProfile threads)
 * over every pushed fleurs_*.wav, on whatever phone is attached. Inputs are never committed; same
 * directory and procedure as NativeEngineTest (targetContext.getExternalFilesDir("raj")). Do NOT use
 * connectedDebugAndroidTest: it uninstalls the test APK and deletes the pushed files. From android/
 * with JAVA_HOME set to Android Studio's JBR:
 *
 *   ./gradlew :asr:installDebugAndroidTest
 *   adb shell mkdir -p /sdcard/Android/data/app.budgetwise.asr.test/files/raj
 *   adb push ~/.cache/budgetwise-eval/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf \
 *       /sdcard/Android/data/app.budgetwise.asr.test/files/raj/
 *   adb push ~/.cache/budgetwise-eval/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf \
 *       /sdcard/Android/data/app.budgetwise.asr.test/files/raj/
 *   adb push ~/.cache/budgetwise-eval/clips/wav/fleurs_*.wav \
 *       /sdcard/Android/data/app.budgetwise.asr.test/files/raj/
 *   adb shell am instrument -w -e class app.budgetwise.asr.OnDeviceBenchmarkTest \
 *       app.budgetwise.asr.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s OnDeviceBenchmark
 *
 * The test skips (assumeTrue) without the model or with fewer than MIN_CLIPS clips, so a green run
 * is only proof if logcat shows the final "RAN benchmarkGoldenClips n=..." line.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBenchmarkTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inputDir: File? = context.getExternalFilesDir("raj")

    private class ReadyManager(private val files: ModelFiles) : ModelManager {
        override val activeVariant = MutableStateFlow<RajVariant?>(VARIANT)
        override val state: MutableStateFlow<ModelState> = MutableStateFlow(ModelState.Ready(files.model.parent.orEmpty()))
        override suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean) = Unit
        override suspend fun delete(variant: RajVariant) = Unit
        override fun filesFor(variant: RajVariant): ModelFiles = files
    }

    @Test
    fun benchmarkGoldenClips() {
        val model = File(inputDir, "Raj-0.6B-Q8_0.gguf")
        val mmproj = File(inputDir, "mmproj-Raj-0.6B-Q8_0.gguf")
        val clips = inputDir?.listFiles { f -> f.name.matches(Regex("fleurs_.*\\.wav")) }.orEmpty().sortedBy { it.name }
        assumeTrue("push model files and >= $MIN_CLIPS clips first, see header", model.isFile && mmproj.isFile && clips.size >= MIN_CLIPS)

        val device = DeviceProfile.detect(context)
        val backends = RajNative.initBackendsOnce(context).replace('\n', ';')
        Log.i(TAG, "device soc=${device.socModel} ramBytes=${device.totalRamBytes} threads=${device.threads()} backends=$backends")

        val classifier = WebRtcFrameClassifier()
        val stt = RajSpeechToText(
            modelManager = ReadyManager(ModelFiles(model, mmproj)),
            engineFactory = NativeRajEngineFactory(context, null),
            segmenter = SpeechSegmenter(classifier),
            device = device,
        )
        val rtfs = mutableListOf<Double>()
        val walls = mutableListOf<Long>()
        var maxVmHwmKb = 0L
        try {
            runBlocking {
                val loadStart = System.nanoTime()
                stt.warmUp()
                Log.i(TAG, "load_ms=${(System.nanoTime() - loadStart) / 1_000_000}")
                Log.i(TAG, "id,audio_ms,wall_ms,rtf,vmhwm_kb")
                for (clip in clips) {
                    val pcm = WavReader.readMono16(clip)
                    val start = System.nanoTime()
                    val result = stt.transcribe(pcm)
                    val wallMs = (System.nanoTime() - start) / 1_000_000
                    val rtf = result.decodeMs.toDouble() / result.audioMs
                    val hwm = vmHwmKb()
                    rtfs += rtf
                    walls += wallMs
                    maxVmHwmKb = maxOf(maxVmHwmKb, hwm)
                    Log.i(TAG, "${clip.nameWithoutExtension},${result.audioMs},$wallMs,${"%.3f".format(rtf)},$hwm")
                    Log.i(TAG, "text ${clip.nameWithoutExtension}: ${result.text}")
                }
            }
        } finally {
            stt.close()
            classifier.close()
        }

        val p50Rtf = percentile(rtfs.sorted(), 50)
        val p95Wall = percentile(walls.sorted(), 95)
        Log.i(
            TAG,
            "RAN benchmarkGoldenClips n=${clips.size} p50_rtf=${"%.3f".format(p50Rtf)} " +
                "p95_wall_ms=$p95Wall max_vmhwm_kb=$maxVmHwmKb threads=${device.threads()}",
        )
        assertTrue("p50 RTF $p50Rtf > $G2_MAX_P50_RTF", p50Rtf <= G2_MAX_P50_RTF)
        assertTrue("max VmHWM $maxVmHwmKb kB > $G2_MAX_VMHWM_KB kB", maxVmHwmKb <= G2_MAX_VMHWM_KB)
    }

    /** Peak resident set of this process in kB, from /proc/self/status. */
    private fun vmHwmKb(): Long =
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmHWM:") }
            .filter { it.isDigit() }
            .toLong()

    /** Nearest-rank percentile of an ascending list. */
    private fun <T> percentile(sorted: List<T>, p: Int): T =
        sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    private companion object {
        const val TAG = "OnDeviceBenchmark"
        val VARIANT = RajVariant.RAJ_0_6B
        const val MIN_CLIPS = 4

        /** G2 limits for RAJ_0_6B: decode no slower than real time, peak RSS within 1.6 GB. */
        const val G2_MAX_P50_RTF = 1.0
        const val G2_MAX_VMHWM_KB = 1_600_000L
    }
}
