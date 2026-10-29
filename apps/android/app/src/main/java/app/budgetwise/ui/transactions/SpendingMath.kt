package app.budgetwise.ui.transactions

import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min

// The month chart's numbers, kept apart from drawing so they can be tested on their own. "By the same day" always
// means the same day number in last month, or its last day when last month is shorter (March 30 compares with
// February 28).

/**
 * This month's spending so far next to last month's. [running] is this month's running total at the end of each day
 * from the 1st to today (index 0 is the 1st). [lastRunning] is last month's running total for every one of its days,
 * empty when last month has no spending at all (nothing to compare with).
 */
data class MonthPace(
    val today: LocalDate,
    val running: List<Long>,
    val lastRunning: List<Long>,
) {
    val month: YearMonth get() = YearMonth.from(today)
    val lastMonth: YearMonth get() = month.minusMonths(1)
    val spent: Long get() = running.lastOrNull() ?: 0L
    val hasLastMonth: Boolean get() = lastRunning.isNotEmpty()

    /** Last month's total by the same day, or null when there is nothing to compare with. */
    val lastBySameDay: Long? get() = if (hasLastMonth) lastRunning[sameDayIndex(today, lastRunning.size)] else null

    /** Positive when this month has spent more than last month had by the same day. */
    val difference: Long? get() = lastBySameDay?.let { spent - it }

    /** The highest point either line reaches on this month's axis: its top. Last month is cut at this month's end. */
    val chartMax: Long
        get() = maxOf(spent, lastRunning.take(month.lengthOfMonth()).maxOrNull() ?: 0L)

    companion object {
        fun empty(today: LocalDate) = MonthPace(today, List(today.dayOfMonth) { 0L }, emptyList())
    }
}

/** What the one-line verdict above the chart says. */
sealed interface PaceVerdict {
    data class Less(val amount: Long) : PaceVerdict
    data class More(val amount: Long) : PaceVerdict
    data object Same : PaceVerdict

    /** No spending last month to compare with; says what this month has spent so far. */
    data class NoLastMonth(val spent: Long) : PaceVerdict

    /** Nothing spent in either month. */
    data object NothingYet : PaceVerdict
}

val MonthPace.verdict: PaceVerdict
    get() {
        val difference = difference
        return when {
            difference == null -> if (spent == 0L) PaceVerdict.NothingYet else PaceVerdict.NoLastMonth(spent)
            difference < 0 -> PaceVerdict.Less(-difference)
            difference > 0 -> PaceVerdict.More(difference)
            else -> PaceVerdict.Same
        }
    }

private fun sameDayIndex(today: LocalDate, lastMonthLength: Int) = min(today.dayOfMonth, lastMonthLength) - 1

/** Running totals of [perDay] (index 0 is the 1st) over [days] days. */
private fun running(perDay: Map<Int, Long>, days: Int): List<Long> {
    var sum = 0L
    return List(days) { i ->
        sum += perDay[i + 1] ?: 0L
        sum
    }
}

/**
 * [daily] holds this and last month's spending; anything outside the two months, or after [today], is ignored.
 * Months of any length work: this month runs to today, last month to its own last day.
 */
fun monthPace(daily: List<DailySpend>, today: LocalDate): MonthPace {
    val month = YearMonth.from(today)
    val lastMonth = month.minusMonths(1)
    val thisMonth = HashMap<Int, Long>()
    val previous = HashMap<Int, Long>()
    for (d in daily) {
        when (YearMonth.from(d.date)) {
            month -> if (!d.date.isAfter(today)) thisMonth.merge(d.date.dayOfMonth, d.amountKyat, Long::plus)
            lastMonth -> previous.merge(d.date.dayOfMonth, d.amountKyat, Long::plus)
        }
    }
    val hasLastMonth = previous.values.any { it != 0L }
    return MonthPace(
        today = today,
        running = running(thisMonth, today.dayOfMonth),
        lastRunning = if (hasLastMonth) running(previous, lastMonth.lengthOfMonth()) else emptyList(),
    )
}

/** One category this month so far and last month by the same day. */
data class CategoryPace(val category: String, val spent: Long, val lastBySameDay: Long) {
    val change: Long get() = spent - lastBySameDay
}

/**
 * The categories this month: [top] are the largest so far (at most five, only ones with spending this month), [rest]
 * every other category spent on in either month, both ordered by this month's amount. [scale] is the end of the bars'
 * shared scale.
 */
data class CategoryBreakdown(val top: List<CategoryPace>, val rest: List<CategoryPace>) {
    /** [rest] as one row ("Other categories"), or null when there is none. */
    val other: CategoryPace?
        get() = if (rest.isEmpty()) null else CategoryPace("", rest.sumOf { it.spent }, rest.sumOf { it.lastBySameDay })

    /** One scale for every bar and tick: the largest of them, rounded up to a round number. */
    val scale: Long get() = niceCeiling(top.maxOfOrNull { maxOf(it.spent, it.lastBySameDay) } ?: 0L)

    companion object {
        val EMPTY = CategoryBreakdown(emptyList(), emptyList())
    }
}

fun categoryBreakdown(daily: List<DailySpend>, today: LocalDate, topCount: Int = 5): CategoryBreakdown {
    val month = YearMonth.from(today)
    val lastMonth = month.minusMonths(1)
    val lastDay = sameDayIndex(today, lastMonth.lengthOfMonth()) + 1
    val spent = HashMap<String, Long>()
    val last = HashMap<String, Long>()
    for (d in daily) {
        when (YearMonth.from(d.date)) {
            month -> if (!d.date.isAfter(today)) spent.merge(d.category, d.amountKyat, Long::plus)
            lastMonth -> if (d.date.dayOfMonth <= lastDay) last.merge(d.category, d.amountKyat, Long::plus)
        }
    }
    val all = (spent.keys + last.keys)
        .map { CategoryPace(it, spent[it] ?: 0L, last[it] ?: 0L) }
        .filter { it.spent != 0L || it.lastBySameDay != 0L }
        .sortedWith(compareByDescending<CategoryPace> { it.spent }.thenByDescending { it.lastBySameDay }.thenBy { it.category })
    val top = all.filter { it.spent > 0 }.take(topCount)
    return CategoryBreakdown(top, all - top.toSet())
}

/**
 * [value] rounded up to a step of 5 in its second digit (62,200 to 65,000; 1,234 to 1,500), so bars end short of the
 * card's edge on a round number. At least 1.
 */
fun niceCeiling(value: Long): Long {
    if (value <= 0) return 1
    val digits = value.toString().length
    if (digits < 2) return value
    var step = 5L
    repeat(digits - 2) { step *= 10 }
    return (value + step - 1) / step * step
}

/**
 * [daily] without the expense [rows] in it: rows swiped away and waiting for Undo leave the chart at once, as they
 * leave the list, before Room drops them.
 */
fun withoutRows(daily: List<DailySpend>, rows: Collection<Transaction>): List<DailySpend> {
    if (rows.isEmpty()) return daily
    val removed = HashMap<Pair<LocalDate, String>, Long>()
    for (row in rows) if (row.type == TxType.EXPENSE) removed.merge(row.date to row.category, row.amountKyat, Long::plus)
    return daily.map { d ->
        val minus = removed[d.date to d.category] ?: return@map d
        d.copy(amountKyat = d.amountKyat - minus)
    }
}
