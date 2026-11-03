package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.ReplaceResult
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.testTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetTransactionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val month = YearMonth.of(2026, 10)
    private val today = month.atDay(21)
    private val clock = Clock.fixed(today.atStartOfDay(ZoneId.of("Asia/Yangon")).toInstant(), ZoneId.of("Asia/Yangon"))
    private val plan = MonthPlan(month, 300_000, 0, listOf(Budget("Groceries", 100_000), Budget("Transport", 80_000)))
    private val plans = FakeMonthPlanSource(listOf(plan))
    private val store = FakeTransactionStore()
    private val settings = FakeSettingsStore()

    private fun row(id: String, amount: Long, category: String = "Groceries", date: LocalDate = today, type: TxType = TxType.EXPENSE) =
        testTransaction(id, amount, date, type, category, clientId = id)

    private fun TestScope.vm(category: String = "Groceries", transactions: TransactionStore = store): BudgetTransactionsViewModel =
        BudgetTransactionsViewModel(
            SavedStateHandle(mapOf(BudgetTransactionsViewModel.ARG_MONTH to month.toString(), BudgetTransactionsViewModel.ARG_CATEGORY to category)),
            plans, transactions, settings, clock, backgroundScope,
        ).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} } }

    @Test
    fun showsOnlyExpensesInTheSelectedMonthAndCategoryNewestFirst() = runTest {
        store.rows.value = listOf(
            row("month start", 20_000, date = month.atDay(1)),
            row("latest", 30_000),
            row("other category", 5_000, "Transport"),
            row("income", 200_000, type = TxType.INCOME),
            row("previous", 90_000, date = month.atDay(1).minusDays(1)),
            row("next", 90_000, date = month.plusMonths(1).atDay(1)),
        )
        val state = vm().state.value

        assertFalse(state.loading)
        assertEquals(listOf("latest", "month start"), state.days.flatMap { it.rows }.map { it.clientId })
        assertEquals(BudgetLine("Groceries", 100_000, 50_000), state.line)
        assertEquals(50_000, state.line!!.remainingKyat)
        assertEquals(2, state.count)
    }

    @Test
    fun amountDateCategoryTypeAndDeleteChangesRecomputeBothBudgetScreensFromTheSameLedger() = runTest {
        store.rows.value = listOf(row("rice", 30_000), row("taxi", 15_000, "Transport"))
        val vm = vm()
        fun assertSpending(spent: Long) {
            val detail = vm.state.value.line!!
            val summary = budgetsState(month, plans.plans.value, store.rows.value, false)
            assertEquals(spent, detail.spentKyat)
            assertEquals(100_000 - spent, detail.remainingKyat)
            assertEquals(detail, summary.lines.first { it.category == "Groceries" })
            assertEquals(plan, summary.plan)
        }

        assertSpending(30_000)
        store.rows.value = listOf(row("rice", 130_000), row("taxi", 15_000, "Transport"))
        assertSpending(130_000)
        assertTrue(vm.state.value.line!!.over)
        store.rows.value = listOf(row("rice", 10_000), row("taxi", 15_000, "Transport"))
        assertSpending(10_000)
        store.rows.value = listOf(row("rice", 10_000, "Transport"))
        assertSpending(0)
        store.rows.value = listOf(row("rice", 10_000, date = month.minusMonths(1).atDay(10)))
        assertSpending(0)
        store.rows.value = listOf(row("rice", 10_000, type = TxType.INCOME))
        assertSpending(0)
        store.rows.value = emptyList()
        assertSpending(0)
    }

    @Test
    fun editingThroughTheExistingStoreReplacementUpdatesTheDetailAndMonthlyBudget() = runTest {
        store.rows.value = listOf(row("rice", 30_000))
        val vm = vm()
        val result = store.replace(listOf("rice"), listOf(NewTransaction(TxType.EXPENSE, 60_000, "Groceries", "Rice edited", today, Source.MANUAL, null)))

        assertTrue(result is ReplaceResult.Saved)
        assertEquals(60_000, vm.state.value.line!!.spentKyat)
        assertEquals(40_000, vm.state.value.line!!.remainingKyat)
        assertEquals("Rice edited", vm.state.value.days.single().rows.single().description)
        assertEquals(60_000, budgetsState(month, plans.plans.value, store.rows.value, false).spentKyat)
    }

    @Test
    fun everythingElseTracksOnlyCategoriesOutsideTheCurrentPlanSplit() = runTest {
        store.rows.value = listOf(row("rice", 20_000), row("doctor", 40_000, "Health"), row("film", 10_000, "Entertainment"))
        val vm = vm(EVERYTHING_ELSE)

        assertEquals(listOf("doctor", "film"), vm.state.value.days.flatMap { it.rows }.map { it.clientId })
        assertEquals(BudgetLine(EVERYTHING_ELSE, 120_000, 50_000), vm.state.value.line)

        // A plan refresh allocated Health: it leaves Everything else, including the share's limit.
        plans.plans.value = listOf(plan.copy(shares = plan.shares + Budget("Health", 50_000)))
        assertEquals(listOf("film"), vm.state.value.days.flatMap { it.rows }.map { it.clientId })
        assertEquals(BudgetLine(EVERYTHING_ELSE, 70_000, 10_000), vm.state.value.line)
    }

    @Test
    fun scannedReceiptRowsRemainIndividualAndDeletingOneNeverDeletesAnotherCategory() = runTest {
        store.rows.value = listOf(
            row("rice", 30_000).copy(receiptId = "receipt", source = Source.SCAN),
            row("fruit", 20_000).copy(receiptId = "receipt", source = Source.SCAN),
            row("taxi", 5_000, "Transport").copy(receiptId = "receipt", source = Source.SCAN),
        )
        val vm = vm()
        assertEquals(2, vm.state.value.days.single().lines.size)
        assertTrue(vm.state.value.days.single().lines.all { it is app.budgetwise.ui.transactions.DayLine.Entry })

        vm.delete("rice")
        assertEquals(20_000, vm.state.value.line!!.spentKyat)
        assertEquals("rice", vm.state.value.pendingDelete!!.clientId)
        assertTrue(store.deleted.isEmpty())
        vm.undoDelete("rice")
        assertEquals(50_000, vm.state.value.line!!.spentKyat)
        assertEquals(1, vm.state.value.restored["rice"])

        vm.delete("rice")
        vm.commitDelete("rice")
        testScheduler.runCurrent()
        assertEquals(listOf("rice"), store.deleted)
        assertEquals(setOf("fruit", "taxi"), store.rows.value.map { it.clientId }.toSet())
        assertEquals(20_000, vm.state.value.line!!.spentKyat)
        assertEquals(25_000, budgetsState(month, plans.plans.value, store.rows.value, false).spentKyat)
    }

    @Test
    fun aSecondDeleteCommitsTheFirstAndLeavingCommitsOnlyThePendingEntry() = runTest {
        store.rows.value = listOf(row("rice", 30_000), row("fruit", 20_000), row("taxi", 5_000, "Transport"))
        val vm = vm()

        vm.delete("taxi") // not contributing to this budget
        assertEquals(null, vm.state.value.pendingDelete)
        vm.delete("rice")
        vm.delete("fruit")
        testScheduler.runCurrent()
        assertEquals(listOf("rice"), store.deleted)
        assertEquals("fruit", vm.state.value.pendingDelete!!.clientId)
        vm.commitPendingDelete()
        vm.commitPendingDelete()
        testScheduler.runCurrent()
        assertEquals(listOf("rice", "fruit"), store.deleted)
        assertEquals(listOf("taxi"), store.rows.value.map { it.clientId })
    }

    @Test
    fun aLocalDeleteFailureRestoresTheEntryAndBudgetAmount() = runTest {
        store.rows.value = listOf(row("rice", 30_000))
        val failing = object : TransactionStore by store {
            override suspend fun delete(clientId: String) { throw IllegalStateException("Room failed") }
        }
        val vm = vm(transactions = failing)

        vm.delete("rice")
        vm.commitPendingDelete()
        testScheduler.runCurrent()

        assertEquals(30_000, vm.state.value.line!!.spentKyat)
        assertEquals(listOf("rice"), vm.state.value.days.flatMap { it.rows }.map { it.clientId })
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun aRestoredEditorCanHandBackDeleteBeforeTheDetailHasEverSubscribed() = runTest {
        val row = row("rice", 30_000)
        store.rows.value = listOf(row)
        // Process restoration starts with the editor above the category detail. The detail's WhileSubscribed
        // state has never run, even though the editor independently loaded the entry from TransactionStore.get.
        val vm = BudgetTransactionsViewModel(
            SavedStateHandle(mapOf(BudgetTransactionsViewModel.ARG_MONTH to month.toString(), BudgetTransactionsViewModel.ARG_CATEGORY to "Groceries")),
            plans, store, settings, clock, backgroundScope,
        )
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.days.isEmpty())

        vm.deleteFromEditor("rice")
        assertTrue(store.deleted.isEmpty())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals("rice", vm.state.value.pendingDelete!!.clientId)
        assertEquals(row, vm.state.value.pendingDelete!!.row)
        assertTrue(vm.state.value.days.isEmpty())
        assertEquals(0, vm.state.value.line!!.spentKyat)
        vm.undoDelete("rice")
        assertEquals(30_000, vm.state.value.line!!.spentKyat)
        assertTrue(store.deleted.isEmpty())

        vm.deleteFromEditor("rice")
        vm.commitPendingDelete()
        testScheduler.runCurrent()
        assertEquals(listOf("rice"), store.deleted)
        assertTrue(store.rows.value.isEmpty())
    }

    @Test
    fun anEditorDeleteCanCommitWithoutAnyDetailStateSubscriber() = runTest {
        store.rows.value = listOf(row("rice", 30_000))
        val vm = BudgetTransactionsViewModel(
            SavedStateHandle(mapOf(BudgetTransactionsViewModel.ARG_MONTH to month.toString(), BudgetTransactionsViewModel.ARG_CATEGORY to "Groceries")),
            plans, store, settings, clock, backgroundScope,
        )

        vm.deleteFromEditor("rice")
        vm.commitPendingDelete()
        testScheduler.runCurrent()

        assertEquals(listOf("rice"), store.deleted)
        assertTrue(store.rows.value.isEmpty())
    }

    @Test
    fun accountLedgerAndPlanChangesRemoveThePriorAccountsExpenses() = runTest {
        store.rows.value = listOf(row("private", 30_000))
        val vm = vm()
        store.rows.value = emptyList()
        plans.plans.value = emptyList()

        assertTrue(vm.state.value.days.isEmpty())
        assertEquals(null, vm.state.value.line)
        assertEquals(0, vm.state.value.count)
    }
}
