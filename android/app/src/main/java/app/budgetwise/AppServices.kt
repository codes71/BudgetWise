package app.budgetwise

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.budgetwise.data.repo.AuthRepository
import app.budgetwise.data.repo.LoginResult
import app.budgetwise.data.repo.LogoutResult
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.sync.SyncEngine
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.data.sync.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// What the shell screens need from :core:data. They are interfaces so ViewModel tests can use fakes; di/AppModule
// binds the implementations below, which only delegate.

/** Sign-in and sign-out, as [AuthRepository] does them (it also schedules the syncs after a sign-in). */
interface AccountActions {
    suspend fun login(email: String, password: String, discardOtherUsersData: Boolean = false): LoginResult

    suspend fun logout(force: Boolean): LogoutResult
}

/** Sync state for Settings. The UI never reads the network, only these flows. */
interface SyncControls {
    val status: StateFlow<SyncStatus>

    /** True while a one-time sync waits to run: no connection yet, or backing off after a failure. */
    val queued: Flow<Boolean>

    /** Rows the server has not confirmed yet (queued, rejected, or awaiting delete). */
    val unsyncedCount: Flow<Int>

    /** Pushes the outbox, then runs a full pull, as soon as there is a connection. */
    fun syncNow()
}

/**
 * Both calls run to the end even when the screen that started them goes away (signing out pops Settings while the
 * second wipe is still running): each changes the session and local data in steps that must not stop half way.
 */
class DefaultAccountActions @Inject constructor(private val auth: AuthRepository) : AccountActions {
    override suspend fun login(email: String, password: String, discardOtherUsersData: Boolean) =
        withContext(NonCancellable) { auth.login(email, password, discardOtherUsersData) }

    override suspend fun logout(force: Boolean) = withContext(NonCancellable) { auth.logout(force) }
}

class DefaultSyncControls @Inject constructor(
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val transactions: TransactionRepository,
    @ApplicationContext private val context: Context,
) : SyncControls {
    override val status: StateFlow<SyncStatus> get() = engine.status

    override val queued: Flow<Boolean>
        get() = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } }
            .distinctUntilChanged()

    override val unsyncedCount: Flow<Int> get() = transactions.observeUnsyncedCount()

    override fun syncNow() = scheduler.enqueue(fullPull = true)
}
