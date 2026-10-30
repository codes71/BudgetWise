package app.budgetwise.asr.engine

import app.budgetwise.asr.VoicePack
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Robolectric only for org.json, which vocab.json parsing and the fixtures use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CtcDecodingTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/voice/$name")) { "missing test resource voice/$name" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val vocabBytes by lazy { resource("vocab.json") }
    private val tokensBytes by lazy { resource("tokens.txt") }
    private val freococo by lazy { FreococoDecoder(FreococoDecoder.parseVocab(String(vocabBytes, Charsets.UTF_8))) }
    private val omnilingual by lazy { OmnilingualDecoder(OmnilingualDecoder.parseTokens(String(tokensBytes, Charsets.UTF_8))) }

    @Test
    fun collapseDropsRepeatsThenBlanks() {
        assertArrayEquals(intArrayOf(5, 5, 7), ctcCollapse(intArrayOf(0, 5, 5, 0, 5, 7, 7, 0)))
        assertArrayEquals(intArrayOf(3), ctcCollapse(intArrayOf(3, 3, 3)))
        assertArrayEquals(intArrayOf(), ctcCollapse(intArrayOf(0, 0)))
        assertArrayEquals(intArrayOf(), ctcCollapse(intArrayOf()))
    }

    @Test
    fun freococoVocabMapsIdsToTokens() {
        val vocab = FreococoDecoder.parseVocab("""{"<blank>": 0, "<unk>": 1, "က": 2, "": 3, "ကော်": 4}""")
        assertEquals(mapOf(0 to "<blank>", 1 to "<unk>", 2 to "က", 3 to "", 4 to "ကော်"), vocab)
        // Empty tokens and ids missing from the vocab are skipped, as transcribe.py does.
        assertEquals("က ကော် <unk>", FreococoDecoder(vocab).text(intArrayOf(0, 2, 2, 3, 0, 4, 9, 1)))
    }

    @Test
    fun freococoRealVocabIsThePinnedFile() {
        assertEquals(VoicePack.FREOCOCO_VOCAB.sha256, sha256(vocabBytes))
        assertEquals("<blank>", FreococoDecoder.parseVocab(String(vocabBytes, Charsets.UTF_8))[0])
    }

    @Test
    fun omnilingualTokensSplitOnTheLastSpace() {
        val tokens = OmnilingualDecoder.parseTokens("<s> 0\n<pad> 1\n  2\n▁ab 3\nc 4\r\n")
        assertArrayEquals(arrayOf("<s>", "<pad>", " ", "▁ab", "c"), tokens)
        // Word starts become spaces, then the ends are trimmed.
        assertEquals("ab c abc", OmnilingualDecoder(tokens).text(intArrayOf(3, 3, 0, 2, 4, 0, 3, 4)))
    }

    @Test
    fun omnilingualRealTokensAreThePinnedFile() {
        assertEquals(VoicePack.OMNILINGUAL_TOKENS.sha256, sha256(tokensBytes))
        assertEquals(9812, omnilingual.vocabSize)
    }

    @Test
    fun badTokensLineIsRejected() {
        try {
            OmnilingualDecoder.parseTokens("nospace\n")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun normalizeGivesZeroMeanUnitVariance() {
        val out = OmnilingualDecoder.normalize(floatArrayOf(1f, 2f, 3f, 4f))
        val scale = sqrt(1.25 + 1e-5)
        assertArrayEquals(floatArrayOf((-1.5 / scale).toFloat(), (-0.5 / scale).toFloat(), (0.5 / scale).toFloat(), (1.5 / scale).toFloat()), out, 0f)
        val mean = out.average()
        val variance = out.sumOf { (it - mean) * (it - mean) } / out.size
        assertEquals(0.0, mean, 1e-7)
        assertEquals(1.0, variance, 1e-4)
    }

    @Test
    fun normalizeOfSilenceStaysFinite() {
        // var + 1e-5 keeps the division finite; the result is all zeros.
        assertArrayEquals(FloatArray(800), OmnilingualDecoder.normalize(FloatArray(800)), 0f)
    }

    @Test
    fun argmaxTakesTheFirstMaximumAndHonoursPosition() {
        val buf = FloatBuffer.wrap(floatArrayOf(9f, /* frame 0 */ 1f, 3f, 3f, /* frame 1 */ -1f, -2f, -0.5f))
        buf.position(1)
        assertArrayEquals(intArrayOf(1, 2), OmnilingualDecoder.argmax(buf, frames = 2, vocab = 3))
    }

    @Test
    fun shortInputReturnsEmptyWithoutRunningTheModel() {
        val noRun: (FloatArray) -> LongArray = { throw AssertionError("freococo ran on short input") }
        assertEquals("", freococo.transcribe(FloatArray(FreococoDecoder.MIN_SAMPLES - 1), noRun))
        val noLogits: (FloatArray) -> Logits = { throw AssertionError("Omnilingual ran on short input") }
        assertEquals("", omnilingual.transcribe(FloatArray(OmnilingualDecoder.MIN_SAMPLES - 1), noLogits))
        assertEquals(8_000, FreococoDecoder.MIN_SAMPLES)
        assertEquals(400, OmnilingualDecoder.MIN_SAMPLES)
    }

    @Test
    fun inputAtTheMinimumRuns() {
        var freococoRan = 0
        freococo.transcribe(FloatArray(FreococoDecoder.MIN_SAMPLES)) { freococoRan++; longArrayOf(0) }
        var omniRan = 0
        omnilingual.transcribe(FloatArray(OmnilingualDecoder.MIN_SAMPLES)) {
            omniRan++
            Logits(FloatBuffer.wrap(FloatArray(omnilingual.vocabSize)), 1, omnilingual.vocabSize)
        }
        assertEquals(1, freococoRan)
        assertEquals(1, omniRan)
    }

    @Test
    fun omnilingualRunsOnNormalizedAudio() {
        var seen: FloatArray? = null
        val audio = FloatArray(1_000) { 0.05f + 0.01f * (it % 7) }
        omnilingual.transcribe(audio) { input ->
            seen = input
            Logits(FloatBuffer.wrap(FloatArray(omnilingual.vocabSize)), 1, omnilingual.vocabSize)
        }
        assertArrayEquals(OmnilingualDecoder.normalize(audio), seen, 0f)
    }

    @Test
    fun omnilingualRejectsLogitsOfAnotherVocabulary() {
        try {
            omnilingual.transcribe(FloatArray(1_000)) { Logits(FloatBuffer.wrap(FloatArray(10)), 1, 10) }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    // Recorded Mac outputs (asr/host/onnx/ort_reference.py fixtures) must decode to the Python text.

    private val fixtures by lazy { JSONObject(String(resource("decode-fixtures.json"), Charsets.UTF_8)) }

    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }

    @Test
    fun fixturesWereMadeWithTheShippedSettings() {
        val options = fixtures.getJSONObject("options")
        assertEquals("disable", options.getString("graph_optimization_level"))
        assertEquals(OnnxSessions.THREADS, options.getInt("intra_op_threads"))
        assertEquals(false, options.getBoolean("kleidiai"))
        assertEquals(false, options.getBoolean("prepacking"))
        assertEquals(false, options.getBoolean("cpu_mem_arena"))
        assertEquals(app.budgetwise.asr.BuildConfig.ONNXRUNTIME_VERSION, fixtures.getString("onnxruntime"))
    }

    @Test
    fun freococoDecodeMatchesPythonOnRecordedIds() {
        val clips = fixtures.getJSONObject("clips")
        assertEquals(4, clips.length())
        for (id in clips.keys()) {
            val clip = clips.getJSONObject(id)
            val ids = clip.getJSONArray("freococo_ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val text = freococo.transcribe(FloatArray(FreococoDecoder.MIN_SAMPLES)) { ids }
            assertEquals(id, clip.getString("freococo_text"), text)
        }
    }

    @Test
    fun omnilingualDecodeMatchesPythonOnRecordedLogits() {
        val vocab = fixtures.getInt("vocab_size")
        assertEquals(omnilingual.vocabSize, vocab)
        val clips = fixtures.getJSONObject("clips")
        for (id in clips.keys()) {
            val clip = clips.getJSONObject(id)
            val frames = clip.getInt("omni_frames")
            // Dense logits: the recorded top 2 per frame, every other class at the clip's minimum logit.
            val dense = FloatArray(frames * vocab).apply { fill(clip.getDouble("omni_floor").toFloat()) }
            val topIds = clip.getJSONArray("omni_top2_ids")
            val topLogits = clip.getJSONArray("omni_top2_logits")
            for (f in 0 until frames) {
                val ids = ints(topIds.getJSONArray(f))
                val logits = topLogits.getJSONArray(f)
                for (k in ids.indices) dense[f * vocab + ids[k]] = logits.getDouble(k).toFloat()
            }
            val text = omnilingual.transcribe(FloatArray(16_000)) { Logits(FloatBuffer.wrap(dense), frames, vocab) }
            assertEquals(id, clip.getString("omni_text"), text)
            assertTrue("$id decoded to nothing", text.isNotEmpty())
        }
    }

    @Test
    fun normalizationMatchesPythonOnRecordedAudio() {
        val n = fixtures.getJSONObject("normalize")
        val pcm = ints(n.getJSONArray("pcm16"))
        val expected = n.getJSONArray("expected").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        val got = OmnilingualDecoder.normalize(FloatArray(pcm.size) { pcm[it] / 32768f })
        var worst = 0f
        for (i in got.indices) worst = maxOf(worst, abs(got[i] - expected[i]))
        assertTrue("max difference $worst", worst <= 1e-6f)
    }
}
