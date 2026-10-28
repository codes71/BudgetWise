package app.budgetwise.asr

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.budgetwise.asr.engine.ModelFiles
import app.budgetwise.asr.engine.NativeRajEngineFactory
import app.budgetwise.asr.engine.RajEngine
import app.budgetwise.asr.engine.RajNative
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Inputs are never committed. The test reads targetContext.getExternalFilesDir("raj"); for the :asr
 * library the target context is the test APK, so the files go to the test package's external dir.
 * Do NOT use connectedDebugAndroidTest: it uninstalls the test APK afterwards, which deletes
 * Android/data/app.budgetwise.asr.test and its pushed files, and a rerun would skip every test.
 * Run from android/ with JAVA_HOME set to Android Studio's JBR:
 *
 *   ./gradlew :asr:installDebugAndroidTest
 *   adb shell mkdir -p /sdcard/Android/data/app.budgetwise.asr.test/files/raj
 *   adb push ~/.cache/budgetwise-eval/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf \
 *       /sdcard/Android/data/app.budgetwise.asr.test/files/raj/
 *   adb push ~/.cache/budgetwise-eval/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf \
 *       /sdcard/Android/data/app.budgetwise.asr.test/files/raj/
 *   adb push ~/.cache/budgetwise-eval/clips/wav/fleurs_02.wav \
 *       /sdcard/Android/data/app.budgetwise.asr.test/files/raj/
 *   adb shell am instrument -w -e class app.budgetwise.asr.NativeEngineTest \
 *       app.budgetwise.asr.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Missing files skip the tests (assumeTrue), so a green run is only proof if logcat (tag
 * NativeEngineTest) shows the "RAN <test>" evidence lines.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val inputDir: File? = context.getExternalFilesDir("raj")
    private lateinit var model: File
    private lateinit var mmproj: File
    private lateinit var clip: File
    private val opened = mutableListOf<RajEngine>()

    @Before
    fun locateInputs() {
        model = File(inputDir, "Raj-0.6B-Q8_0.gguf")
        mmproj = File(inputDir, "mmproj-Raj-0.6B-Q8_0.gguf")
        clip = File(inputDir, "fleurs_02.wav")
    }

    @After
    fun closeEngines() {
        opened.forEach { it.close() }
    }

    private fun requireInputs() {
        assumeTrue("push model files first, see header", model.isFile && mmproj.isFile && clip.isFile)
    }

    private fun openEngine(): RajEngine {
        requireInputs()
        val factory = NativeRajEngineFactory(context, forcedLanguage = null)
        return factory.open(ModelFiles(model, mmproj), threads = 4).also { opened += it }
    }

    private fun expectedText(): String =
        instrumentation.context.assets.open("raj/fleurs_02.expected.txt").bufferedReader().use { it.readText().trim() }

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

    @Test
    fun transcribesGoldenClip() {
        val engine = openEngine()
        val out = engine.transcribe(WavReader.readMono16(clip), maxNewTokens = 256)
        val want = expectedText()
        Log.i(TAG, "RAN transcribesGoldenClip")
        Log.i(TAG, "device: ${out.text}")
        Log.i(TAG, "host:   $want")
        Log.i(TAG, "encodeMs=${out.encodeMs} decodeMs=${out.decodeMs} genTokens=${out.genTokens}")
        val dist = editDistance(out.text, want)
        val limit = want.codePointCount(0, want.length) * 5 / 100
        assertTrue("edit distance $dist > $limit", dist <= limit)
    }

    @Test
    fun abortStopsWithin500ms() {
        val engine = openEngine()
        val pcm = WavReader.readMono16(clip)
        val first = engine.transcribe(pcm, maxNewTokens = 256)
        assumeTrue("decode too short to abort mid-run", first.decodeMs >= 1000)
        val abortAfterMs = first.encodeMs + first.decodeMs / 2

        val abortedAt = AtomicLong(0)
        val aborter = Thread {
            Thread.sleep(abortAfterMs)
            abortedAt.set(System.nanoTime())
            engine.abort()
        }
        aborter.start()
        try {
            engine.transcribe(pcm, maxNewTokens = 256)
            fail("expected CancellationException (run ended before the abort at ${abortAfterMs}ms)")
        } catch (e: CancellationException) {
            val latencyMs = (System.nanoTime() - abortedAt.get()) / 1_000_000
            Log.i(TAG, "RAN abortStopsWithin500ms: abort latency ${latencyMs}ms (issued ${abortAfterMs}ms after start)")
            assertTrue("abort took ${latencyMs}ms", latencyMs <= 500)
        } finally {
            aborter.join()
        }
    }

    @Test
    fun transcribeAfterAbortSucceeds() {
        val engine = openEngine()
        val pcm = WavReader.readMono16(clip)
        val first = engine.transcribe(pcm, maxNewTokens = 256)
        assumeTrue("decode too short to abort mid-run", first.decodeMs >= 1000)
        val aborter = Thread {
            Thread.sleep(first.encodeMs + first.decodeMs / 2)
            engine.abort()
        }
        aborter.start()
        var cancelled = false
        try {
            engine.transcribe(pcm, maxNewTokens = 256)
        } catch (_: CancellationException) {
            cancelled = true
        } finally {
            aborter.join()
        }
        assertTrue("the abort never hit a run", cancelled)
        val again = engine.transcribe(pcm, maxNewTokens = 256)
        Log.i(TAG, "RAN transcribeAfterAbortSucceeds: after abort -> ${again.text}")
        assertTrue("empty text after abort", again.text.isNotEmpty())
        assertEquals(first.text, again.text)
    }

    @Test
    fun loadFailsCleanlyOnTruncatedFile() {
        requireInputs()
        RajNative.initBackendsOnce(context)
        val truncated = File(context.cacheDir, "truncated-raj.gguf")
        try {
            model.inputStream().use { input ->
                truncated.outputStream().use { output ->
                    val buf = ByteArray(1 shl 20)
                    var left = 10 * 1024 * 1024
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size, left))
                        if (n < 0) break
                        output.write(buf, 0, n)
                        left -= n
                    }
                }
            }
            val handle = RajNative.load(truncated.path, mmproj.path, 4, 1024, null)
            assertEquals(0L, handle)
            val err = RajNative.lastError(0L)
            Log.i(TAG, "RAN loadFailsCleanlyOnTruncatedFile: load error: $err")
            assertTrue("lastError empty", err.isNotEmpty())
        } finally {
            truncated.delete()
        }
    }

    @Test
    fun freeTwiceIsSafe() {
        val engine = openEngine()
        engine.close()
        engine.close()
        Log.i(TAG, "RAN freeTwiceIsSafe")
    }

    @Test
    fun transcribeAfterCloseThrows() {
        val engine = openEngine()
        engine.close()
        try {
            engine.transcribe(FloatArray(16000), maxNewTokens = 8)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        try {
            engine.abort()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        Log.i(TAG, "RAN transcribeAfterCloseThrows")
    }

    private companion object {
        const val TAG = "NativeEngineTest"
    }
}
