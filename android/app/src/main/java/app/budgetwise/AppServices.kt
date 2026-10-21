package app.budgetwise

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.repo.AuthRepository
import app.budgetwise.data.repo.BudgetRepository
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.LoginResult
import app.budgetwise.data.repo.LogoutResult
import app.budgetwise.data.repo.NewTransaction
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

/** The ledger as the transaction screens use it: Room flows and offline-first writes ([TransactionRepository]). */
interface TransactionStore {
    fun observe(): Flow<List<Transaction>>

    /** Saves PENDING rows plus their outbox ops and requests a sync; works offline. Returns the new clientIds. */
    suspend fun addAll(inputs: List<NewTransaction>): List<String>

    suspend fun delete(clientId: String)
}

/** Monthly limits from the Room cache ([BudgetRepository]); [refresh] replaces the cache with the server list. */
interface BudgetSource {
    fun observe(): Flow<List<Budget>>

    suspend fun refresh(): ApiResult<Unit>
}

/** Category names for the add form ([CategoryRepository]): the cached server list, else the eight built-ins. */
interface CategorySource {
    val categories: Flow<List<String>>

    /** Fetches the server list for next time; a failure keeps the cache. */
    suspend fun refresh()
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

/**
 * Writes run to the end even when the screen that started them goes away (the user leaves the add form while it
 * saves): a cancelled Room transaction would roll back an entry the user saw being saved.
 */
class DefaultTransactionStore @Inject constructor(private val repo: TransactionRepository) : TransactionStore {
    override fun observe(): Flow<List<Transaction>> = repo.observe()

    override suspend fun addAll(inputs: List<NewTransaction>) = withContext(NonCancellable) { repo.addAll(inputs) }

    override suspend fun delete(clientId: String) = withContext(NonCancellable) { repo.delete(clientId) }
}

class DefaultBudgetSource @Inject constructor(private val repo: BudgetRepository) : BudgetSource {
    override fun observe(): Flow<List<Budget>> = repo.observe()

    override suspend fun refresh(): ApiResult<Unit> = repo.refresh()
}

class DefaultCategorySource @Inject constructor(private val repo: CategoryRepository) : CategorySource {
    override val categories: Flow<List<String>> get() = repo.categories

    override suspend fun refresh() {
        repo.refresh()
    }
}
