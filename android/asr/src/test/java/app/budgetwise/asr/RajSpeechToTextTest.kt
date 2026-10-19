package app.budgetwise.asr

import app.budgetwise.asr.audio.FrameClassifier
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.engine.EngineOutput
import app.budgetwise.asr.engine.ModelFiles
import app.budgetwise.asr.engine.RajEngine
import app.budgetwise.asr.engine.RajEngineFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher

class RajSpeechToTextTest {
    private val pool = Executors.newCachedThreadPool()
    private val poolDispatcher: CoroutineDispatcher = pool.asCoroutineDispatcher()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private class FakeManager(variant: RajVariant? = RajVariant.RAJ_0_6B, ready: Boolean = true) : ModelManager {
        override val activeVariant = MutableStateFlow(variant)
        override val state: MutableStateFlow<ModelState> =
            MutableStateFlow(if (ready) ModelState.Ready("/models") else ModelState.Missing)
        var verified = ready
        override suspend fun ensureDownloaded(variant: RajVariant, allowMetered: Boolean) = Unit
        override suspend fun delete(variant: RajVariant) = Unit
        override fun filesFor(variant: RajVariant): ModelFiles? =
            if (verified) ModelFiles(File("/models/${variant.name}/m.gguf"), File("/models/${variant.name}/mm.gguf")) else null
    }

    private open class FakeEngine(val files: ModelFiles) : RajEngine {
        val transcribeCalls = AtomicInteger()
        val maxTokens = mutableListOf<Int>()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val aborts = AtomicInteger()
        @Volatile var closed = false
        var replies: (Int) -> String = { "text$it" }

        override fun transcribe(pcm: FloatArray, maxNewTokens: Int): EngineOutput {
            val n = transcribeCalls.incrementAndGet()
            synchronized(maxTokens) { maxTokens += maxNewTokens }
            val now = running.incrementAndGet()
            maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return run(n)
            } finally {
                running.decrementAndGet()
            }
        }

        open fun run(n: Int): EngineOutput = replies(n).let { EngineOutput(it, it, 1, 0, 0) }

        override fun abort() {
            aborts.incrementAndGet()
        }

        override fun close() {
            closed = true
        }
    }

    private class Factory(val make: (ModelFiles) -> FakeEngine = { FakeEngine(it) }) : RajEngineFactory {
        val opened = mutableListOf<FakeEngine>()
        val threads = mutableListOf<Int>()
        override fun open(files: ModelFiles, threads: Int): RajEngine {
            this.threads += threads
            return make(files).also { synchronized(opened) { opened += it } }
        }
    }

    /** Segmenter whose classifier marks every frame as speech, so non-silent audio is one segment. */
    private fun segmenter(speech: Boolean = true, max: Int = 20_000) =
        SpeechSegmenter(FrameClassifier { speech }, maxSegmentMs = max)

    private val device = DeviceProfile(8_000_000_000L, listOf(2_000_000, 2_000_000, 1_000_000), "test")

    private fun stt(
        manager: ModelManager = FakeManager(),
        factory: RajEngineFactory = Factory(),
        segmenter: SpeechSegmenter = segmenter(),
        clock: () -> Long = { 0L },
        dispatcher: CoroutineDispatcher = poolDispatcher,
    ) = RajSpeechToText(manager, factory, segmenter, device, clock, dispatcher)

    private fun audio(samples: Int = 16_000) = FloatArray(samples) { 0.1f }

    @Test
    fun notReadyThrowsModelNotReady() = runBlocking {
        val manager = FakeManager(variant = null, ready = false)
        val factory = Factory()
        try {
            stt(manager, factory).transcribe(audio())
            fail("expected ModelNotReady")
        } catch (e: AsrException.ModelNotReady) {
            assertEquals(ModelState.Missing, e.state)
        }
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun unverifiedFilesThrowModelNotReady() = runBlocking {
        val manager = FakeManager().also { it.verified = false }
        try {
            stt(manager).transcribe(audio())
            fail("expected ModelNotReady")
        } catch (_: AsrException.ModelNotReady) {
        }
    }

    @Test
    fun silenceReturnsEmptyTextWithoutOpeningEngine() = runBlocking {
        val factory = Factory()
        val result = stt(factory = factory, segmenter = segmenter(speech = false)).transcribe(audio(32_000))
        assertEquals("", result.text)
        assertEquals(2000L, result.audioMs)
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun joinsSegmentsWithSingleSpace() = runBlocking {
        val factory = Factory { f ->
            FakeEngine(f).also { it.replies = { n -> listOf("  one ", "", "two  ")[n - 1] } }
        }
        // 3 segments of at most 500 ms: 1.5 s of speech split by maxSegmentMs.
        val result = stt(factory = factory, segmenter = segmenter(max = 500)).transcribe(audio(24_000))
        assertEquals(3, factory.opened.single().transcribeCalls.get())
        assertEquals("one two", result.text)
    }

    @Test
    fun audioMsFrom16kSamples() = runBlocking {
        assertEquals(2000L, stt().transcribe(audio(32_000)).audioMs)
    }

    @Test
    fun decodeMsIsWallTimeOfTheCall() = runBlocking {
        val ticks = longArrayOf(1_000, 1_750).iterator()
        val result = stt(clock = { ticks.next() }).transcribe(audio())
        assertEquals(750L, result.decodeMs)
    }

    @Test
    fun concurrentCallsAreSerialized() = runBlocking {
        val factory = Factory { f ->
            object : FakeEngine(f) {
                override fun run(n: Int): EngineOutput {
                    Thread.sleep(40)
                    return super.run(n)
                }
            }
        }
        val s = stt(factory = factory)
        val jobs = List(4) { async(poolDispatcher) { s.transcribe(audio()) } }
        jobs.forEach { it.await() }
        val engine = factory.opened.single()
        assertEquals(4, engine.transcribeCalls.get())
        assertEquals(1, engine.maxRunning.get())
    }

    @Test
    fun cancellationCallsAbortAndNextCallWorks() = runBlocking {
        val started = CountDownLatch(1)
        val abortLatch = CountDownLatch(1)
        val factory = Factory { f ->
            object : FakeEngine(f) {
                override fun run(n: Int): EngineOutput {
                    if (n > 1) return super.run(n)
                    started.countDown()
                    abortLatch.await(10, TimeUnit.SECONDS)
                    throw CancellationException("aborted")
                }

                override fun abort() {
                    super.abort()
                    abortLatch.countDown()
                }
            }
        }
        val s = stt(factory = factory)
        val job = launch(poolDispatcher) { s.transcribe(audio()) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        val engine = factory.opened.single()
        assertTrue(engine.aborts.get() >= 1)
        assertFalse(engine.closed)

        assertEquals("text2", s.transcribe(audio()).text)
        assertEquals(1, factory.opened.size)
        assertEquals(1, engine.maxRunning.get())
    }

    @Test
    fun engineReusedAcrossCalls() = runBlocking {
        val factory = Factory()
        val s = stt(factory = factory)
        s.transcribe(audio())
        s.transcribe(audio())
        assertEquals(1, factory.opened.size)
        assertEquals(listOf(2), factory.threads)
    }

    @Test
    fun variantSwitchReopensEngine() = runBlocking {
        val manager = FakeManager()
        val factory = Factory()
        val s = stt(manager, factory)
        s.transcribe(audio())
        manager.activeVariant.value = RajVariant.RAJ_1_7B
        s.transcribe(audio())
        assertEquals(2, factory.opened.size)
        assertTrue(factory.opened[0].closed)
        assertFalse(factory.opened[1].closed)
        assertTrue(factory.opened[1].files.model.path.contains("RAJ_1_7B"))
    }

    @Test
    fun maxTokensScalesWithAudio() = runBlocking {
        assertEquals(46, RajSpeechToText.maxNewTokens(16_000))
        assertEquals(76, RajSpeechToText.maxNewTokens(32_000))
        assertEquals(17, RajSpeechToText.maxNewTokens(1))
        val factory = Factory()
        stt(factory = factory).transcribe(audio(16_000))
        // 1 s speech plus 200 ms pad each side is clipped to the available audio.
        assertEquals(listOf(RajSpeechToText.maxNewTokens(16_000)), factory.opened.single().maxTokens)
    }

    @Test
    fun maxTokensForTwentySecondSegmentFitsContext() {
        assertEquals(616, RajSpeechToText.maxNewTokens(320_000))
    }

    @Test
    fun modelIdIncludesRevisionAndLlamaSha() = runBlocking {
        assertEquals("raj-0.6b-q8_0@92b89ba+llama.cpp@fb34fc2", stt().transcribe(audio()).modelId)
    }

    @Test
    fun releaseClosesIdleEngineAndNextCallReopens() = runBlocking {
        val factory = Factory()
        val s = stt(factory = factory)
        s.transcribe(audio())
        s.release()
        assertTrue(factory.opened[0].closed)
        s.transcribe(audio())
        assertEquals(2, factory.opened.size)
    }

    @Test
    fun releaseDuringCallClosesAfterCall() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory = Factory { f ->
            object : FakeEngine(f) {
                override fun run(n: Int): EngineOutput {
                    started.countDown()
                    proceed.await(10, TimeUnit.SECONDS)
                    return super.run(n)
                }
            }
        }
        val s = stt(factory = factory)
        val call = async(poolDispatcher) { s.transcribe(audio()) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        s.release() // must return immediately and must not close under the running call
        assertFalse(factory.opened.single().closed)
        proceed.countDown()
        assertEquals("text1", call.await().text)
        assertTrue(factory.opened.single().closed)
    }

    @Test
    fun warmUpOpensEngineWhenReadyAndIsNoOpOtherwise() = runBlocking {
        val factory = Factory()
        stt(FakeManager(variant = null, ready = false), factory).warmUp()
        assertTrue(factory.opened.isEmpty())

        val s = stt(factory = factory)
        s.warmUp()
        s.transcribe(audio())
        assertEquals(1, factory.opened.size)
    }

    @Test
    fun closeIsIdempotentAndClosesEngine() = runBlocking {
        val factory = Factory()
        val s = stt(factory = factory)
        s.transcribe(audio())
        s.close()
        s.close()
        assertTrue(factory.opened.single().closed)
    }

    @Test
    fun worksWithStandardTestDispatcher() = runTest {
        val s = stt(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals("text1", s.transcribe(audio()).text)
    }
}
