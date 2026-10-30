package app.budgetwise.asr

import app.budgetwise.asr.audio.FrameClassifier
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.engine.SpeechEngine
import app.budgetwise.asr.engine.SpeechEngineFactory
import app.budgetwise.asr.engine.VoiceFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnnxSpeechToTextTest {
    private val pool = Executors.newCachedThreadPool()
    private val poolDispatcher: CoroutineDispatcher = pool.asCoroutineDispatcher()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private class FakeManager(ready: Boolean = true) : ModelManager {
        override val state: MutableStateFlow<ModelState> =
            MutableStateFlow(if (ready) ModelState.Ready("/models") else ModelState.Missing)
        var verified = ready
        var dir = "v1"
        override suspend fun ensureDownloaded(allowMetered: Boolean) = Unit
        override suspend fun delete() = Unit
        override fun files(): VoiceFiles? = if (verified) {
            VoiceFiles(File("/m/$dir/f.onnx"), File("/m/$dir/vocab.json"), File("/m/$dir/o.onnx"), File("/m/$dir/tokens.txt"))
        } else {
            null
        }
    }

    private open class FakeEngine(val model: VoiceModel) : SpeechEngine {
        val transcribeCalls = AtomicInteger()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val aborts = AtomicInteger()
        @Volatile var closed = false
        var replies: (Int) -> String = { "${model.name.lowercase()}$it" }

        override fun transcribe(pcm: FloatArray): String {
            val n = transcribeCalls.incrementAndGet()
            val now = running.incrementAndGet()
            maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                return run(n)
            } finally {
                running.decrementAndGet()
            }
        }

        open fun run(n: Int): String = replies(n)

        override fun abort() {
            aborts.incrementAndGet()
        }

        override fun close() {
            closed = true
        }
    }

    private class Factory(val make: (VoiceModel) -> FakeEngine = { FakeEngine(it) }) : SpeechEngineFactory {
        val opened = mutableListOf<FakeEngine>()
        val files = mutableListOf<VoiceFiles>()
        override fun open(model: VoiceModel, files: VoiceFiles): SpeechEngine {
            synchronized(opened) { this.files += files }
            return make(model).also { synchronized(opened) { opened += it } }
        }

        fun of(model: VoiceModel) = opened.single { it.model == model }
    }

    /** Segmenter whose classifier marks every frame as speech, so non-silent audio is one segment. */
    private fun segmenter(speech: Boolean = true, max: Int = 20_000) =
        SpeechSegmenter(FrameClassifier { speech }, maxSegmentMs = max)

    private fun stt(
        manager: ModelManager = FakeManager(),
        factory: SpeechEngineFactory = Factory(),
        segmenter: SpeechSegmenter = segmenter(),
        clock: () -> Long = { 0L },
        dispatcher: CoroutineDispatcher = poolDispatcher,
    ) = OnnxSpeechToText(manager, factory, segmenter, clock, dispatcher)

    private fun audio(samples: Int = 16_000) = FloatArray(samples) { 0.1f }

    @Test
    fun notReadyThrowsModelNotReady() = runBlocking {
        val factory = Factory()
        try {
            stt(FakeManager(ready = false), factory).transcribe(audio())
            fail("expected ModelNotReady")
        } catch (e: AsrException.ModelNotReady) {
            assertEquals(ModelState.Missing, e.state)
        }
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun returnsOneTranscriptPerModelInOrder() = runBlocking {
        val result = stt().transcribe(audio())
        assertEquals(VoiceModel.entries, result.transcripts.map { it.model })
        assertEquals("freococo1", result.textOf(VoiceModel.FREOCOCO))
        assertEquals("omnilingual1", result.textOf(VoiceModel.OMNILINGUAL))
        assertFalse(result.isEmpty)
    }

    @Test
    fun bothModelsSeeTheSameSegments() = runBlocking {
        val seen = mutableMapOf<VoiceModel, MutableList<Int>>()
        val factory = Factory { model ->
            object : FakeEngine(model) {
                override fun transcribe(pcm: FloatArray): String {
                    synchronized(seen) { seen.getOrPut(model) { mutableListOf() } += pcm.size }
                    return super.transcribe(pcm)
                }
            }
        }
        stt(factory = factory, segmenter = segmenter(max = 500)).transcribe(audio(24_000))
        assertEquals(3, seen.getValue(VoiceModel.FREOCOCO).size)
        assertEquals(seen.getValue(VoiceModel.FREOCOCO), seen.getValue(VoiceModel.OMNILINGUAL))
    }

    @Test
    fun silenceReturnsEmptyTranscriptsWithoutOpeningEngines() = runBlocking {
        val factory = Factory()
        val result = stt(factory = factory, segmenter = segmenter(speech = false)).transcribe(audio(32_000))
        assertTrue(result.isEmpty)
        assertEquals(VoiceModel.entries, result.transcripts.map { it.model })
        assertTrue(result.transcripts.all { it.text == "" && it.decodeMs == 0L })
        assertEquals(2000L, result.audioMs)
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun joinsSegmentsWithSingleSpacePerModel() = runBlocking {
        val factory = Factory { m ->
            FakeEngine(m).also { it.replies = { n -> listOf("  one ", "", "two  ")[n - 1] } }
        }
        // 3 segments of at most 500 ms: 1.5 s of speech split by maxSegmentMs.
        val result = stt(factory = factory, segmenter = segmenter(max = 500)).transcribe(audio(24_000))
        assertEquals(3, factory.of(VoiceModel.FREOCOCO).transcribeCalls.get())
        assertEquals(3, factory.of(VoiceModel.OMNILINGUAL).transcribeCalls.get())
        assertEquals(listOf("one two", "one two"), result.transcripts.map { it.text })
    }

    @Test
    fun largestModelLoadsFirst() = runBlocking {
        val factory = Factory()
        stt(factory = factory).warmUp()
        assertEquals(listOf(VoiceModel.OMNILINGUAL, VoiceModel.FREOCOCO), factory.opened.map { it.model })
    }

    @Test
    fun modelsRunOneAfterTheOther() = runBlocking {
        val running = AtomicInteger()
        val overlap = AtomicInteger()
        val factory = Factory { m ->
            object : FakeEngine(m) {
                override fun run(n: Int): String {
                    if (running.incrementAndGet() > 1) overlap.incrementAndGet()
                    Thread.sleep(20)
                    running.decrementAndGet()
                    return super.run(n)
                }
            }
        }
        stt(factory = factory).transcribe(audio())
        assertEquals(0, overlap.get())
    }

    @Test
    fun perModelDecodeMsAndTotal() = runBlocking {
        // start, freococo start/end, omnilingual start/end, end
        val ticks = longArrayOf(1_000, 1_100, 1_300, 1_300, 2_000, 2_050).iterator()
        val result = stt(clock = { ticks.next() }).transcribe(audio())
        assertEquals(listOf(200L, 700L), result.transcripts.map { it.decodeMs })
        assertEquals(1_050L, result.decodeMs)
    }

    @Test
    fun deletedModelClosesCachedEnginesOnTranscribe() = runBlocking {
        val manager = FakeManager()
        val factory = Factory()
        val s = stt(manager, factory)
        s.transcribe(audio())
        manager.verified = false
        try {
            s.transcribe(audio())
            fail("expected ModelNotReady")
        } catch (_: AsrException.ModelNotReady) {
        }
        assertTrue(factory.opened.all { it.closed })
        manager.verified = true
        s.transcribe(audio())
        assertEquals(4, factory.opened.size)
    }

    @Test
    fun deletedModelClosesCachedEnginesOnWarmUp() = runBlocking {
        val manager = FakeManager()
        val factory = Factory()
        val s = stt(manager, factory)
        s.warmUp()
        manager.verified = false
        s.warmUp()
        assertEquals(2, factory.opened.size)
        assertTrue(factory.opened.all { it.closed })
    }

    @Test
    fun newPackFilesReopenEngines() = runBlocking {
        val manager = FakeManager()
        val factory = Factory()
        val s = stt(manager, factory)
        s.transcribe(audio())
        manager.dir = "v2"
        s.transcribe(audio())
        assertEquals(4, factory.opened.size)
        assertTrue(factory.opened.take(2).all { it.closed })
        assertFalse(factory.opened.drop(2).any { it.closed })
        assertTrue(factory.files.last().omnilingualModel.path.contains("/v2/"))
    }

    @Test
    fun audioMsFrom16kSamples() = runBlocking {
        assertEquals(2000L, stt().transcribe(audio(32_000)).audioMs)
    }

    @Test
    fun modelIdNamesRevisionAndRuntime() = runBlocking {
        val ids = stt().transcribe(audio()).transcripts.map { it.modelId }
        assertEquals(
            listOf(
                "freococo-myanmar-asr@e40d48e+onnxruntime@${BuildConfig.ONNXRUNTIME_VERSION}",
                "omnilingual-ctc-1b-int8@17db76e+onnxruntime@${BuildConfig.ONNXRUNTIME_VERSION}",
            ),
            ids,
        )
    }

    @Test
    fun concurrentCallsAreSerialized() = runBlocking {
        val gate = CountDownLatch(1)
        val factory = Factory { m ->
            object : FakeEngine(m) {
                override fun run(n: Int): String {
                    gate.await(10, TimeUnit.SECONDS)
                    return super.run(n)
                }
            }
        }
        val s = stt(factory = factory)
        val jobs = List(4) { async(poolDispatcher) { s.transcribe(audio()) } }
        delay(150) // let the other three reach the mutex while the first is blocked in the engine
        assertEquals(1, factory.of(VoiceModel.FREOCOCO).running.get())
        gate.countDown()
        jobs.forEach { it.await() }
        for (engine in factory.opened) {
            assertEquals(4, engine.transcribeCalls.get())
            assertEquals(1, engine.maxRunning.get())
        }
    }

    /** Freococo engine whose first call blocks until [abortsNeeded] aborts arrived, then holds on [hold]. */
    private class BlockingEngine(
        model: VoiceModel,
        val started: CountDownLatch,
        val hold: CountDownLatch,
        val abortsNeeded: Int,
    ) : FakeEngine(model) {
        private val abortSeen = CountDownLatch(1)
        @Volatile var abortArrived = false

        override fun run(n: Int): String {
            if (n > 1 || model != VoiceModel.FREOCOCO) return super.run(n)
            started.countDown()
            abortArrived = abortSeen.await(10, TimeUnit.SECONDS)
            hold.await(10, TimeUnit.SECONDS)
            throw CancellationException("aborted")
        }

        override fun abort() {
            if (aborts.incrementAndGet() >= abortsNeeded) abortSeen.countDown()
        }
    }

    @Test
    fun cancellationCallsAbortAndNextCallWorks() = runBlocking {
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val factory = Factory { BlockingEngine(it, started, hold, abortsNeeded = 1) }
        val s = stt(factory = factory)
        val job = launch(poolDispatcher) { s.transcribe(audio()) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        val engine = factory.of(VoiceModel.FREOCOCO) as BlockingEngine
        while (engine.aborts.get() < 1) delay(10)
        // The aborted call has not returned yet, so the outer call must still be running.
        delay(100)
        assertFalse(job.isCompleted)
        hold.countDown()
        job.join()
        assertTrue("abort() must release the blocked call", engine.abortArrived)
        assertFalse(engine.closed)
        // The cancelled call never reached Omnilingual.
        assertEquals(0, factory.of(VoiceModel.OMNILINGUAL).transcribeCalls.get())

        assertEquals("freococo2", s.transcribe(audio()).textOf(VoiceModel.FREOCOCO))
        assertEquals(2, factory.opened.size)
        assertEquals(1, engine.maxRunning.get())
    }

    @Test
    fun cancellationAbortsOnSingleThreadDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "engine-single") }
        try {
            val started = CountDownLatch(1)
            val hold = CountDownLatch(1)
            val factory = Factory { BlockingEngine(it, started, hold, abortsNeeded = 1) }
            val s = stt(factory = factory, dispatcher = executor.asCoroutineDispatcher())
            val job = launch(poolDispatcher) { s.transcribe(audio()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            val engine = factory.of(VoiceModel.FREOCOCO) as BlockingEngine
            // The only engine thread is blocked in the call, so abort must not need it.
            val deadline = System.currentTimeMillis() + 3_000
            while (engine.aborts.get() < 1 && System.currentTimeMillis() < deadline) delay(10)
            assertTrue("abort must be issued while the engine thread is blocked", engine.aborts.get() >= 1)
            hold.countDown()
            job.join()
            assertEquals("freococo2", s.transcribe(audio()).textOf(VoiceModel.FREOCOCO))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun lostFirstAbortIsReissued() = runBlocking {
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1).also { it.countDown() }
        val factory = Factory { BlockingEngine(it, started, hold, abortsNeeded = 2) }
        val s = stt(factory = factory)
        val job = launch(poolDispatcher) { s.transcribe(audio()) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        val engine = factory.of(VoiceModel.FREOCOCO) as BlockingEngine
        assertTrue("second abort must free the call", engine.abortArrived)
        assertTrue(engine.aborts.get() >= 2)
        assertEquals("freococo2", s.transcribe(audio()).textOf(VoiceModel.FREOCOCO))
    }

    @Test
    fun releaseDuringThrowingCallClosesEngines() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory = Factory { m ->
            object : FakeEngine(m) {
                override fun run(n: Int): String {
                    started.countDown()
                    proceed.await(10, TimeUnit.SECONDS)
                    throw AsrException.EngineFailure(2, "boom")
                }
            }
        }
        val s = stt(factory = factory)
        val call = async(poolDispatcher) { runCatching { s.transcribe(audio()) } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        s.release()
        proceed.countDown()
        assertTrue(call.await().exceptionOrNull() is AsrException.EngineFailure)
        assertTrue(factory.opened.all { it.closed })
    }

    @Test
    fun callQueuedBehindCloseThrows() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory = Factory { m ->
            object : FakeEngine(m) {
                override fun run(n: Int): String {
                    started.countDown()
                    proceed.await(10, TimeUnit.SECONDS)
                    return super.run(n)
                }
            }
        }
        val s = stt(factory = factory)
        val first = async(poolDispatcher) { s.transcribe(audio()) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val queued = async(poolDispatcher) { runCatching { s.transcribe(audio()) } }
        delay(100)
        s.close()
        proceed.countDown()
        assertEquals("freococo1", first.await().textOf(VoiceModel.FREOCOCO))
        assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
        assertEquals(2, factory.opened.size)
        assertTrue(factory.opened.all { it.transcribeCalls.get() == 1 && it.closed })
        s.warmUp() // no-op after close
        assertEquals(2, factory.opened.size)
    }

    @Test
    fun engineRunsOnInjectedDispatcherThread() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "stt-test-thread") }
        try {
            val seen = mutableListOf<String>()
            val factory = Factory { m ->
                object : FakeEngine(m) {
                    override fun run(n: Int): String {
                        synchronized(seen) { seen += Thread.currentThread().name }
                        return super.run(n)
                    }
                }
            }
            val caller = Thread.currentThread().name
            stt(factory = factory, dispatcher = executor.asCoroutineDispatcher()).transcribe(audio())
            assertEquals(2, seen.size)
            assertTrue(seen.all { it.startsWith("stt-test-thread") }) // coroutine debug appends a suffix
            assertFalse(caller == "stt-test-thread")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun enginesReusedAcrossCalls() = runBlocking {
        val factory = Factory()
        val s = stt(factory = factory)
        s.transcribe(audio())
        s.transcribe(audio())
        assertEquals(2, factory.opened.size)
    }

    @Test
    fun releaseClosesIdleEnginesAndNextCallReopens() = runBlocking {
        val factory = Factory()
        val s = stt(factory = factory)
        s.transcribe(audio())
        s.release()
        assertTrue(factory.opened.all { it.closed })
        s.transcribe(audio())
        assertEquals(4, factory.opened.size)
    }

    @Test
    fun releaseDuringCallClosesAfterCall() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory = Factory { m ->
            object : FakeEngine(m) {
                override fun run(n: Int): String {
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
        assertFalse(factory.opened.any { it.closed })
        proceed.countDown()
        assertEquals("freococo1", call.await().textOf(VoiceModel.FREOCOCO))
        assertTrue(factory.opened.all { it.closed })
    }

    @Test
    fun warmUpOpensEnginesWhenReadyAndIsNoOpOtherwise() = runBlocking {
        val factory = Factory()
        stt(FakeManager(ready = false), factory).warmUp()
        assertTrue(factory.opened.isEmpty())

        val s = stt(factory = factory)
        s.warmUp()
        s.transcribe(audio())
        assertEquals(2, factory.opened.size)
    }

    @Test
    fun closeIsIdempotentAndClosesEngines() = runBlocking {
        val factory = Factory()
        val s = stt(factory = factory)
        s.transcribe(audio())
        s.close()
        s.close()
        assertTrue(factory.opened.all { it.closed })
    }

    @Test
    fun worksWithStandardTestDispatcher() = runTest {
        val s = stt(dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals("freococo1", s.transcribe(audio()).textOf(VoiceModel.FREOCOCO))
    }
}
