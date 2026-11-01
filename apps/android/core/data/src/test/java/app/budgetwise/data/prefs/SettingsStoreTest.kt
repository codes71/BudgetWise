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
    fun defaultsAreOffExceptCloudParse() = runTest(UnconfinedTestDispatcher()) {
        val s = store()
        assertFalse(s.autoSaveHighConfidence.first())
        assertTrue("voice has no offline mode", s.cloudParseEnabled.first())
        assertFalse(s.myanmarDigits.first())
        assertFalse(s.voiceNoticeShown.first())
        assertNull(s.lastPulledAt("u1").first())
    }

    @Test
    fun theVoiceNoticeIsRememberedOnceShown() = runTest(UnconfinedTestDispatcher()) {
        val s = store()
        s.setVoiceNoticeShown()
        assertTrue(s.voiceNoticeShown.first())
    }

    @Test
    fun flagsAndPerAccountCursorsRoundTrip() = runTest(UnconfinedTestDispatcher()) {
        val s = store()
        s.setAutoSaveHighConfidence(true)
        s.setCloudParseEnabled(false)
        s.setMyanmarDigits(true)
        val at = Instant.parse("2026-10-21T06:00:00.123Z")
        s.setLastPulledAt("u1", at)
        assertTrue(s.autoSaveHighConfidence.first())
        assertFalse(s.cloudParseEnabled.first())
        assertTrue(s.myanmarDigits.first())
        assertEquals(at, s.lastPulledAt("u1").first())
        assertNull("each account has its own cursor", s.lastPulledAt("u2").first())
        s.setLastPulledAt("u2", at.plusSeconds(60))
        assertEquals(at, s.lastPulledAt("u1").first())
    }

    @Test
    fun aCorruptFileYieldsDefaultsAndIsWritableAgain() = runTest(UnconfinedTestDispatcher()) {
        tmp.root.resolve("corrupt.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55, 0x66))
        val s = DataStoreSettingsStore(
            createAppPreferences(backgroundScope) { tmp.root.resolve("corrupt.preferences_pb") },
        )
        assertFalse(s.myanmarDigits.first())
        assertNull(s.lastPulledAt("u1").first())
        s.setMyanmarDigits(true)
        assertTrue(s.myanmarDigits.first())
    }
}
