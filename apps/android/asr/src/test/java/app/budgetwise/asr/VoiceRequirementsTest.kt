package app.budgetwise.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRequirementsTest {
    @Test
    fun fourGigabytePhonesRunVoiceAndThreeGigabyteOnesDoNot() {
        // What Android reports: the 4 GB emulator, a Xiaomi 14 (12 GB), and a typical 3 GB phone.
        assertTrue(VoiceRequirements.supported(4_160_389_120L, lowRamDevice = false))
        assertTrue(VoiceRequirements.supported(11_710_951_424L, lowRamDevice = false))
        assertFalse(VoiceRequirements.supported(2_850_000_000L, lowRamDevice = false))
    }

    @Test
    fun aPhoneThatCallsItselfLowRamNeverRunsVoice() {
        assertFalse(VoiceRequirements.supported(11_710_951_424L, lowRamDevice = true))
    }
}
