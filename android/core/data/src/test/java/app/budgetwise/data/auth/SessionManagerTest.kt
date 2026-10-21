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
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = UserInfo(id = "u1", email = "a@b.co", fullName = "Aye Aye")

    private class FakeStore(var session: StoredSession? = null) : TokenStore {
        var clearCalls = 0
        override suspend fun save(token: String, expiresAt: Instant, user: UserInfo) {
            session = StoredSession(token, expiresAt, user)
        }
        override suspend fun load(): StoredSession? = session
        override suspend fun clear() {
            clearCalls++
            session = null
        }
    }

    private fun manager(expiresIn: Duration?, store: FakeStore = FakeStore()): Pair<SessionManager, FakeStore> {
        if (expiresIn != null) store.session = StoredSession("tok", now.plus(expiresIn), user)
        return SessionManager(store, clock) to store
    }

    @Test
    fun noStoredSessionIsSignedOut() = runTest {
        val (sm, _) = manager(null)
        sm.load()
        assertEquals(SessionState.SignedOut, sm.state.value)
        assertNull(sm.bearer())
    }

    @Test
    fun expiredTokenYieldsExpiredState() = runTest {
        val (sm, _) = manager(Duration.ofSeconds(-1))
        sm.load()
        assertEquals(SessionState.Expired, sm.state.value)
    }

    @Test
    fun bearerNullAfterExpiry() = runTest {
        val ticking = MutableClock(now)
        val sm2 = SessionManager(FakeStore(StoredSession("tok", now.plusSeconds(5), user)), ticking)
        sm2.load()
        assertEquals("tok", sm2.bearer())
        ticking.instant = now.plusSeconds(6)
        assertNull(sm2.bearer())
        assertEquals(SessionState.Expired, sm2.state.value)
    }

    @Test
    fun onUnauthorizedKeepsLocalData() = runTest {
        val (sm, store) = manager(Duration.ofDays(10))
        sm.load()
        sm.onUnauthorized()
        assertEquals(SessionState.Expired, sm.state.value)
        assertNull(sm.bearer())
        assertNull(store.session)
        assertEquals(1, store.clearCalls)
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
        sm.signIn("newtok", now.plus(Duration.ofDays(30)), user)
        assertNotNull(store.session)
        assertEquals("newtok", sm.bearer())
        assertTrue(sm.state.value is SessionState.SignedIn)
    }

    @Test
    fun signOutClearsEverything() = runTest {
        val (sm, store) = manager(Duration.ofDays(10))
        sm.load()
        sm.signOut()
        assertEquals(SessionState.SignedOut, sm.state.value)
        assertNull(sm.bearer())
        assertNull(store.session)
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = instant
    }
}
