package app.budgetwise.ui.budgets

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.model.Budget
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.TestUser
import app.budgetwise.testing.testTransaction
import java.time.Clock
import java.time.Duration
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-10-21").atTime(9, 30).atZone(zone).toInstant(), zone)
    private val budgets = FakeBudgetSource()
    private val store = FakeTransactionStore()
    private val settings = FakeSettingsStore()

    private fun d(s: String) = LocalDate.parse(s)

    private fun session(expiresIn: Duration = Duration.ofDays(20)) =
        SessionManager(FakeTokenStore(StoredSession("tok", clock.instant().plus(expiresIn), TestUser)), clock)
            .also { runBlocking { it.load() } }

    private fun TestScope.vm(session: SessionManager = session()) =
        BudgetsViewModel(budgets, store, settings, session, clock).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        }

    @Test
    fun spentThisMonthSumsOnlyExpensesInMonth() = runTest {
        budgets.budgets.value = listOf(
            Budget("Groceries", 100_000),
            Budget("Transport", 50_000),
            Budget("Health", 20_000),
        )
        store.rows.value = listOf(
            testTransaction("rice", 10_000, d("2026-10-01")),
            testTransaction("oil", 5_000, d("2026-10-21")),
            testTransaction("refund", 7_000, d("2026-10-10"), type = TxType.INCOME),
            testTransaction("last month", 3_000, d("2026-09-30")),
            testTransaction("next month", 2_000, d("2026-11-01")),
            testTransaction("last year", 1_000, d("2025-10-15")),
            testTransaction("taxi", 60_000, d("2026-10-15"), category = "Transport"),
            testTransaction("lunch", 4_000, d("2026-10-15"), category = "Food and Drink Item"),
        )

        val state = vm().state.value
        val lines = state.lines.associateBy { it.category }

        assertEquals(YearMonth.of(2026, 10), state.month)
        assertEquals(listOf("Groceries", "Transport", "Health"), state.lines.map { it.category })

        val groceries = lines.getValue("Groceries")
        assertEquals(15_000, groceries.spentKyat)
        assertEquals(85_000, groceries.remainingKyat)
        assertFalse(groceries.over)
        assertEquals(0.15f, groceries.progress, 0.0001f)

        val transport = lines.getValue("Transport")
        assertEquals(60_000, transport.spentKyat)
        assertEquals(-10_000, transport.remainingKyat)
        assertTrue(transport.over)
        assertEquals(1.2f, transport.progress, 0.0001f)

        assertEquals(0, lines.getValue("Health").spentKyat)
        assertEquals(0f, lines.getValue("Health").progress, 0f)

        assertEquals(170_000, state.totalLimitKyat)
        assertEquals(75_000, state.totalSpentKyat)
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

    @Test
    fun refreshesOnOpenWithASession() = runTest {
        val gate = CompletableDeferred<Unit>()
        budgets.gate = gate
        val vm = vm()

        assertEquals(1, budgets.refreshes)
        assertTrue(vm.state.value.fetching)
        // Opening the tab fetches quietly: the pull-to-refresh spinner is only for a pull.
        assertFalse(vm.state.value.refreshing)
        // No cached budgets yet: the empty state waits for the first answer instead of flashing.
        assertFalse(vm.state.value.showEmpty)

        gate.complete(Unit)

        assertFalse(vm.state.value.fetching)
        assertTrue(vm.state.value.showEmpty)
    }

    @Test
    fun aPullDuringTheQuietFetchShowsTheSpinnerWithoutFetchingTwice() = runTest {
        val gate = CompletableDeferred<Unit>()
        budgets.gate = gate
        val vm = vm()

        vm.refresh()

        assertEquals(1, budgets.refreshes)
        assertTrue(vm.state.value.refreshing)

        gate.complete(Unit)

        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun pullToRefreshSpinsUntilTheFetchEnds() = runTest {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        budgets.gate = gate

        vm.refresh()

        assertEquals(2, budgets.refreshes)
        assertTrue(vm.state.value.refreshing)

        gate.complete(Unit)

        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun withoutASessionShowsTheCacheWithoutCallingTheServer() = runTest {
        budgets.budgets.value = listOf(Budget("Groceries", 100_000))
        val vm = vm(session(expiresIn = Duration.ofSeconds(-1)))

        vm.refresh()

        assertEquals(0, budgets.refreshes)
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf("Groceries"), vm.state.value.lines.map { it.category })
    }

    @Test
    fun pullToRefreshFetchesAgain() = runTest {
        val vm = vm()

        vm.refresh()

        assertEquals(2, budgets.refreshes)
        assertFalse(vm.state.value.refreshing)
    }
}
