package app.budgetwise.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    @Test
    fun bufferKeepsUpToItsLimitAndSaysWhenFull() {
        val buffer = PcmBuffer(maxSamples = 20_000)
        val chunk = ShortArray(15_000) { 16_384 }
        assertTrue(buffer.append(chunk, chunk.size))
        assertFalse("the second chunk only half fits", buffer.append(chunk, chunk.size))
        val audio = buffer.toFloatArray()
        assertEquals(20_000, audio.size)
        assertEquals(0.5f, audio.last(), 0f)
    }

    @Test
    fun clearDropsTheAudio() {
        val buffer = PcmBuffer(maxSamples = 100)
        buffer.append(shortArrayOf(1, 2, 3), 3)
        buffer.clear()
        assertArrayEquals(FloatArray(0), buffer.toFloatArray(), 0f)
    }

    @Test
    fun levelMapsSilenceToZeroAndFullScaleToOne() {
        assertEquals(0f, Pcm.level(ShortArray(320), 320), 0f)
        assertEquals(1f, Pcm.level(ShortArray(320) { Short.MAX_VALUE }, 320), 0.001f)
        // A sine at -20 dBFS peak is about -23 dBFS RMS: inside the range, not at either end.
        val speech = ShortArray(320) { (3277 * kotlin.math.sin(it / 5.0)).toInt().toShort() }
        val level = Pcm.level(speech, speech.size)
        assertTrue("$level", level in 0.5f..0.7f)
        assertEquals(0f, Pcm.level(speech, 0), 0f)
    }
}
