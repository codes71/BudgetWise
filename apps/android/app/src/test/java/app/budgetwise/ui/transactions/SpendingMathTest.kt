package app.budgetwise.ui.transactions

import app.budgetwise.data.model.DailySpend
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.testTransaction
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendingMathTest {
    private val today = LocalDate.parse("2026-10-23")

    // The design kit's sample: daily spending October 1 to 23 and all of September.
    private val october = listOf(
        9_800, 4_200, 15_400, 2_800, 0, 7_000, 5_600, 21_000, 3_500, 8_400, 0, 4_900, 11_200, 6_300, 26_400, 4_200, 7_700,
        2_100, 0, 9_100, 24_500, 8_200, 4_100,
    )
    private val september = listOf(
        12_000, 5_500, 9_000, 0, 14_500, 6_000, 3_000, 22_000, 7_500, 4_000, 10_500, 0, 8_000, 18_500, 5_000, 6_500,
        3_500, 9_500, 24_000, 7_000, 4_500, 15_000, 9_500, 6_000, 3_500, 8_000, 2_800, 4_500, 0, 7_000,
    )

    private fun days(month: LocalDate, amounts: List<Int>, category: String = "Groceries") =
        amounts.mapIndexedNotNull { i, a -> if (a == 0) null else DailySpend(month.withDayOfMonth(i + 1), category, a.toLong()) }

    private val kit = days(today, october) + days(today.minusMonths(1), september)

    @Test
    fun theKitsMonthIsEighteenThousandSixHundredBelowSeptember() {
        val pace = monthPace(kit, today)

        assertEquals(23, pace.running.size)
        assertEquals(9_800L, pace.running.first())
        assertEquals(186_400L, pace.spent)
        assertEquals(30, pace.lastRunning.size)
        assertEquals(236_800L, pace.lastRunning.last())
        assertEquals(205_000L, pace.lastBySameDay)
        assertEquals(PaceVerdict.Less(18_600), pace.verdict)
        // The chart's top is September's month end, the higher of the two lines.
        assertEquals(236_800L, pace.chartMax)
    }

    @Test
    fun runningTotalsCarryDaysWithoutSpending() {
        val pace = monthPace(kit, today)

        // October 5 has nothing: the total stays where October 4 left it.
        assertEquals(pace.running[3], pace.running[4])
        assertTrue(pace.running.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun aShorterLastMonthComparesWithItsLastDay() {
        // March 30 against February 2026, which ends on the 28th.
        val march30 = LocalDate.parse("2026-03-30")
        val daily = listOf(
            DailySpend(LocalDate.parse("2026-02-10"), "Groceries", 5_000),
            DailySpend(LocalDate.parse("2026-02-28"), "Groceries", 2_000),
            DailySpend(LocalDate.parse("2026-03-02"), "Groceries", 4_000),
        )
        val pace = monthPace(daily, march30)

        assertEquals(28, pace.lastRunning.size)
        assertEquals(7_000L, pace.lastBySameDay)
        assertEquals(PaceVerdict.Less(3_000), pace.verdict)
    }

    @Test
    fun aLongerLastMonthIsCutAtThisMonthsEnd() {
        // February 28 against January: January 29 to 31 lie past February's end, so they do not set the chart's top.
        val feb28 = LocalDate.parse("2026-02-28")
        val daily = listOf(
            DailySpend(LocalDate.parse("2026-01-05"), "Groceries", 10_000),
            DailySpend(LocalDate.parse("2026-01-30"), "Housing", 500_000),
            DailySpend(LocalDate.parse("2026-02-03"), "Groceries", 12_000),
        )
        val pace = monthPace(daily, feb28)

        assertEquals(31, pace.lastRunning.size)
        assertEquals(10_000L, pace.lastBySameDay)
        assertEquals(12_000L, pace.chartMax)
        assertEquals(PaceVerdict.More(2_000), pace.verdict)
    }

    @Test
    fun anEmptyMonthWithNothingLastMonthSaysNothingYet() {
        val pace = monthPace(emptyList(), today)

        assertEquals(List(23) { 0L }, pace.running)
        assertFalse(pace.hasLastMonth)
        assertNull(pace.lastBySameDay)
        assertEquals(PaceVerdict.NothingYet, pace.verdict)
        assertEquals(0L, pace.chartMax)
    }

    @Test
    fun withoutLastMonthItSaysWhatThisMonthHasSpent() {
        val pace = monthPace(days(today, october), today)

        assertFalse(pace.hasLastMonth)
        assertEquals(PaceVerdict.NoLastMonth(186_400), pace.verdict)
    }

    @Test
    fun nothingSpentYetAgainstALastMonthIsLess() {
        val pace = monthPace(days(today.minusMonths(1), september), today)

        assertEquals(PaceVerdict.Less(205_000), pace.verdict)
    }

    @Test
    fun theSameAmountSaysSame() {
        val daily = listOf(
            DailySpend(today.withDayOfMonth(2), "Groceries", 3_000),
            DailySpend(today.minusMonths(1).withDayOfMonth(20), "Health", 3_000),
        )

        assertEquals(PaceVerdict.Same, monthPace(daily, today).verdict)
    }

    @Test
    fun entriesDatedAfterTodayOrOutsideTheTwoMonthsAreLeftOut() {
        val daily = listOf(
            DailySpend(today.withDayOfMonth(2), "Groceries", 3_000),
            // Entered on the web for later this month, and two months back.
            DailySpend(today.plusDays(3), "Groceries", 90_000),
            DailySpend(today.minusMonths(2), "Groceries", 70_000),
        )
        val pace = monthPace(daily, today)

        assertEquals(3_000L, pace.spent)
        assertFalse(pace.hasLastMonth)
    }

    /** The kit's By category sample: October so far against September by the 23rd. */
    private val kitCategories = listOf(
        DailySpend(today.withDayOfMonth(1), "Groceries", 62_200),
        DailySpend(today.withDayOfMonth(2), "Food and Drink Item", 48_500),
        DailySpend(today.withDayOfMonth(3), "Utilities", 24_500),
        DailySpend(today.withDayOfMonth(4), "Transport", 21_251),
        DailySpend(today.withDayOfMonth(5), "Health", 12_500),
        DailySpend(today.withDayOfMonth(6), "Other", 10_449),
        DailySpend(today.withDayOfMonth(7), "Housing", 7_000),
        DailySpend(today.minusMonths(1).withDayOfMonth(1), "Groceries", 59_900),
        DailySpend(today.minusMonths(1).withDayOfMonth(2), "Food and Drink Item", 52_500),
        DailySpend(today.minusMonths(1).withDayOfMonth(3), "Utilities", 26_000),
        DailySpend(today.minusMonths(1).withDayOfMonth(4), "Transport", 18_800),
        DailySpend(today.minusMonths(1).withDayOfMonth(5), "Health", 21_000),
        DailySpend(today.minusMonths(1).withDayOfMonth(6), "Other", 26_800),
        // After the 23rd: not part of "by the same day".
        DailySpend(today.minusMonths(1).withDayOfMonth(28), "Groceries", 40_000),
    )

    @Test
    fun theKitsCategoriesAreOrderedByThisMonthWithTheirChange() {
        val breakdown = categoryBreakdown(kitCategories, today)

        assertEquals(
            listOf(
                CategoryPace("Groceries", 62_200, 59_900),
                CategoryPace("Food and Drink Item", 48_500, 52_500),
                CategoryPace("Utilities", 24_500, 26_000),
                CategoryPace("Transport", 21_251, 18_800),
                CategoryPace("Health", 12_500, 21_000),
            ),
            breakdown.top,
        )
        assertEquals(listOf(2_300L, -4_000L, -1_500L, 2_451L, -8_500L), breakdown.top.map { it.change })
        assertEquals(CategoryPace("", 17_449, 26_800), breakdown.other)
        assertEquals(-9_351L, breakdown.other!!.change)
        // Every bar on one scale: 62,200 rounds up to 65,000, so Groceries fills 95.7% of its track.
        assertEquals(65_000L, breakdown.scale)
    }

    @Test
    fun aCategorySpentOnOnlyLastMonthGoesUnderOtherCategories() {
        val daily = listOf(
            DailySpend(today.withDayOfMonth(2), "Groceries", 3_000),
            DailySpend(today.minusMonths(1).withDayOfMonth(2), "Entertainment", 15_000),
        )
        val breakdown = categoryBreakdown(daily, today)

        assertEquals(listOf("Groceries"), breakdown.top.map { it.category })
        assertEquals(listOf(CategoryPace("Entertainment", 0, 15_000)), breakdown.rest)
    }

    @Test
    fun aMonthWithNoSpendingHasNoCategories() {
        val breakdown = categoryBreakdown(emptyList(), today)

        assertTrue(breakdown.top.isEmpty())
        assertNull(breakdown.other)
        assertEquals(1L, breakdown.scale)
    }

    @Test
    fun niceCeilingRoundsUpInStepsOfFiveInTheSecondDigit() {
        assertEquals(65_000L, niceCeiling(62_200))
        assertEquals(1_500L, niceCeiling(1_234))
        assertEquals(10_000L, niceCeiling(9_800))
        assertEquals(50L, niceCeiling(50))
        assertEquals(7L, niceCeiling(7))
        assertEquals(1L, niceCeiling(0))
    }

    @Test
    fun rowsWaitingForUndoLeaveTheChart() {
        val tea = testTransaction("tea", 1_500, today, category = "Food and Drink Item")
        val salary = testTransaction("salary", 500_000, today, type = TxType.INCOME, category = "Income")
        val daily = listOf(DailySpend(today, "Food and Drink Item", 4_000), DailySpend(today, "Groceries", 2_000))

        assertEquals(
            listOf(DailySpend(today, "Food and Drink Item", 2_500), DailySpend(today, "Groceries", 2_000)),
            withoutRows(daily, listOf(tea, salary)),
        )
    }
}
