package app.budgetwise.asr

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import app.budgetwise.asr.audio.FrameClassifier
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.engine.SpeechEngine
import app.budgetwise.asr.engine.SpeechEngineFactory
import app.budgetwise.asr.engine.VoiceFiles
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
        override val state: MutableStateFlow<ModelState> = MutableStateFlow(ModelState.Ready("/models"))
        override suspend fun ensureDownloaded(allowMetered: Boolean) = Unit
        override suspend fun delete() = Unit
        override fun files() = VoiceFiles(File("/m/f.onnx"), File("/m/vocab.json"), File("/m/o.onnx"), File("/m/tokens.txt"))
    }

    private class RecordingEngine : SpeechEngine {
        @Volatile var closed = false
        override fun transcribe(pcm: FloatArray) = "text"
        override fun abort() = Unit
        override fun close() {
            closed = true
        }
    }

    private class Factory : SpeechEngineFactory {
        val opened = mutableListOf<RecordingEngine>()
        override fun open(model: VoiceModel, files: VoiceFiles): SpeechEngine = RecordingEngine().also { opened += it }
    }

    private val factory = Factory()
    private val stt = OnnxSpeechToText(
        modelManager = FakeManager(),
        engineFactory = factory,
        segmenter = SpeechSegmenter(FrameClassifier { true }),
        clock = { 0L },
    )
    private val guard = MemoryGuard(stt)

    /** One second of a non-silent tone, so the always-speech classifier yields one segment. */
    private fun transcribeOnce() = runBlocking { stt.transcribe(FloatArray(16_000) { 0.3f }) }

    @Test
    fun releasesEnginesOnTrimMemory() {
        transcribeOnce()
        assertEquals(2, factory.opened.size)

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse(factory.opened.any { it.closed })

        guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue(factory.opened.all { it.closed })

        transcribeOnce()
        assertEquals(4, factory.opened.size)
        assertFalse(factory.opened.drop(2).any { it.closed })
    }

    @Test
    fun releasesOnLowMemory() {
        transcribeOnce()
        guard.onLowMemory()
        assertTrue(factory.opened.all { it.closed })
    }

    @Test
    fun configurationChangeKeepsEngine() {
        transcribeOnce()
        guard.onConfigurationChanged(Configuration())
        assertFalse(factory.opened.any { it.closed })
    }
}
