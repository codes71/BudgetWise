package app.budgetwise.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for 16-bit PCM mono WAV files, returning samples scaled to [-1, 1). Takes plain PCM and
 * WAVE_FORMAT_EXTENSIBLE with the PCM sub-format (what ffmpeg writes for the user's recordings).
 */
object WavReader {
    fun readMono16(file: File): FloatArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.remaining() >= 12 && tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "not a WAV file" }
        var pos = 12
        var channels = 0
        var bits = 0
        while (pos + 8 <= buf.limit()) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                val format = buf.getShort(body).toInt() and 0xFFFF
                val pcm = format == PCM || (format == EXTENSIBLE && size >= 26 && buf.getShort(body + 24).toInt() == PCM)
                require(pcm) { "not PCM (format $format)" }
                channels = buf.getShort(body + 2).toInt()
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(channels == 1 && bits == 16) { "need 16-bit mono, got $bits-bit x$channels" }
                val n = minOf(size, buf.limit() - body) / 2
                return FloatArray(n) { buf.getShort(body + it * 2) / 32768f }
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk")
    }

    private const val PCM = 1
    private const val EXTENSIBLE = 0xFFFE

    private fun tag(buf: ByteBuffer, at: Int): String =
        String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
}
