package app.budgetwise.asr

import android.os.SystemClock
import app.budgetwise.asr.audio.FrameClassifier
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.audio.WebRtcFrameClassifier
import app.budgetwise.asr.engine.OnnxEngineFactory
import app.budgetwise.asr.engine.SpeechEngine
import app.budgetwise.asr.engine.SpeechEngineFactory
import app.budgetwise.asr.engine.VoiceFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.EnumMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device Burmese speech recognition that runs every [VoiceModel] on the same trimmed audio, one model after
 * the other. Calls are serialized, the loaded engines are cached, and cancelling a call aborts the running model
 * before the next call starts.
 *
 * Omnilingual is about 1 GB once loaded and needs more while it loads, so it is always loaded first, before
 * freococo (about 0.1 GB) is in memory; that keeps the load peak to Omnilingual's own.
 */
class OnnxSpeechToText(
    private val modelManager: ModelManager,
    private val engineFactory: SpeechEngineFactory,
    private val segmenter: SpeechSegmenter,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeechToText, Closeable {
    private val mutex = Mutex()
    private val engines = EnumMap<VoiceModel, SpeechEngine>(VoiceModel::class.java)
    private var engineFiles: VoiceFiles? = null
    private var ownedClassifier: Closeable? = null
    private var ownedExecutor: ExecutorService? = null
    private val releaseRequested = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    override suspend fun transcribe(audio: FloatArray): AsrResult {
        check(!closeRequested.get()) { CLOSED_MESSAGE }
        try {
            return mutex.withLock {
                try {
                    check(!closeRequested.get()) { CLOSED_MESSAGE }
                    transcribeLocked(audio)
                } finally {
                    cleanupInCall()
                }
            }
        } finally {
            drainCleanup()
        }
    }

    /** Loads every engine ahead of the first call. No-op when the pack is not ready or after close. */
    suspend fun warmUp() {
        try {
            mutex.withLock {
                try {
                    if (closeRequested.get()) return@withLock
                    val files = modelManager.files() ?: run {
                        dropEnginesIfModelGone()
                        return@withLock
                    }
                    withContext(dispatcher) { openEngines(files) }
                } finally {
                    cleanupInCall()
                }
            }
        } finally {
            drainCleanup()
        }
    }

    /**
     * Frees the loaded engines. Never blocks: when a call is in flight the engines are closed by that
     * call as soon as it finishes. A later call reopens them.
     */
    fun release() {
        releaseRequested.set(true)
        drainCleanup()
    }

    override fun close() {
        closeRequested.set(true)
        drainCleanup()
    }

    private suspend fun transcribeLocked(audio: FloatArray): AsrResult {
        val startedAt = clock()
        val files = modelManager.files() ?: run {
            dropEnginesIfModelGone()
            throw AsrException.ModelNotReady(modelManager.state.value)
        }
        // Only the blocking engine work runs on [dispatcher]. The awaiting coroutine stays off it, so
        // a cancel can issue the abort even while a single engine thread is stuck inside a call.
        val segments = withContext(dispatcher) { segmenter.segments(audio) }
        if (segments.isNotEmpty()) withContext(dispatcher) { openEngines(files) }
        val transcripts = VoiceModel.entries.map { model ->
            if (segments.isEmpty()) return@map Transcript(model, modelIdOf(model), "", 0L)
            val modelStartedAt = clock()
            val engine = engines.getValue(model)
            val text = segments.map { transcribeSegment(engine, it).trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            Transcript(model, modelIdOf(model), text, clock() - modelStartedAt)
        }
        return AsrResult(
            transcripts = transcripts,
            audioMs = audio.size.toLong() / SAMPLES_PER_MS,
            decodeMs = clock() - startedAt,
        )
    }

    /**
     * The engine call blocks and ignores coroutine cancellation, so cancellation aborts it, and the
     * scope waits for the aborted call to return before the caller's mutex is released.
     */
    private suspend fun transcribeSegment(engine: SpeechEngine, segment: FloatArray): String = coroutineScope {
        val call = async(dispatcher) { engine.transcribe(segment) }
        try {
            call.await()
        } catch (e: CancellationException) {
            // An abort can land before the call has started its run, so repeat it until the call has returned.
            withContext(NonCancellable) {
                while (!call.isCompleted) {
                    runCatching { engine.abort() }
                    withTimeoutOrNull(ABORT_RETRY_MS) { call.join() }
                }
            }
            throw e
        }
    }

    /** The pack was deleted or became unverified: free the cached engines. Caller holds the mutex. */
    private suspend fun dropEnginesIfModelGone() {
        if (engines.isEmpty()) return
        withContext(NonCancellable + dispatcher) { closeEngines() }
    }

    /** Opens every missing engine, the largest first. Caller holds the mutex. */
    private fun openEngines(files: VoiceFiles) {
        if (engineFiles != files) closeEngines()
        engineFiles = files
        for (model in LOAD_ORDER) {
            if (model !in engines) engines[model] = engineFactory.open(model, files)
        }
    }

    /** Caller holds the mutex and no transcribe is running. */
    private fun closeEngines() {
        val old = engines.values.toList()
        engines.clear()
        engineFiles = null
        old.forEach { it.close() }
    }

    /** Caller holds the mutex and no transcribe is running. */
    private fun cleanupLocked() {
        val closing = closeRequested.get()
        if (releaseRequested.getAndSet(false) || closing) closeEngines()
        if (closing) {
            ownedClassifier?.close()
            ownedClassifier = null
            ownedExecutor?.shutdown()
            ownedExecutor = null
        }
    }

    private suspend fun cleanupInCall() {
        if (releaseRequested.get() || closeRequested.get()) {
            withContext(NonCancellable + dispatcher) { cleanupLocked() }
        }
    }

    /** Runs pending cleanup now if no call holds the mutex; otherwise the holder does it. */
    private fun drainCleanup() {
        if (!releaseRequested.get() && !closeRequested.get()) return
        if (mutex.tryLock()) {
            try {
                cleanupLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** Creates the classifier on first use so app startup and unit tests never touch native code. */
    private class LazyClassifier : FrameClassifier, Closeable {
        private val delegate = lazy { WebRtcFrameClassifier() }

        override fun isSpeech(frame: ShortArray): Boolean = delegate.value.isSpeech(frame)

        override fun close() {
            if (delegate.isInitialized()) delegate.value.close()
        }
    }

    companion object {
        private const val CLOSED_MESSAGE = "OnnxSpeechToText is closed"
        private const val ABORT_RETRY_MS = 50L
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLES_PER_MS = SAMPLE_RATE / 1000

        /** Largest first, so its load peak happens while nothing else is loaded. */
        internal val LOAD_ORDER = listOf(VoiceModel.OMNILINGUAL, VoiceModel.FREOCOCO)

        fun modelIdOf(model: VoiceModel): String = "${model.id}+onnxruntime@${BuildConfig.ONNXRUNTIME_VERSION}"

        /** For the app's Hilt binding. Nothing native is touched until the first transcribe or warmUp. */
        fun create(modelManager: ModelManager): OnnxSpeechToText {
            val classifier = LazyClassifier()
            // One stable thread for every engine call leaves Dispatchers.Default free; ONNX Runtime adds its
            // own intra-op threads. close() shuts it down after the last cleanup.
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-onnx").apply { isDaemon = true } }
            return OnnxSpeechToText(
                modelManager = modelManager,
                engineFactory = OnnxEngineFactory(),
                segmenter = SpeechSegmenter(classifier),
                dispatcher = executor.asCoroutineDispatcher(),
            ).also {
                it.ownedClassifier = classifier
                it.ownedExecutor = executor
            }
        }
    }
}
