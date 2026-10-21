package app.budgetwise.data.repo

import androidx.room.withTransaction
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.LoginBody
import app.budgetwise.data.net.safeApi
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.sync.SyncScheduler
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val settings: SettingsStore,
    private val syncScheduler: SyncScheduler,
) {
    /** Serializes the commit step of login with logout; [SessionManager] serializes the session writes themselves. */
    private val mutex = Mutex()

    /**
     * [ApiResult.Unauthorized] means the email or password is wrong: the session is not touched (no
     * `onUnauthorized`). On success the session is saved and a full sync plus the periodic sync are scheduled.
     */
    suspend fun login(email: String, password: String): ApiResult<UserInfo> {
        val token = when (val r = safeApi { api.login(LoginBody(email, password)) }) {
            is ApiResult.Ok -> r.v
            is ApiResult.Unauthorized -> return r
            is ApiResult.Failure -> return r
        }
        val expiresAt = try {
            Instant.parse(token.expiresAt)
        } catch (_: DateTimeParseException) {
            return ApiResult.Failure("bad_response", "Unexpected server response", retryable = false)
        }
        val user = UserInfo(token.user.id, token.user.email, token.user.fullName.orEmpty())
        mutex.withLock {
            session.signIn(token.token, expiresAt, user)
            syncScheduler.enqueue(fullPull = true)
            syncScheduler.periodic()
        }
        return ApiResult.Ok(user)
    }

    /**
     * With unsynced rows and `force = false`, changes nothing and returns [LogoutResult.NeedsConfirmation].
     * Otherwise cancels sync work, signs out, and empties transactions, outbox, budgets and the pull cursor so the
     * next user never pushes or sees these rows.
     */
    suspend fun logout(force: Boolean): LogoutResult = mutex.withLock {
        if (!force) {
            val unsynced = db.transactionDao().unsyncedCount()
            if (unsynced > 0) return@withLock LogoutResult.NeedsConfirmation(unsynced)
        }
        syncScheduler.cancelAll()
        session.signOut()
        db.withTransaction {
            db.transactionDao().deleteAll()
            db.outboxDao().deleteAll()
            db.budgetDao().deleteAll()
        }
        settings.clearLastPulledAt()
        LogoutResult.SignedOut
    }
}
