package app.budgetwise.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.BudgetSource
import app.budgetwise.MonthPlanSource
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One share of this month's plan, or Everything else. [remainingKyat] is negative once spending passes the limit. */
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

/** A month that ended with a plan but no end check yet: the card that opens its check. [leftKyat] is below 0 when over. */
data class MonthToCheck(val month: YearMonth, val leftKyat: Long)

data class BudgetsUiState(
    val month: YearMonth,
    /** True until Room's first answer. */
    val loading: Boolean = true,
    /** This month's plan; null until one is set. */
    val plan: MonthPlan? = null,
    /** Every expense this month: the shares' spending and Everything else's together. */
    val spentKyat: Long = 0,
    /** One line per share, in the plan's order. */
    val lines: List<BudgetLine> = emptyList(),
    /** What was spent outside the shares, against what is not split; null when there is neither. */
    val everythingElse: BudgetLine? = null,
    /** Last month's expenses, under "Set {Month}'s budget". */
    val lastMonthSpentKyat: Long = 0,
    /** Last month had a plan and has not been checked. */
    val toCheck: MonthToCheck? = null,
    val myanmarDigits: Boolean = false,
    /** The pull-to-refresh spinner: only for a pull, not for the quiet fetch each time the tab opens. */
    val refreshing: Boolean = false,
    /** A fetch is on its way, quiet or pulled. */
    val fetching: Boolean = false,
) {
    val totalKyat: Long get() = plan?.totalKyat ?: 0

    /** What is left of the month's total; negative once the month is over budget. */
    val leftKyat: Long get() = totalKyat - spentKyat

    /** "One amount for the month", only once the cache has answered and no fetch is still on its way. */
    val showFirstPlan: Boolean get() = !loading && !fetching && plan == null
}

/** EXPENSE rows dated in [month], summed per category. Income never counts against a budget. */
internal fun spentByCategory(rows: List<Transaction>, month: YearMonth): Map<String, Long> =
    rows.asSequence()
        .filter { it.type == TxType.EXPENSE && YearMonth.from(it.date) == month }
        .groupingBy { it.category }
        .fold(0L) { sum, row -> sum + row.amountKyat }

/** Every EXPENSE in [month]: what a month's budget is spent on, shares and Everything else together. */
internal fun spentIn(rows: List<Transaction>, month: YearMonth): Long =
    rows.sumOf { if (it.type == TxType.EXPENSE && YearMonth.from(it.date) == month) it.amountKyat else 0L }

internal fun shareLines(plan: MonthPlan, spent: Map<String, Long>): List<BudgetLine> =
    plan.shares.map { BudgetLine(it.category, it.limitKyat, spent[it.category] ?: 0L) }

/**
 * Everything else: the part of the total no share has, against what was spent in categories without a share. Null
 * when nothing is unsplit and nothing was spent outside the shares, so the row would only say 0 of 0.
 */
internal fun everythingElseLine(plan: MonthPlan, spent: Map<String, Long>): BudgetLine? {
    val shared = plan.shares.mapTo(HashSet()) { it.category }
    val outside = spent.entries.sumOf { (category, amount) -> if (category in shared) 0L else amount }
    val unsplit = plan.unsplitKyat.coerceAtLeast(0)
    return if (unsplit == 0L && outside == 0L) null else BudgetLine(EVERYTHING_ELSE, unsplit, outside)
}

/** [BudgetLine.category] of the Everything else line: no category has an empty name. */
internal const val EVERYTHING_ELSE = ""

internal fun budgetsState(
    month: YearMonth,
    plans: List<MonthPlan>,
    rows: List<Transaction>,
    myanmarDigits: Boolean,
): BudgetsUiState {
    val previous = month.minusMonths(1)
    val plan = plans.firstOrNull { it.month == month }
    val lastPlan = plans.firstOrNull { it.month == previous }
    val spent = spentByCategory(rows, month)
    return BudgetsUiState(
        month = month,
        loading = false,
        plan = plan,
        spentKyat = spent.values.sum(),
        lines = plan?.let { shareLines(it, spent) }.orEmpty(),
        everythingElse = plan?.let { everythingElseLine(it, spent) },
        lastMonthSpentKyat = spentIn(rows, previous),
        // Last month is always over by now; the check can be saved once, after which the card goes.
        toCheck = lastPlan?.takeIf { it.endCheck == null }?.let { MonthToCheck(previous, it.totalKyat - spentIn(rows, previous)) },
        myanmarDigits = myanmarDigits,
    )
}

/**
 * This month's plan from the cache (refreshed with last month's when the tab opens and on pull to refresh), with what
 * was spent counted from the transactions in Room, so an entry saved offline counts at once. Plans are set in the plan
 * editor; the legacy per-category budgets are refreshed too, for voice answers and as an editor's first split.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val plans: MonthPlanSource,
    private val budgets: BudgetSource,
    transactions: TransactionStore,
    settings: SettingsStore,
    private val session: SessionManager,
    @DeviceClock private val clock: Clock,
) : ViewModel() {
    private data class Fetch(val running: Boolean = false, val pulled: Boolean = false)

    private val fetch = MutableStateFlow(Fetch())

    /** The month on screen, read again each time the tab opens, so it moves on at midnight on the 1st. */
    private val month = MutableStateFlow(YearMonth.now(clock))

    val state: StateFlow<BudgetsUiState> =
        month.flatMapLatest { m ->
            combine(plans.observeRange(m.minusMonths(1), m), transactions.observe(), settings.myanmarDigits, fetch) { cached, rows, digits, f ->
                budgetsState(m, cached, rows, digits).copy(refreshing = f.running && f.pulled, fetching = f.running)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState(month = month.value))

    init {
        // Starts before Room's first answer, so the first-plan screen never flashes on the first open. BudgetsRoute
        // asks again each time the tab opens; a request while one runs is ignored.
        load()
    }

    /** The quiet fetch each time the tab opens: no spinner, the cached plan stays on screen meanwhile. */
    fun load() {
        month.value = YearMonth.now(clock)
        fetch(pulled = false)
    }

    /** Pull to refresh: the spinner shows until the fetch ends. */
    fun refresh() = fetch(pulled = true)

    /**
     * Fetches last month's and this month's plans, and the legacy budgets. Without a session the calls could only
     * fail, so the cache is shown as it is.
     */
    private fun fetch(pulled: Boolean) {
        if (session.state.value !is SessionState.SignedIn) return
        if (fetch.value.running) {
            // A pull during the quiet fetch shows the spinner for the rest of it, instead of a second fetch.
            if (pulled) fetch.update { it.copy(pulled = true) }
            return
        }
        fetch.value = Fetch(running = true, pulled = pulled)
        val m = month.value
        viewModelScope.launch {
            try {
                coroutineScope {
                    // A failed answer keeps that cache; the screen keeps showing it.
                    launch { quietly { plans.refresh(m.minusMonths(1), m) } }
                    launch { quietly { budgets.refresh() } }
                }
            } finally {
                fetch.value = Fetch()
            }
        }
    }

    /** Writing a cache can fail (Room); the old cache stays, and the other fetch goes on. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
