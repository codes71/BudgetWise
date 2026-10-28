package app.budgetwise.ui.common

import app.budgetwise.data.auth.SessionState
import java.time.Duration
import java.time.Instant

/**
 * Why the user should sign in again. Expired is said by the header's Sign in chip; the early warning is a notice on
 * Transactions, once per visit.
 */
sealed interface SessionNotice {
    /** Token gone: local data stays visible, sync is paused until sign-in. */
    data object Expired : SessionNotice

    /** The token ends within three days ([daysLeft] rounded up, at least 1). */
    data class ExpiringSoon(val daysLeft: Int) : SessionNotice
}

fun sessionNoticeFor(session: SessionState, now: Instant): SessionNotice? = when (session) {
    SessionState.Expired -> SessionNotice.Expired
    is SessionState.SignedIn -> if (session.expiringSoon) SessionNotice.ExpiringSoon(daysLeft(session.expiresAt, now)) else null
    // Signed in on the phone: the header's chip says the entries have not synced yet.
    is SessionState.SignedInOffline, SessionState.SignedOut -> null
}

private fun daysLeft(expiresAt: Instant, now: Instant): Int {
    val left = Duration.between(now, expiresAt)
    if (left <= Duration.ZERO) return 1
    val days = left.toDays()
    return (if (left > Duration.ofDays(days)) days + 1 else days).toInt()
}
