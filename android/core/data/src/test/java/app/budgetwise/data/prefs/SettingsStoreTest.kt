package app.budgetwise.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.store(name: String = "prefs") = DataStoreSettingsStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("$name.preferences_pb") },
    )

    @Test
    fun defaultsAreOff() = runTest(UnconfinedTestDispatcher()) {
        val s = store()
        assertFalse(s.autoSaveHighConfidence.first())
        assertFalse(s.cloudParseEnabled.first())
        assertFalse(s.myanmarDigits.first())
        assertNull(s.lastPulledAt.first())
    }

    @Test
    fun flagsAndCursorRoundTripAndCursorClears() = runTest(UnconfinedTestDispatcher()) {
        val s = store()
        s.setAutoSaveHighConfidence(true)
        s.setCloudParseEnabled(true)
        s.setMyanmarDigits(true)
        val at = Instant.parse("2026-10-21T06:00:00.123Z")
        s.setLastPulledAt(at)
        assertTrue(s.autoSaveHighConfidence.first())
        assertTrue(s.cloudParseEnabled.first())
        assertTrue(s.myanmarDigits.first())
        assertEquals(at, s.lastPulledAt.first())
        s.clearLastPulledAt()
        assertNull(s.lastPulledAt.first())
        assertTrue("clearing the cursor leaves the flags", s.cloudParseEnabled.first())
    }
}
