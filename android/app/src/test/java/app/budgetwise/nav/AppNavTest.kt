package app.budgetwise.nav

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.junit.Assert.assertNotEquals
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
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h914dp")
class AppNavTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    /** What an expired token leaves behind: no token, the user identity kept (see SessionManager.onUnauthorized). */
    @BindValue @JvmField val tokenStore: TokenStore = FakeTokenStore(StoredSession("", Instant.EPOCH, TestUser))

    @Inject lateinit var session: SessionManager

    @Before
    fun setUp() {
        // Settings reads queued work from WorkManager; HiltTestApplication does not configure it.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Context>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
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
        // The empty state waits for Room's first answer, which arrives from Room's own thread.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.budgets_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.budgets_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_expired_message)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.nav_transactions)).performClick()
        compose.onNodeWithText(text(R.string.session_expired_message)).assertIsDisplayed()
    }

    @Test
    fun signOutForgetsTheSavedTabState() {
        var state by mutableStateOf<SessionState>(SessionState.Expired)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            BudgetWiseTheme { AppNav(session = state, navController = nav) }
        }

        compose.onNodeWithText(text(R.string.nav_budgets)).performClick()
        val firstBudgets = compose.runOnIdle { nav.currentBackStackEntry!!.id }
        // Leaving Budgets through the bottom bar saves its back stack (ViewModel included) to restore later.
        compose.onNodeWithText(text(R.string.nav_settings)).performClick()

        compose.runOnIdle { state = SessionState.SignedOut }
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).assertIsDisplayed()

        // Another account signs in; sign-in navigates the way LoginRoute does on success.
        compose.runOnIdle {
            state = SessionState.SignedIn(TestUser.copy(id = "u2", email = "ko@example.com"), Instant.now().plusSeconds(86_400 * 20L), false)
            nav.navigate(Routes.TRANSACTIONS) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        compose.onNodeWithText(text(R.string.nav_budgets)).performClick()

        compose.runOnIdle {
            assertEquals(Routes.BUDGETS, nav.currentDestination?.route)
            assertNotEquals("the previous user's Budgets entry came back", firstBudgets, nav.currentBackStackEntry!!.id)
        }
    }
}
