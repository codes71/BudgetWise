package app.budgetwise.asr

import android.content.Context
import android.os.SystemClock
import app.budgetwise.asr.audio.FrameClassifier
import app.budgetwise.asr.audio.SpeechSegmenter
import app.budgetwise.asr.audio.WebRtcFrameClassifier
import app.budgetwise.asr.engine.EngineOutput
import app.budgetwise.asr.engine.ModelFiles
import app.budgetwise.asr.engine.NativeRajEngineFactory
import app.budgetwise.asr.engine.RajEngine
import app.budgetwise.asr.engine.RajEngineFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * On-device Burmese speech recognition with Raj. Calls are serialized, the loaded engine is cached
 * for the active variant, and cancelling a call aborts the native work before the next call starts.
 */
class RajSpeechToText(
    private val modelManager: ModelManager,
    private val engineFactory: RajEngineFactory,
    private val segmenter: SpeechSegmenter,
    private val device: DeviceProfile,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeechToText, Closeable {
    private val mutex = Mutex()
    private var engine: RajEngine? = null
    private var engineVariant: RajVariant? = null
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

    /** Loads the engine for the active variant ahead of the first call. No-op when no model is ready or after close. */
    suspend fun warmUp() {
        try {
            mutex.withLock {
                try {
                    if (closeRequested.get()) return@withLock
                    val (variant, files) = readyModel() ?: run {
                        dropEngineIfModelGone()
                        return@withLock
                    }
                    withContext(dispatcher) { engineFor(variant, files) }
                } finally {
                    cleanupInCall()
                }
            }
        } finally {
            drainCleanup()
        }
    }

    /**
     * Frees the loaded engine. Never blocks: when a call is in flight the engine is closed by that
     * call as soon as it finishes. A later call reopens the engine.
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
        val (variant, files) = readyModel() ?: run {
            dropEngineIfModelGone()
            throw AsrException.ModelNotReady(modelManager.state.value)
        }
        // Only the blocking native work runs on [dispatcher]. The awaiting coroutine stays off it, so
        // a cancel can issue the abort even while a single native thread is stuck inside a call.
        val segments = withContext(dispatcher) { segmenter.segments(audio) }
        var truncated = 0
        val text = if (segments.isEmpty()) {
            ""
        } else {
            val engine = withContext(dispatcher) { engineFor(variant, files) }
            segments.map { segment ->
                val limit = maxNewTokens(segment.size)
                val out = transcribeSegment(engine, segment, limit)
                if (out.genTokens >= limit) truncated++
                out.text
            }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }
        return AsrResult(
            text = text,
            modelId = "${variant.modelId}+llama.cpp@${BuildConfig.LLAMA_CPP_SHA}",
            audioMs = audio.size.toLong() / SAMPLES_PER_MS,
            decodeMs = clock() - startedAt,
            truncatedSegments = truncated,
        )
    }

    /**
     * The native call blocks and ignores coroutine cancellation, so cancellation aborts it, and the
     * scope waits for the aborted call to return before the caller's mutex is released.
     */
    private suspend fun transcribeSegment(engine: RajEngine, segment: FloatArray, limit: Int): EngineOutput = coroutineScope {
        val call = async(dispatcher) { engine.transcribe(segment, limit) }
        try {
            call.await()
        } catch (e: CancellationException) {
            // One abort can land before the native call clears its flag at start, so repeat it
            // until the call has actually returned.
            withContext(NonCancellable) {
                while (!call.isCompleted) {
                    runCatching { engine.abort() }
                    withTimeoutOrNull(ABORT_RETRY_MS) { call.join() }
                }
            }
            throw e
        }
    }

    private fun readyModel(): Pair<RajVariant, ModelFiles>? {
        val variant = modelManager.activeVariant.value ?: return null
        val files = modelManager.filesFor(variant) ?: return null
        return variant to files
    }

    /** The model was deleted or became unverified: unmap the cached engine. Caller holds the mutex. */
    private suspend fun dropEngineIfModelGone() {
        if (engine == null) return
        withContext(NonCancellable + dispatcher) { closeEngine() }
    }

    /** Caller holds the mutex. */
    private fun engineFor(variant: RajVariant, files: ModelFiles): RajEngine {
        engine?.takeIf { engineVariant == variant }?.let { return it }
        closeEngine()
        return engineFactory.open(files, device.threads()).also {
            engine = it
            engineVariant = variant
        }
    }

    /** Caller holds the mutex and no transcribe is running. */
    private fun closeEngine() {
        val old = engine
        engine = null
        engineVariant = null
        old?.close()
    }

    /** Caller holds the mutex and no transcribe is running. */
    private fun cleanupLocked() {
        val closing = closeRequested.get()
        if (releaseRequested.getAndSet(false) || closing) closeEngine()
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
        private const val CLOSED_MESSAGE = "RajSpeechToText is closed"
        private const val ABORT_RETRY_MS = 50L
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLES_PER_MS = SAMPLE_RATE / 1000
        private const val TOKENS_PER_SECOND = 30
        private const val TOKEN_MARGIN = 16

        /** For WS2's Hilt binding. Nothing native is touched until the first transcribe or warmUp. */
        fun create(context: Context, modelManager: ModelManager): RajSpeechToText {
            val classifier = LazyClassifier()
            // One stable thread for every native call keeps libomp's master thread constant and
            // leaves Dispatchers.Default free. close() shuts it down after the last cleanup.
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "raj-native").apply { isDaemon = true } }
            return RajSpeechToText(
                modelManager = modelManager,
                engineFactory = NativeRajEngineFactory(context.applicationContext, null),
                segmenter = SpeechSegmenter(classifier),
                device = DeviceProfile.detect(context),
                dispatcher = executor.asCoroutineDispatcher(),
            ).also {
                it.ownedClassifier = classifier
                it.ownedExecutor = executor
            }
        }

        /** ceil(seconds x K) + margin, K = 30 generated tokens per audio second (Task 1: max 26.8). */
        fun maxNewTokens(samples: Int): Int =
            ceil(samples / SAMPLE_RATE.toDouble() * TOKENS_PER_SECOND).toInt() + TOKEN_MARGIN
    }
}
