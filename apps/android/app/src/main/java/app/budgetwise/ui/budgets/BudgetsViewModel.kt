package app.budgetwise.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.BudgetSource
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One budget this month. [remainingKyat] is negative once spending passes the limit. */
data class BudgetLine(val category: String, val limitKyat: Long, val spentKyat: Long) {
    val remainingKyat: Long get() = limitKyat - spentKyat

    val over: Boolean get() = spentKyat > limitKyat

    /**
     * Whole percent spent, rounded down, for the label: 53,000 of 100,000 is 53 (Float math gave 52), and it never
     * says 100 before the limit is reached. [progress] stays Float, for the bar only.
     */
    val percentUsed: Long
        get() = when {
            limitKyat > 0 -> if (spentKyat <= Long.MAX_VALUE / 100) spentKyat * 100 / limitKyat else spentKyat / limitKyat * 100
            spentKyat > 0 -> 100
            else -> 0
        }

    /** Share of the limit spent; above 1 when over. A zero limit counts as fully used by any spending. */
    val progress: Float
        get() = when {
            limitKyat > 0 -> spentKyat.toFloat() / limitKyat
            spentKyat > 0 -> 1f
            else -> 0f
        }
}

data class BudgetsUiState(
    val month: YearMonth,
    /** True until Room's first answer. */
    val loading: Boolean = true,
    val lines: List<BudgetLine> = emptyList(),
    val myanmarDigits: Boolean = false,
    /** The pull-to-refresh spinner: only for a pull, not for the quiet fetch each time the tab opens. */
    val refreshing: Boolean = false,
    /** A fetch is on its way, quiet or pulled. */
    val fetching: Boolean = false,
) {
    val totalLimitKyat: Long get() = lines.sumOf { it.limitKyat }
    val totalSpentKyat: Long get() = lines.sumOf { it.spentKyat }

    /** "No budgets yet", only once the cache has answered and no fetch is still on its way. */
    val showEmpty: Boolean get() = !loading && !fetching && lines.isEmpty()
}

/** EXPENSE rows dated in [month], summed per category. Income never counts against a budget. */
internal fun spentByCategory(rows: List<Transaction>, month: YearMonth): Map<String, Long> =
    rows.asSequence()
        .filter { it.type == TxType.EXPENSE && YearMonth.from(it.date) == month }
        .groupingBy { it.category }
        .fold(0L) { sum, row -> sum + row.amountKyat }

internal fun budgetLines(budgets: List<Budget>, rows: List<Transaction>, month: YearMonth): List<BudgetLine> {
    val spent = spentByCategory(rows, month)
    return budgets.map { BudgetLine(it.category, it.limitKyat, spent[it.category] ?: 0L) }
}

/**
 * Limits come from the server (cached in Room, refreshed when the screen opens and on pull to refresh); what was
 * spent is counted from the transactions in Room, so an entry saved offline counts at once. The API cannot change a
 * budget: they are set on the web.
 */
@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val budgets: BudgetSource,
    transactions: TransactionStore,
    settings: SettingsStore,
    private val session: SessionManager,
    @DeviceClock private val clock: Clock,
) : ViewModel() {
    private data class Fetch(val running: Boolean = false, val pulled: Boolean = false)

    private val fetch = MutableStateFlow(Fetch())

    val state: StateFlow<BudgetsUiState> =
        combine(budgets.observe(), transactions.observe(), settings.myanmarDigits, fetch) { limits, rows, digits, f ->
            val month = YearMonth.from(LocalDate.now(clock))
            BudgetsUiState(
                month = month,
                loading = false,
                lines = budgetLines(limits, rows, month),
                myanmarDigits = digits,
                refreshing = f.running && f.pulled,
                fetching = f.running,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState(month = YearMonth.from(LocalDate.now(clock))))

    init {
        // Starts before Room's first answer, so "No budgets yet" never flashes on the first open. BudgetsRoute asks
        // again each time the tab opens; a request while one runs is ignored.
        load()
    }

    /** The quiet fetch each time the tab opens: no spinner, the cached budgets stay on screen meanwhile. */
    fun load() = fetch(pulled = false)

    /** Pull to refresh: the spinner shows until the fetch ends. */
    fun refresh() = fetch(pulled = true)

    /** Fetches the server's budgets. Without a session the call could only fail, so the cache is shown as it is. */
    private fun fetch(pulled: Boolean) {
        if (session.state.value !is SessionState.SignedIn) return
        if (fetch.value.running) {
            // A pull during the quiet fetch shows the spinner for the rest of it, instead of a second fetch.
            if (pulled) fetch.update { it.copy(pulled = true) }
            return
        }
        fetch.value = Fetch(running = true, pulled = pulled)
        viewModelScope.launch {
            try {
                // A failed answer keeps the cache; the screen keeps showing it.
                budgets.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Writing the cache failed (Room); the old cache stays.
            } finally {
                fetch.value = Fetch()
            }
        }
    }
}
