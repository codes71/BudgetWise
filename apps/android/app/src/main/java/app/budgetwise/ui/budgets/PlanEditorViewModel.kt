package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.BudgetSource
import app.budgetwise.CategorySource
import app.budgetwise.MonthPlanSource
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.di.DeviceClock
import app.budgetwise.ui.common.LAKH
import app.budgetwise.ui.common.typedKyatDigits
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlanStep { TOTAL, SPLIT }

/** Where the first split came from: this month's plan, last month's, the legacy budgets, or nowhere. */
enum class StartedFrom { PLAN, PREVIOUS, LEGACY, NOTHING }

/** Why a save failed; the form keeps everything typed. */
enum class SaveError { NETWORK, SPLIT_OVER_TOTAL, UNKNOWN_CATEGORY, SIGN_IN, OTHER }

/**
 * What the month before can add to this one: its checked on-hand amount ([checkedOn] is the day it was checked, in the
 * phone's zone), else its computed leftover (not checked yet). [saved] when it is the amount this month's plan was
 * saved with, kept as it was.
 */
data class Carry(val from: YearMonth, val amountKyat: Long, val checkedOn: LocalDate?, val saved: Boolean = false)

/** One category's share as typed: plain digits, empty while it has no amount. */
data class ShareDraft(val category: String, val digits: String) {
    val amountKyat: Long get() = digits.toLongOrNull() ?: 0L

    /** A share needs more than 0 to be saved. */
    val missing: Boolean get() = amountKyat <= 0L
}

/** What was spent in [month], for "What you spent before". */
data class MonthSpent(val month: YearMonth, val amountKyat: Long)

data class PlanEditorUiState(
    val month: YearMonth,
    /** [month] is the phone's current month: step 1 asks about "this month". */
    val isThisMonth: Boolean = false,
    /** False while the starting values are read; the form waits, so nothing typed is ever replaced. */
    val loaded: Boolean = false,
    val step: PlanStep = PlanStep.TOTAL,
    /** The new money for the month as typed: plain digits, shown grouped. */
    val newMoneyDigits: String = "",
    val carry: Carry? = null,
    val carryTicked: Boolean = false,
    /** In the order the user set them; saved in this order. */
    val shares: List<ShareDraft> = emptyList(),
    val startedFrom: StartedFrom = StartedFrom.NOTHING,
    /** The share whose sheet is open. */
    val editing: String? = null,
    /** The Add a category sheet is open. */
    val addingCategory: Boolean = false,
    /** The share changed last: marked when the split goes over the month. */
    val lastChanged: String? = null,
    /** The user's expense categories, for Add a category. */
    val categories: List<String> = emptyList(),
    /** Each category's spending in the month before [month]. */
    val lastMonthSpent: Map<String, Long> = emptyMap(),
    /** Each category's average over the months before [month] that had spending; empty with fewer than two. */
    val averageSpent: Map<String, Long> = emptyMap(),
    /** Up to three months before [month], newest first, with what was spent; months without spending left out. */
    val spentBefore: List<MonthSpent> = emptyList(),
    val saving: Boolean = false,
    val saveError: SaveError? = null,
    /** The server's unknown category, while its share is still in the split: its row is marked. */
    val unknownCategory: String? = null,
    /** The plans could not be fetched before the editor opened: it started from what the phone had. */
    val stale: Boolean = false,
    val myanmarDigits: Boolean = false,
) {
    val previousMonth: YearMonth get() = month.minusMonths(1)

    val newMoneyKyat: Long get() = newMoneyDigits.toLongOrNull() ?: 0L

    /** What is carried over: the carry's amount while its box is ticked. */
    val carriedKyat: Long get() = if (carryTicked) carry?.amountKyat ?: 0L else 0L

    val totalKyat: Long get() = newMoneyKyat + carriedKyat

    val splitKyat: Long get() = shares.sumOf { it.amountKyat }

    /** Left to split, shown as Everything else; negative when the shares add up to more than the total. */
    val unsplitKyat: Long get() = totalKyat - splitKyat

    val over: Boolean get() = splitKyat > totalKyat

    /** Shares without an amount; while there are any, Save waits and their rows are marked. */
    val missingShares: List<String> get() = shares.filter { it.missing }.map { it.category }

    /** The new money that makes the total equal the split: "Raise the month to X". */
    val raisedNewMoneyKyat: Long get() = splitKyat - carriedKyat

    /** Categories without a share, for Add a category. A plan takes at most [MAX_SHARES]. */
    val unshared: List<String>
        get() = if (shares.size >= MAX_SHARES) emptyList() else categories.filter { c -> shares.none { it.category == c } }

    val canGoOn: Boolean get() = loaded && totalKyat > 0

    val canSave: Boolean get() = loaded && !saving && totalKyat > 0 && !over && missingShares.isEmpty()

    companion object {
        /** The server's limit on shares in one plan. */
        const val MAX_SHARES = 50
    }
}

sealed interface PlanEditorEvent {
    data object Saved : PlanEditorEvent
}

/** The starting values of the editor. */
internal data class PlanStart(
    val newMoneyDigits: String,
    val shares: List<ShareDraft>,
    val startedFrom: StartedFrom,
    val carry: Carry?,
    val carryTicked: Boolean,
)

/**
 * The editor's starting values for [month]: its own plan if it has one; else last month's new money and shares; else
 * the legacy budgets as shares, with the new money left for the user. The carry card is there when last month has a
 * plan (or this plan already carries something) and something would be carried. A saved plan that carries something
 * keeps that amount, ticked. Otherwise it is last month's checked on-hand amount when there is one, else last month's
 * computed leftover, never below 0, ticked only when checked and the plan is new. [tickCarry] (straight after the end
 * check) takes the checked amount and ticks it, also on a saved plan.
 */
internal fun planStart(
    month: YearMonth,
    existing: MonthPlan?,
    previous: MonthPlan?,
    legacy: List<Budget>,
    previousSpentKyat: Long,
    tickCarry: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): PlanStart {
    fun drafts(shares: List<Budget>) = shares.filter { it.limitKyat > 0 }.distinctBy { it.category }.map { ShareDraft(it.category, it.limitKyat.toString()) }
    fun dayOf(check: EndCheck?) = check?.checkedAt?.atZone(zone)?.toLocalDate()
    val (newMoney, shares, from) = when {
        existing != null -> Triple(existing.newMoneyKyat, drafts(existing.shares), StartedFrom.PLAN)
        previous != null -> Triple(previous.newMoneyKyat, drafts(previous.shares), StartedFrom.PREVIOUS)
        else -> {
            val legacyShares = drafts(legacy.filter { it.category != CategoryRepository.INCOME })
            Triple(0L, legacyShares, if (legacyShares.isEmpty()) StartedFrom.NOTHING else StartedFrom.LEGACY)
        }
    }
    // A saved plan keeps what it carries, unless the user comes straight from the end check to carry what they have.
    val keepSaved = existing != null && existing.carriedOverKyat > 0 && !tickCarry
    val carry = when {
        keepSaved -> Carry(
            from = month.minusMonths(1),
            amountKyat = existing.carriedOverKyat,
            checkedOn = previous?.endCheck?.takeIf { it.onHandKyat == existing.carriedOverKyat }?.let(::dayOf),
            saved = true,
        )
        previous != null -> Carry(
            from = previous.month,
            amountKyat = previous.endCheck?.onHandKyat ?: (previous.totalKyat - previousSpentKyat).coerceAtLeast(0),
            checkedOn = dayOf(previous.endCheck),
        )
        else -> null
    }?.takeIf { it.amountKyat > 0 }
    val ticked = carry != null && (keepSaved || tickCarry || (existing == null && carry.checkedOn != null))
    return PlanStart(if (newMoney > 0) newMoney.toString() else "", shares, from, carry, ticked)
}

/** Spending before [month], for the hints: last month per category, the averages, and the months' totals. */
internal data class SpendStats(val lastMonth: Map<String, Long>, val average: Map<String, Long>, val before: List<MonthSpent>)

/**
 * The three months before [month]. Only months with spending count, so a user who started last month is not shown a
 * third of what they spend; an average needs two such months (with one it would only repeat last month's figure), and
 * is rounded to the nearest 1,000 Ks from 1,000 up, as a suggestion should be.
 */
internal fun spendStats(rows: List<Transaction>, month: YearMonth): SpendStats {
    val months = (1L..3L).map { month.minusMonths(it) }
    val perMonth = months.associateWith { spentByCategory(rows, it) }
    val active = months.filter { m -> perMonth.getValue(m).values.any { it > 0 } }
    val average = if (active.size < 2) {
        emptyMap()
    } else {
        active.flatMap { perMonth.getValue(it).keys }.toSet().associateWith { category ->
            val sum = active.sumOf { perMonth.getValue(it)[category] ?: 0L }
            val mean = (sum + active.size / 2) / active.size
            if (mean >= 1_000) (mean + 500) / 1_000 * 1_000 else mean
        }.filterValues { it > 0 }
    }
    return SpendStats(
        lastMonth = perMonth.getValue(month.minusMonths(1)),
        average = average,
        before = active.map { MonthSpent(it, perMonth.getValue(it).values.sum()) },
    )
}

/**
 * The plan editor for one month, in two steps: the month's total (new money, plus what last month left when ticked),
 * then its split into category shares. Saving needs the network; a failed save keeps everything typed.
 */
@HiltViewModel
class PlanEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val plans: MonthPlanSource,
    private val budgets: BudgetSource,
    categories: CategorySource,
    private val transactions: TransactionStore,
    settings: SettingsStore,
    @DeviceClock clock: Clock,
) : ViewModel() {
    val month: YearMonth = handle.get<String>(ARG_MONTH)?.let(::parseMonth) ?: YearMonth.now(clock)
    private val zone: ZoneId = clock.zone
    private val tickCarry: Boolean = handle.get<Boolean>(ARG_TICK_CARRY) ?: false

    private val _state = MutableStateFlow(PlanEditorUiState(month = month, isThisMonth = month == YearMonth.now(clock)))
    val state: StateFlow<PlanEditorUiState> = _state.asStateFlow()

    private val _events = Channel<PlanEditorEvent>(Channel.BUFFERED)
    val events: Flow<PlanEditorEvent> = _events.receiveAsFlow()

    private val rows: Flow<List<Transaction>> = transactions.observe().catch { emit(emptyList()) }

    init {
        viewModelScope.launch {
            // The starting values come from the server's plans when it can be reached; the form waits for them.
            val (plansFetched, legacyFetched) = coroutineScope {
                val fetchedPlans = async { fetched { plans.refresh(month.minusMonths(1), month) } }
                val fetchedLegacy = async { fetched { budgets.refresh() } }
                fetchedPlans.await() to fetchedLegacy.await()
            }
            var usedLegacy = false
            val start = try {
                val existing = plans.observe(month).first()
                val previous = plans.observe(month.minusMonths(1)).first()
                usedLegacy = existing == null && previous == null
                val legacy = if (usedLegacy) budgets.observe().first() else emptyList()
                planStart(month, existing, previous, legacy, spentIn(rows.first(), month.minusMonths(1)), tickCarry, zone)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The cache could not be read (Room): an empty plan, which the user can still fill in and save.
                planStart(month, null, null, emptyList(), 0)
            }
            _state.update {
                it.copy(
                    loaded = true,
                    stale = !plansFetched || (usedLegacy && !legacyFetched),
                    newMoneyDigits = start.newMoneyDigits,
                    shares = start.shares,
                    startedFrom = start.startedFrom,
                    carry = start.carry,
                    carryTicked = start.carryTicked,
                )
            }
        }
        viewModelScope.launch {
            combine(rows, categories.categories, settings.myanmarDigits) { all, names, digits ->
                Triple(spendStats(all, month), names.filter { it != CategoryRepository.INCOME }.distinct(), digits)
            }.collect { (stats, names, digits) ->
                _state.update {
                    it.copy(
                        lastMonthSpent = stats.lastMonth,
                        averageSpent = stats.average,
                        spentBefore = stats.before,
                        categories = names,
                        myanmarDigits = digits,
                    )
                }
            }
        }
    }

    /** True when [call] fetched; a failure, or a cache write that failed, leaves the cache as it was. */
    private suspend fun fetched(call: suspend () -> ApiResult<Unit>): Boolean = try {
        call() is ApiResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun onNewMoneyChange(text: String) {
        // A decimal point or an amount too large is refused: the field keeps what it had.
        val digits = typedKyatDigits(text) ?: return
        _state.update { it.copy(newMoneyDigits = digits, saveError = null).settled() }
    }

    /** The 10 / 15 / 20 / 25 lakh chips. */
    fun onPickLakh(lakhs: Long) = _state.update { it.copy(newMoneyDigits = (lakhs * LAKH).toString(), saveError = null).settled() }

    fun onCarryTicked(ticked: Boolean) = _state.update { it.copy(carryTicked = ticked, saveError = null).settled() }

    /** Step 1's Next: on to the split, once the month has a total. */
    fun next() = _state.update { if (it.canGoOn) it.copy(step = PlanStep.SPLIT) else it }

    /** Back from the split goes to the total; true when it did, false when the editor should close. */
    fun back(): Boolean {
        if (_state.value.step != PlanStep.SPLIT) return false
        _state.update { it.copy(step = PlanStep.TOTAL, editing = null, addingCategory = false) }
        return true
    }

    fun openShare(category: String) = _state.update { it.copy(editing = category, addingCategory = false) }

    fun closeShare() = _state.update { it.copy(editing = null) }

    fun onShareChange(category: String, text: String) {
        val digits = typedKyatDigits(text) ?: return
        setShare(category, digits)
    }

    /** A chip in the share sheet: last month's spending, the average, or all that is left. */
    fun onShareAmount(category: String, amountKyat: Long) = setShare(category, amountKyat.coerceAtLeast(0).toString())

    private fun setShare(category: String, digits: String) = _state.update { s ->
        s.copy(
            shares = s.shares.map { if (it.category == category) it.copy(digits = digits) else it },
            lastChanged = category,
            saveError = null,
        ).settled()
    }

    /** Takes the category out of the split: its spending counts against Everything else again. */
    fun removeShare(category: String) = _state.update { s ->
        s.copy(
            shares = s.shares.filterNot { it.category == category },
            editing = if (s.editing == category) null else s.editing,
            lastChanged = if (s.lastChanged == category) null else s.lastChanged,
            saveError = null,
        ).settled()
    }

    fun openAddCategory() = _state.update { it.copy(addingCategory = true, editing = null) }

    fun closeAddCategory() = _state.update { it.copy(addingCategory = false) }

    /** Adds [category] with no amount yet and opens its share sheet. */
    fun addCategory(category: String) = _state.update { s ->
        if (s.shares.any { it.category == category } || s.shares.size >= PlanEditorUiState.MAX_SHARES) {
            s.copy(addingCategory = false)
        } else {
            s.copy(shares = s.shares + ShareDraft(category, ""), addingCategory = false, editing = category, lastChanged = category, saveError = null).settled()
        }
    }

    /** "Raise the month to X": the new money that makes the total equal the split. */
    fun raiseMonth() = _state.update { s ->
        if (!s.over) s else s.copy(newMoneyDigits = s.raisedNewMoneyKyat.toString(), saveError = null).settled()
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, saveError = null, unknownCategory = null) }
        val plan = MonthPlan(month, s.newMoneyKyat, s.carriedKyat, s.shares.map { Budget(it.category, it.amountKyat) })
        viewModelScope.launch {
            val result = try {
                plans.save(plan)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The server's answer could not be cached (Room).
                ApiResult.Failure("local", "Could not save the plan on the phone", retryable = true)
            }
            when (result) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(saving = false) }
                    _events.send(PlanEditorEvent.Saved)
                }
                is ApiResult.Unauthorized -> _state.update { it.copy(saving = false, saveError = SaveError.SIGN_IN) }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        saving = false,
                        saveError = saveErrorFor(result.code),
                        unknownCategory = if (result.code == UNKNOWN_CATEGORY) unknownCategoryIn(result.msg, it.shares) else null,
                    )
                }
            }
        }
    }

    /**
     * A change clears the save error, except the unknown category's: it stays, with its row marked, until that share
     * is taken out of the split.
     */
    private fun PlanEditorUiState.settled(): PlanEditorUiState {
        val unknown = unknownCategory?.takeIf { name -> shares.any { it.category == name } }
        return if (unknown != null) copy(saveError = SaveError.UNKNOWN_CATEGORY) else copy(unknownCategory = null)
    }

    companion object {
        private const val UNKNOWN_CATEGORY = "unknown_category"

        /** The server says "Unknown category: Health"; the name, when it is one of [shares]. */
        internal fun unknownCategoryIn(message: String, shares: List<ShareDraft>): String? {
            val name = message.substringAfter("Unknown category:", missingDelimiterValue = "").trim()
            return name.takeIf { n -> n.isNotEmpty() && shares.any { it.category == n } }
        }

        /** The plan's month, "2026-11". */
        const val ARG_MONTH = "month"

        /** Opened straight from the end check: the carry starts ticked at the checked amount. */
        const val ARG_TICK_CARRY = "tickCarry"

        internal fun saveErrorFor(code: String): SaveError = when (code) {
            NETWORK_ERROR_CODE -> SaveError.NETWORK
            "split_over_total" -> SaveError.SPLIT_OVER_TOTAL
            UNKNOWN_CATEGORY -> SaveError.UNKNOWN_CATEGORY
            else -> SaveError.OTHER
        }

        internal fun parseMonth(text: String): YearMonth? = try {
            YearMonth.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
