package app.budgetwise.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.SyncControls
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.sync.SyncStatus
import app.budgetwise.di.ApplicationScope
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One day of entries, newest first. */
data class DaySection(val date: LocalDate, val rows: List<Transaction>)

/** Totals for [month]. [balance] is negative when more went out than came in. */
data class MonthSummary(val month: YearMonth, val spent: Long = 0, val income: Long = 0) {
    val balance: Long get() = income - spent
}

data class TransactionsUiState(
    val today: LocalDate,
    /** True until Room's first answer, so the empty state never flashes before the list. */
    val loading: Boolean = true,
    val days: List<DaySection> = emptyList(),
    val summary: MonthSummary = MonthSummary(YearMonth.from(today)),
    val myanmarDigits: Boolean = false,
    val refreshing: Boolean = false,
    /** The row swiped away whose snackbar (with Undo) is showing; it is deleted when the snackbar ends. */
    val pendingDelete: String? = null,
    /** How often Undo brought each row back. Part of the row's list key, so it returns with its swipe reset. */
    val restored: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = !loading && days.isEmpty()
}

sealed interface TransactionsEvent {
    /** A pull-to-refresh sync ran but could not reach the server (offline, or adb reverse is down). */
    data object Unreachable : TransactionsEvent

    /** A pull-to-refresh sync is waiting for a connection and has not started. */
    data object WaitingForNetwork : TransactionsEvent
}

/** Rows grouped by day, newest day first; within a day the newest change first. */
internal fun groupByDay(rows: List<Transaction>): List<DaySection> =
    rows.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.updatedAt })
        .groupBy { it.date }
        .map { (date, dayRows) -> DaySection(date, dayRows) }

internal fun monthSummary(rows: List<Transaction>, month: YearMonth): MonthSummary {
    var spent = 0L
    var income = 0L
    for (row in rows) {
        if (YearMonth.from(row.date) != month) continue
        if (row.type == TxType.EXPENSE) spent += row.amountKyat else income += row.amountKyat
    }
    return MonthSummary(month, spent, income)
}

/**
 * The list reads Room only. Swipe to delete hides the row at once and offers Undo; the delete reaches the
 * repository when the snackbar ends without Undo, when another row is swiped, or when the user leaves the screen.
 * Deletes run in [appScope], so leaving the screen never cancels one half way.
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactions: TransactionStore,
    private val sync: SyncControls,
    settings: SettingsStore,
    @DeviceClock private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private data class Local(
        /** Rows swiped away: pending Undo, or deleted and waiting for Room to drop them. Ids are never reused. */
        val hidden: Set<String> = emptySet(),
        val pendingDelete: String? = null,
        val refreshRequested: Boolean = false,
        val restored: Map<String, Int> = emptyMap(),
    )

    private val local = MutableStateFlow(Local())
    private val _events = Channel<TransactionsEvent>(Channel.BUFFERED)
    val events: Flow<TransactionsEvent> = _events.receiveAsFlow()
    private var refreshJob: Job? = null

    val state: StateFlow<TransactionsUiState> =
        combine(transactions.observe(), local, settings.myanmarDigits, sync.status) { rows, l, digits, status ->
            val today = LocalDate.now(clock)
            val visible = rows.filterNot { it.clientId in l.hidden }
            TransactionsUiState(
                today = today,
                loading = false,
                days = groupByDay(visible),
                summary = monthSummary(visible, YearMonth.from(today)),
                myanmarDigits = digits,
                refreshing = l.refreshRequested || status == SyncStatus.Running,
                pendingDelete = l.pendingDelete,
                restored = l.restored,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState(today = LocalDate.now(clock)))

    /** Swiped away: hide it and offer Undo. A row still waiting for its Undo window is deleted now. */
    fun delete(clientId: String) {
        val previous = local.value.pendingDelete
        local.update { it.copy(hidden = it.hidden + clientId, pendingDelete = clientId) }
        if (previous != null && previous != clientId) send(previous)
    }

    fun undoDelete(clientId: String) {
        if (local.value.pendingDelete != clientId) return
        local.update {
            it.copy(
                hidden = it.hidden - clientId,
                pendingDelete = null,
                restored = it.restored + (clientId to (it.restored[clientId] ?: 0) + 1),
            )
        }
    }

    /** The snackbar for [clientId] ended without Undo. Ignored when that row was already undone or committed. */
    fun commitDelete(clientId: String) {
        if (local.value.pendingDelete != clientId) return
        local.update { it.copy(pendingDelete = null) }
        send(clientId)
    }

    /** The screen is going away while a snackbar shows: the delete happens, as if the snackbar had timed out. */
    fun commitPendingDelete() {
        local.value.pendingDelete?.let(::commitDelete)
    }

    override fun onCleared() {
        commitPendingDelete()
    }

    private fun send(clientId: String) {
        appScope.launch {
            try {
                transactions.delete(clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The row was not deleted (a Room failure): show it again.
                local.update { it.copy(hidden = it.hidden - clientId) }
            }
        }
    }

    /**
     * Pull to refresh: push, then a full pull. The indicator stays up from the pull until the run ends, also while
     * WorkManager starts the worker; a run that never starts (no connection) or cannot reach the server is reported.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        val before = sync.status.value
        sync.syncNow()
        refreshJob = viewModelScope.launch {
            local.update { it.copy(refreshRequested = true) }
            try {
                val started = withTimeoutOrNull(START_TIMEOUT_MS) {
                    sync.status.first { it == SyncStatus.Running || (it is SyncStatus.LastResult && it != before) }
                }
                if (started == null) {
                    _events.send(TransactionsEvent.WaitingForNetwork)
                    return@launch
                }
                // A run WorkManager stops (replaced, connection lost, signed out) puts the previous status back
                // instead of recording a result: stop waiting then too, with nothing to report. A replacing run
                // still shows the indicator through its own Running status.
                val done = started as? SyncStatus.LastResult ?: sync.status.first { it != SyncStatus.Running }
                if (done is SyncStatus.LastResult && done != before && done.outcome == SyncOutcome.RetryLater) {
                    _events.send(TransactionsEvent.Unreachable)
                }
            } finally {
                local.update { it.copy(refreshRequested = false) }
            }
        }
    }

    companion object {
        /** WorkManager starts a sync within moments when there is a connection; longer means it is waiting for one. */
        const val START_TIMEOUT_MS = 4_000L
    }
}
