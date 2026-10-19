package app.budgetwise.asr

/** Turns 16 kHz mono PCM into text, fully on device. */
interface SpeechToText {
    /** [audio] is 16 kHz mono in -1..1. Returns `text = ""` for silence. */
    suspend fun transcribe(audio: FloatArray): AsrResult
}

data class AsrResult(
    val text: String,
    val modelId: String,
    val audioMs: Long,
    val decodeMs: Long,
)
