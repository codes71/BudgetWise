package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.sync.SyncScheduler
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private lateinit var h: RepoHarness
    private lateinit var auth: AuthRepository

    @Before
    fun setUp() {
        h = RepoHarness()
        auth = AuthRepository(h.api, h.sync.session, h.sync.db, h.settings, h.scheduler)
    }

    @After fun tearDown() { h.close() }

    private val tokenBody = """{"token":"new-token-not-a-secret","expiresAt":"2026-12-01T00:00:00Z",
        "user":{"id":"u2","email":"b@c.co","fullName":"Bo Bo"}}"""

    @Test
    fun loginSavesSessionAndSchedulesFullSyncAndPeriodic() = runBlocking {
        h.json(200, tokenBody)
        val r = auth.login("b@c.co", "pw")
        assertEquals("u2", (r as ApiResult.Ok).v.id)
        assertEquals("new-token-not-a-secret", h.sync.session.bearer())
        assertTrue(h.sync.session.state.value is SessionState.SignedIn)
        assertEquals(1, h.work(SyncScheduler.UNIQUE_WORK).size)
        assertEquals(1, h.work(SyncScheduler.PERIODIC_WORK).size)
    }

    @Test
    fun login401ReturnsWrongCredentialsAndLeavesSessionUntouched() = runBlocking {
        val before = h.sync.session.bearer()
        assertNotNull(before)
        h.json(401, """{"error":{"code":"unauthorized","message":"Invalid credentials"}}""")
        val r = auth.login("a@b.co", "bad")
        assertTrue(r is ApiResult.Unauthorized)
        assertEquals(before, h.sync.session.bearer())
        assertTrue(h.sync.session.state.value is SessionState.SignedIn)
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).isEmpty())
    }

    @Test
    fun loginNetworkFailureIsRetryableFailure() = runBlocking {
        h.apiServer.shutdown()
        val r = auth.login("a@b.co", "pw")
        assertTrue(r is ApiResult.Failure && r.retryable)
    }

    @Test
    fun staleUnauthorizedAfterFreshSignInIsIgnored() = runBlocking {
        val stale = h.sync.session.bearer()
        h.json(200, tokenBody)
        auth.login("b@c.co", "pw")
        h.sync.session.onUnauthorized(stale)
        assertEquals("new-token-not-a-secret", h.sync.session.bearer())
    }

    @Test
    fun logoutWithoutForceAsksWhenRowsAreUnsynced() = runBlocking {
        h.transactions.addAll(listOf(newTx()))
        val r = auth.logout(force = false)
        assertEquals(LogoutResult.NeedsConfirmation(1), r)
        assertNotNull(h.sync.session.bearer())
        assertEquals(1, h.transactions.observe().first().size)
    }

    @Test
    fun logoutWithoutForceSignsOutWhenEverythingIsSynced() = runBlocking {
        h.sync.seedSynced("c1")
        assertEquals(LogoutResult.SignedOut, auth.logout(force = false))
        assertEquals(SessionState.SignedOut, h.sync.session.state.value)
        assertEquals(0, h.transactions.observe().first().size)
    }

    @Test
    fun logoutForceEmptiesDatabaseCursorAndCancelsWork() = runBlocking {
        h.transactions.addAll(listOf(newTx(), newTx()))
        h.sync.seedSynced("c-synced")
        h.sync.db.budgetDao().replaceAll(listOf(app.budgetwise.data.db.BudgetEntity("Other", 5000)))
        h.settings.setLastPulledAt(Instant.parse("2026-10-21T05:00:00Z"))
        h.scheduler.periodic()

        assertEquals(LogoutResult.SignedOut, auth.logout(force = true))

        assertEquals(SessionState.SignedOut, h.sync.session.state.value)
        assertNull(h.sync.session.bearer())
        assertEquals(0, h.sync.db.transactionDao().observeAll().first().size)
        assertEquals(0, h.sync.db.transactionDao().unsyncedCount())
        assertEquals(0, h.sync.pendingOps().size)
        assertEquals(0, h.sync.db.budgetDao().observeAll().first().size)
        assertNull(h.settings.lastPulledAt.first())
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).all { it.state.isFinished })
        assertTrue(h.work(SyncScheduler.PERIODIC_WORK).all { it.state.isFinished })
    }

    private fun newTx() = NewTransaction(
        app.budgetwise.nlu.TxType.EXPENSE, 100, "Other", "x", java.time.LocalDate.parse("2026-10-21"),
        app.budgetwise.data.model.Source.MANUAL, null,
    )
}
