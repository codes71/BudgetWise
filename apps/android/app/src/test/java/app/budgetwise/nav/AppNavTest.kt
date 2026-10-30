package app.budgetwise.nav

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import app.budgetwise.data.auth.UserInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun anExpiredSessionShowsTheSignInChip() {
        assertEquals(SessionState.Expired, session.state.value)
        showApp()

        // Expired keeps the user in the app: transactions with the bottom bar, and the header's chip asks to sign in.
        compose.onNodeWithText(text(R.string.nav_budgets)).assertIsDisplayed()
        compose.onNode(hasContentDescription(text(R.string.chip_sign_in_description)) and hasClickAction())
            .assertIsDisplayed()
            .performClick()

        // Sign-in opens with the known email filled in, and without the bottom bar.
        compose.onNodeWithText(TestUser.email).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_budgets)).assertDoesNotExist()
    }

    @Test
    fun theGearOpensSettingsOnTopWithABackArrow() {
        showApp()

        compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_budgets)).assertDoesNotExist()

        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.onNodeWithText(text(R.string.settings_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.nav_budgets)).assertIsDisplayed()
    }

    @Test
    fun theAddPillOpensTheForm() {
        showApp()

        compose.onNode(hasText(text(R.string.action_add)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.manual_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_budgets)).assertDoesNotExist()
    }

    @Test
    fun signedOutStartsAtTheWelcomeScreenWithoutTheBottomBar() {
        showApp(SessionState.SignedOut)

        compose.onNode(hasText(text(R.string.welcome_create)) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_transactions)).assertDoesNotExist()
        // Both ways in: the second opens sign-in, and its back arrow returns to the welcome screen.
        compose.onNode(hasText(text(R.string.welcome_sign_in)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.onNode(hasText(text(R.string.welcome_create)) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun signInAndCreateAccountSwapWithoutStackingUp() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            BudgetWiseTheme { AppNav(session = SessionState.SignedOut, navController = nav) }
        }

        compose.onNode(hasText(text(R.string.welcome_create)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.welcome_create)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).performClick()
        val stack = compose.runOnIdle { nav.currentBackStack.value.mapNotNull { it.destination.route } }
        assertEquals(listOf(Routes.WELCOME, Routes.CREATE_ACCOUNT, Routes.LOGIN), stack)
    }

    @Test
    fun signedOutInTheFirstCompositionDoesNotTouchTheGraphTooEarly() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            BudgetWiseTheme { AppNav(session = SessionState.SignedOut, navController = nav) }
        }

        compose.onNode(hasText(text(R.string.welcome_create)) and hasClickAction()).assertIsDisplayed()
        assertEquals(Routes.WELCOME, compose.runOnIdle { nav.currentDestination?.route })
    }

    @Test
    fun theFormClosesEvenWhenItsEntryIsNotResumed() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.TRANSACTIONS) {
                composable(Routes.TRANSACTIONS) { Text("list") }
                composable(Routes.MANUAL) { Text("form") }
            }
        }

        // Frozen clock: the enter transition never finishes, so the entry stays STARTED (what a Saved event that
        // arrives mid-transition or in the background meets). dropUnlessResumed drops the call in that state.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate(Routes.MANUAL) }
        compose.mainClock.advanceTimeByFrame()
        val state = compose.runOnIdle { nav.currentBackStackEntry!!.lifecycle.currentState }
        assertFalse("premise: the entry is not resumed", state == Lifecycle.State.RESUMED)

        compose.runOnIdle {
            nav.closeManual()
            nav.closeManual()
        }
        compose.mainClock.autoAdvance = true
        assertEquals(Routes.TRANSACTIONS, compose.runOnIdle { nav.currentDestination?.route })
    }

    @Test
    fun bottomBarSwitchesBetweenTheTwoTabs() {
        showApp()

        compose.onNodeWithText(text(R.string.nav_budgets)).performClick()
        // The empty state waits for Room's first answer, which arrives from Room's own thread.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.budgets_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.budgets_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_empty_title)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.nav_transactions)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.transactions_empty_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.transactions_empty_title)).assertIsDisplayed()
    }

    private fun signedIn(user: UserInfo) = SessionState.SignedIn(user, Instant.now().plusSeconds(86_400 * 20L), false)

    private val otherUser = TestUser.copy(id = "u2", email = "ko@example.com")

    /**
     * Opens Budgets and leaves it for Transactions through the bottom bar, which saves its back stack (ViewModel included) to restore
     * later. Then the session goes through [between], [next] signs in the way LoginRoute does on success, and Budgets
     * is opened again. True when the saved Budgets entry came back.
     */
    private fun savedBudgetsComeBack(start: SessionState, between: List<SessionState>, next: UserInfo): Boolean {
        var state by mutableStateOf(start)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            BudgetWiseTheme { AppNav(session = state, navController = nav) }
        }

        compose.onNodeWithText(text(R.string.nav_budgets)).performClick()
        val firstBudgets = compose.runOnIdle { nav.currentBackStackEntry!!.id }
        compose.onNodeWithText(text(R.string.nav_transactions)).performClick()

        for (s in between) {
            compose.runOnIdle { state = s }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            state = signedIn(next)
            nav.navigate(Routes.TRANSACTIONS) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        compose.onNodeWithText(text(R.string.nav_budgets)).performClick()

        return compose.runOnIdle {
            assertEquals(Routes.BUDGETS, nav.currentDestination?.route)
            nav.currentBackStackEntry!!.id == firstBudgets
        }
    }

    @Test
    fun signOutForgetsTheSavedTabState() {
        assertFalse(savedBudgetsComeBack(start = SessionState.Expired, between = listOf(SessionState.SignedOut), next = otherUser))
    }

    @Test
    fun anotherAccountAfterAnExpiredSessionGetsFreshTabs() {
        // AuthRepository switches accounts without a sign-out: Expired goes straight to SignedIn as someone else.
        assertFalse(savedBudgetsComeBack(start = signedIn(TestUser), between = listOf(SessionState.Expired), next = otherUser))
    }

    @Test
    fun anAccountSigningInOnAnExpiredStartGetsFreshTabs() {
        // The app started expired, so whose tabs these are is not known: they are not handed to whoever signs in.
        assertFalse(savedBudgetsComeBack(start = SessionState.Expired, between = emptyList(), next = otherUser))
    }

    @Test
    fun theSameAccountSigningInAgainKeepsItsTabs() {
        assertTrue(savedBudgetsComeBack(start = signedIn(TestUser), between = listOf(SessionState.Expired), next = TestUser))
    }
}
