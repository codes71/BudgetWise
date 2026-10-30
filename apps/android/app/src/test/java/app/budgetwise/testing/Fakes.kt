package app.budgetwise.testing

import app.budgetwise.AccountActions
import app.budgetwise.BudgetSource
import app.budgetwise.CategorySource
import app.budgetwise.NetworkStatus
import app.budgetwise.SyncControls
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SavedAccounts
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.LoginResult
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.sync.SyncStatus
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

val TestUser = UserInfo(id = "u1", email = "aye@example.com", fullName = "Aye Aye")

/** In-memory [TokenStore]. [gate], when set, holds [load] until completed. */
class FakeTokenStore(var accounts: SavedAccounts = SavedAccounts.EMPTY) : TokenStore {
    /** [session] as the one, active account (none when null). */
    constructor(session: StoredSession?) : this(session?.let(SavedAccounts::of) ?: SavedAccounts.EMPTY)

    var gate: CompletableDeferred<Unit>? = null

    override suspend fun load(): SavedAccounts {
        gate?.await()
        return accounts
    }

    override suspend fun save(accounts: SavedAccounts) {
        this.accounts = accounts
    }
}

data class LoginCall(val email: String, val password: String)

class FakeAccountActions : AccountActions {
    val logins = mutableListOf<LoginCall>()
    var logouts = 0

    /** May throw, like AuthRepository on a Keystore or Room failure. */
    var onLogin: (LoginCall) -> LoginResult = { error("no login result set") }

    /** May throw, like AuthRepository on a Room or Keystore failure. */
    var onLogout: () -> Unit = {}

    /** When set, login suspends here, so a test can look at the loading state. */
    var loginGate: CompletableDeferred<Unit>? = null

    override suspend fun login(email: String, password: String): LoginResult {
        val call = LoginCall(email, password)
        logins += call
        loginGate?.await()
        return onLogin(call)
    }

    override suspend fun logout() {
        logouts++
        onLogout()
    }
}

class FakeSyncControls : SyncControls {
    override val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val lastResult = MutableStateFlow<SyncStatus.LastResult?>(null)
    override val queued = MutableStateFlow(false)
    override val unsyncedCount = MutableStateFlow(0)
    var syncNowCalls = 0

    override fun syncNow() {
        syncNowCalls++
    }

    fun start() {
        status.value = SyncStatus.Running
    }

    /** A run records [outcome], result first, as SyncEngine does. */
    fun finish(outcome: SyncOutcome, at: Instant = Instant.EPOCH): SyncStatus.LastResult =
        SyncStatus.LastResult(at, outcome).also {
            lastResult.value = it
            status.value = it
        }

    /** WorkManager stopped the run: the status before it comes back and no result is recorded. */
    fun stop(restored: SyncStatus) {
        status.value = restored
    }
}

class FakeNetworkStatus(online: Boolean = true) : NetworkStatus {
    override val online = MutableStateFlow(online)
}

class FakeSettingsStore : SettingsStore {
    /** When set, setMyanmarDigits throws it (a DataStore IOException). */
    var writeFailure: Exception? = null

    override val autoSaveHighConfidence = MutableStateFlow(false)
    override val cloudParseEnabled = MutableStateFlow(true)
    override val myanmarDigits = MutableStateFlow(false)
    val lastPulled = mutableMapOf<String, MutableStateFlow<Instant?>>()

    override fun lastPulledAt(owner: String): Flow<Instant?> = lastPulled.getOrPut(owner) { MutableStateFlow(null) }

    override suspend fun setAutoSaveHighConfidence(on: Boolean) {
        autoSaveHighConfidence.value = on
    }

    override suspend fun setCloudParseEnabled(on: Boolean) {
        cloudParseEnabled.value = on
    }

    override suspend fun setMyanmarDigits(on: Boolean) {
        writeFailure?.let { throw it }
        myanmarDigits.value = on
    }

    override suspend fun setLastPulledAt(owner: String, at: Instant) {
        lastPulled.getOrPut(owner) { MutableStateFlow(null) }.value = at
    }
}

/** In-memory ledger. [rows] is what Room would hold; [added] and [deleted] record the writes. */
class FakeTransactionStore(initial: List<Transaction> = emptyList()) : TransactionStore {
    val rows = MutableStateFlow(initial)
    val added = mutableListOf<NewTransaction>()
    val deleted = mutableListOf<String>()

    /** When set, addAll throws it (a Room failure). */
    var addFailure: Exception? = null

    override fun observe(): Flow<List<Transaction>> = rows

    /** What the DAO query answers: [rows]' expenses in the range, summed per day and category. */
    override fun observeDailySpend(from: LocalDate, to: LocalDate): Flow<List<DailySpend>> = rows.map { all ->
        all.filter { it.type == TxType.EXPENSE && !it.date.isBefore(from) && !it.date.isAfter(to) }
            .groupBy { it.date to it.category }
            .map { (key, rows) -> DailySpend(key.first, key.second, rows.sumOf { it.amountKyat }) }
    }

    override suspend fun addAll(inputs: List<NewTransaction>): List<String> {
        addFailure?.let { throw it }
        added += inputs
        val ids = inputs.map { UUID.randomUUID().toString() }
        rows.value = inputs.zip(ids) { input, id -> input.toPendingRow(id) } + rows.value
        return ids
    }

    override suspend fun delete(clientId: String) {
        deleted += clientId
        rows.value = rows.value.filterNot { it.clientId == clientId }
    }

    private fun NewTransaction.toPendingRow(id: String) = Transaction(
        clientId = id, serverId = null, type = type, amountKyat = amountKyat, category = category,
        description = description, date = date, source = source, transcript = transcript,
        updatedAt = Instant.EPOCH, syncState = SyncState.PENDING,
    )
}

class FakeBudgetSource(initial: List<Budget> = emptyList()) : BudgetSource {
    val budgets = MutableStateFlow(initial)
    var refreshes = 0
    var result: ApiResult<Unit> = ApiResult.Ok(Unit)

    /** When set, refresh suspends here, so a test can look at the refreshing state. */
    var gate: CompletableDeferred<Unit>? = null

    override fun observe(): Flow<List<Budget>> = budgets

    override suspend fun refresh(): ApiResult<Unit> {
        refreshes++
        gate?.await()
        return result
    }
}

class FakeCategorySource(names: List<String> = CategoryRepository.BUILT_IN) : CategorySource {
    override val categories = MutableStateFlow(names)
    var refreshes = 0

    /** When set, refresh throws it (writing the cache to DataStore failed). */
    var refreshFailure: Exception? = null

    override suspend fun refresh() {
        refreshes++
        refreshFailure?.let { throw it }
    }
}

/** A row as Room would hold it. */
fun testTransaction(
    description: String,
    amount: Long,
    date: LocalDate,
    type: TxType = TxType.EXPENSE,
    category: String = "Groceries",
    syncState: SyncState = SyncState.SYNCED,
    clientId: String = UUID.randomUUID().toString(),
) = Transaction(
    clientId = clientId, serverId = if (syncState == SyncState.SYNCED) "s-$clientId" else null, type = type,
    amountKyat = amount, category = category, description = description, date = date, source = Source.MANUAL,
    transcript = null, updatedAt = Instant.EPOCH, syncState = syncState,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
