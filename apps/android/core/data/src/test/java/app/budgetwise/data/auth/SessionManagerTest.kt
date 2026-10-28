package app.budgetwise.data.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = UserInfo(id = "u1", email = "a@b.co", fullName = "Aye Aye")
    private val other = UserInfo(id = "u2", email = "b@c.co", fullName = "Bo Bo")

    /** A check made with few iterations, so the tests stay fast; [PasswordCheckTest] covers the real one. */
    private val check = PasswordCheck(ByteArray(16) { 1 }, ByteArray(32) { 2 }, 1)

    private class FakeStore(var accounts: SavedAccounts = SavedAccounts.EMPTY) : TokenStore {
        var saves = 0
        override suspend fun load(): SavedAccounts = accounts
        override suspend fun save(accounts: SavedAccounts) {
            saves++
            this.accounts = accounts
        }
    }

    private fun manager(expiresIn: Duration?, store: FakeStore = FakeStore()): Pair<SessionManager, FakeStore> {
        if (expiresIn != null) store.accounts = SavedAccounts.of(StoredSession("tok", now.plus(expiresIn), user))
        return SessionManager(store, clock) to store
    }

    @Test
    fun noStoredSessionIsSignedOut() = runTest {
        val (sm, _) = manager(null)
        sm.load()
        assertEquals(SessionState.SignedOut, sm.state.value)
        assertNull(sm.bearer())
        assertNull(sm.activeUserId.value)
        assertNull(sm.knownUser)
    }

    @Test
    fun expiredTokenYieldsExpiredStateAndKeepsTheOwner() = runTest {
        val (sm, _) = manager(Duration.ofSeconds(-1))
        sm.load()
        assertEquals(SessionState.Expired, sm.state.value)
        assertEquals("u1", sm.activeUserId.value)
        assertEquals(user, sm.knownUser)
    }

    @Test
    fun bearerNullAfterExpiry() = runTest {
        val ticking = MutableClock(now)
        val sm2 = SessionManager(FakeStore(SavedAccounts.of(StoredSession("tok", now.plusSeconds(5), user))), ticking)
        sm2.load()
        assertEquals("tok", sm2.bearer())
        ticking.instant = now.plusSeconds(6)
        assertNull(sm2.bearer())
        assertEquals(SessionState.Expired, sm2.state.value)
    }

    @Test
    fun onUnauthorizedKeepsTheAccountAndItsCheck() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        sm.onUnauthorized()
        assertEquals(SessionState.Expired, sm.state.value)
        assertNull(sm.bearer())
        val saved = store.accounts.byId("u1")!!
        assertNull(saved.access)
        assertEquals(check, saved.check)
        assertEquals("u1", store.accounts.activeId)
    }

    @Test
    fun expiredSurvivesRestartAfterOnUnauthorized() = runTest {
        val (sm, store) = manager(Duration.ofDays(10))
        sm.load()
        sm.onUnauthorized()
        val restarted = SessionManager(store, clock)
        restarted.load()
        assertEquals(SessionState.Expired, restarted.state.value)
        assertNull(restarted.bearer())
    }

    @Test
    fun unauthorizedWithoutSentTokenLeavesStoreIntact() = runTest {
        val (sm, store) = manager(Duration.ofDays(10))
        // load() not finished yet: no account in memory, a request went out without a header and got 401.
        sm.onUnauthorized()
        assertEquals("tok", store.accounts.byId("u1")?.access?.token)
        sm.load()
        assertEquals("tok", sm.bearer())
    }

    @Test
    fun aStaleUnauthorizedIsIgnored() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("old", now.plus(Duration.ofDays(10)), user)
        sm.signIn("new", now.plus(Duration.ofDays(10)), user)
        sm.onUnauthorized("u1", "old")
        assertEquals("new", sm.bearer())
    }

    @Test
    fun secretsAreRedactedInToString() {
        assertFalse(StoredSession("secret-token-xyz", now, user).toString().contains("secret-token-xyz"))
        assertFalse(AccessToken("secret-token-xyz", now).toString().contains("secret-token-xyz"))
        assertFalse(PendingSignIn(user, "hunter2").toString().contains("hunter2"))
    }

    @Test
    fun warnsWhenUnderThreeDaysLeft() = runTest {
        val (soon, _) = manager(Duration.ofDays(2))
        soon.load()
        val s = soon.state.value as SessionState.SignedIn
        assertTrue(s.expiringSoon)
        assertEquals(user, s.user)

        val (fine, _) = manager(Duration.ofDays(10))
        fine.load()
        assertFalse((fine.state.value as SessionState.SignedIn).expiringSoon)
    }

    @Test
    fun signInStoresAndExposesBearer() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("newtok", now.plus(Duration.ofDays(30)), user, check)
        assertEquals("newtok", store.accounts.active?.access?.token)
        assertEquals(check, store.accounts.active?.check)
        assertEquals("newtok", sm.bearer())
        assertTrue(sm.state.value is SessionState.SignedIn)
        assertEquals("u1", sm.activeUserId.value)
    }

    @Test
    fun aSecondAccountKeepsTheFirstOnesSessionAcrossARestart() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("tok-1", now.plus(Duration.ofDays(30)), user)
        sm.signIn("tok-2", now.plus(Duration.ofDays(30)), other)

        assertEquals("u2", sm.activeUserId.value)
        assertEquals("tok-2", sm.bearer())
        assertEquals("tok-1", sm.bearerFor("u1"))

        val restarted = SessionManager(store, clock).also { it.load() }
        assertEquals("u2", restarted.activeUserId.value)
        assertEquals("tok-1", restarted.bearerFor("u1"))
    }

    @Test
    fun signOutWithNothingQueuedDropsTheTokenButKeepsTheAccountAndCheck() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)

        sm.signOut(keepSession = false)

        assertEquals(SessionState.SignedOut, sm.state.value)
        assertNull(sm.activeUserId.value)
        assertNull(sm.knownUser)
        assertNull(sm.bearerFor("u1"))
        val saved = store.accounts.byId("u1")!!
        assertTrue(saved.signedOut)
        assertEquals(check, saved.check)
        assertSame(saved, sm.accountFor("a@b.co"))
    }

    @Test
    fun signOutWithQueuedEntriesKeepsTheTokenUntilTheOutboxDrains() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user)

        sm.signOut(keepSession = true)
        assertEquals(SessionState.SignedOut, sm.state.value)
        assertEquals("tok", sm.bearerFor("u1"))

        sm.dropDrainedSessions(stillQueued = setOf("u1"))
        assertEquals("tok", sm.bearerFor("u1"))
        sm.dropDrainedSessions(stillQueued = emptySet())
        assertNull(sm.bearerFor("u1"))
    }

    @Test
    fun drainingNeverDropsTheActiveOrASignedInAccount() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok-1", now.plus(Duration.ofDays(10)), user)
        sm.signIn("tok-2", now.plus(Duration.ofDays(10)), other) // u1 switched away, not signed out

        sm.dropDrainedSessions(stillQueued = emptySet())

        assertEquals("tok-1", sm.bearerFor("u1"))
        assertEquals("tok-2", sm.bearerFor("u2"))
    }

    @Test
    fun signingInAgainClearsTheSignOut() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user)
        sm.signOut(keepSession = true)
        sm.signIn("tok2", now.plus(Duration.ofDays(10)), user)
        assertFalse(store.accounts.byId("u1")!!.signedOut)
        sm.dropDrainedSessions(emptySet())
        assertEquals("tok2", sm.bearer())
    }

    @Test
    fun accountForMatchesTheEmailInAnyCaseAndSpacing() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user.copy(email = "Aye@Example.com"), check)
        assertEquals("u1", sm.accountFor("  aye@example.COM ")?.user?.id)
        assertNull(sm.accountFor("someone@example.com"))
    }

    @Test
    fun anEmailTakenOverByANewAccountIdOnlySignsThatOneInOffline() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        val recreated = user.copy(id = "u9") // the same email, a new account on the server
        val fresh = PasswordCheck(ByteArray(16) { 5 }, ByteArray(32) { 6 }, 1)

        sm.signIn("tok9", now.plus(Duration.ofDays(10)), recreated, fresh)

        assertEquals("u9", sm.accountFor("a@b.co")?.user?.id)
        assertEquals(fresh, sm.accountFor("a@b.co")?.check)
        assertEquals("the old account and its entries stay", "tok", sm.bearerFor("u1"))
    }

    @Test
    fun offlineSignInHoldsThePasswordInMemoryOnly() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        sm.onUnauthorized()
        sm.signOut(keepSession = false)

        sm.signInOffline("u1", "pw")

        assertEquals(SessionState.SignedInOffline(user), sm.state.value)
        assertEquals("u1", sm.activeUserId.value)
        assertNull(sm.bearer())
        assertEquals(listOf("pw"), sm.pendingSignIns().map { it.password })
        assertFalse(store.accounts.byId("u1")!!.signedOut)

        val restarted = SessionManager(store, clock).also { it.load() }
        assertEquals("a restart before the exchange asks to sign in again", SessionState.Expired, restarted.state.value)
        assertTrue(restarted.pendingSignIns().isEmpty())
    }

    @Test
    fun offlineSignInOfAnAccountWithAValidTokenIsSignedIn() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok-1", now.plus(Duration.ofDays(10)), user, check)
        sm.signIn("tok-2", now.plus(Duration.ofDays(10)), other)

        sm.signInOffline("u1", "pw")

        assertTrue(sm.state.value is SessionState.SignedIn)
        assertEquals("tok-1", sm.bearer())
        assertEquals(1, sm.pendingSignIns().size)
    }

    @Test
    fun theExchangeSavesTheSessionAndDropsThePassword() = runTest {
        val (sm, store) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        sm.onUnauthorized()
        sm.signInOffline("u1", "pw")
        val fresh = PasswordCheck(ByteArray(16) { 3 }, ByteArray(32) { 4 }, 1)

        sm.completePendingSignIn(sm.pendingSignIns().single(), "new", now.plus(Duration.ofDays(30)), user, fresh)

        assertEquals("new", sm.bearer())
        assertTrue(sm.state.value is SessionState.SignedIn)
        assertTrue(sm.pendingSignIns().isEmpty())
        assertEquals(fresh, store.accounts.byId("u1")!!.check)
    }

    @Test
    fun aReplacedPendingSignInIsNotCompletedOrDropped() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        sm.onUnauthorized()
        sm.signInOffline("u1", "first")
        val stale = sm.pendingSignIns().single()
        sm.signInOffline("u1", "second")

        sm.completePendingSignIn(stale, "new", now.plus(Duration.ofDays(30)), user, check)
        sm.dropPendingSignIn(stale, forgetCheck = true)

        assertNull(sm.bearer())
        assertEquals(listOf("second"), sm.pendingSignIns().map { it.password })
        assertEquals(check, sm.accountFor("a@b.co")?.check)
    }

    @Test
    fun aRefusedPendingSignInForgetsItsCheck() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        sm.onUnauthorized()
        sm.signInOffline("u1", "pw")

        sm.dropPendingSignIn(sm.pendingSignIns().single(), forgetCheck = true)

        assertEquals(SessionState.Expired, sm.state.value)
        assertNull(sm.accountFor("a@b.co")?.check)
        assertNotNull(sm.accountFor("a@b.co"))
    }

    @Test
    fun anOnlineSignInDropsAPendingPassword() = runTest {
        val (sm, _) = manager(null)
        sm.signIn("tok", now.plus(Duration.ofDays(10)), user, check)
        sm.onUnauthorized()
        sm.signInOffline("u1", "pw")
        sm.signIn("tok2", now.plus(Duration.ofDays(10)), user)
        assertTrue(sm.pendingSignIns().isEmpty())
        assertEquals("the check of the earlier sign-in stays", check, sm.accountFor("a@b.co")?.check)
    }

    @Test
    fun theOldSingleSessionFormatLoadsAsOneActiveAccount() {
        val expired = SavedAccounts.of(StoredSession("", now, user))
        assertEquals("u1", expired.activeId)
        assertNull("the old expired marker means no token", expired.active!!.access)
        assertEquals("tok", SavedAccounts.of(StoredSession("tok", now, user)).active!!.access!!.token)
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = instant
    }
}
