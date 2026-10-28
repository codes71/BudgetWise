package app.budgetwise.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    private fun profile(ramBytes: Long = 8_000_000_000L, khz: List<Int>) =
        DeviceProfile(totalRamBytes = ramBytes, coreMaxKhz = khz, socModel = "test")

    @Test
    fun threadsCountsBigCores() {
        // 2 x 2.2 GHz + 6 x 1.8 GHz: 1.8 / 2.2 = 0.818 < 0.85, so only the two fast cores count.
        assertEquals(2, profile(khz = List(2) { 2_200_000 } + List(6) { 1_800_000 }).threads())
        // 1 + 3 + 4 layout: 2.4 / 2.8 = 0.857 >= 0.85, so 1 + 3 = 4.
        assertEquals(
            4,
            profile(khz = listOf(2_800_000) + List(3) { 2_400_000 } + List(4) { 1_800_000 }).threads(),
        )
    }

    @Test
    fun threadsNeverBelowTwo() {
        assertEquals(2, profile(khz = listOf(3_000_000) + List(7) { 1_000_000 }).threads())
        assertEquals(2, profile(khz = emptyList()).threads())
        assertEquals(2, profile(khz = listOf(0, 0)).threads())
    }

    @Test
    fun threadsNeverAboveFour() {
        assertEquals(4, profile(khz = List(8) { 2_000_000 }).threads())
    }

    @Test
    fun variantEligibilityBlocks17BBelow7_5GB() {
        val khz = List(8) { 2_000_000 }
        assertFalse(profile(4_000_000_000L, khz).eligible(RajVariant.RAJ_1_7B))
        assertFalse(profile(6_000_000_000L, khz).eligible(RajVariant.RAJ_1_7B))
        assertTrue(profile(8_000_000_000L, khz).eligible(RajVariant.RAJ_1_7B))
        assertTrue(profile(7_900_000_000L, khz).eligible(RajVariant.RAJ_1_7B))
        assertTrue(profile(4_000_000_000L, khz).eligible(RajVariant.RAJ_0_6B))
    }
}
