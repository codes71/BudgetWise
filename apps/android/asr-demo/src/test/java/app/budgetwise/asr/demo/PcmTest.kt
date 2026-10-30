package app.budgetwise.asr.demo

import app.budgetwise.asr.AsrResult
import app.budgetwise.asr.Transcript
import app.budgetwise.asr.VoiceModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {

    @Test
    fun toFloatScalesFullRange() {
        val out = Pcm.toFloat(shortArrayOf(-32768, 32767, 0), 3)
        assertEquals(-1.0f, out[0], 0f)
        assertEquals(32767 / 32768f, out[1], 0f)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun toFloatHonoursCount() {
        assertEquals(2, Pcm.toFloat(shortArrayOf(1, 2, 3), 2).size)
    }

    @Test
    fun bufferStopsAtMax() {
        val buf = PcmBuffer(5)
        assertTrue(buf.append(shortArrayOf(1, 2, 3), 3))
        assertFalse(buf.append(shortArrayOf(4, 5, 6), 3))
        assertEquals(5, buf.toFloatArray().size)
        assertFalse(buf.append(shortArrayOf(7), 1))
        assertEquals(5, buf.toFloatArray().size)
    }

    @Test
    fun bufferKeepsOrderAcrossChunks() {
        val buf = PcmBuffer(100_000)
        val a = ShortArray(40_000) { (it % 100).toShort() }
        assertTrue(buf.append(a, a.size))
        assertTrue(buf.append(a, a.size))
        val out = buf.toFloatArray()
        assertEquals(80_000, out.size)
        assertArrayEquals(Pcm.toFloat(a, a.size), out.copyOfRange(40_000, 80_000), 0f)
    }

    @Test
    fun bufferClearZeroes() {
        val buf = PcmBuffer(10)
        buf.append(shortArrayOf(9, 9, 9), 3)
        buf.clear()
        assertEquals(0, buf.toFloatArray().size)
        assertTrue(buf.append(shortArrayOf(1), 1))
        assertEquals(1, buf.toFloatArray().size)
    }

    @Test
    fun rtfHandlesZeroAudio() {
        assertEquals(0.0, Pcm.rtf(0, 500), 0.0)
        assertEquals(2.0, Pcm.rtf(1000, 2000), 1e-9)
    }

    private val result = AsrResult(
        transcripts = listOf(
            Transcript(VoiceModel.FREOCOCO, "freococo-id", "ကော် ဖီ", 300),
            Transcript(VoiceModel.OMNILINGUAL, "omni-id", "", 3700),
        ),
        audioMs = 3200,
        decodeMs = 4100,
    )

    @Test
    fun metricsLineFormat() {
        assertEquals(
            "audio 3.2 s, decode 4.1 s, RTF 1.28\nfreococo 0.3 s, freococo-id\nOmnilingual 3.7 s, omni-id",
            Pcm.metricsLine(result),
        )
    }

    @Test
    fun transcriptTextLabelsEachModelAndMarksEmptyOnes() {
        assertEquals("freococo: ကော် ဖီ\nOmnilingual: -", Pcm.transcriptText(result, "-"))
    }

    @Test
    fun readErrorMessagesAreDistinctAndMentionCode() {
        val codes = listOf(
            android.media.AudioRecord.ERROR_INVALID_OPERATION,
            android.media.AudioRecord.ERROR_BAD_VALUE,
            android.media.AudioRecord.ERROR_DEAD_OBJECT,
            android.media.AudioRecord.ERROR,
        )
        val messages = codes.map { MicErrors.readError(it) }
        assertEquals(codes.size, messages.toSet().size)
        assertTrue(MicErrors.readError(-99).contains("-99"))
        assertTrue(MicErrors.readError(android.media.AudioRecord.ERROR_DEAD_OBJECT).contains("disconnected"))
    }

    @Test
    fun micUnavailableMessage() {
        assertEquals("Microphone unavailable (in use by another app?)", MicErrors.UNAVAILABLE)
    }
}
