package app.budgetwise.asr.demo

import android.media.AudioRecord
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
    ) + if (r.truncatedSegments > 0) ", truncated ${r.truncatedSegments}" else ""
}

internal object MicErrors {
    const val UNAVAILABLE = "Microphone unavailable (in use by another app?)"

    fun readError(code: Int): String = when (code) {
        AudioRecord.ERROR_DEAD_OBJECT -> "Microphone disconnected (read error $code)"
        AudioRecord.ERROR_INVALID_OPERATION -> "Microphone stopped unexpectedly (read error $code)"
        AudioRecord.ERROR_BAD_VALUE -> "Microphone read failed: bad value (read error $code)"
        else -> "Microphone read failed (read error $code)"
    }
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
        for ((index, chunk) in chunks.withIndex()) {
            val start = index * CHUNK
            val part = Pcm.toFloat(chunk, minOf(CHUNK, size - start))
            System.arraycopy(part, 0, out, start, part.size)
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
