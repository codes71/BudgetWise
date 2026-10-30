package app.budgetwise.data.sync

import app.budgetwise.data.auth.PasswordCheck
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.net.LoginBody
import app.budgetwise.data.sync.SyncHarness.Companion.USER
import app.budgetwise.data.sync.SyncHarness.Companion.USER2
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class MultiAccountSyncTest {
    private lateinit var h: SyncHarness

    @Before
    fun setUp() {
        h = SyncHarness()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun anInactiveAccountPushesWithItsOwnTokenAndIsNotPulled() = runBlocking {
        val first = h.addLocal(2)
        h.signInSecond()
        val second = h.addLocal(1, owner = USER2.id)

        val outcome = h.engine.syncOnce()

        assertEquals("the outcome is the active account's", SyncOutcome.Success(pushed = 1, pulled = 0), outcome)
        assertEquals(listOf(first), h.server.posts) // u1's rows went out with u1's token
        assertEquals(listOf(second), h.server2.posts)
        assertTrue("an inactive account only pushes", h.server.gets.isEmpty())
        assertEquals(1, h.server2.gets.size)
        first.forEach {
            val row = h.tx.byClientId(it)!!
            assertEquals(SyncState.SYNCED, row.syncState)
            assertEquals(USER.id, row.ownerId)
            assertEquals(h.server.rows.getValue(it).id, row.serverId)
        }
        assertTrue(h.pendingOps(USER.id).isEmpty())
        assertTrue(h.pendingOps(USER2.id).isEmpty())
        assertFalse(h.engine.othersWaiting())
    }

    @Test
    fun anExpiredInactiveAccountKeepsItsEntriesUntilItSignsInAgain() = runBlocking {
        val (id) = h.addLocal(1)
        h.signInSecond()
        h.session.onUnauthorized(USER.id, h.server.token) // u1's session ends while u2 is active

        h.engine.syncOnce()

        assertTrue(h.server.posts.isEmpty())
        assertEquals(listOf(id), h.pendingOps(USER.id).map { it.clientId })
        assertFalse("nothing another run could push", h.engine.othersWaiting())

        h.session.signIn(h.server.token, h.now.plus(Duration.ofDays(30)), USER)
        assertEquals(SyncOutcome.Success(pushed = 1, pulled = 0), h.engine.syncOnce())
        assertEquals(listOf(listOf(id)), h.server.posts)
    }

    @Test
    fun signingOutWithQueuedEntriesUploadsThemThenDropsTheSession() = runBlocking {
        val (id) = h.addLocal(1)
        h.session.signOut(keepSession = true)
        assertEquals(SessionState.SignedOut, h.session.state.value)
        assertNotNull(h.session.bearerFor(USER.id))

        assertEquals(SyncOutcome.NeedsLogin, h.engine.syncOnce())

        assertEquals(listOf(listOf(id)), h.server.posts)
        assertEquals(SyncState.SYNCED, h.tx.byClientId(id)!!.syncState)
        assertNull("the token goes once the outbox is empty", h.session.bearerFor(USER.id))
        assertNotNull("the account and its entries stay", h.tokens.saved.byId(USER.id))
    }

    @Test
    fun aServerErrorForAnInactiveAccountLeavesItsEntriesWaiting() = runBlocking {
        val (id) = h.addLocal(1)
        h.signInSecond()
        h.server.htmlStatusOnPost = 503

        assertEquals(SyncOutcome.Success(pushed = 0, pulled = 0), h.engine.syncOnce())

        assertEquals(listOf(id), h.pendingOps(USER.id).map { it.clientId })
        assertEquals(SyncState.PENDING, h.tx.byClientId(id)!!.syncState)
        assertTrue(h.engine.othersWaiting())
    }

    @Test
    fun aPullNeverTakesOverOrRemovesAnotherAccountsRow() = runBlocking {
        h.signInSecond()
        val theirs = h.seedSynced("shared", owner = USER2.id, on = h.server2)
        val onlyTheirs = h.seedSynced("only-u2", owner = USER2.id, on = h.server2)
        h.server.put("shared", category = "Health") // the same clientId under the other account
        h.session.signIn(h.server.token, h.now.plus(Duration.ofDays(30)), USER) // u1 active again

        h.engine.syncOnce(fullPull = true)

        assertEquals(theirs, h.tx.byClientId("shared"))
        assertEquals(onlyTheirs, h.tx.byClientId("only-u2"))
        assertTrue(h.tx.observeAll(USER.id).first().isEmpty())
        assertEquals(setOf("shared", "only-u2"), h.tx.observeAll(USER2.id).first().map { it.clientId }.toSet())
    }

    @Test
    fun eachAccountHasItsOwnPullCursor() = runBlocking {
        h.server.put("c1")
        h.engine.syncOnce()
        assertNotNull(h.cursor.lastPulledAt(USER.id).first())

        h.signInSecond()
        h.engine.syncOnce()

        assertEquals("u2's first pull starts from scratch", listOf<String?>(null), h.server2.gets)
        assertEquals(USER2.id, h.session.activeUserId.value)
        assertEquals(listOf("c1"), h.tx.observeAll(USER.id).first().map { it.clientId })
    }

    /** u1 signed in online once (so a check exists), lost its token, and signed in again offline with [password]. */
    private suspend fun signedInOffline(password: String = "pw") {
        h.session.signIn(h.server.token, h.now.plus(Duration.ofDays(30)), USER, PasswordCheck.create(password))
        h.session.onUnauthorized(USER.id, h.server.token)
        h.session.signInOffline(USER.id, password)
        assertEquals(SessionState.SignedInOffline(USER), h.session.state.value)
    }

    @Test
    fun anOfflineSignInTradesThePasswordForASessionOnTheFirstRun() = runBlocking {
        signedInOffline()
        val (id) = h.addLocal(1)
        h.backend.login = { FakeBackend.tokenFor(h.server.token, USER.id, USER.email) }

        val outcome = h.engine.syncOnce()

        assertEquals(SyncOutcome.Success(pushed = 1, pulled = 0), outcome)
        assertEquals(listOf(LoginBody(USER.email, "pw")), h.backend.logins)
        assertTrue(h.session.state.value is SessionState.SignedIn)
        assertTrue("the password is dropped", h.session.pendingSignIns().isEmpty())
        assertTrue(h.tokens.saved.byId(USER.id)!!.check!!.matches("pw"))
        assertEquals(listOf(listOf(id)), h.server.posts)

        h.engine.syncOnce()
        assertEquals("traded once", 1, h.backend.logins.size)
    }

    @Test
    fun aRefusedPasswordEndsTheOfflineSignInButKeepsTheEntries() = runBlocking {
        signedInOffline()
        val (id) = h.addLocal(1)
        // The default answer is 401: the password was changed on the web.

        assertEquals(SyncOutcome.NeedsLogin, h.engine.syncOnce())

        assertEquals(SessionState.Expired, h.session.state.value)
        assertTrue(h.session.pendingSignIns().isEmpty())
        assertNull("the old password no longer signs in offline", h.tokens.saved.byId(USER.id)!!.check)
        assertEquals(listOf(id), h.pendingOps().map { it.clientId })
        assertTrue(h.server.posts.isEmpty())
    }

    @Test
    fun anUnreachableServerKeepsThePasswordForTheNextRun() = runBlocking {
        signedInOffline()
        h.addLocal(1)
        h.backend.login = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce())

        assertEquals(SessionState.SignedInOffline(USER), h.session.state.value)
        assertEquals(1, h.session.pendingSignIns().size)
        assertTrue("nothing else is tried", h.server.posts.isEmpty() && h.server.gets.isEmpty())
    }

    @Test
    fun aServerErrorOnTheExchangeIsRetriedAndDoesNotAskToSignIn() = runBlocking {
        signedInOffline()
        h.backend.login = { MockResponse().setResponseCode(503) }

        assertEquals(SyncOutcome.RetryLater, h.engine.syncOnce())

        assertEquals(SessionState.SignedInOffline(USER), h.session.state.value)
        assertEquals(1, h.session.pendingSignIns().size)
    }

    @Test
    fun anEmailThatNowSignsInSomeoneElseEndsTheOfflineSignIn() = runBlocking {
        signedInOffline()
        h.backend.login = { FakeBackend.tokenFor(h.server2.token, "someone-else", USER.email) }

        h.engine.syncOnce()

        assertEquals(SessionState.Expired, h.session.state.value)
        assertNull(h.tokens.saved.byId(USER.id)!!.check)
        assertNull(h.tokens.saved.byId("someone-else"))
    }

    @Test
    fun entriesOfAnOfflineSignInStillUploadAfterSignOut() = runBlocking {
        signedInOffline()
        val (id) = h.addLocal(1)
        h.session.signOut(keepSession = true)
        h.backend.login = { FakeBackend.tokenFor(h.server.token, USER.id, USER.email) }

        h.engine.syncOnce()

        assertEquals(listOf(listOf(id)), h.server.posts)
        assertEquals(SessionState.SignedOut, h.session.state.value)
        assertNull("dropped again once drained", h.session.bearerFor(USER.id))
        assertTrue(h.tokens.saved.byId(USER.id)!!.check!!.matches("pw"))
    }
}
