package app.budgetwise.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.AccessToken
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.auth.toGrant
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.DeleteAccountBody
import app.budgetwise.data.net.LoginBody
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import app.budgetwise.data.sync.SyncEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject

sealed interface AccountDeletionResult {
    data object Success : AccountDeletionResult
    data object WrongPassword : AccountDeletionResult
    data object NeedsInternet : AccountDeletionResult
    data object AccountChanged : AccountDeletionResult
    data object TryAgain : AccountDeletionResult
    data object DeletionIncomplete : AccountDeletionResult
    data object LocalCleanupFailed : AccountDeletionResult
}

/** Password-confirmed server deletion precedes every local mutation. Other accounts and shared models are kept. */
@Singleton
class AccountDeletionRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
    private val db: BudgetWiseDb,
    private val sync: SyncEngine,
    private val writes: CacheWrites,
    private val preferences: DataStore<Preferences>,
) {
    suspend fun delete(
        expectedId: String,
        password: String,
        afterLocalDeletion: suspend () -> Unit = {},
    ): AccountDeletionResult = withContext(NonCancellable) {
        if (password.isEmpty()) return@withContext AccountDeletionResult.WrongPassword
        sync.withoutSync {
            if (expectedId in preferences.data.first()[PENDING].orEmpty()) {
                return@withoutSync try {
                    session.finishConfirmedDeletion(expectedId) { cleanup(expectedId); afterLocalDeletion() }
                    clearReceipt(expectedId)
                    AccountDeletionResult.Success
                } catch (_: Exception) { AccountDeletionResult.LocalCleanupFailed }
            }
            var result: AccountDeletionResult = AccountDeletionResult.AccountChanged
            var confirmed = false
            try {
                val deleted = session.deleteActiveAccount(
                    expectedId,
                    deleteRemote = { user, rememberToken ->
                        result = deleteRemote(user, password, rememberToken)
                        confirmed = result == AccountDeletionResult.Success
                        confirmed
                    },
                    cleanupLocal = {
                        preferences.edit { it[PENDING] = it[PENDING].orEmpty() + expectedId }
                        cleanup(expectedId)
                        afterLocalDeletion()
                    },
                )
                if (deleted) {
                    clearReceipt(expectedId)
                    AccountDeletionResult.Success
                } else result
            } catch (_: Exception) {
                if (confirmed) AccountDeletionResult.LocalCleanupFailed else AccountDeletionResult.TryAgain
            }
        }
    }

    /** Must precede loading saved credentials, so an interrupted cleanup never briefly signs the deleted owner in. */
    suspend fun quarantinePendingDeletions() {
        session.quarantineConfirmedDeletions(preferences.data.first()[PENDING].orEmpty())
    }

    /** A durable, local-only receipt ensures an interrupted cleanup never needs the deleted server to log in. */
    suspend fun recoverPendingDeletions(afterLocalDeletion: suspend (String) -> Unit = {}) = withContext(NonCancellable) {
        val pending = preferences.data.first()[PENDING].orEmpty()
        session.quarantineConfirmedDeletions(pending)
        for (owner in pending) sync.withoutSync {
            session.finishConfirmedDeletion(owner) { cleanup(owner); afterLocalDeletion(owner) }
            clearReceipt(owner)
        }
    }

    private suspend fun cleanup(owner: String) {
        writes.removeOwner(owner) {
            db.withTransaction {
                db.outboxDao().deleteFor(owner)
                db.transactionDao().deleteFor(owner)
                db.budgetDao().deleteFor(owner)
                db.monthPlanDao().deleteFor(owner)
            }
        }
        preferences.edit { p ->
            p.asMap().keys.filter {
                it.name == "last_pulled_at_ms.$owner" || it.name == "categories_json.$owner" ||
                    it.name.startsWith("month_end_posted.$owner.")
            }.forEach { p.remove(it) }
        }
    }

    private suspend fun clearReceipt(owner: String) {
        preferences.edit { p ->
            val remaining = p[PENDING].orEmpty() - owner
            if (remaining.isEmpty()) p.remove(PENDING) else p[PENDING] = remaining
        }
    }

    private suspend fun deleteRemote(user: UserInfo, password: String, rememberToken: suspend (AccessToken) -> Unit): AccountDeletionResult {
        val stored = session.deletionAccessFor(user.id)
        if (stored != null) {
            rememberToken(stored)
            val first = request(stored.token, password)
            if (first != RemoteResult.UNAUTHORIZED) return first.toPublic()
        }
        // An expired token or partially deleted account may be reauthenticated without switching local identity or
        // replacing stored credentials. The account-transition lock remains held during both requests.
        val grant = when (val reauth = safeApi { api.login(LoginBody(user.email, password)) }) {
            is ApiResult.Ok -> reauth.v.toGrant()?.takeIf { it.user.id == user.id }
                ?: return AccountDeletionResult.AccountChanged
            is ApiResult.Unauthorized -> return AccountDeletionResult.WrongPassword
            is ApiResult.Failure -> return if (reauth.code == NETWORK_ERROR_CODE) AccountDeletionResult.NeedsInternet else AccountDeletionResult.TryAgain
        }
        rememberToken(AccessToken(grant.token, grant.expiresAt))
        return request(grant.token, password).toPublic()
    }

    private suspend fun request(token: String, password: String): RemoteResult =
        when (val call = safeApi { api.deleteAccount(authorization(token), DeleteAccountBody(password)) }) {
            is ApiResult.Ok -> {
                val response = call.v
                if (response.code() == 204) RemoteResult.SUCCESS else {
                    val code = try {
                        response.errorBody()?.use { JSONObject(it.string()).optJSONObject("error")?.optString("code") }
                    } catch (_: Exception) { null }
                    when {
                        response.code() == 401 && code == "invalid_password" -> RemoteResult.WRONG_PASSWORD
                        response.code() == 401 -> RemoteResult.UNAUTHORIZED
                        response.code() == 503 && code == "deletion_incomplete" -> RemoteResult.INCOMPLETE
                        else -> RemoteResult.RETRY
                    }
                }
            }
            is ApiResult.Unauthorized -> RemoteResult.UNAUTHORIZED
            is ApiResult.Failure -> if (call.code == NETWORK_ERROR_CODE) RemoteResult.OFFLINE else RemoteResult.RETRY
        }

    private enum class RemoteResult { SUCCESS, WRONG_PASSWORD, UNAUTHORIZED, OFFLINE, INCOMPLETE, RETRY }
    private companion object { val PENDING = stringSetPreferencesKey("confirmed_account_deletions") }
    private fun RemoteResult.toPublic(): AccountDeletionResult = when (this) {
        RemoteResult.SUCCESS -> AccountDeletionResult.Success
        RemoteResult.WRONG_PASSWORD -> AccountDeletionResult.WrongPassword
        RemoteResult.UNAUTHORIZED -> AccountDeletionResult.AccountChanged
        RemoteResult.OFFLINE -> AccountDeletionResult.NeedsInternet
        RemoteResult.INCOMPLETE -> AccountDeletionResult.DeletionIncomplete
        RemoteResult.RETRY -> AccountDeletionResult.TryAgain
    }
}
