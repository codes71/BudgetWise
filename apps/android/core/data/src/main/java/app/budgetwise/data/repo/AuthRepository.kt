package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.LoginBody
import app.budgetwise.data.net.safeApi
import app.budgetwise.data.sync.SyncEngine
import app.budgetwise.data.sync.SyncScheduler
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LoginResult {
    data class Success(val user: UserInfo) : LoginResult

    /** The server answered 401: the email or password is wrong. The session is untouched. */
    data object WrongCredentials : LoginResult

    /**
     * The device holds unsynced rows that belong to another user (or to an unknown owner). Nothing was changed.
     * Ask the user, then call `login(..., discardOtherUsersData = true)`.
     */
    data class ConfirmDiscard(val previousEmail: String?, val unsyncedCount: Int) : LoginResult

    data class Failure(val code: String, val message: String, val retryable: Boolean) : LoginResult
}

sealed interface LogoutResult {
    data object SignedOut : LogoutResult

    /** Unsynced rows exist and `force` was false. The UI asks, then calls `logout(force = true)`. */
    data class NeedsConfirmation(val unsyncedCount: Int) : LogoutResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
    private val db: BudgetWiseDb,
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val categories: CategoryRepository,
) {
    /** Serializes login with logout; [SessionManager] serializes the session writes themselves. */
    private val mutex = Mutex()

    /**
     * Signs in. When the new user is not the user whose data is on the device (the stored identity survives
     * token expiry), that data is wiped before sign-in so it is never pushed to the new account. If it holds
     * unsynced rows the wipe needs consent: without [discardOtherUsersData] the result is
     * [LoginResult.ConfirmDiscard] and nothing changes. The same user signing in again keeps every row.
     */
    suspend fun login(email: String, password: String, discardOtherUsersData: Boolean = false): LoginResult {
        val token = when (val r = safeApi { api.login(LoginBody(email, password)) }) {
            is ApiResult.Ok -> r.v
            is ApiResult.Unauthorized -> return LoginResult.WrongCredentials
            is ApiResult.Failure -> return LoginResult.Failure(r.code, r.msg, r.retryable)
        }
        val expiresAt = try {
            Instant.parse(token.expiresAt)
        } catch (_: DateTimeParseException) {
            return LoginResult.Failure("bad_response", "Unexpected server response", retryable = false)
        }
        val user = UserInfo(token.user.id, token.user.email, token.user.fullName.orEmpty())
        return mutex.withLock {
            val previous = session.knownUser
            if (previous?.id != user.id) {
                val unsynced = db.transactionDao().unsyncedCount()
                if (unsynced > 0 && !discardOtherUsersData) {
                    return@withLock LoginResult.ConfirmDiscard(previous?.email, unsynced)
                }
                // Also the case of an unknown owner (previous == null): wiping an empty database is harmless.
                wipe()
            }
            session.signIn(token.token, expiresAt, user)
            syncScheduler.enqueue(fullPull = true)
            syncScheduler.periodic()
            LoginResult.Success(user)
        }
    }

    /**
     * With unsynced rows and `force = false`, changes nothing and returns [LogoutResult.NeedsConfirmation].
     * Otherwise empties the local data first (so a crash never leaves rows without an owner), then signs out.
     */
    suspend fun logout(force: Boolean): LogoutResult = mutex.withLock {
        if (!force) {
            val unsynced = db.transactionDao().unsyncedCount()
            if (unsynced > 0) return@withLock LogoutResult.NeedsConfirmation(unsynced)
        }
        wipe()
        session.signOut()
        // A sync that was queued behind the first wipe could have pulled the old user's rows back in before the
        // sign-out took effect; now that there is no token none can, so clear once more.
        syncEngine.wipeLocal()
        LogoutResult.SignedOut
    }

    /** Cancels scheduled sync, then wipes local data under the sync engine's lock, plus user-specific caches. */
    private suspend fun wipe() {
        syncScheduler.cancelAll()
        syncEngine.wipeLocal()
        categories.clear()
    }
}
