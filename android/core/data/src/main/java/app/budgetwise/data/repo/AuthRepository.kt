package app.budgetwise.data.repo

import app.budgetwise.data.auth.PasswordCheck
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.auth.toGrant
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.claimUnowned
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.LoginBody
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.net.safeApi
import app.budgetwise.data.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface LoginResult {
    /**
     * Signed in, as [user]. [offline] when the server could not be reached and the password matched the offline check
     * this phone saved at an earlier online sign-in: the entries on the phone show and sync once it is back online.
     */
    data class Success(val user: UserInfo, val offline: Boolean = false) : LoginResult

    /** The server answered 401, or offline the password does not match. Nothing changed. */
    data object WrongCredentials : LoginResult

    /** The server could not be reached and this account never signed in on this phone: it needs the internet once. */
    data object NeedsInternet : LoginResult

    data class Failure(val code: String, val message: String, val retryable: Boolean) : LoginResult
}

/**
 * Signing in and out. Several accounts can live on the phone: each keeps its own entries, session and offline password
 * check, so neither switching accounts nor signing out ever deletes an entry or asks first.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
    private val db: BudgetWiseDb,
    private val syncScheduler: SyncScheduler,
) {
    /** Serializes login with logout; [SessionManager] serializes the session writes themselves. */
    private val mutex = Mutex()

    /**
     * Loads the saved accounts at process start, then gives the rows saved before several accounts (no owner yet) to
     * the account in the saved session, also when its token has expired. Without a saved session they wait for the
     * next account that signs in online ([login]).
     */
    suspend fun loadSession() {
        session.load()
        session.knownUser?.let { db.claimUnowned(it.id) }
    }

    /**
     * Signs in online: saves the session and a fresh offline password check, gives rows without an owner to this account
     * and schedules a full pull plus the periodic sync. A 401 is [LoginResult.WrongCredentials] and never falls back to
     * the phone. Only a server that cannot be reached (no connection, a timeout) signs in offline, for an account used
     * on this phone before ([signInOffline]).
     */
    suspend fun login(email: String, password: String): LoginResult {
        val token = when (val r = safeApi { api.login(LoginBody(email, password)) }) {
            is ApiResult.Ok -> r.v
            is ApiResult.Unauthorized -> return LoginResult.WrongCredentials
            is ApiResult.Failure ->
                return if (r.code == NETWORK_ERROR_CODE) signInOffline(email, password) else LoginResult.Failure(r.code, r.msg, r.retryable)
        }
        val grant = token.toGrant() ?: return LoginResult.Failure("bad_response", "Unexpected server response", retryable = false)
        val check = withContext(Dispatchers.Default) { PasswordCheck.create(password) }
        return mutex.withLock {
            // Rows saved before several accounts, when no session was saved: the first account online takes them.
            db.claimUnowned(grant.user.id)
            session.signIn(grant.token, grant.expiresAt, grant.user, check)
            syncScheduler.enqueue(fullPull = true)
            syncScheduler.periodic()
            LoginResult.Success(grant.user)
        }
    }

    /**
     * The phone half of sign-in. The account's entries show at once and new ones save; the typed password stays in
     * memory until the first sync reaches the server and trades it for a session ([SessionManager.signInOffline]).
     */
    private suspend fun signInOffline(email: String, password: String): LoginResult {
        val account = session.accountFor(email)
        val check = account?.check ?: return LoginResult.NeedsInternet
        if (!withContext(Dispatchers.Default) { check.matches(password) }) return LoginResult.WrongCredentials
        return mutex.withLock {
            session.signInOffline(account.user.id, password)
            // Runs once there is a connection: the exchange, then the usual push and full pull.
            syncScheduler.enqueue(fullPull = true)
            syncScheduler.periodic()
            LoginResult.Success(account.user, offline = true)
        }
    }

    /**
     * Signs out without asking and without deleting anything. Entries that have not uploaded yet keep the account's
     * session so they upload in the background; the session is dropped once they have. The offline password check stays.
     */
    suspend fun logout() = mutex.withLock {
        val owner = session.activeUserId.value
        val waiting = owner != null && db.outboxDao().countFor(owner) > 0
        session.signOut(keepSession = waiting)
        if (waiting) syncScheduler.enqueue(fullPull = false)
    }
}
