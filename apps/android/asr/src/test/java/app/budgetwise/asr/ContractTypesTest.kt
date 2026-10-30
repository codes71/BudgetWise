package app.budgetwise.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTypesTest {

    // Adding a ModelState subtype breaks this `when` at compile time, which is the point.
    private fun describe(state: ModelState): String = when (state) {
        ModelState.Missing -> "missing"
        is ModelState.Downloading -> "downloading ${state.progress}"
        is ModelState.Ready -> "ready ${state.path}"
        is ModelState.Failed -> "failed ${state.reason}"
    }

    @Test
    fun modelStateWhenIsExhaustive() {
        assertEquals("missing", describe(ModelState.Missing))
        assertEquals("downloading 0.5", describe(ModelState.Downloading(0.5f)))
        assertEquals("ready /models/voice/v1", describe(ModelState.Ready("/models/voice/v1")))
        assertEquals("failed no space", describe(ModelState.Failed("no space")))
    }

    @Test
    fun asrResultEquality() {
        val a = AsrResult(
            transcripts = listOf(Transcript(VoiceModel.FREOCOCO, "freococo", "abc", 300)),
            audioMs = 4000,
            decodeMs = 5200,
        )
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertNotEquals(a, a.copy(transcripts = listOf(a.transcripts.single().copy(text = "abd"))))
    }

    @Test
    fun asrResultTextLookupAndEmptiness() {
        val heard = AsrResult(
            transcripts = listOf(
                Transcript(VoiceModel.FREOCOCO, "f", "", 0),
                Transcript(VoiceModel.OMNILINGUAL, "o", "abc", 0),
            ),
            audioMs = 1000,
            decodeMs = 10,
        )
        assertEquals("abc", heard.textOf(VoiceModel.OMNILINGUAL))
        assertEquals("", heard.textOf(VoiceModel.FREOCOCO))
        assertFalse(heard.isEmpty)
        assertTrue(heard.copy(transcripts = heard.transcripts.map { it.copy(text = "") }).isEmpty)
    }

    @Test
    fun asrExceptionCarriesState() {
        val e = AsrException.ModelNotReady(ModelState.Missing)
        assertEquals("model not ready: Missing", e.message)
        val f = AsrException.EngineFailure(7, "decode failed")
        assertEquals(7, f.code)
        assertEquals("decode failed", f.message)
    }

    @Test
    fun voiceModelsRunFreococoThenOmnilingual() {
        assertEquals(listOf("FREOCOCO", "OMNILINGUAL"), VoiceModel.entries.map { it.name })
    }

    @Test
    fun voicePackPinsEveryFile() {
        assertEquals(1_109_890_746L, VoicePack.downloadBytes)
        assertEquals(VoicePack.files.size, VoicePack.files.map { it.name }.toSet().size)
        for (f in VoicePack.files) assertEquals(f.path, 64, f.sha256.length)
    }
}
