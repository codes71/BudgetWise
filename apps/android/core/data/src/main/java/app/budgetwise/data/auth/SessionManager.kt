package app.budgetwise.data.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The active account, as the screens see it. */
sealed interface SessionState {
    data object SignedOut : SessionState

    /** [expiringSoon] is true when fewer than [SessionManager.WARN_BEFORE_EXPIRY] remain. */
    data class SignedIn(val user: UserInfo, val expiresAt: Instant, val expiringSoon: Boolean) : SessionState

    /**
     * Signed in on this phone with the offline password check, without a usable token yet. The typed password waits in
     * memory until the app first reaches the server, which trades it for a token ([SessionManager.pendingSignIns]). A
     * restart before that loads as [Expired].
     */
    data class SignedInOffline(val user: UserInfo) : SessionState

    /** Token expired or rejected. Local data and the outbox are kept; sync pauses until re-login. */
    data object Expired : SessionState
}

/** A password typed at an offline sign-in, held in memory only until the server has taken it or refused it. */
class PendingSignIn internal constructor(val user: UserInfo, val password: String) {
    override fun toString() = "PendingSignIn(user=$user, password=<redacted>)"
}

/**
 * Every account on this phone, in memory, and which one is active. Call [load] once at startup; afterwards
 * [bearerFor] is a plain memory read and is safe to call from any thread.
 *
 * Ordinary sign-out keeps accounts, entries and offline password checks. Explicit server-confirmed deletion removes
 * only that account. Each retained account has its own session, so an inactive account can still push its outbox with
 * its own token. Every change is saved to the [TokenStore] before it shows in memory.
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: TokenStore,
    private val clock: Clock,
) {
    @Volatile private var saved: SavedAccounts = SavedAccounts.EMPTY

    /** By user id. Memory only: a restart forgets them, and those accounts load as expired. */
    @Volatile private var pending: Map<String, PendingSignIn> = emptyMap()

    /** Guards every change of [saved] and [pending] together with the flows, so they never disagree. */
    private val lock = Any()
    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _activeUserId = MutableStateFlow<String?>(null)

    /**
     * Owner of what the screens show and of every new entry: the active account, also while its token is expired.
     * Null when signed out.
     */
    val activeUserId: StateFlow<String?> = _activeUserId.asStateFlow()

    /**
     * The active account's user, kept after its token expires (null after sign-out or on a fresh install). Sign-in
     * fills in their email and greets them back.
     */
    val knownUser: UserInfo? get() = saved.active?.user?.takeUnless { it.id in removedIds }

    /** Serializes sign-in, sign-out and 401 handling so a late 401 cannot overwrite a fresh sign-in. */
    private val mutex = Mutex()
    @Volatile private var deletingId: String? = null
    @Volatile private var removedIds: Set<String> = emptySet()

    /** New local entries must not race a confirmed account deletion. Reads remain available until completion. */
    val writableUserId: String? get() = saved.activeId?.takeUnless { it == deletingId || it in removedIds }
    fun wasDeleted(userId: String): Boolean = userId in removedIds

    /** Startup installs confirmed-deletion receipts before loading saved sessions. Never persist a partial load. */
    fun quarantineConfirmedDeletions(owners: Set<String>) = synchronized(lock) {
        removedIds = removedIds + owners
        publish(saved, pending.filterKeys { it !in removedIds })
    }

    suspend fun load() = mutex.withLock {
        publish(store.load(), pending)
    }

    /**
     * Online sign-in: saves the session and the fresh [check], makes the account active and clears its sign-out. Another
     * account on the phone with the same email (one the server has since replaced) loses its check, so offline sign-in
     * with that email can only reach this account.
     */
    suspend fun signIn(token: String, expiresAt: Instant, user: UserInfo, check: PasswordCheck? = null) = mutex.withLock {
        kotlin.check(user.id !in removedIds) { "This account was deleted" }
        val previous = saved.byId(user.id)
        val account = SavedAccount(user, AccessToken(token, expiresAt), check ?: previous?.check, signedOut = false, deletionAccess = previous?.deletionAccess)
        val email = user.email.normalizedEmail()
        val replaced = saved.accounts.filter { it.user.id != user.id && it.check != null && it.user.email.normalizedEmail() == email }
        val next = replaced.fold(saved) { all, a -> all.with(a.copy(check = null)) }
        commit(next.with(account).copy(activeId = user.id), pending - user.id)
    }

    /** The account [email] signed in with on this phone (trimmed, any case), or null when it never did. */
    fun accountFor(email: String): SavedAccount? {
        val wanted = email.normalizedEmail()
        val matching = saved.accounts.filter { it.user.id !in removedIds && it.user.email.normalizedEmail() == wanted }
        return matching.firstOrNull { it.check != null } ?: matching.firstOrNull()
    }

    /**
     * Offline sign-in of the saved account [userId], once the caller has matched [password] against its check. The
     * account becomes active and keeps its token if it still has one; [password] is held in memory for the exchange.
     */
    suspend fun signInOffline(userId: String, password: String) = mutex.withLock {
        check(userId !in removedIds) { "This account was deleted" }
        val account = saved.byId(userId) ?: return@withLock
        commit(
            saved.with(account.copy(signedOut = false)).copy(activeId = userId),
            pending + (userId to PendingSignIn(account.user, password)),
        )
    }

    /** Passwords from offline sign-ins that still wait for the server, active account or not. */
    fun pendingSignIns(): List<PendingSignIn> = pending.values.filter { it.user.id !in removedIds }

    /**
     * The server took [signIn]'s password: saves the fresh session and [check] and forgets the password. The active
     * account does not change. Ignored when a newer sign-in of that account replaced [signIn] meanwhile.
     */
    suspend fun completePendingSignIn(signIn: PendingSignIn, token: String, expiresAt: Instant, user: UserInfo, check: PasswordCheck) =
        mutex.withLock {
            if (signIn.user.id in removedIds) return@withLock
            if (pending[signIn.user.id] !== signIn) return@withLock
            val account = saved.byId(signIn.user.id) ?: return@withLock
            commit(
                saved.with(account.copy(user = user, access = AccessToken(token, expiresAt), check = check)),
                pending - signIn.user.id,
            )
        }

    /**
     * Forgets [signIn]'s password without a session. [forgetCheck] when the server refused it: the saved check matches a
     * password that no longer works, so it must not sign anyone in offline again.
     */
    suspend fun dropPendingSignIn(signIn: PendingSignIn, forgetCheck: Boolean) = mutex.withLock {
        if (pending[signIn.user.id] !== signIn) return@withLock
        val account = saved.byId(signIn.user.id)
        val next = if (forgetCheck && account != null) saved.with(account.copy(check = null)) else saved
        commit(next, pending - signIn.user.id)
    }

    /** The active account's token; null when signed out, expired or not given yet. Never blocks. */
    fun bearer(): String? = saved.activeId?.let(::bearerFor)

    /** [userId]'s token while it is valid, active account or not. Never blocks. */
    fun bearerFor(userId: String): String? {
        if (userId in removedIds) return null
        val access = saved.byId(userId)?.access ?: return null
        if (clock.instant().isBefore(access.expiresAt)) return access.token
        // Ran out: if this is the active account, the screens now show it as expired.
        synchronized(lock) { _state.value = stateOf(saved, pending) }
        return null
    }

    /** Only account-deletion retry may reuse a token after server confirmation but before local cleanup finishes. */
    internal fun deletionAccessFor(userId: String): AccessToken? = saved.byId(userId)?.let { account ->
        account.deletionAccess?.takeIf { clock.instant().isBefore(it.expiresAt) }
            ?: account.access?.takeIf { clock.instant().isBefore(it.expiresAt) }
    }

    /** A 401 on an authenticated call made for the active account; see the other overload. */
    suspend fun onUnauthorized(sentToken: String? = null) {
        saved.activeId?.let { onUnauthorized(it, sentToken) }
    }

    /**
     * For a 401 on an authenticated call only. A 401 from login (wrong password) is not a session expiry. The token of
     * [userId] is dropped, the account and its check are kept, so a restart still loads it as [SessionState.Expired].
     * Nothing changes when the account has no token (no request could have carried one), or when [sentToken] is given
     * and is no longer its token (the user signed in again while the request was in flight: the 401 is stale).
     */
    suspend fun onUnauthorized(userId: String, sentToken: String?) = mutex.withLock {
        val account = saved.byId(userId) ?: return@withLock
        val access = account.access ?: return@withLock
        if (sentToken != null && sentToken != access.token) return@withLock
        commit(saved.with(account.copy(access = null)), pending)
    }

    /**
     * Signs the active account out without deleting anything. [keepSession] when its outbox still holds entries: the
     * token (and a password waiting from an offline sign-in) stay so they upload in the background, and
     * [dropDrainedSessions] drops the token afterwards. The offline password check always stays.
     */
    suspend fun signOut(keepSession: Boolean) = mutex.withLock {
        val account = saved.active
        if (account == null) {
            commit(saved.copy(activeId = null), pending)
            return@withLock
        }
        val out = account.copy(signedOut = true, access = if (keepSession) account.access else null)
        commit(saved.with(out).copy(activeId = null), if (keepSession) pending else pending - account.user.id)
    }

    /**
     * Pin the confirmed account across the online deletion and local cleanup. A login, account switch, logout or
     * stale 401 cannot change its identity halfway through. Failure before remote confirmation changes no saved
     * account. Once confirmed, cleanup and persistence finish even if navigation cancels the caller.
     */
    suspend fun deleteActiveAccount(
        expectedId: String,
        deleteRemote: suspend (UserInfo, suspend (AccessToken) -> Unit) -> Boolean,
        cleanupLocal: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val account = saved.active
        if (account?.user?.id != expectedId) return@withLock false
        deletingId = expectedId
        try {
            val confirmed = deleteRemote(account.user) { token ->
                // Persist before sending DELETE. A server-success/reply-loss must remain retryable even after
                // ordinary sync marks the account unauthorized, or the process dies before saving its 204 receipt.
                commit(saved.with(checkNotNull(saved.byId(expectedId)).copy(deletionAccess = token)), pending)
            }
            if (!confirmed) return@withLock false
            withContext(NonCancellable) {
                removedIds = removedIds + expectedId
                cleanupLocal()
                commit(
                    saved.copy(activeId = null, accounts = saved.accounts.filterNot { it.user.id == expectedId }),
                    pending - expectedId,
                )
            }
            true
        } finally {
            deletingId = null
        }
    }

    /** Startup recovery uses a receipt persisted only after server 204, and never changes another active account. */
    suspend fun finishConfirmedDeletion(owner: String, cleanupLocal: suspend () -> Unit) = mutex.withLock {
        withContext(NonCancellable) {
            removedIds = removedIds + owner
            cleanupLocal()
            commit(
                saved.copy(
                    activeId = saved.activeId.takeUnless { it == owner },
                    accounts = saved.accounts.filterNot { it.user.id == owner },
                ),
                pending - owner,
            )
        }
    }

    /** Drops the token of each signed-out account whose outbox has drained: every one not in [stillQueued]. */
    suspend fun dropDrainedSessions(stillQueued: Set<String>) = mutex.withLock {
        val drained = saved.accounts.filter {
            it.signedOut && it.access != null && it.user.id != saved.activeId && it.user.id !in stillQueued
        }
        if (drained.isEmpty()) return@withLock
        commit(drained.fold(saved) { all, a -> all.with(a.copy(access = null)) }, pending)
    }

    /** Saves first: when the store throws, memory keeps the previous state. */
    private suspend fun commit(next: SavedAccounts, nextPending: Map<String, PendingSignIn>) {
        if (next != saved) store.save(next)
        publish(next, nextPending)
    }

    private fun publish(next: SavedAccounts, nextPending: Map<String, PendingSignIn>) = synchronized(lock) {
        saved = next
        pending = nextPending
        _activeUserId.value = next.activeId?.takeUnless { it in removedIds }
        _state.value = stateOf(next, nextPending)
    }

    private fun stateOf(s: SavedAccounts, p: Map<String, PendingSignIn>): SessionState {
        val account = s.active ?: return SessionState.SignedOut
        if (account.user.id in removedIds) return SessionState.SignedOut
        val access = account.access
        val now = clock.instant()
        if (access != null && now.isBefore(access.expiresAt)) {
            val left = Duration.between(now, access.expiresAt)
            return SessionState.SignedIn(account.user, access.expiresAt, left < WARN_BEFORE_EXPIRY)
        }
        return if (account.user.id in p) SessionState.SignedInOffline(account.user) else SessionState.Expired
    }

    private fun String.normalizedEmail() = trim().lowercase(Locale.ROOT)

    companion object {
        val WARN_BEFORE_EXPIRY: Duration = Duration.ofDays(3)
    }
}
