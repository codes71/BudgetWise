package app.budgetwise.ui.common

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class PermissionMemoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun camera() = PermissionMemory(context, Manifest.permission.CAMERA)

    @Test
    fun aDismissedDialogIsAskedAgain() {
        // The prompt on entry, closed with back: no answer, and no rationale on Android 11 and later.
        camera().answered(granted = false, rationale = false)
        assertFalse(camera().blocked)

        // Tapped Allow, and dismissed again after a moment.
        val memory = camera()
        memory.tapped()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        memory.answered(granted = false, rationale = false)
        assertFalse(camera().blocked)
    }

    @Test
    fun theSecondDontAllowLeavesOnlySettings() {
        camera().answered(granted = false, rationale = true)
        assertFalse("Android still asks after one refusal", camera().blocked)

        camera().answered(granted = false, rationale = false)
        assertTrue(camera().blocked)
        // Remembered on the phone, for the next scan.
        assertTrue(PermissionMemory(context, Manifest.permission.CAMERA).blocked)
        assertFalse("each permission on its own", PermissionMemory(context, Manifest.permission.RECORD_AUDIO).blocked)
    }

    @Test
    fun aRefusalFasterThanAnyoneCouldAnswerWasBlockedElsewhere() {
        // Refused in Settings: the dialog never shows, and the answer comes back at once.
        val memory = camera()
        memory.tapped()
        memory.answered(granted = false, rationale = false)
        assertTrue(camera().blocked)
    }

    @Test
    fun allowingForgetsTheRefusals() {
        camera().answered(granted = false, rationale = true)
        camera().answered(granted = false, rationale = false)

        camera().allowed()

        assertFalse(camera().blocked)
        camera().answered(granted = false, rationale = false)
        assertFalse("a dismissal after it is asked again", camera().blocked)
    }
}
