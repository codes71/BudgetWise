package app.budgetwise.ui.common

import app.budgetwise.data.auth.SessionState
import app.budgetwise.testing.TestUser
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionNoticeTest {
    private val now = Instant.parse("2026-10-21T00:00:00Z")

    private fun signedIn(left: Duration, soon: Boolean = true) = SessionState.SignedIn(TestUser, now.plus(left), soon)

    @Test
    fun expiredSessionAsksToSignInAgain() {
        assertEquals(SessionNotice.Expired, sessionNoticeFor(SessionState.Expired, now))
    }

    @Test
    fun healthyOrSignedOutSessionShowsNothing() {
        assertNull(sessionNoticeFor(signedIn(Duration.ofDays(20), soon = false), now))
        assertNull(sessionNoticeFor(SessionState.SignedOut, now))
    }

    @Test
    fun expiringSoonCountsWholeDaysLeftRoundedUp() {
        assertEquals(SessionNotice.ExpiringSoon(3), sessionNoticeFor(signedIn(Duration.ofDays(2).plusHours(1)), now))
        assertEquals(SessionNotice.ExpiringSoon(1), sessionNoticeFor(signedIn(Duration.ofHours(5)), now))
        // expiringSoon is computed at load; a token that ran out since then still says one day, never zero.
        assertEquals(SessionNotice.ExpiringSoon(1), sessionNoticeFor(signedIn(Duration.ofMinutes(-5)), now))
    }
}
