package app.budgetwise.asr

/** Turns 16 kHz mono PCM into text, fully on device, with every [VoiceModel] on the same audio. */
interface SpeechToText {
    /** [audio] is 16 kHz mono in -1..1. For silence every transcript's text is "" and no model runs. */
    suspend fun transcribe(audio: FloatArray): AsrResult
}

/** What one model heard. [modelId] names the model revision and the runtime version. */
data class Transcript(
    val model: VoiceModel,
    val modelId: String,
    val text: String,
    val decodeMs: Long,
)

data class AsrResult(
    /** One per [VoiceModel], in [VoiceModel.entries] order. */
    val transcripts: List<Transcript>,
    val audioMs: Long,
    /** Wall time of the whole call: silence trimming, every model, and model load when not warmed up. */
    val decodeMs: Long,
) {
    /** True when no model heard anything, which includes silence. */
    val isEmpty: Boolean get() = transcripts.all { it.text.isEmpty() }

    fun textOf(model: VoiceModel): String = transcripts.firstOrNull { it.model == model }?.text.orEmpty()
}
