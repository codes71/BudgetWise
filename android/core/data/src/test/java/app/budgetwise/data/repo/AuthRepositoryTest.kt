package app.budgetwise.data.repo

import androidx.work.WorkInfo
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.model.Source
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private lateinit var categories: CategoryRepository
    private lateinit var auth: AuthRepository

    @Before
    fun setUp() {
        h = RepoHarness()
        categories = CategoryRepository(h.dataStore, h.api, h.sync.session)
        auth = AuthRepository(h.api, h.sync.session, h.sync.db, h.sync.engine, h.scheduler, categories)
    }

    @After fun tearDown() { h.close() }

    /** User u2 (the harness session is user u1). */
    private val tokenBody = tokenFor("u2", "b@c.co")

    private fun tokenFor(id: String, email: String) = """{"token":"new-token-not-a-secret","expiresAt":"2026-12-01T00:00:00Z",
        "user":{"id":"$id","email":"$email","fullName":"Bo Bo"}}"""

    private fun newTx() = NewTransaction(
        TxType.EXPENSE, 100, "Other", "x", LocalDate.parse("2026-10-21"), Source.MANUAL, null,
    )

    @Test
    fun loginSavesSessionAndSchedulesFullSyncAndPeriodic() = runBlocking {
        h.transactions.addAll(listOf(newTx())) // an earlier incremental sync is queued
        assertEquals(1, h.work(SyncScheduler.UNIQUE_WORK).size)
        h.json(200, tokenFor("u1", "a@b.co"))
        val r = auth.login("a@b.co", "pw")
        assertEquals("u1", (r as LoginResult.Success).user.id)
        assertEquals("new-token-not-a-secret", h.sync.session.bearer())
        assertTrue(h.sync.session.state.value is SessionState.SignedIn)
        // A full pull uses REPLACE, so it takes the queued incremental request's place (an incremental one would
        // have been appended, giving two).
        val sync = h.work(SyncScheduler.UNIQUE_WORK)
        assertEquals(1, sync.size)
        assertEquals(WorkInfo.State.ENQUEUED, sync.single().state)
        assertEquals(1, h.work(SyncScheduler.PERIODIC_WORK).size)
    }

    @Test
    fun login401ReturnsWrongCredentialsAndLeavesSessionUntouched() = runBlocking {
        val before = h.sync.session.bearer()
        assertNotNull(before)
        h.json(401, """{"error":{"code":"unauthorized","message":"Invalid credentials"}}""")
        assertEquals(LoginResult.WrongCredentials, auth.login("a@b.co", "bad"))
        assertEquals(before, h.sync.session.bearer())
        assertTrue(h.sync.session.state.value is SessionState.SignedIn)
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).isEmpty())
    }

    @Test
    fun loginNetworkFailureIsRetryableFailure() = runBlocking {
        h.apiServer.shutdown()
        val r = auth.login("a@b.co", "pw")
        assertTrue(r is LoginResult.Failure && r.retryable)
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
    fun differentUserOverExpiredSessionWithUnsyncedRowsAsksAndChangesNothing() = runBlocking {
        h.transactions.addAll(listOf(newTx()))
        h.sync.session.onUnauthorized() // user u1 is now Expired, the identity is kept
        h.json(200, tokenBody)

        val r = auth.login("b@c.co", "pw")

        assertEquals(LoginResult.ConfirmDiscard("a@b.co", 1), r)
        assertEquals(SessionState.Expired, h.sync.session.state.value)
        assertEquals(1, h.transactions.observe().first().size)
        assertEquals(1, h.sync.pendingOps().size)
        assertTrue("nothing reached the data API", h.sync.server.posts.isEmpty())
        assertEquals("only the login request hit the auth server", 1, h.apiServer.requestCount)
    }

    @Test
    fun confirmingDiscardWipesTheOldUsersRowsAndSignsInTheNewUser() = runBlocking {
        h.transactions.addAll(listOf(newTx()))
        h.sync.session.onUnauthorized()
        h.sync.cursor.setLastPulledAt(Instant.parse("2026-10-21T05:00:00Z"))
        h.json(200, tokenBody)

        val r = auth.login("b@c.co", "pw", discardOtherUsersData = true)

        assertTrue(r is LoginResult.Success)
        assertEquals(0, h.transactions.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertNull(h.sync.cursor.lastPulledAt.first())
        assertEquals("u2", (h.sync.session.state.value as SessionState.SignedIn).user.id)
    }

    @Test
    fun differentUserWithNothingUnsyncedWipesSilently() = runBlocking {
        h.sync.seedSynced("c-old")
        h.sync.session.onUnauthorized()
        h.json(200, tokenBody)
        assertTrue(auth.login("b@c.co", "pw") is LoginResult.Success)
        assertEquals(0, h.transactions.observe().first().size)
    }

    @Test
    fun sameUserSigningInAgainKeepsTheirRows() = runBlocking {
        h.transactions.addAll(listOf(newTx()))
        h.sync.session.onUnauthorized()
        h.json(200, tokenFor("u1", "a@b.co"))
        assertTrue(auth.login("a@b.co", "pw") is LoginResult.Success)
        assertEquals(1, h.transactions.observe().first().size)
        assertEquals(1, h.sync.pendingOps().size)
    }

    @Test
    fun logoutWithoutForceAsksWhenRowsAreUnsynced() = runBlocking {
        h.transactions.addAll(listOf(newTx()))
        assertEquals(LogoutResult.NeedsConfirmation(1), auth.logout(force = false))
        assertNotNull(h.sync.session.bearer())
        assertEquals(1, h.transactions.observe().first().size)
    }

    @Test
    fun logoutWithoutForceSignsOutWhenEverythingIsSynced() = runBlocking {
        h.sync.seedSynced("c1")
        assertEquals(LogoutResult.SignedOut, auth.logout(force = false))
        assertEquals(SessionState.SignedOut, h.sync.session.state.value)
        assertNull(h.sync.session.knownUser)
        assertEquals(0, h.transactions.observe().first().size)
    }

    @Test
    fun logoutForceEmptiesDatabaseCursorCategoriesAndCancelsWork() = runBlocking {
        h.transactions.addAll(listOf(newTx(), newTx())) // queues sync work
        h.sync.seedSynced("c-synced")
        h.sync.db.budgetDao().replaceAll(listOf(BudgetEntity("Other", 5000)))
        h.sync.cursor.setLastPulledAt(Instant.parse("2026-10-21T05:00:00Z"))
        h.json(200, """{"categories":["Rent"]}""")
        categories.refresh()
        h.scheduler.periodic()
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).isNotEmpty())
        assertTrue(h.work(SyncScheduler.PERIODIC_WORK).isNotEmpty())

        assertEquals(LogoutResult.SignedOut, auth.logout(force = true))

        assertEquals(SessionState.SignedOut, h.sync.session.state.value)
        assertNull(h.sync.session.bearer())
        assertEquals(0, h.sync.db.transactionDao().observeAll().first().size)
        assertEquals(0, h.sync.db.transactionDao().unsyncedCount())
        assertEquals(0, h.sync.pendingOps().size)
        assertEquals(0, h.sync.db.budgetDao().observeAll().first().size)
        assertNull(h.sync.cursor.lastPulledAt.first())
        assertEquals(CategoryRepository.BUILT_IN, categories.categories.first())
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(h.work(SyncScheduler.PERIODIC_WORK).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun wipeRequestedWhileSyncIsInFlightLeavesNothingBehind() = runBlocking {
        h.sync.addLocal(1)
        val inPost = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.sync.server.duringPost = {
            inPost.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val sync = async(Dispatchers.IO) { h.sync.engine.syncOnce(fullPull = true) }
        assertTrue(inPost.await(10, TimeUnit.SECONDS))
        val wipe = async(Dispatchers.IO) { auth.logout(force = true) }
        kotlinx.coroutines.delay(500) // let the wipe reach the engine while the POST is still unanswered
        release.countDown() // the server answers: the ack would normally land first, then the wipe runs

        withTimeout(20_000) { sync.await(); wipe.await() }

        assertEquals(0, h.sync.db.transactionDao().observeAll().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertNull(h.sync.cursor.lastPulledAt.first())
        assertTrue("no server DELETE survives the wipe", h.sync.server.deletes.isEmpty())
    }
}
