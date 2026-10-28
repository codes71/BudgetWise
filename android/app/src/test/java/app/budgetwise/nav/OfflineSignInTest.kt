package app.budgetwise.nav

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.HiltTestActivity
import app.budgetwise.R
import app.budgetwise.data.auth.AuthBindingsModule
import app.budgetwise.data.auth.PasswordCheck
import app.budgetwise.data.auth.SavedAccount
import app.budgetwise.data.auth.SavedAccounts
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.net.NetConfig
import app.budgetwise.di.NetConfigModule
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.TestUser
import app.budgetwise.ui.theme.BudgetWiseTheme
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sign-in with the server out of reach, through the real Hilt graph: real AuthRepository and SessionManager, a
 * network that refuses every connection, and only the Keystore store faked. The account signed in online here once
 * (so the phone holds its offline check) and then signed out.
 */
@HiltAndroidTest
@UninstallModules(AuthBindingsModule::class, NetConfigModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h914dp")
class OfflineSignInTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val tokenStore: TokenStore = FakeTokenStore(
        SavedAccounts(
            activeId = null,
            accounts = listOf(SavedAccount(TestUser, access = null, check = PasswordCheck.create(PASSWORD), signedOut = true)),
        ),
    )

    /** Nothing listens on port 1: each request fails to connect, as with no internet. */
    @BindValue @JvmField val netConfig = NetConfig("http://127.0.0.1:1/api/v1/", debugLogging = false)

    @Inject lateinit var session: SessionManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Context>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        hilt.inject()
        runBlocking { session.load() }
    }

    private fun text(@StringRes id: Int) = compose.activity.getString(id)

    private fun signIn(email: String, password: String) {
        compose.setContent {
            val live by session.state.collectAsState()
            BudgetWiseTheme { AppNav(session = live) }
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(email)
        fields[1].performTextInput(password)
        compose.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).performClick()
    }

    private fun waitForText(@StringRes id: Int) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aKnownAccountSignsInWithoutTheInternetAndLandsOnTransactions() {
        signIn(TestUser.email, PASSWORD)

        waitForText(R.string.transactions_signed_in_offline)
        compose.onNodeWithText(text(R.string.transactions_signed_in_offline)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_expired_message)).assertDoesNotExist()
        assertEquals(SessionState.SignedInOffline(TestUser), session.state.value)
    }

    @Test
    fun theWrongPasswordIsRefusedOfflineToo() {
        signIn(TestUser.email, "not-it")

        waitForText(R.string.login_error_wrong_credentials)
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun anAccountNeverUsedOnThisPhoneNeedsTheInternet() {
        signIn("new@example.com", PASSWORD)

        waitForText(R.string.login_error_needs_internet)
        compose.onNodeWithText(text(R.string.login_error_needs_internet)).assertIsDisplayed()
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    private companion object {
        const val PASSWORD = "tea-shop-2026"
    }
}
