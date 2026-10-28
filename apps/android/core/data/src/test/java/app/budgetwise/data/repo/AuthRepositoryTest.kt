package app.budgetwise.data.repo

import androidx.work.WorkInfo
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.net.NetLogging
import app.budgetwise.data.net.createApiService
import app.budgetwise.data.net.createOkHttpClient
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        auth = AuthRepository(h.api, h.sync.session, h.sync.db, h.scheduler)
    }

    @After fun tearDown() { h.close() }

    private val session get() = h.sync.session

    private fun tokenFor(id: String, email: String) = """{"token":"new-token-not-a-secret","expiresAt":"2026-12-01T00:00:00Z",
        "user":{"id":"$id","email":"$email","fullName":"Bo Bo"}}"""

    private fun newTx() = NewTransaction(
        TxType.EXPENSE, 100, "Other", "x", LocalDate.parse("2026-10-21"), Source.MANUAL, null,
    )

    /** u1 signs in online with [password] (saving the offline check), then signs out with nothing queued. */
    private suspend fun knownAndSignedOut(password: String = "pw") {
        h.json(200, tokenFor("u1", "a@b.co"))
        assertTrue(auth.login("a@b.co", password) is LoginResult.Success)
        auth.logout()
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun loginSavesSessionAndCheckAndSchedulesFullSyncAndPeriodic() = runBlocking {
        h.transactions.addAll(listOf(newTx())) // an earlier incremental sync is queued
        assertEquals(1, h.work(SyncScheduler.UNIQUE_WORK).size)
        h.json(200, tokenFor("u1", "a@b.co"))
        val r = auth.login("a@b.co", "pw")
        assertEquals("u1", (r as LoginResult.Success).user.id)
        assertFalse(r.offline)
        assertEquals("new-token-not-a-secret", session.bearer())
        assertTrue(session.state.value is SessionState.SignedIn)
        assertTrue("the offline check is saved", session.accountFor("a@b.co")!!.check!!.matches("pw"))
        // A full pull uses REPLACE, so it takes the queued incremental request's place (an incremental one would
        // have been appended, giving two).
        val sync = h.work(SyncScheduler.UNIQUE_WORK)
        assertEquals(1, sync.size)
        assertEquals(WorkInfo.State.ENQUEUED, sync.single().state)
        assertEquals(1, h.work(SyncScheduler.PERIODIC_WORK).size)
    }

    @Test
    fun login401IsWrongCredentialsAndNeverFallsBackToThePhone() = runBlocking {
        knownAndSignedOut()
        h.json(401, """{"error":{"code":"unauthorized","message":"Invalid credentials"}}""")
        // The password matches the phone's check, but the server said no (changed on the web): the server wins.
        assertEquals(LoginResult.WrongCredentials, auth.login("a@b.co", "pw"))
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun login401LeavesTheCurrentSessionUntouched() = runBlocking {
        val before = session.bearer()
        assertNotNull(before)
        h.json(401, """{"error":{"code":"unauthorized","message":"Invalid credentials"}}""")
        assertEquals(LoginResult.WrongCredentials, auth.login("a@b.co", "bad"))
        assertEquals(before, session.bearer())
        assertTrue(session.state.value is SessionState.SignedIn)
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).isEmpty())
    }

    @Test
    fun aServerErrorIsAFailureNotAnOfflineSignIn() = runBlocking {
        knownAndSignedOut()
        h.json(503, """{"error":{"code":"internal_error","message":"down"}}""")
        val r = auth.login("a@b.co", "pw")
        assertTrue(r is LoginResult.Failure && r.retryable && r.code == "internal_error")
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun staleUnauthorizedAfterFreshSignInIsIgnored() = runBlocking {
        val stale = session.bearer()
        h.json(200, tokenFor("u2", "b@c.co"))
        auth.login("b@c.co", "pw")
        session.onUnauthorized(stale)
        assertEquals("new-token-not-a-secret", session.bearer())
    }

    @Test
    fun switchingAccountsKeepsTheFirstAccountsEntriesAndSessionWithoutAsking() = runBlocking {
        val (id) = h.transactions.addAll(listOf(newTx())) // u1's, not synced yet
        h.json(200, tokenFor("u2", "b@c.co"))

        assertTrue(auth.login("b@c.co", "pw") is LoginResult.Success)

        assertEquals("u2", session.activeUserId.value)
        assertTrue("the screens show u2's entries only", h.transactions.observe().first().isEmpty())
        assertEquals("u1", h.sync.tx.byClientId(id)!!.ownerId)
        assertEquals(listOf(id), h.sync.pendingOps("u1").map { it.clientId })
        assertNotNull("u1 keeps its session to push in the background", session.bearerFor("u1"))
    }

    @Test
    fun sameUserSigningInAgainKeepsTheirRows() = runBlocking {
        h.transactions.addAll(listOf(newTx()))
        session.onUnauthorized()
        h.json(200, tokenFor("u1", "a@b.co"))
        assertTrue(auth.login("a@b.co", "pw") is LoginResult.Success)
        assertEquals(1, h.transactions.observe().first().size)
        assertEquals(1, h.sync.pendingOps().size)
    }

    @Test
    fun theFirstOnlineSignInTakesRowsWithoutAnOwner() = runBlocking {
        val (old) = h.sync.addLocal(1, owner = null) // saved before several accounts, no session at the update
        h.json(200, tokenFor("u2", "b@c.co"))
        auth.login("b@c.co", "pw")
        assertEquals("u2", h.sync.tx.byClientId(old)!!.ownerId)
        assertEquals(listOf(old), h.sync.pendingOps("u2").map { it.clientId })

        h.json(200, tokenFor("u1", "a@b.co"))
        auth.login("a@b.co", "pw")
        assertEquals("only the first one", "u2", h.sync.tx.byClientId(old)!!.ownerId)
    }

    @Test
    fun loadingTheSavedSessionGivesItTheRowsFromBeforeSeveralAccounts() = runBlocking {
        val (old) = h.sync.addLocal(1, owner = null)
        session.onUnauthorized() // an expired session still names its owner
        val restarted = SessionManager(h.sync.tokens, h.sync.clock)

        AuthRepository(h.api, restarted, h.sync.db, h.scheduler).loadSession()

        assertEquals(SessionState.Expired, restarted.state.value)
        assertEquals("u1", h.sync.tx.byClientId(old)!!.ownerId)
        assertEquals(listOf(old), h.sync.pendingOps("u1").map { it.clientId })
    }

    @Test
    fun withoutASavedSessionOldRowsWaitForTheNextOnlineSignIn() = runBlocking {
        val (old) = h.sync.addLocal(1, owner = null)
        val empty = SessionManager(app.budgetwise.data.sync.MemoryTokenStore(), h.sync.clock)

        AuthRepository(h.api, empty, h.sync.db, h.scheduler).loadSession()

        assertNull(h.sync.tx.byClientId(old)!!.ownerId)
    }

    @Test
    fun offlineWithAKnownAccountAndTheRightPasswordSignsInFromThePhone() = runBlocking {
        knownAndSignedOut()
        val (id) = h.sync.addLocal(1)
        h.apiServer.shutdown()

        val r = auth.login("a@b.co", "pw")

        assertEquals(LoginResult.Success(session.knownUser!!, offline = true), r)
        assertEquals(SessionState.SignedInOffline(session.knownUser!!), session.state.value)
        assertEquals(listOf(id), h.transactions.observe().first().map { it.clientId })
        assertEquals(1, session.pendingSignIns().size)
        assertEquals("the exchange runs once there is a connection", 1, h.work(SyncScheduler.UNIQUE_WORK).size)
        assertEquals(1, h.work(SyncScheduler.PERIODIC_WORK).size)
    }

    @Test
    fun offlineWithAKnownAccountMatchesTheEmailInAnyCase() = runBlocking {
        knownAndSignedOut()
        h.apiServer.shutdown()
        assertTrue((auth.login("A@B.CO", "pw") as LoginResult.Success).offline)
    }

    @Test
    fun offlineWithAKnownAccountAndAWrongPasswordIsWrongCredentials() = runBlocking {
        knownAndSignedOut()
        h.apiServer.shutdown()
        assertEquals(LoginResult.WrongCredentials, auth.login("a@b.co", "nope"))
        assertEquals(SessionState.SignedOut, session.state.value)
        assertTrue(session.pendingSignIns().isEmpty())
    }

    @Test
    fun offlineWithAnAccountNeverUsedHereNeedsTheInternet() = runBlocking {
        knownAndSignedOut()
        h.apiServer.shutdown()
        assertEquals(LoginResult.NeedsInternet, auth.login("new@c.co", "pw"))
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun offlineWithAnAccountThatHasNoCheckYetNeedsTheInternet() = runBlocking {
        // The harness signed u1 in without a check, which is how the update from one account leaves the phone.
        auth.logout()
        h.apiServer.shutdown()
        assertEquals(LoginResult.NeedsInternet, auth.login("a@b.co", "pw"))
    }

    @Test
    fun aTimeoutCountsAsOfflineToo() = runBlocking {
        knownAndSignedOut()
        val slow = createOkHttpClient(NetLogging.None).newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        val impatient = AuthRepository(
            createApiService("http://127.0.0.1:${h.apiServer.port}/api/v1/", slow), session, h.sync.db, h.scheduler,
        )
        h.apiServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue((impatient.login("a@b.co", "pw") as LoginResult.Success).offline)
    }

    @Test
    fun logoutWithNothingQueuedKeepsEntriesAndTheCheckButDropsTheToken() = runBlocking {
        h.json(200, tokenFor("u1", "a@b.co"))
        auth.login("a@b.co", "pw")
        h.sync.seedSynced("c1")
        h.scheduler.periodic()

        auth.logout()

        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(session.knownUser)
        assertNull(session.bearerFor("u1"))
        assertEquals(SyncState.SYNCED, h.sync.tx.byClientId("c1")!!.syncState)
        assertTrue(session.accountFor("a@b.co")!!.check!!.matches("pw"))
        assertTrue(
            "background sync keeps running for the accounts on the phone",
            h.work(SyncScheduler.PERIODIC_WORK).none { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun logoutWithQueuedEntriesKeepsThemAndTheSessionAndQueuesTheUpload() = runBlocking {
        val ids = h.transactions.addAll(listOf(newTx(), newTx()))

        auth.logout()

        assertEquals(SessionState.SignedOut, session.state.value)
        assertNotNull(session.bearerFor("u1"))
        assertEquals(ids.toSet(), h.sync.pendingOps("u1").map { it.clientId }.toSet())
        assertTrue(ids.all { h.sync.tx.byClientId(it)!!.syncState == SyncState.PENDING })
        assertTrue(h.work(SyncScheduler.UNIQUE_WORK).any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun logoutWhileASyncIsInFlightLosesNothing() = runBlocking {
        val (id) = h.sync.addLocal(1)
        val inPost = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.sync.server.duringPost = {
            inPost.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val sync = async(Dispatchers.IO) { h.sync.engine.syncOnce(fullPull = true) }
        assertTrue(inPost.await(10, TimeUnit.SECONDS))
        val out = async(Dispatchers.IO) { auth.logout() }
        kotlinx.coroutines.delay(200)
        release.countDown()

        withTimeout(20_000) { sync.await(); out.await() }

        val row = h.sync.tx.byClientId(id)!!
        assertEquals(SyncState.SYNCED, row.syncState)
        assertEquals("u1", row.ownerId)
        assertTrue(h.sync.pendingOps().isEmpty())
        assertFalse(session.state.value is SessionState.SignedIn)
    }
}
