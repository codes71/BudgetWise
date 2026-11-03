package app.budgetwise.ui.budgets

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.TestUser
import app.budgetwise.testing.testTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-10-21").atTime(9, 30).atZone(zone).toInstant(), zone)
    private val october = YearMonth.of(2026, 10)
    private val september = YearMonth.of(2026, 9)
    private val plans = FakeMonthPlanSource()
    private val budgets = FakeBudgetSource()
    private val store = FakeTransactionStore()
    private val settings = FakeSettingsStore()

    private fun d(s: String) = LocalDate.parse(s)

    private fun session(expiresIn: Duration = Duration.ofDays(20)) =
        SessionManager(FakeTokenStore(StoredSession("tok", clock.instant().plus(expiresIn), TestUser)), clock)
            .also { runBlocking { it.load() } }

    private fun TestScope.vm(session: SessionManager = session()) =
        BudgetsViewModel(plans, budgets, store, settings, session, clock).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        }

    private val octoberPlan = MonthPlan(
        october,
        newMoneyKyat = 1_800_000,
        carriedOverKyat = 200_000,
        shares = listOf(Budget("Groceries", 350_000), Budget("Transport", 150_000), Budget("Housing", 500_000)),
    )

    @Test
    fun sharesAndEverythingElseCountOnlyThisMonthsExpenses() = runTest {
        plans.plans.value = listOf(octoberPlan)
        store.rows.value = listOf(
            testTransaction("rice", 100_000, d("2026-10-01")),
            testTransaction("oil", 50_000, d("2026-10-21")),
            testTransaction("salary", 900_000, d("2026-10-10"), type = TxType.INCOME, category = "Income"),
            testTransaction("last month", 3_000, d("2026-09-30")),
            testTransaction("next month", 2_000, d("2026-11-01")),
            testTransaction("taxi", 162_400, d("2026-10-15"), category = "Transport"),
            // No share for these: they count against Everything else.
            testTransaction("doctor", 40_000, d("2026-10-12"), category = "Health"),
            testTransaction("film", 18_900, d("2026-10-13"), category = "Entertainment"),
        )

        val state = vm().state.value
        val lines = state.lines.associateBy { it.category }

        assertEquals(october, state.month)
        assertEquals(octoberPlan, state.plan)
        // The plan's order; the screen sorts.
        assertEquals(listOf("Groceries", "Transport", "Housing"), state.lines.map { it.category })
        assertEquals(150_000, lines.getValue("Groceries").spentKyat)
        assertEquals(200_000, lines.getValue("Groceries").remainingKyat)
        assertEquals(162_400, lines.getValue("Transport").spentKyat)
        assertTrue(lines.getValue("Transport").over)
        assertEquals(0, lines.getValue("Housing").spentKyat)

        val rest = state.everythingElse!!
        assertEquals(EVERYTHING_ELSE, rest.category)
        // 2,000,000 total minus 1,000,000 split.
        assertEquals(1_000_000, rest.limitKyat)
        assertEquals(58_900, rest.spentKyat)

        assertEquals(2_000_000, state.totalKyat)
        assertEquals(371_300, state.spentKyat)
        assertEquals(1_628_700, state.leftKyat)
        assertFalse(state.showFirstPlan)
    }

    @Test
    fun everythingElseIsHiddenWhenTheSplitTakesTheMonthAndNothingWasSpentOutsideIt() = runTest {
        plans.plans.value = listOf(MonthPlan(october, 500_000, 0, listOf(Budget("Groceries", 500_000))))
        store.rows.value = listOf(testTransaction("rice", 10_000, d("2026-10-01")))

        val vm = vm()
        assertNull(vm.state.value.everythingElse)

        // Spending outside the shares shows it again, over its 0.
        store.rows.value += testTransaction("doctor", 4_000, d("2026-10-02"), category = "Health")
        val rest = vm.state.value.everythingElse!!
        assertEquals(0, rest.limitKyat)
        assertTrue(rest.over)
    }

    @Test
    fun aMonthOverItsTotalHasANegativeLeftover() = runTest {
        plans.plans.value = listOf(MonthPlan(october, 100_000, 0, emptyList()))
        store.rows.value = listOf(testTransaction("rent", 130_000, d("2026-10-01"), category = "Housing"))

        val state = vm().state.value

        assertEquals(-30_000, state.leftKyat)
        assertEquals(BudgetLine(EVERYTHING_ELSE, 100_000, 130_000), state.everythingElse)
    }

    @Test
    fun withoutAPlanTheFirstPlanScreenWaitsForTheFetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate
        store.rows.value = listOf(
            testTransaction("rice", 1_842_300, d("2026-09-10")),
            testTransaction("salary", 900_000, d("2026-09-01"), type = TxType.INCOME, category = "Income"),
        )
        val vm = vm()

        // No plan cached yet: the first-plan screen waits for the answer instead of flashing.
        assertTrue(vm.state.value.fetching)
        assertFalse(vm.state.value.showFirstPlan)

        gate.complete(Unit)

        assertTrue(vm.state.value.showFirstPlan)
        assertNull(vm.state.value.plan)
        assertEquals(1_842_300, vm.state.value.lastMonthSpentKyat)
    }

    @Test
    fun aPlanThatArrivesReplacesTheFirstPlanScreen() = runTest {
        val vm = vm()
        assertTrue(vm.state.value.showFirstPlan)

        plans.plans.value = listOf(octoberPlan)

        assertFalse(vm.state.value.showFirstPlan)
        assertEquals(octoberPlan, vm.state.value.plan)
    }

    @Test
    fun lastMonthWithAPlanAndNoCheckOffersTheCheck() = runTest {
        plans.plans.value = listOf(MonthPlan(september, 2_000_000, 0, listOf(Budget("Housing", 500_000))))
        store.rows.value = listOf(
            testTransaction("rent", 500_000, d("2026-09-01"), category = "Housing"),
            testTransaction("rice", 1_156_500, d("2026-09-20")),
            testTransaction("this month", 7_000, d("2026-10-02")),
        )

        val vm = vm()

        assertEquals(MonthToCheck(september, 343_500), vm.state.value.toCheck)
        // Also on the first-plan screen: this month has no plan yet.
        assertTrue(vm.state.value.showFirstPlan)

        // Once checked, the card goes.
        plans.plans.value = listOf(plans.plans.value.single().copy(endCheck = EndCheck(343_500, Instant.EPOCH)))
        assertNull(vm.state.value.toCheck)
    }

    @Test
    fun aMonthThatEndedOverItsBudgetIsOfferedForCheckingToo() = runTest {
        plans.plans.value = listOf(MonthPlan(september, 100_000, 0, emptyList()), octoberPlan)
        store.rows.value = listOf(testTransaction("rice", 120_000, d("2026-09-20")))

        assertEquals(MonthToCheck(september, -20_000), vm().state.value.toCheck)
    }

    @Test
    fun noCheckWithoutLastMonthsPlan() = runTest {
        plans.plans.value = listOf(octoberPlan, MonthPlan(YearMonth.of(2026, 8), 100_000, 0, emptyList()))

        assertNull(vm().state.value.toCheck)
    }

    @Test
    fun openingTheTabFetchesLastAndThisMonthsPlansAndTheLegacyBudgets() = runTest {
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate
        val vm = vm()

        assertEquals(listOf(september to october), plans.refreshes)
        assertEquals(1, budgets.refreshes)
        assertTrue(vm.state.value.fetching)
        // Opening the tab fetches quietly: the pull-to-refresh spinner is only for a pull.
        assertFalse(vm.state.value.refreshing)

        gate.complete(Unit)

        assertFalse(vm.state.value.fetching)
    }

    @Test
    fun aPullDuringTheQuietFetchShowsTheSpinnerWithoutFetchingTwice() = runTest {
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate
        val vm = vm()

        vm.refresh()

        assertEquals(1, plans.refreshes.size)
        assertTrue(vm.state.value.refreshing)

        gate.complete(Unit)

        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun pullToRefreshSpinsUntilTheFetchEnds() = runTest {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate

        vm.refresh()

        assertEquals(2, plans.refreshes.size)
        assertEquals(2, budgets.refreshes)
        assertTrue(vm.state.value.refreshing)

        gate.complete(Unit)

        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun aFailedPlanFetchKeepsTheCacheAndStillRefreshesTheLegacyBudgets() = runTest {
        plans.plans.value = listOf(octoberPlan)
        plans.refreshResult = ApiResult.Failure("network", "offline", retryable = true)

        val vm = vm()

        assertEquals(1, budgets.refreshes)
        assertFalse(vm.state.value.fetching)
        assertEquals(octoberPlan, vm.state.value.plan)
    }

    @Test
    fun withoutASessionShowsTheCacheWithoutCallingTheServer() = runTest {
        plans.plans.value = listOf(octoberPlan)
        val vm = vm(session(expiresIn = Duration.ofSeconds(-1)))

        vm.refresh()

        assertEquals(0, plans.refreshes.size)
        assertEquals(0, budgets.refreshes)
        assertFalse(vm.state.value.refreshing)
        assertEquals(octoberPlan, vm.state.value.plan)
    }

    @Test
    fun spendingExactlyTheLimitIsNotOver() {
        val line = BudgetLine("Groceries", limitKyat = 50_000, spentKyat = 50_000)
        assertFalse(line.over)
        assertEquals(0, line.remainingKyat)
        assertTrue(BudgetLine("Other", limitKyat = 0, spentKyat = 1).over)
        assertEquals(0f, BudgetLine("Other", limitKyat = 0, spentKyat = 0).progress, 0f)
    }

    @Test
    fun percentLabelIsWholeNumbersRoundedDown() {
        // Float math showed 52% for 53,000 of 100,000.
        assertEquals(53L, BudgetLine("Groceries", limitKyat = 100_000, spentKyat = 53_000).percentUsed)
        assertEquals(59L, BudgetLine("Groceries", limitKyat = 100_000, spentKyat = 59_000).percentUsed)
        assertEquals(106L, BudgetLine("Groceries", limitKyat = 100_000, spentKyat = 106_000).percentUsed)
        // Never "100%" before the limit is actually reached.
        assertEquals(99L, BudgetLine("Groceries", limitKyat = 1_000, spentKyat = 999).percentUsed)
        assertEquals(0L, BudgetLine("Groceries", limitKyat = 100_000, spentKyat = 0).percentUsed)
        assertEquals(100L, BudgetLine("Other", limitKyat = 0, spentKyat = 1).percentUsed)
        // No overflow for absurd sums.
        assertEquals(Long.MAX_VALUE / 1_000 * 100, BudgetLine("Other", limitKyat = 1_000, spentKyat = Long.MAX_VALUE).percentUsed)
    }
}
