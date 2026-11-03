package app.budgetwise.ui.updates

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class UpdatePromptTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = mutableStateOf<UpdateState>(UpdateState.Idle)
    private var downloads = 0
    private var cancels = 0
    private var installs = 0

    private fun text(id: Int) = context.getString(id)

    private fun show() = compose.setContent {
        BudgetWiseTheme {
            UpdatePrompt(state.value, {}, { downloads++ }, { cancels++ }, { installs++ })
        }
    }

    @Test
    fun dismissingAnOfferDoesNotReopenItOnAnotherCheck() {
        show()
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertDoesNotExist()
        compose.runOnIdle { state.value = UpdateState.Available(testRelease) }
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.updates_not_now)).performClick()
        compose.runOnIdle { state.value = UpdateState.Checking }
        compose.runOnIdle { state.value = UpdateState.Available(testRelease) }
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertDoesNotExist()
        assertEquals(0, downloads)
        compose.runOnIdle { state.value = UpdateState.Available(testRelease.copy(versionCode = 3, versionName = "0.3.0")) }
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertIsDisplayed()
    }

    @Test
    fun cancellingDownloadClosesTheOfferAndDoesNotImmediatelyOpenAgain() {
        state.value = UpdateState.Available(testRelease)
        show()
        compose.onNodeWithText(text(R.string.updates_download)).performClick()
        assertEquals(1, downloads)
        compose.runOnIdle { state.value = UpdateState.Downloading(testRelease, 10, 100) }
        compose.onNodeWithText(text(R.string.updates_cancel_download)).performClick()
        assertEquals(1, cancels)
        compose.runOnIdle { state.value = UpdateState.Available(testRelease) }
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertDoesNotExist()
    }

    @Test
    fun downloadCompletionOffersInstallationAndLaterLeavesItForSettings() {
        state.value = UpdateState.Available(testRelease)
        show()
        compose.onNodeWithText(text(R.string.updates_download)).performClick()
        compose.runOnIdle { state.value = UpdateState.Ready(testRelease) }
        assertEquals(0, installs)
        compose.onNodeWithText(text(R.string.updates_install)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.updates_later)).performClick()
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertDoesNotExist()
        assertEquals(0, installs)
    }

    @Test
    fun aRestoredReadyUpdateOffersInstallationWithoutGoingThroughSettings() {
        state.value = UpdateState.Ready(testRelease)
        show()
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.updates_install)).assertIsDisplayed().performClick()
        assertEquals(1, installs)
        assertEquals(0, downloads)
    }

    @Test
    fun dismissingARestoredReadyUpdateDoesNotReopenItOnStateChanges() {
        state.value = UpdateState.Ready(testRelease)
        show()
        compose.onNodeWithText(text(R.string.updates_later)).performClick()
        compose.runOnIdle { state.value = UpdateState.Checking }
        compose.runOnIdle { state.value = UpdateState.Ready(testRelease) }
        compose.onNodeWithText(text(R.string.updates_prompt_title)).assertDoesNotExist()
        assertEquals(0, installs)
        assertEquals(0, downloads)
    }
}
