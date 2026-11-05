package app.budgetwise.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.repo.AccountDeletionResult
import app.budgetwise.ui.theme.BudgetWiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class AccountPrivacySectionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    @Test fun accountControlsOpenPrivacyAndOnlyOfferDeletionForAnAccount() {
        var privacy = 0
        var delete = 0
        val account = mutableStateOf(false)
        compose.setContent {
            BudgetWiseTheme { AccountPrivacyControls(account.value, { privacy++ }, { delete++ }) }
        }
        compose.onNodeWithText(text(R.string.account_privacy_policy)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, privacy)
        compose.onNodeWithText(text(R.string.account_delete)).assertDoesNotExist()
        compose.runOnIdle { account.value = true }
        compose.onNodeWithText(text(R.string.account_delete)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, delete)
    }

    @Test fun passwordIsRequiredAndDeletionWaitsForAnExplicitConfirmation() {
        var confirmations = 0
        val state = mutableStateOf(AccountDeletionUiState(accountId = "u1", email = "friend@example.com"))
        compose.setContent {
            BudgetWiseTheme {
                AccountDeletionDialog(state.value, { state.value = state.value.copy(password = it) }, { confirmations++ }, {})
            }
        }
        compose.onNodeWithText(text(R.string.account_delete_unsynced)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_delete_confirm)).performScrollTo().assertIsNotEnabled()
        assertEquals(0, confirmations)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("current-password")
        compose.onNodeWithText(text(R.string.account_delete_confirm)).performScrollTo().performClick()
        assertEquals(1, confirmations)
    }

    @Test fun keepingTheAccountNeverSubmitsDeletion() {
        var confirmations = 0
        var dismissals = 0
        compose.setContent {
            BudgetWiseTheme {
                AccountDeletionDialog(AccountDeletionUiState("u1", "friend@example.com", "pw"), {}, { confirmations++ }, { dismissals++ })
            }
        }
        compose.onNodeWithText(text(R.string.account_delete_keep)).performScrollTo().performClick()
        assertEquals(1, dismissals)
        assertEquals(0, confirmations)
    }

    @Test fun inFlightDeletionDisablesThePasswordAndBothActions() {
        compose.setContent {
            BudgetWiseTheme {
                AccountDeletionDialog(AccountDeletionUiState("u1", "friend@example.com", busy = true), {}, {}, {})
            }
        }
        // Disabled text fields correctly remove SetText. Locate the retained password semantics instead.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasText(text(R.string.account_deleting)) and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.account_delete_keep)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun failedOfflineDeletionSaysThePhoneDataWasKept() {
        compose.setContent {
            BudgetWiseTheme {
                AccountDeletionDialog(
                    AccountDeletionUiState("u1", "friend@example.com", failure = AccountDeletionResult.NeedsInternet), {}, {}, {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.account_delete_offline)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_delete_confirm)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun wrongPasswordIsShownWithoutTreatingDeletionAsSuccessful() {
        compose.setContent {
            BudgetWiseTheme {
                AccountDeletionDialog(
                    AccountDeletionUiState("u1", "friend@example.com", failure = AccountDeletionResult.WrongPassword), {}, {}, {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.account_delete_wrong_password)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_delete_done)).assertDoesNotExist()
    }

    @Test fun largeTextDarkModeKeepsTheSafeActionReachable() {
        var kept = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                BudgetWiseTheme(darkTheme = true) {
                    AccountDeletionDialog(AccountDeletionUiState("u1", "friend@example.com", "pw"), {}, {}, { kept++ })
                }
            }
        }
        compose.onNodeWithText(text(R.string.account_delete_keep)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, kept)
    }
}
