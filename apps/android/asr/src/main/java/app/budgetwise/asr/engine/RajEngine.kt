package app.budgetwise.asr.engine

import java.io.Closeable
import java.io.File

/** Verified on-disk model files for one variant. */
data class ModelFiles(val model: File, val mmproj: File)

/**
 * One transcription. [text] is the cleaned transcript, [raw] the model output before cleanup.
 * [genTokens] equal to the requested limit means generation was cut off at the limit.
 */
data class EngineOutput(
    val text: String,
    val raw: String,
    val genTokens: Int,
    val encodeMs: Long,
    val decodeMs: Long,
)

/**
 * A loaded Raj model. One instance must not run two [transcribe] calls at once (callers serialize);
 * [abort] and [close] may be called from any thread.
 */
interface RajEngine : Closeable {
    /**
     * Blocks until the transcript is ready. Throws [java.util.concurrent.CancellationException] when
     * [abort] stopped it, [app.budgetwise.asr.AsrException.EngineFailure] on a native failure and
     * [IllegalStateException] after [close].
     */
    fun transcribe(pcm: FloatArray, maxNewTokens: Int): EngineOutput

    /** Stops the transcribe in flight, if any. Throws [IllegalStateException] after [close]. */
    fun abort()
}

fun interface RajEngineFactory {
    fun open(files: ModelFiles, threads: Int): RajEngine
}
