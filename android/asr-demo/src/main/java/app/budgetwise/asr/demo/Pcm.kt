package app.budgetwise.asr.demo

import app.budgetwise.asr.AsrResult
import java.util.Locale

internal object Pcm {
    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 30

    fun toFloat(src: ShortArray, n: Int): FloatArray = FloatArray(n) { src[it] / 32768f }

    fun rtf(audioMs: Long, decodeMs: Long): Double = if (audioMs <= 0L) 0.0 else decodeMs.toDouble() / audioMs

    fun metricsLine(r: AsrResult): String = String.format(
        Locale.US,
        "audio %.1f s, decode %.1f s, RTF %.2f, %s",
        r.audioMs / 1000.0,
        r.decodeMs / 1000.0,
        rtf(r.audioMs, r.decodeMs),
        r.modelId,
    )
}

/** In-memory PCM16 accumulator. Grows in chunks and never holds more than [maxSamples]. */
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
        for (i in 0 until size) out[i] = chunks[i / CHUNK][i % CHUNK] / 32768f
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
