package app.budgetwise.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.NetworkStatus
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
import java.util.Locale
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

/** One day of entries, newest first. [lines] is what the list shows: a receipt's entries are one line. */
data class DaySection(val date: LocalDate, val rows: List<Transaction>, val lines: List<DayLine> = dayLines(rows))

/** A line of a day's list: one entry, or the entries saved from one scanned receipt. */
sealed interface DayLine {
    /** The line's id in the list and for Undo: the entry's clientId, or the receipt's id. */
    val key: String

    /** The entries a swipe deletes. */
    val clientIds: List<String>

    data class Entry(val row: Transaction) : DayLine {
        override val key: String get() = row.clientId
        override val clientIds: List<String> get() = listOf(row.clientId)
    }

    /**
     * A receipt's entries (expenses, largest first) as one line. [scannedTwice]: an earlier receipt that day has the
     * same shop and total, so this one may be the same paper scanned again.
     */
    data class Receipt(val receiptId: String, val entries: List<Transaction>, val scannedTwice: Boolean = false) : DayLine {
        override val key: String get() = receiptId
        override val clientIds: List<String> get() = entries.map { it.clientId }
        val merchant: String? get() = entries.firstNotNullOfOrNull { it.merchant }
        val amountKyat: Long get() = entries.sumOf { it.amountKyat }
    }
}

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
    /**
     * The pull-to-refresh spinner: only for a refresh the user pulled. The syncs that run on their own (at launch,
     * after a save, on reconnect) stay out of sight, or the spinner drops into the screen for no visible reason.
     */
    val refreshing: Boolean = false,
    /**
     * The line swiped away whose notice (with Undo) is showing, by its [DayLine.key]; its entries are deleted when the
     * notice ends.
     */
    val pendingDelete: String? = null,
    /** [pendingDelete]'s rows, for the notice to name them; empty once Room has dropped them. */
    val pendingDeleteRows: List<Transaction> = emptyList(),
    /** How often Undo brought each line back, by key. Part of the line's list key, so it returns with its swipe reset. */
    val restored: Map<String, Int> = emptyMap(),
    /** No internet. Entries still save on the phone and sync later; the header's chip says so. */
    val offline: Boolean = false,
    /** The month chart's Total view: this month's running total against last month's by the same day. */
    val pace: MonthPace = MonthPace.empty(today),
    /** The month chart's By category view. */
    val categories: CategoryBreakdown = CategoryBreakdown.EMPTY,
) {
    val isEmpty: Boolean get() = !loading && days.isEmpty()

    /** Entries, but no expense yet in any month (a new account with its income in): home offers the first one. */
    val noExpensesYet: Boolean get() = !loading && days.isNotEmpty() && days.none { day -> day.rows.any { it.type == TxType.EXPENSE } }
}

sealed interface TransactionsEvent {
    /**
     * A pull-to-refresh sync ended in RetryLater: the server could not be reached (offline, adb reverse down) or
     * could not take the upload yet (the pull may still have applied).
     */
    data object Unreachable : TransactionsEvent

    /** A pull-to-refresh sync is waiting for a connection and has not started. */
    data object WaitingForNetwork : TransactionsEvent
}

/** Rows grouped by day, newest day first; within a day the newest change first. */
internal fun groupByDay(rows: List<Transaction>): List<DaySection> =
    rows.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.updatedAt })
        .groupBy { it.date }
        .map { (date, dayRows) -> DaySection(date, dayRows) }

/**
 * A day's rows (newest first) as the list shows them: a receipt's entries become one line where its newest entry is. A
 * receipt with the shop (in any case) and the total of an earlier one that day is marked as maybe scanned twice.
 */
internal fun dayLines(rows: List<Transaction>): List<DayLine> {
    val receipts = rows.groupBy { it.receiptId }
    val lines = rows.mapNotNull { row ->
        val id = row.receiptId ?: return@mapNotNull DayLine.Entry(row)
        val entries = receipts.getValue(id)
        // The receipt's first row is its newest entry: the line goes there, and its other rows add nothing.
        if (entries.first() === row) DayLine.Receipt(id, entries.sortedByDescending { it.amountKyat }) else null
    }
    // Oldest first, so the first of two alike stays plain and the later one is marked.
    val earlier = mutableSetOf<Pair<String, Long>>()
    return lines.asReversed().map { line ->
        val shop = (line as? DayLine.Receipt)?.merchant?.trim()?.lowercase(Locale.ROOT)
        if (line !is DayLine.Receipt || shop == null || earlier.add(shop to line.amountKyat)) line else line.copy(scannedTwice = true)
    }.asReversed()
}

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
 * The list and the month chart read Room only. Swipe to delete hides the row at once (from the chart too) and offers
 * Undo; the delete reaches the repository when the notice ends without Undo, when another row is swiped, or when the
 * user leaves the screen.
 * Deletes run in [appScope], so leaving the screen never cancels one half way.
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactions: TransactionStore,
    private val sync: SyncControls,
    settings: SettingsStore,
    network: NetworkStatus,
    @DeviceClock private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    /** A line swiped away and waiting for its Undo window: its key and the entries it takes with it. */
    private data class PendingDelete(val key: String, val clientIds: List<String>)

    private data class Local(
        /** Rows swiped away: pending Undo, or deleted and waiting for Room to drop them. Ids are never reused. */
        val hidden: Set<String> = emptySet(),
        val pendingDelete: PendingDelete? = null,
        val refreshRequested: Boolean = false,
        val restored: Map<String, Int> = emptyMap(),
    )

    private val local = MutableStateFlow(Local())
    private val _events = Channel<TransactionsEvent>(Channel.BUFFERED)
    val events: Flow<TransactionsEvent> = _events.receiveAsFlow()
    private var refreshJob: Job? = null

    /** This month and last, for the chart; read once when the screen opens, like "today". */
    private val dailySpend = YearMonth.from(LocalDate.now(clock)).let { month ->
        transactions.observeDailySpend(month.minusMonths(1).atDay(1), month.atEndOfMonth())
    }

    val state: StateFlow<TransactionsUiState> =
        combine(transactions.observe(), local, settings.myanmarDigits, network.online, dailySpend) { rows, l, digits, online, daily ->
            val today = LocalDate.now(clock)
            val (hidden, visible) = rows.partition { it.clientId in l.hidden }
            val spending = withoutRows(daily, hidden)
            TransactionsUiState(
                today = today,
                loading = false,
                days = groupByDay(visible),
                summary = monthSummary(visible, YearMonth.from(today)),
                myanmarDigits = digits,
                refreshing = l.refreshRequested,
                pendingDelete = l.pendingDelete?.key,
                pendingDeleteRows = l.pendingDelete?.let { pending -> hidden.filter { it.clientId in pending.clientIds } }.orEmpty(),
                restored = l.restored,
                offline = !online,
                pace = monthPace(spending, today),
                categories = categoryBreakdown(spending, today),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState(today = LocalDate.now(clock)))

    /**
     * Swiped away: hide the line (an entry, or every entry of a receipt) and offer Undo. A line still waiting for its
     * Undo window is deleted now.
     */
    fun delete(line: DayLine) = deleteEntries(line.key, line.clientIds)

    /**
     * [delete] for the entries [clientIds] of the line [key]: a swipe, or Delete entry in the form (and Delete this
     * receipt on the receipt's screen), which hands its entries back here for the same notice and Undo.
     */
    fun deleteEntries(key: String, clientIds: List<String>) {
        val previous = local.value.pendingDelete
        local.update { it.copy(hidden = it.hidden + clientIds, pendingDelete = PendingDelete(key, clientIds)) }
        if (previous != null && previous.key != key) previous.clientIds.forEach(::send)
    }

    /** Undo: the line [key] comes back, all its entries. */
    fun undoDelete(key: String) {
        val pending = local.value.pendingDelete?.takeIf { it.key == key } ?: return
        local.update {
            it.copy(
                hidden = it.hidden - pending.clientIds.toSet(),
                pendingDelete = null,
                restored = it.restored + (key to (it.restored[key] ?: 0) + 1),
            )
        }
    }

    /** The notice for the line [key] ended without Undo. Ignored when that line was already undone or committed. */
    fun commitDelete(key: String) {
        val pending = local.value.pendingDelete?.takeIf { it.key == key } ?: return
        local.update { it.copy(pendingDelete = null) }
        pending.clientIds.forEach(::send)
    }

    /** The screen is going away while a notice shows: the delete happens, as if the notice had timed out. */
    fun commitPendingDelete() {
        local.value.pendingDelete?.let { commitDelete(it.key) }
    }

    override fun onCleared() {
        commitPendingDelete()
    }

    /** Undo on "Added 3 entries": deletes what a scan just saved, like a swipe would, without a second notice. */
    fun undoAdded(clientIds: List<String>) = clientIds.forEach(::send)

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
     * Pull to refresh: push, then a full pull. The indicator stays up from the pull until a run records a result,
     * also while WorkManager starts the worker; a run that never starts (no connection) or ends in RetryLater is
     * reported.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        val baseline = sync.lastResult.value
        sync.syncNow()
        refreshJob = viewModelScope.launch {
            local.update { it.copy(refreshRequested = true) }
            try {
                val result = awaitNewResult(baseline)
                when {
                    result == null -> _events.send(TransactionsEvent.WaitingForNetwork)
                    result.outcome == SyncOutcome.RetryLater -> _events.send(TransactionsEvent.Unreachable)
                }
            } finally {
                local.update { it.copy(refreshRequested = false) }
            }
        }
    }

    /**
     * The first result recorded after [baseline], or null when no run starts within [START_TIMEOUT_MS]. A run that
     * WorkManager stops without a result (this refresh replaced it, or the connection dropped) puts the previous
     * status back; the wait then goes on for the run that takes its place, with a fresh start timeout.
     */
    private suspend fun awaitNewResult(baseline: SyncStatus.LastResult?): SyncStatus.LastResult? {
        val progress = combine(sync.status, sync.lastResult) { status, result -> status to result }
        while (true) {
            val (_, started) = withTimeoutOrNull(START_TIMEOUT_MS) {
                progress.first { (status, result) -> status == SyncStatus.Running || result !== baseline }
            } ?: return null
            if (started !== baseline) return started
            val (_, ended) = progress.first { (status, result) -> status != SyncStatus.Running || result !== baseline }
            if (ended !== baseline) return ended
        }
    }

    companion object {
        /** WorkManager starts a sync within moments when there is a connection; longer means it is waiting for one. */
        const val START_TIMEOUT_MS = 4_000L
    }
}
