package app.budgetwise.asr.engine

import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.sqrt

/*
 * Pre- and post-processing around the two ONNX models, kept free of ONNX Runtime so JVM tests can check it against
 * the Python reference (asr/host/onnx/ort_reference.py), which mirrors freococo's transcribe.py and sherpa-onnx.
 */

private const val BLANK_ID = 0

/** Greedy CTC: keeps an id only when it differs from the previous frame's id, then drops the blank (id 0). */
internal fun ctcCollapse(frameIds: IntArray): IntArray {
    val out = ArrayList<Int>(frameIds.size)
    var prev = -1
    for (id in frameIds) {
        if (id != prev && id != BLANK_ID) out += id
        prev = id
    }
    return out.toIntArray()
}

/** freococo's decode, as transcribe.py: collapse, look ids up in vocab.json, skip empty tokens, join with spaces. */
internal class FreococoDecoder(private val idToToken: Map<Int, String>) {

    /** [run] takes the audio and returns the model's `ids` output, one id per frame. */
    fun transcribe(pcm: FloatArray, run: (FloatArray) -> LongArray): String {
        if (pcm.size < MIN_SAMPLES) return ""
        return text(run(pcm).let { ids -> IntArray(ids.size) { ids[it].toInt() } })
    }

    fun text(frameIds: IntArray): String =
        ctcCollapse(frameIds).asSequence().mapNotNull { id -> idToToken[id]?.takeIf { it.isNotEmpty() } }.joinToString(" ")

    companion object {
        /** transcribe.py returns "" for clips under 0.5 s without running the model. */
        const val MIN_SAMPLES = 8_000

        /** vocab.json maps each token to its id. */
        fun parseVocab(json: String): Map<Int, String> {
            val obj = JSONObject(json)
            val out = HashMap<Int, String>(obj.length())
            for (token in obj.keys()) out[obj.getInt(token)] = token
            return out
        }
    }
}

/** The `logits` output: [frames] rows of [vocab] values from the buffer's position on. */
internal class Logits(val values: FloatBuffer, val frames: Int, val vocab: Int)

/** Omnilingual's decode, as sherpa-onnx: collapse, concatenate tokens, `▁` becomes a space, trim. */
internal class OmnilingualDecoder(private val tokens: Array<String?>) {
    val vocabSize: Int get() = tokens.size

    /** [run] takes the normalized audio and returns the model's logits. */
    fun transcribe(pcm: FloatArray, run: (FloatArray) -> Logits): String {
        if (pcm.size < MIN_SAMPLES) return ""
        val logits = run(normalize(pcm))
        check(logits.vocab == vocabSize) { "logits have ${logits.vocab} classes, tokens.txt has $vocabSize" }
        return text(argmax(logits.values, logits.frames, logits.vocab))
    }

    fun text(frameIds: IntArray): String = buildString {
        for (id in ctcCollapse(frameIds)) append(tokens.getOrNull(id).orEmpty())
    }.replace(WORD_START, ' ').trim()

    companion object {
        /** Shorter input is "" without running the model. */
        const val MIN_SAMPLES = 400
        private const val WORD_START = '▁'
        private const val EPSILON = 1e-5

        /** tokens.txt has `<token> <id>` per line. A token can itself be a space, so the id follows the last space. */
        fun parseTokens(text: String): Array<String?> {
            val pairs = text.split('\n').filter { it.isNotEmpty() }.map { line ->
                val at = line.lastIndexOf(' ')
                require(at >= 0) { "bad tokens.txt line: $line" }
                line.substring(0, at) to line.substring(at + 1).trim().toInt()
            }
            val out = arrayOfNulls<String>(pairs.maxOf { it.second } + 1)
            for ((token, id) in pairs) out[id] = token
            return out
        }

        /**
         * Zero mean and unit variance, the variance taken from the centred samples, divided by sqrt(var + 1e-5).
         * Computed in double and rounded to float once, like the Mac reference.
         */
        fun normalize(audio: FloatArray): FloatArray {
            var sum = 0.0
            for (x in audio) sum += x
            val mean = sum / audio.size
            var squares = 0.0
            for (x in audio) {
                val d = x - mean
                squares += d * d
            }
            val scale = sqrt(squares / audio.size + EPSILON)
            return FloatArray(audio.size) { ((audio[it] - mean) / scale).toFloat() }
        }

        /** Index of the largest of [vocab] logits in each of [frames] rows; the first one on a tie, like numpy. */
        fun argmax(logits: FloatBuffer, frames: Int, vocab: Int): IntArray {
            require(logits.remaining() >= frames.toLong() * vocab) { "logits hold ${logits.remaining()} values" }
            val base = logits.position()
            return IntArray(frames) { frame ->
                val row = base + frame * vocab
                var best = 0
                var bestValue = logits.get(row)
                for (k in 1 until vocab) {
                    val v = logits.get(row + k)
                    if (v > bestValue) {
                        best = k
                        bestValue = v
                    }
                }
                best
            }
        }
    }
}
