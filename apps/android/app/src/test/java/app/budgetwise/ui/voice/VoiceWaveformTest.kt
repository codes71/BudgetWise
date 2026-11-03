package app.budgetwise.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWaveformTest {
    @Test
    fun historyContainsOnlyTheLastTwelveObservedLevelsInOrder() {
        val levels = (1..30).map { it / 30f }
        val history = levels.fold(emptyList<Float>(), ::observedWaveform)

        assertEquals(levels.takeLast(12), history)
    }

    @Test
    fun anObservedSilenceClearsSpeechAndTheNextUtteranceStartsFresh() {
        val spoken = listOf(0.2f, 0.8f, 0.4f).fold(emptyList<Float>(), ::observedWaveform)
        val silence = observedWaveform(spoken, 0f)

        assertTrue(silence.isEmpty())
        assertEquals(listOf(0.3f), observedWaveform(silence, 0.3f))
    }

    @Test
    fun invalidLevelsCannotProduceMovingOrOutOfBoundsBars() {
        assertTrue(observedWaveform(listOf(0.8f), Float.NaN).isEmpty())
        assertTrue(observedWaveform(listOf(0.8f), Float.POSITIVE_INFINITY).isEmpty())
        assertTrue(observedWaveform(listOf(0.8f), -1f).isEmpty())
        assertEquals(listOf(1f), observedWaveform(emptyList(), 2f))
    }
}
