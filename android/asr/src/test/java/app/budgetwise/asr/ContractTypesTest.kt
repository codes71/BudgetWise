package app.budgetwise.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals("ready /models/raj", describe(ModelState.Ready("/models/raj")))
        assertEquals("failed no space", describe(ModelState.Failed("no space")))
    }

    @Test
    fun asrResultEquality() {
        val a = AsrResult(text = "abc", modelId = "raj-0.6b", audioMs = 4000, decodeMs = 5200)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertNotEquals(a, a.copy(text = "abd"))
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
    fun rajVariantsPresent() {
        assertEquals(listOf("RAJ_0_6B", "RAJ_1_7B"), RajVariant.values().map { it.name })
    }
}
