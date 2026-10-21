package app.budgetwise.nav

import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.budgetwise.HiltTestActivity
import app.budgetwise.R
import app.budgetwise.data.auth.AuthBindingsModule
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.TestUser
import app.budgetwise.ui.theme.BudgetWiseTheme
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shell with the real Hilt graph (sign-in uses `hiltViewModel()`); only the Keystore token store is faked. */
@HiltAndroidTest
@UninstallModules(AuthBindingsModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AppNavTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    /** What an expired token leaves behind: no token, the user identity kept (see SessionManager.onUnauthorized). */
    @BindValue @JvmField val tokenStore: TokenStore = FakeTokenStore(StoredSession("", Instant.EPOCH, TestUser))

    @Inject lateinit var session: SessionManager

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { session.load() }
    }

    private fun text(@StringRes id: Int) = compose.activity.getString(id)

    private fun showApp(state: SessionState? = null) = compose.setContent {
        val live by session.state.collectAsState()
        BudgetWiseTheme { AppNav(session = state ?: live) }
    }

    @Test
    fun expiredSessionBannerShowsSignInAction() {
        assertEquals(SessionState.Expired, session.state.value)
        showApp()

        // Expired keeps the user in the app: transactions with the bottom bar, plus the banner.
        compose.onNodeWithText(text(R.string.session_expired_message)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_settings)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.session_sign_in)) and hasClickAction()).assertIsDisplayed().performClick()

        // Sign-in opens with the known email filled in, and without the bottom bar.
        compose.onNodeWithText(TestUser.email).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_settings)).assertDoesNotExist()
    }

    @Test
    fun signedOutStartsAtSignInWithoutTheBottomBar() {
        showApp(SessionState.SignedOut)

        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_transactions)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.session_expired_message)).assertDoesNotExist()
    }

    @Test
    fun bottomBarSwitchesBetweenTheThreeDestinations() {
        showApp()

        compose.onNodeWithText(text(R.string.nav_budgets)).performClick()
        compose.onNodeWithText(text(R.string.budgets_placeholder)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_expired_message)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.nav_transactions)).performClick()
        compose.onNodeWithText(text(R.string.session_expired_message)).assertIsDisplayed()
    }
}
