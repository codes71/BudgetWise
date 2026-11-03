package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.MonthPlanSource
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.di.ApplicationScope
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.transactions.DayLine
import app.budgetwise.ui.transactions.DaySection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A deletion handed back from an entry editor can arrive before this list has subscribed to Room. */
data class BudgetPendingDelete(val clientId: String, val row: Transaction? = null)

/** One month's expenses contributing to a category share, with the same entry actions as Home. */
data class BudgetTransactionsUiState(
    val month: YearMonth,
    val category: String,
    val today: LocalDate,
    val loading: Boolean = true,
    val line: BudgetLine? = null,
    val days: List<DaySection> = emptyList(),
    val myanmarDigits: Boolean = false,
    val pendingDelete: BudgetPendingDelete? = null,
    val restored: Map<String, Int> = emptyMap(),
) {
    val count: Int get() = days.sumOf { it.rows.size }
}

/**
 * Expenses only, dated within the plan's month. Everything else follows the current plan's unallocated categories.
 * Receipt entries stay individual here: opening or deleting Groceries must never change that receipt's Transport.
 */
internal fun budgetTransactions(rows: List<Transaction>, month: YearMonth, category: String, plan: MonthPlan?): List<Transaction> {
    if (plan == null) return emptyList()
    val shared = plan.shares.mapTo(HashSet()) { it.category }
    return rows.filter {
        it.type == TxType.EXPENSE && YearMonth.from(it.date) == month &&
            if (category == EVERYTHING_ELSE) it.category !in shared else it.category == category
    }.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.updatedAt })
}

/**
 * Reads the same account-scoped Room ledger as Budgets. Edits made in the existing entry form update this list and
 * both budget totals immediately, including offline. Limits are never changed by a transaction edit or deletion.
 */
@HiltViewModel
class BudgetTransactionsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    plans: MonthPlanSource,
    private val transactions: TransactionStore,
    settings: SettingsStore,
    @DeviceClock private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val month = YearMonth.parse(checkNotNull(savedState.get<String>(ARG_MONTH)))
    private val category = savedState.get<String>(ARG_CATEGORY).orEmpty()
    private val today = MutableStateFlow(LocalDate.now(clock))

    private data class Local(
        val hidden: Set<String> = emptySet(),
        val pending: BudgetPendingDelete? = null,
        val restored: Map<String, Int> = emptyMap(),
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<BudgetTransactionsUiState> =
        combine(plans.observe(month), transactions.observe(), settings.myanmarDigits, local, today) { plan, rows, digits, l, day ->
            val visible = budgetTransactions(rows.filterNot { it.clientId in l.hidden }, month, category, plan)
            val spent = visible.sumOf { it.amountKyat }
            val limit = if (category == EVERYTHING_ELSE) plan?.unsplitKyat?.coerceAtLeast(0) else plan?.shares?.firstOrNull { it.category == category }?.limitKyat
            BudgetTransactionsUiState(
                month = month,
                category = category,
                today = day,
                loading = false,
                line = limit?.let { BudgetLine(category, it, spent) },
                // Never group a receipt: every row represents only the amount counted in this budget.
                days = visible.groupBy { it.date }.map { (date, entries) -> DaySection(date, entries, entries.map { DayLine.Entry(it) }) },
                myanmarDigits = digits,
                pendingDelete = l.pending?.let { pending -> pending.copy(row = rows.firstOrNull { it.clientId == pending.clientId } ?: pending.row) },
                restored = l.restored,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetTransactionsUiState(month, category, today.value))

    /** Refresh Today/Yesterday labels after returning overnight, without changing the selected budget month. */
    fun refreshToday() {
        today.value = LocalDate.now(clock)
    }

    /** Hide just this contributing entry while its Undo notice is showing. */
    fun delete(clientId: String) {
        val row = state.value.days.flatMap { it.rows }.firstOrNull { it.clientId == clientId } ?: return
        queueDelete(BudgetPendingDelete(clientId, row))
    }

    /**
     * The existing editor already loaded this account's entry. Its Delete action must work even when process
     * restoration created this ViewModel behind the editor and the list has never collected [state].
     */
    fun deleteFromEditor(clientId: String) {
        val row = state.value.days.flatMap { it.rows }.firstOrNull { it.clientId == clientId }
        queueDelete(BudgetPendingDelete(clientId, row))
    }

    private fun queueDelete(pending: BudgetPendingDelete) {
        if (local.value.pending?.clientId == pending.clientId) return
        val previous = local.value.pending
        local.update { it.copy(hidden = it.hidden + pending.clientId, pending = pending) }
        previous?.let { send(it.clientId) }
    }

    fun undoDelete(clientId: String) {
        if (local.value.pending?.clientId != clientId) return
        local.update { it.copy(hidden = it.hidden - clientId, pending = null, restored = it.restored + (clientId to (it.restored[clientId] ?: 0) + 1)) }
    }

    fun commitDelete(clientId: String) {
        if (local.value.pending?.clientId != clientId) return
        local.update { it.copy(pending = null) }
        send(clientId)
    }

    fun commitPendingDelete() {
        local.value.pending?.let { commitDelete(it.clientId) }
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
                // The entry is still in Room after a failed local write, so restore it and its spending.
                local.update { it.copy(hidden = it.hidden - clientId, restored = it.restored + (clientId to (it.restored[clientId] ?: 0) + 1)) }
            }
        }
    }

    companion object {
        const val ARG_MONTH = "budgetMonth"
        const val ARG_CATEGORY = "budgetCategory"
    }
}
