package app.budgetwise.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private var signOuts = 0

    private fun show(state: SettingsUiState) = compose.setContent {
        BudgetWiseTheme {
            SettingsScreen(
                state = state,
                now = now,
                onBack = {},
                onSyncNow = {},
                onSignOut = { signOuts++ },
                onMyanmarDigitsChange = {},
                onSignIn = {},
                version = "Version 0.1.0 (1)",
            )
        }
    }

    private val signedIn = SettingsUiState(
        account = AccountInfo("Aye Aye", "aye@example.com"),
        sync = SyncSummary(SyncPhase.SYNCED, now.minusSeconds(120)),
        unsyncedCount = 2,
    )

    @Test
    fun theVersionIsTheLastRowAndThereIsNoServerRow() {
        show(signedIn)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Version 0.1.0 (1)"))
        compose.onNodeWithText("Version 0.1.0 (1)").assertIsDisplayed()
        compose.onNodeWithText("Server", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Developer", substring = true).assertDoesNotExist()
        // The sync button and its state stay.
        compose.onNodeWithText(context.getString(R.string.settings_sync_now)).assertExists()
    }

    @Test
    fun signOutWithUnsyncedEntriesHappensWithoutADialog() {
        show(signedIn)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.settings_sign_out)))
        compose.onNodeWithText(context.getString(R.string.settings_sign_out)).performClick()

        assertEquals(1, signOuts)
        compose.onNodeWithText(context.getString(R.string.action_cancel)).assertDoesNotExist()
    }
}
