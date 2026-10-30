package app.budgetwise.asr.audio

/** Classifies one 30 ms frame (480 samples of 16 kHz mono 16-bit PCM) as speech or not. */
fun interface FrameClassifier {
    fun isSpeech(frame: ShortArray): Boolean
}

/**
 * Trims silence from 16 kHz mono float audio and returns the utterance as one segment, split only
 * when it exceeds [maxSegmentMs]. Interior pauses never split an utterance that fits. An empty
 * result means no speech and must never be sent to the ASR engine.
 */
class SpeechSegmenter(
    private val classifier: FrameClassifier,
    val maxSegmentMs: Int = 20_000,
    val minSpeechMs: Int = 300,
    val padMs: Int = 200,
) {
    init {
        require(maxSegmentMs >= MIN_SEGMENT_MS) { "maxSegmentMs must be >= $MIN_SEGMENT_MS" }
        require(padMs >= 0) { "padMs must be >= 0" }
        require(minSpeechMs >= 0) { "minSpeechMs must be >= 0" }
    }

    fun segments(audio: FloatArray): List<FloatArray> {
        val speech = speechMask(audio)
        val first = speech.indexOfFirst { it }
        if (first < 0) return emptyList()
        val last = speech.indexOfLast { it }

        val pad = padMs * SAMPLES_PER_MS
        val spanEnd = minOf(audio.size, (last + 1) * FRAME_SAMPLES + pad)
        val maxSamples = maxSegmentMs * SAMPLES_PER_MS
        val minCutSamples = maxSamples * 3 / 5

        val out = ArrayList<FloatArray>()
        var start = maxOf(0, first * FRAME_SAMPLES - pad)
        while (spanEnd - start > maxSamples) {
            val cut = pauseCut(speech, start, last, minCutSamples, maxSamples) ?: (start + maxSamples)
            trimmed(audio, speech, start, cut, pad)?.let { out += it }
            start = cut
        }
        trimmed(audio, speech, start, spanEnd, pad)?.let { out += it }
        return out
    }

    private fun speechMask(audio: FloatArray): BooleanArray {
        val frameCount = (audio.size + FRAME_SAMPLES - 1) / FRAME_SAMPLES
        val mask = BooleanArray(frameCount)
        val frame = ShortArray(FRAME_SAMPLES)
        for (f in 0 until frameCount) {
            val base = f * FRAME_SAMPLES
            for (i in 0 until FRAME_SAMPLES) {
                frame[i] = if (base + i < audio.size) toPcm16(audio[base + i]) else 0
            }
            mask[f] = classifier.isSpeech(frame)
        }
        // Bridge flicker first: gaps shorter than padMs between speech frames are speech.
        forEachRun(mask, false) { from, to ->
            if (from > 0 && to < mask.size && (to - from) * FRAME_MS < padMs) mask.fill(true, from, to)
        }
        // Then drop isolated blips shorter than minSpeechMs.
        val minFrames = (minSpeechMs + FRAME_MS - 1) / FRAME_MS
        forEachRun(mask, true) { from, to -> if (to - from < minFrames) mask.fill(false, from, to) }
        return mask
    }

    private inline fun forEachRun(mask: BooleanArray, value: Boolean, action: (from: Int, to: Int) -> Unit) {
        var f = 0
        while (f < mask.size) {
            if (mask[f] != value) {
                f++
                continue
            }
            var end = f
            while (end < mask.size && mask[end] == value) end++
            action(f, end)
            f = end
        }
    }

    /**
     * Midpoint of the longest interior non-speech run whose midpoint lies within
     * [segStart + minCut, segStart + maxCut], or null when there is no such pause.
     */
    private fun pauseCut(speech: BooleanArray, segStart: Int, lastFrame: Int, minCut: Int, maxCut: Int): Int? {
        var best: Int? = null
        var bestLen = 0
        var f = segStart / FRAME_SAMPLES
        while (f <= lastFrame) {
            if (speech[f]) {
                f++
                continue
            }
            var end = f
            while (end <= lastFrame && !speech[end]) end++
            val mid = (f + end) * FRAME_SAMPLES / 2
            if (mid >= segStart + minCut && mid <= segStart + maxCut && end - f > bestLen) {
                best = mid
                bestLen = end - f
            }
            f = end
        }
        return best
    }

    /** Trims [from, to) to the speech inside it plus padding; null when no speech is left. */
    private fun trimmed(audio: FloatArray, speech: BooleanArray, from: Int, to: Int, pad: Int): FloatArray? {
        var firstFrame = -1
        var lastFrame = -1
        for (f in from / FRAME_SAMPLES..(to - 1) / FRAME_SAMPLES) {
            if (f >= speech.size || !speech[f]) continue
            if (firstFrame < 0) firstFrame = f
            lastFrame = f
        }
        if (firstFrame < 0) return null
        val start = maxOf(from, firstFrame * FRAME_SAMPLES - pad)
        val end = minOf(to, (lastFrame + 1) * FRAME_SAMPLES + pad)
        return slice(audio, start, end)
    }

    private fun slice(audio: FloatArray, from: Int, to: Int): FloatArray {
        val size = maxOf(to - from, MIN_SEGMENT_SAMPLES)
        return FloatArray(size).also { audio.copyInto(it, 0, from, to) }
    }

    private fun toPcm16(sample: Float): Short =
        Math.round(sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toShort()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_MS = SAMPLE_RATE / 1000
        const val FRAME_MS = 30
        const val FRAME_SAMPLES = FRAME_MS * SAMPLES_PER_MS
        const val MIN_SEGMENT_MS = 500
        const val MIN_SEGMENT_SAMPLES = MIN_SEGMENT_MS * SAMPLES_PER_MS
    }
}
