package app.budgetwise.ui.login

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(state: LoginUiState) = compose.setContent {
        BudgetWiseTheme {
            LoginScreen(
                state = state,
                onEmailChange = {},
                onPasswordChange = {},
                onSubmit = {},
                onClose = null,
                serverUrl = null,
            )
        }
    }

    @Test
    fun signInButtonIsDisabledWhileSigningIn() {
        // TalkBack reads the button's enabled state; a busy button must say it cannot be pressed again.
        show(LoginUiState(email = "aye@example.com", password = "secret", loading = true))

        compose.onNodeWithText(context.getString(R.string.login_signing_in)).assertIsNotEnabled()
    }

    @Test
    fun noConnectionForANewAccountShowsTheRefusal() {
        show(LoginUiState(email = "aye@example.com", password = "secret", error = LoginError.NeedsInternet))

        compose.onNodeWithText(context.getString(R.string.login_error_needs_internet)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.login_submit)).assertIsEnabled()
    }

    @Test
    fun signInButtonIsEnabledWithBothFieldsFilled() {
        show(LoginUiState(email = "aye@example.com", password = "secret"))

        compose.onNodeWithText(context.getString(R.string.login_submit)).assertIsEnabled()
    }
}
