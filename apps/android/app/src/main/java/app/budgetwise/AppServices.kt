package app.budgetwise

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.repo.AuthRepository
import app.budgetwise.data.repo.BudgetRepository
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.LoginResult
import app.budgetwise.data.repo.RegisterResult
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.sync.SyncEngine
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.data.sync.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// What the shell screens need from :core:data. They are interfaces so ViewModel tests can use fakes; di/AppModule
// binds the implementations below, which only delegate.

/**
 * Sign-in and sign-out, as [AuthRepository] does them (it also schedules the syncs after a sign-in). Neither deletes an
 * entry: every account keeps its own on the phone.
 */
interface AccountActions {
    /** Online, or offline for an account used on this phone before. */
    suspend fun login(email: String, password: String): LoginResult

    /** Creates the account on the server and signs it in; online only. */
    suspend fun register(email: String, password: String, fullName: String): RegisterResult

    /** Never asks: entries not uploaded yet keep uploading in the background. */
    suspend fun logout()
}

/** Loads the saved accounts at process start ([AuthRepository.loadSession]); [AppStartup] calls it once. */
fun interface SessionLoader {
    suspend fun load()
}

/** Sync state for Settings. The UI never reads the network, only these flows. */
interface SyncControls {
    val status: StateFlow<SyncStatus>

    /** The latest finished run; a stopped run never puts an older value back ([SyncEngine.lastResult]). */
    val lastResult: StateFlow<SyncStatus.LastResult?>

    /** True while a one-time sync waits to run: no connection yet, or backing off after a failure. */
    val queued: Flow<Boolean>

    /** The active account's rows the server has not confirmed yet (queued, rejected, or awaiting delete). */
    val unsyncedCount: Flow<Int>

    /** Pushes the outboxes, then runs a full pull of the active account, as soon as there is a connection. */
    fun syncNow()
}

/** The ledger as the transaction screens use it: Room flows and offline-first writes ([TransactionRepository]). */
interface TransactionStore {
    fun observe(): Flow<List<Transaction>>

    /** The active account's spending from [from] to [to] (both included), per day and category, for the month chart. */
    fun observeDailySpend(from: LocalDate, to: LocalDate): Flow<List<DailySpend>>

    /** Saves PENDING rows plus their outbox ops and requests a sync; works offline. Returns the new clientIds. */
    suspend fun addAll(inputs: List<NewTransaction>): List<String>

    suspend fun delete(clientId: String)
}

/** Monthly limits from the Room cache ([BudgetRepository]); [refresh] replaces the cache with the server list. */
interface BudgetSource {
    fun observe(): Flow<List<Budget>>

    suspend fun refresh(): ApiResult<Unit>
}

/** Whether the phone can reach the internet, for the header's Offline chip. */
interface NetworkStatus {
    val online: Flow<Boolean>
}

/** Category names for the add form ([CategoryRepository]): the cached server list, else the eight built-ins. */
interface CategorySource {
    val categories: Flow<List<String>>

    /** Fetches the server list for next time; a failure keeps the cache. */
    suspend fun refresh()
}

/**
 * Both calls run to the end even when the screen that started them goes away (signing out pops Settings while the
 * session is still being saved): each changes the session and local data in steps that must not stop half way.
 */
class DefaultAccountActions @Inject constructor(private val auth: AuthRepository) : AccountActions {
    override suspend fun login(email: String, password: String) = withContext(NonCancellable) { auth.login(email, password) }

    override suspend fun register(email: String, password: String, fullName: String) =
        withContext(NonCancellable) { auth.register(email, password, fullName) }

    override suspend fun logout() = withContext(NonCancellable) { auth.logout() }
}

class DefaultSessionLoader @Inject constructor(private val auth: AuthRepository) : SessionLoader {
    override suspend fun load() = auth.loadSession()
}

class DefaultSyncControls @Inject constructor(
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val transactions: TransactionRepository,
    @ApplicationContext private val context: Context,
) : SyncControls {
    override val status: StateFlow<SyncStatus> get() = engine.status

    override val lastResult: StateFlow<SyncStatus.LastResult?> get() = engine.lastResult

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

    override fun observeDailySpend(from: LocalDate, to: LocalDate): Flow<List<DailySpend>> = repo.observeDailySpend(from, to)

    override suspend fun addAll(inputs: List<NewTransaction>) = withContext(NonCancellable) { repo.addAll(inputs) }

    override suspend fun delete(clientId: String) = withContext(NonCancellable) { repo.delete(clientId) }
}

/**
 * The default network, from [ConnectivityManager]: online while it has validated internet, so a Wi-Fi without
 * internet counts as offline. Starts from the current network.
 */
class DefaultNetworkStatus @Inject constructor(@ApplicationContext private val context: Context) : NetworkStatus {
    override val online: Flow<Boolean>
        get() = callbackFlow {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            fun NetworkCapabilities?.online() = this != null &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            trySend(cm.getNetworkCapabilities(cm.activeNetwork).online())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(capabilities.online())
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()
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
