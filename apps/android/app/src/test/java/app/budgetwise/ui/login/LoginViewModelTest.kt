package app.budgetwise.ui.login

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.repo.LoginResult
import app.budgetwise.testing.FakeAccountActions
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.LoginCall
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.TestUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val accounts = FakeAccountActions()

    private fun session(stored: StoredSession? = null) =
        SessionManager(FakeTokenStore(stored), Clock.fixed(now, ZoneOffset.UTC)).also { runBlocking { it.load() } }

    private fun TestScope.collectEvents(vm: LoginViewModel): List<LoginEvent> {
        val events = mutableListOf<LoginEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    private fun LoginViewModel.fill(email: String = TestUser.email, password: String = "secret") {
        onEmailChange(email)
        onPasswordChange(password)
    }

    @Test
    fun login_successNavigatesAndStoresToken() = runTest {
        accounts.onLogin = { LoginResult.Success(TestUser) }
        val vm = LoginViewModel(accounts, session())
        val events = collectEvents(vm)

        vm.fill(email = "  ${TestUser.email} ")
        vm.submit()

        // AuthRepository stores the token and enqueues the full sync and the periodic sync itself; the ViewModel
        // only calls it (once, with the trimmed email) and then navigates.
        assertEquals(listOf(LoginCall(TestUser.email, "secret", discard = false)), accounts.logins)
        assertEquals(listOf(LoginEvent.SignedIn), events)
        assertTrue(vm.state.value.signedIn)
        assertNull(vm.state.value.error)
        assertEquals("", vm.state.value.password) // not kept in memory after sign-in
    }

    @Test
    fun login_wrongPasswordShowsServerMessage() = runTest {
        accounts.onLogin = { LoginResult.WrongCredentials }
        val session = session()
        val vm = LoginViewModel(accounts, session)
        val events = collectEvents(vm)

        vm.fill(password = "wrong")
        vm.submit()

        assertEquals(LoginError.WrongCredentials, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.signedIn)
        assertTrue(events.isEmpty())
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals("wrong", vm.state.value.password) // kept so the user can fix a typo
    }

    @Test
    fun networkFailureShowsConnectionMessage() = runTest {
        accounts.onLogin = { LoginResult.Failure("network", "timeout", retryable = true) }
        val vm = LoginViewModel(accounts, session())
        val events = collectEvents(vm)

        vm.fill()
        vm.submit()

        assertEquals(LoginError.Network, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertTrue(events.isEmpty())
    }

    @Test
    fun blankFieldsDisableSubmit() = runTest {
        val vm = LoginViewModel(accounts, session())
        assertFalse(vm.state.value.canSubmit)

        vm.fill(email = "   ", password = "secret")
        assertFalse(vm.state.value.canSubmit)
        vm.fill(email = TestUser.email, password = "")
        assertFalse(vm.state.value.canSubmit)

        vm.submit()
        vm.submit("  ", "secret")
        assertTrue(accounts.logins.isEmpty())

        vm.fill()
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun loadingDisablesTheFormAndIgnoresASecondSubmit() = runTest {
        val gate = CompletableDeferred<Unit>()
        accounts.loginGate = gate
        accounts.onLogin = { LoginResult.WrongCredentials }
        val vm = LoginViewModel(accounts, session())

        vm.fill()
        vm.submit()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        assertEquals(1, accounts.logins.size)

        gate.complete(Unit)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun tooManyAttemptsShowsTryAgainLater() = runTest {
        accounts.onLogin = { LoginResult.Failure("rate_limited", "Too many requests", retryable = true) }
        val vm = LoginViewModel(accounts, session())

        vm.fill()
        vm.submit()

        assertEquals(LoginError.RateLimited(minutes = null), vm.state.value.error)
        assertEquals(LoginError.RateLimited(minutes = 15), loginErrorFor(LoginResult.Failure("http_429", "Try again in 15 minutes", true)))
    }

    @Test
    fun serverAndUnexpectedFailuresAreTold() {
        assertEquals(LoginError.Server, loginErrorFor(LoginResult.Failure("internal_error", "boom", retryable = true)))
        assertEquals(LoginError.Generic, loginErrorFor(LoginResult.Failure("bad_response", "?", retryable = false)))
    }

    @Test
    fun editingAFieldClearsTheError() = runTest {
        accounts.onLogin = { LoginResult.WrongCredentials }
        val vm = LoginViewModel(accounts, session())
        vm.fill()
        vm.submit()

        vm.onPasswordChange("secret2")

        assertNull(vm.state.value.error)
    }

    @Test
    fun otherUsersUnsyncedRowsAskBeforeDeleting() = runTest {
        accounts.onLogin = { call ->
            if (call.discard) LoginResult.Success(TestUser) else LoginResult.ConfirmDiscard("old@example.com", 3)
        }
        val vm = LoginViewModel(accounts, session())
        val events = collectEvents(vm)

        vm.fill()
        vm.submit()
        assertEquals(DiscardPrompt(previousEmail = "old@example.com", unsyncedCount = 3), vm.state.value.discardPrompt)
        assertFalse(vm.state.value.loading)
        assertTrue(events.isEmpty())

        vm.confirmDiscard()

        assertEquals(
            listOf(LoginCall(TestUser.email, "secret", discard = false), LoginCall(TestUser.email, "secret", discard = true)),
            accounts.logins,
        )
        assertNull(vm.state.value.discardPrompt)
        assertEquals(listOf(LoginEvent.SignedIn), events)
    }

    @Test
    fun confirmDiscardRetriesWithTheSubmittedCredentials() = runTest {
        accounts.onLogin = { call ->
            if (call.discard) LoginResult.Success(TestUser) else LoginResult.ConfirmDiscard("old@example.com", 1)
        }
        val vm = LoginViewModel(accounts, session())
        vm.fill(email = "typed@example.com", password = "typed")

        // The overload sends credentials that differ from the fields; the retry must send the same ones again.
        vm.submit("  other@example.com ", "pw2")
        vm.confirmDiscard()

        assertEquals(
            listOf(LoginCall("other@example.com", "pw2", discard = false), LoginCall("other@example.com", "pw2", discard = true)),
            accounts.logins,
        )
    }

    @Test
    fun anUnexpectedExceptionShowsTheGenericErrorAndEndsLoading() = runTest {
        // AuthRepository can throw on a Keystore or Room failure instead of returning a result.
        accounts.onLogin = { throw IllegalStateException("keystore unavailable") }
        val vm = LoginViewModel(accounts, session())
        val events = collectEvents(vm)
        vm.fill()

        vm.submit()

        assertEquals(LoginError.Generic, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.signedIn)
        assertTrue(vm.state.value.canSubmit)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellingTheDiscardPromptChangesNothing() = runTest {
        accounts.onLogin = { LoginResult.ConfirmDiscard(null, 2) }
        val vm = LoginViewModel(accounts, session())
        val events = collectEvents(vm)

        vm.fill()
        vm.submit()
        vm.dismissDiscard()

        assertNull(vm.state.value.discardPrompt)
        assertEquals(1, accounts.logins.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun expiredSessionPrefillsTheKnownEmail() {
        val expired = session(StoredSession("", now.minusSeconds(1), TestUser))
        assertEquals(SessionState.Expired, expired.state.value)

        val vm = LoginViewModel(accounts, expired)

        assertEquals(TestUser.email, vm.state.value.email)
        assertTrue(vm.state.value.returning)
        assertFalse(LoginViewModel(accounts, session()).state.value.returning)
    }

    @Test
    fun stateNeverPrintsThePassword() {
        val state = LoginUiState(email = TestUser.email, password = "hunter2")
        assertFalse(state.toString().contains("hunter2"))
    }
}
