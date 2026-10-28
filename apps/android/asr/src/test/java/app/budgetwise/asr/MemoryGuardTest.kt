package app.budgetwise.asr

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import app.budgetwise.asr.audio.FrameClassifier
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.engine.EngineOutput
import app.budgetwise.asr.engine.ModelFiles
import app.budgetwise.asr.engine.RajEngine
import app.budgetwise.asr.engine.RajEngineFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryGuardTest {
    private class FakeManager : ModelManager {
        override val activeVariant = MutableStateFlow<RajVariant?>(RajVariant.RAJ_0_6B)
        override val state: MutableStateFlow<ModelState> = MutableStateFlow(ModelState.Ready("/models"))
        override suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean) = Unit
        override suspend fun delete(variant: RajVariant) = Unit
        override fun filesFor(variant: RajVariant) = ModelFiles(File("/models/m.gguf"), File("/models/mm.gguf"))
    }

    private class RecordingEngine : RajEngine {
        @Volatile var closed = false
        override fun transcribe(pcm: FloatArray, maxNewTokens: Int) = EngineOutput("text", "text", 1, 0, 0)
        override fun abort() = Unit
        override fun close() {
            closed = true
        }
    }

    private class Factory : RajEngineFactory {
        val opened = mutableListOf<RecordingEngine>()
        override fun open(files: ModelFiles, threads: Int): RajEngine = RecordingEngine().also { opened += it }
    }

    private val factory = Factory()
    private val stt = RajSpeechToText(
        modelManager = FakeManager(),
        engineFactory = factory,
        segmenter = SpeechSegmenter(FrameClassifier { true }),
        device = DeviceProfile(8_000_000_000L, listOf(2_000_000, 2_000_000), "test"),
        clock = { 0L },
    )
    private val guard = MemoryGuard(stt)

    /** One second of a non-silent tone, so the always-speech classifier yields one segment. */
    private fun transcribeOnce() = runBlocking { stt.transcribe(FloatArray(16_000) { 0.3f }) }

    @Test
    fun releasesEngineOnTrimMemory() {
        transcribeOnce()
        assertEquals(1, factory.opened.size)

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(factory.opened[0].closed)

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue(factory.opened[0].closed)

        transcribeOnce()
        assertEquals(2, factory.opened.size)
        assertFalse(factory.opened[1].closed)
    }

    @Test
    fun releasesOnLowMemory() {
        transcribeOnce()
        guard.onLowMemory()
        assertTrue(factory.opened[0].closed)
    }

    @Test
    fun configurationChangeKeepsEngine() {
        transcribeOnce()
        guard.onConfigurationChanged(Configuration())
        assertFalse(factory.opened[0].closed)
    }
}
