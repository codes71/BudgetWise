package app.budgetwise.voice

import kotlin.math.log10
import kotlin.math.sqrt

internal object Pcm {
    const val SAMPLE_RATE = 16_000

    /** The quietest level that still moves the listening screen's rings, in dBFS. */
    private const val FLOOR_DB = -60.0

    /**
     * Loudness of [n] samples as 0..1: their RMS in dBFS mapped from [FLOOR_DB] (0) to full scale (1), so ordinary
     * speech (about -30 to -15 dBFS) lands in the middle of the range.
     */
    fun level(src: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = src[i] / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / n)
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }
}

/** In-memory PCM16 accumulator. Grows in one-second chunks and never holds more than [maxSamples]. */
internal class PcmBuffer(private val maxSamples: Int) {
    private val chunks = ArrayList<ShortArray>()
    private var size = 0

    /** Appends up to the remaining room. Returns false when [src] did not fit completely. */
    fun append(src: ShortArray, n: Int): Boolean {
        val take = minOf(n, maxSamples - size)
        var copied = 0
        while (copied < take) {
            val offset = size % CHUNK
            if (offset == 0) chunks.add(ShortArray(CHUNK))
            val room = minOf(CHUNK - offset, take - copied)
            System.arraycopy(src, copied, chunks.last(), offset, room)
            copied += room
            size += room
        }
        return take == n
    }

    fun toFloatArray(): FloatArray {
        val out = FloatArray(size)
        for ((index, chunk) in chunks.withIndex()) {
            val start = index * CHUNK
            for (i in 0 until minOf(CHUNK, size - start)) out[start + i] = chunk[i] / 32768f
        }
        return out
    }

    /** Zero-fills the stored audio before dropping it. */
    fun clear() {
        for (c in chunks) c.fill(0)
        chunks.clear()
        size = 0
    }

    private companion object {
        const val CHUNK = Pcm.SAMPLE_RATE
    }
}
