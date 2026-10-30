package app.budgetwise.asr.engine

import app.budgetwise.asr.VoiceModel
import java.io.Closeable
import java.io.File

/** Verified on-disk files of the [app.budgetwise.asr.VoicePack]. */
data class VoiceFiles(
    val freococoModel: File,
    val freococoVocab: File,
    val omnilingualModel: File,
    val omnilingualTokens: File,
)

/**
 * One loaded speech model. One instance must not run two [transcribe] calls at once (callers serialize);
 * [abort] and [close] may be called from any thread.
 */
interface SpeechEngine : Closeable {
    /**
     * Blocks until the transcript of [pcm] (16 kHz mono in -1..1) is ready. Throws
     * [java.util.concurrent.CancellationException] when [abort] stopped it,
     * [app.budgetwise.asr.AsrException.EngineFailure] on a runtime failure and [IllegalStateException] after [close].
     */
    fun transcribe(pcm: FloatArray): String

    /** Stops the transcribe in flight, if any. Throws [IllegalStateException] after [close]. */
    fun abort()
}

fun interface SpeechEngineFactory {
    fun open(model: VoiceModel, files: VoiceFiles): SpeechEngine
}
