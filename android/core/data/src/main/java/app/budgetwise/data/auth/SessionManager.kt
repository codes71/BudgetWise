package app.budgetwise.data.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object SignedOut : SessionState

    /** [expiringSoon] is true when fewer than [SessionManager.WARN_BEFORE_EXPIRY] remain. */
    data class SignedIn(val user: UserInfo, val expiresAt: Instant, val expiringSoon: Boolean) : SessionState

    /** Token expired or rejected. Local data and the outbox are kept; sync pauses until re-login. */
    data object Expired : SessionState
}

/**
 * In-memory session. Call [load] once at startup; afterwards [bearer] is a plain memory read and is
 * safe to call from an OkHttp interceptor thread.
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: TokenStore,
    private val clock: Clock,
) {
    @Volatile private var session: StoredSession? = null
    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    suspend fun load() {
        val stored = store.load()
        if (stored == null) {
            session = null
            _state.value = SessionState.SignedOut
        } else {
            apply(stored)
        }
    }

    suspend fun signIn(token: String, expiresAt: Instant, user: UserInfo) {
        store.save(token, expiresAt, user)
        apply(StoredSession(token, expiresAt, user))
    }

    /** Null when signed out or expired. Never blocks. */
    fun bearer(): String? {
        val s = session ?: return null
        if (!clock.instant().isBefore(s.expiresAt)) {
            session = null
            _state.value = SessionState.Expired
            return null
        }
        return s.token
    }

    /**
     * For a 401 on an authenticated call only. A 401 from login (wrong password) is not a session expiry.
     * If no token was sent (session null: already expired, or [load] not finished) the stored session is
     * left untouched. Otherwise the token is dropped but the user identity is kept with an already-expired
     * marker (empty token), so a restart still loads as [SessionState.Expired].
     */
    suspend fun onUnauthorized() {
        val sent = session ?: return
        session = null
        _state.value = SessionState.Expired
        store.save("", clock.instant(), sent.user)
    }

    suspend fun signOut() {
        session = null
        _state.value = SessionState.SignedOut
        store.clear()
    }

    private fun apply(s: StoredSession) {
        val left = Duration.between(clock.instant(), s.expiresAt)
        if (left.isNegative || left.isZero) {
            session = null
            _state.value = SessionState.Expired
        } else {
            session = s
            _state.value = SessionState.SignedIn(s.user, s.expiresAt, left < WARN_BEFORE_EXPIRY)
        }
    }

    companion object {
        val WARN_BEFORE_EXPIRY: Duration = Duration.ofDays(3)
    }
}
