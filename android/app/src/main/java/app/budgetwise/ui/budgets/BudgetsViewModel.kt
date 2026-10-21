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
import kotlinx.coroutines.launch

/** One budget this month. [remainingKyat] is negative once spending passes the limit. */
data class BudgetLine(val category: String, val limitKyat: Long, val spentKyat: Long) {
    val remainingKyat: Long get() = limitKyat - spentKyat

    val over: Boolean get() = spentKyat > limitKyat

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
    val refreshing: Boolean = false,
) {
    val totalLimitKyat: Long get() = lines.sumOf { it.limitKyat }
    val totalSpentKyat: Long get() = lines.sumOf { it.spentKyat }

    /** "No budgets yet", only once the cache has answered and no refresh is still on its way. */
    val showEmpty: Boolean get() = !loading && !refreshing && lines.isEmpty()
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
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<BudgetsUiState> =
        combine(budgets.observe(), transactions.observe(), settings.myanmarDigits, refreshing) { limits, rows, digits, busy ->
            val month = YearMonth.from(LocalDate.now(clock))
            BudgetsUiState(
                month = month,
                loading = false,
                lines = budgetLines(limits, rows, month),
                myanmarDigits = digits,
                refreshing = busy,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState(month = YearMonth.from(LocalDate.now(clock))))

    init {
        refresh()
    }

    /** Fetches the server's budgets. Without a session the call could only fail, so the cache is shown as it is. */
    fun refresh() {
        if (refreshing.value || session.state.value !is SessionState.SignedIn) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                // A failed answer keeps the cache; the screen keeps showing it.
                budgets.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Writing the cache failed (Room); the old cache stays.
            } finally {
                refreshing.value = false
            }
        }
    }
}
