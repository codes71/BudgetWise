package app.budgetwise.voice

import kotlinx.coroutines.flow.StateFlow

/** The microphone for voice entry. Audio stays in memory and is never written to disk. */
interface Microphone {
    /**
     * Starts listening. The caller must hold RECORD_AUDIO. Throws [MicUnavailableException] when the microphone cannot
     * start (another app holds it).
     */
    fun start(): Recording
}

class MicUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Why a [Recording] stopped on its own. */
enum class RecordingEnd {
    /** [Microphone]'s time limit was reached; the audio so far is kept. */
    LIMIT,

    /** The microphone failed mid-recording (disconnected, taken by a call). */
    ERROR,
}

/** One capture, from [Microphone.start] until [stop] or [cancel]. */
interface Recording {
    /** Loudness of the latest 20 ms, 0 (silence) to 1 (loud speech), for the listening screen. */
    val level: StateFlow<Float>

    /** Null while capturing; set when the capture stopped on its own. [stop] still returns what was heard. */
    val ended: StateFlow<RecordingEnd?>

    /** Stops and returns the audio: 16 kHz mono in -1..1. The internal copy is zeroed. Later calls return nothing. */
    fun stop(): FloatArray

    /** Stops and throws the audio away. */
    fun cancel()
}
