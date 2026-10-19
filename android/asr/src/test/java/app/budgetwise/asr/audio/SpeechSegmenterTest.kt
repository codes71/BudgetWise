package app.budgetwise.asr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {
    private val rate = 16_000
    private val frame = 480.0

    // Non-zero samples count as speech, so the scripted classifier needs no native VAD.
    private val classifier = FrameClassifier { frame -> frame.any { it.toInt() != 0 } }
    private val segmenter = SpeechSegmenter(classifier)

    private fun audio(seconds: Double, vararg speech: Pair<Double, Double>): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        for ((from, to) in speech) {
            for (i in (from * rate).toInt() until minOf((to * rate).toInt(), out.size)) out[i] = 0.5f
        }
        return out
    }

    @Test
    fun silenceOnlyReturnsEmpty() {
        assertTrue(segmenter.segments(audio(5.0)).isEmpty())
    }

    @Test
    fun trimsSilenceKeepingPad() {
        val result = segmenter.segments(audio(10.0, 3.0 to 5.0))
        assertEquals(1, result.size)
        // 2 s speech + 200 ms pad on each side
        assertEquals((2.4 * rate).toDouble(), result[0].size.toDouble(), frame)
    }

    @Test
    fun blipShorterThanMinSpeechIsDropped() {
        val result = segmenter.segments(audio(5.0, 2.0 to 2.1))
        assertTrue(result.isEmpty())
    }

    @Test
    fun splitsAtLongestPauseUnderMax() {
        val result = segmenter.segments(audio(45.0, 0.0 to 14.0, 14.3 to 31.0, 31.3 to 45.0))
        assertEquals(3, result.size)
        assertTrue(result.all { it.size <= 20 * rate })
        // cuts land in the middle of each 300 ms pause (shorter than 2 x pad, so no natural gap): 14.15 s and 31.15 s
        val expected = listOf(14.15, 17.0, 13.85)
        result.forEachIndexed { i, seg -> assertEquals(expected[i] * rate, seg.size.toDouble(), 2 * frame) }
    }

    @Test
    fun hardSplitsWithoutPause() {
        val result = segmenter.segments(audio(25.0, 0.0 to 25.0))
        assertEquals(listOf(20 * rate, 5 * rate), result.map { it.size })
    }

    @Test
    fun shortSegmentPaddedTo500ms() {
        // clip is only 400 ms, so pad cannot extend it and the segment is zero-padded
        val result = segmenter.segments(audio(0.4, 0.0 to 0.4))
        assertEquals(1, result.size)
        assertEquals(8_000, result[0].size)
    }
}
