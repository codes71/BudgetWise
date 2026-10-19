package app.budgetwise.asr.audio

/** Classifies one 30 ms frame (480 samples of 16 kHz mono 16-bit PCM) as speech or not. */
fun interface FrameClassifier {
    fun isSpeech(frame: ShortArray): Boolean
}

/**
 * Trims silence from 16 kHz mono float audio and splits long speech into segments of at most
 * [maxSegmentMs]. An empty result means no speech and must never be sent to the ASR engine.
 */
class SpeechSegmenter(
    private val classifier: FrameClassifier,
    val maxSegmentMs: Int = 20_000,
    val minSpeechMs: Int = 300,
    val padMs: Int = 200,
) {
    fun segments(audio: FloatArray): List<FloatArray> {
        val speech = speechMask(audio)
        val spans = paddedSpans(speech, audio.size)
        val maxSamples = maxSegmentMs * SAMPLES_PER_MS
        val minCutSamples = maxSamples * 3 / 5

        val out = ArrayList<FloatArray>()
        for (span in spans) {
            var start = span.start
            while (span.end - start > maxSamples) {
                val cut = pauseCut(speech, start, span, minCutSamples, maxSamples) ?: (start + maxSamples)
                out += slice(audio, start, cut)
                start = cut
            }
            out += slice(audio, start, span.end)
        }
        return out
    }

    private class Span(val start: Int, val end: Int, val firstFrame: Int, val lastFrame: Int)

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
        // Drop speech runs shorter than minSpeechMs.
        val minFrames = (minSpeechMs + FRAME_MS - 1) / FRAME_MS
        var f = 0
        while (f < frameCount) {
            if (!mask[f]) {
                f++
                continue
            }
            var end = f
            while (end < frameCount && mask[end]) end++
            if (end - f < minFrames) mask.fill(false, f, end)
            f = end
        }
        return mask
    }

    /** One span per speech region, padded and clipped to the clip, with overlapping spans merged. */
    private fun paddedSpans(speech: BooleanArray, totalSamples: Int): List<Span> {
        val pad = padMs * SAMPLES_PER_MS
        val spans = ArrayList<Span>()
        var f = 0
        while (f < speech.size) {
            if (!speech[f]) {
                f++
                continue
            }
            var end = f
            while (end < speech.size && speech[end]) end++
            val start = maxOf(0, f * FRAME_SAMPLES - pad)
            val stop = minOf(totalSamples, end * FRAME_SAMPLES + pad)
            val prev = spans.lastOrNull()
            if (prev != null && start <= prev.end) {
                spans[spans.size - 1] = Span(prev.start, stop, prev.firstFrame, end - 1)
            } else {
                spans += Span(start, stop, f, end - 1)
            }
            f = end
        }
        return spans
    }

    /**
     * Midpoint of the longest non-speech run whose midpoint lies within
     * [segStart + minCut, segStart + maxCut], or null when there is no such pause.
     */
    private fun pauseCut(speech: BooleanArray, segStart: Int, span: Span, minCut: Int, maxCut: Int): Int? {
        var best: Int? = null
        var bestLen = 0
        var f = span.firstFrame
        while (f <= span.lastFrame) {
            if (speech[f]) {
                f++
                continue
            }
            var end = f
            while (end <= span.lastFrame && !speech[end]) end++
            val mid = (f + end) * FRAME_SAMPLES / 2
            val len = end - f
            if (mid >= segStart + minCut && mid <= segStart + maxCut && len > bestLen) {
                best = mid
                bestLen = len
            }
            f = end
        }
        return best
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
        const val MIN_SEGMENT_SAMPLES = 500 * SAMPLES_PER_MS
    }
}
