package app.budgetwise.ui.settings

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.repo.LogoutResult
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.sync.SyncStatus
import app.budgetwise.testing.FakeAccountActions
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.TestUser
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val accounts = FakeAccountActions()
    private val sync = FakeSyncControls()
    private val settings = FakeSettingsStore()

    private fun session(expiresIn: Duration = Duration.ofDays(20)) =
        SessionManager(FakeTokenStore(StoredSession("tok", now.plus(expiresIn), TestUser)), Clock.fixed(now, ZoneOffset.UTC))
            .also { runBlocking { it.load() } }

    private fun TestScope.vm(session: SessionManager = session()) = SettingsViewModel(accounts, session, sync, settings).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    @Test
    fun signOutWithUnsyncedRowsAsksFirst() = runTest {
        accounts.onLogout = { force -> if (force) LogoutResult.SignedOut else LogoutResult.NeedsConfirmation(3) }
        val vm = vm()

        vm.signOut(force = false)

        assertEquals(listOf(false), accounts.logouts)
        assertEquals(3, vm.state.value.signOutPrompt)
        assertFalse(vm.state.value.signingOut)

        vm.signOut(force = true)

        assertEquals(listOf(false, true), accounts.logouts)
        assertNull(vm.state.value.signOutPrompt)
    }

    @Test
    fun dismissingTheSignOutPromptKeepsTheSession() = runTest {
        accounts.onLogout = { LogoutResult.NeedsConfirmation(1) }
        val vm = vm()

        vm.signOut(force = false)
        vm.dismissSignOut()

        assertNull(vm.state.value.signOutPrompt)
        assertEquals(listOf(false), accounts.logouts)
    }

    @Test
    fun signOutWithEverythingSyncedDoesNotAsk() = runTest {
        val vm = vm()

        vm.signOut(force = false)

        assertEquals(listOf(false), accounts.logouts)
        assertNull(vm.state.value.signOutPrompt)
    }

    @Test
    fun anUnexpectedSignOutExceptionShowsAnErrorAndEndsSigningOut() = runTest {
        // AuthRepository can throw on a Room or Keystore failure instead of returning a result.
        accounts.onLogout = { throw IllegalStateException("database closed") }
        val vm = vm()

        vm.signOut(force = false)

        assertFalse(vm.state.value.signingOut)
        assertTrue(vm.state.value.signOutFailed)
        assertNull(vm.state.value.signOutPrompt)

        // Trying again clears the error and is not blocked by a stuck "signing out".
        accounts.onLogout = { LogoutResult.SignedOut }
        vm.signOut(force = false)

        assertEquals(listOf(false, false), accounts.logouts)
        assertFalse(vm.state.value.signOutFailed)
    }

    @Test
    fun myanmarDigitsSwitchIsSaved() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.myanmarDigits)

        vm.setMyanmarDigits(true)

        assertTrue(settings.myanmarDigits.value)
        assertTrue(vm.state.value.myanmarDigits)
    }

    @Test
    fun showsAccountAndUnsyncedCount() = runTest {
        sync.unsyncedCount.value = 4
        val vm = vm()

        assertEquals(AccountInfo(name = TestUser.fullName, email = TestUser.email), vm.state.value.account)
        assertEquals(4, vm.state.value.unsyncedCount)
        assertFalse(vm.state.value.sessionExpired)

        sync.unsyncedCount.value = 0
        assertEquals(0, vm.state.value.unsyncedCount)
    }

    @Test
    fun expiredSessionKeepsTheAccountAndCannotSync() = runTest {
        val vm = vm(session(expiresIn = Duration.ofSeconds(-1)))

        assertTrue(vm.state.value.sessionExpired)
        assertEquals(TestUser.email, vm.state.value.account?.email)
        assertEquals(SyncPhase.NEEDS_SIGN_IN, vm.state.value.sync.phase)
        assertFalse(vm.state.value.canSyncNow)
    }

    @Test
    fun syncNowRequestsAFullSync() = runTest {
        val vm = vm()
        assertTrue(vm.state.value.canSyncNow)

        vm.syncNow()

        assertEquals(1, sync.syncNowCalls)

        sync.status.value = SyncStatus.Running
        assertEquals(SyncPhase.SYNCING, vm.state.value.sync.phase)
        assertFalse(vm.state.value.canSyncNow)
    }

    @Test
    fun syncSummaryReadsTheLastRun() {
        val at = now.minusSeconds(90)
        fun last(outcome: SyncOutcome) = SyncStatus.LastResult(at, outcome)

        assertEquals(SyncSummary(SyncPhase.NEVER), syncSummary(SyncStatus.Idle, queued = false, expired = false))
        assertEquals(SyncSummary(SyncPhase.WAITING), syncSummary(SyncStatus.Idle, queued = true, expired = false))
        assertEquals(SyncSummary(SyncPhase.SYNCING), syncSummary(SyncStatus.Running, queued = true, expired = false))
        assertEquals(
            SyncSummary(SyncPhase.SYNCED, at),
            syncSummary(last(SyncOutcome.Success(pushed = 1, pulled = 2)), queued = false, expired = false),
        )
        // A later write queues another run; the last good sync time stays visible.
        assertEquals(
            SyncSummary(SyncPhase.WAITING, at),
            syncSummary(last(SyncOutcome.Success(pushed = 1, pulled = 2)), queued = true, expired = false),
        )
        // A failed run is retried with backoff (queued), but the failure is what the user needs to see.
        assertEquals(SyncSummary(SyncPhase.OFFLINE, at), syncSummary(last(SyncOutcome.RetryLater), queued = true, expired = false))
        assertEquals(
            SyncSummary(SyncPhase.REJECTED, at, rejectedCount = 2),
            syncSummary(last(SyncOutcome.PermanentFailure(listOf("a", "b"), 0, 0)), queued = false, expired = false),
        )
        assertEquals(SyncSummary(SyncPhase.NEEDS_SIGN_IN, at), syncSummary(last(SyncOutcome.NeedsLogin), queued = false, expired = false))
        assertEquals(SyncSummary(SyncPhase.NEEDS_SIGN_IN), syncSummary(SyncStatus.Running, queued = false, expired = true))
    }
}
