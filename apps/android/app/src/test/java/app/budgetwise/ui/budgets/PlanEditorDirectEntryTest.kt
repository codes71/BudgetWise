package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlanEditorDirectEntryTest {
    @get:Rule val main = MainDispatcherRule()
    private val month = YearMonth.of(2026, 11)
    private val plans = FakeMonthPlanSource()
    private val category = "ကျောင်း / Books & fees?"
    private val plan = MonthPlan(month, 1_000_000, 0, listOf(Budget(category, 200_000), Budget("Groceries", 300_000)))
    private val clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Asia/Yangon"))

    private fun handle(selected: String? = category, split: Boolean = true) = SavedStateHandle(
        mapOf(PlanEditorViewModel.ARG_MONTH to month.toString(), PlanEditorViewModel.ARG_START_SPLIT to split, PlanEditorViewModel.ARG_SELECTED_CATEGORY to selected),
    )

    private fun vm(handle: SavedStateHandle = handle()) = PlanEditorViewModel(
        handle, plans, FakeBudgetSource(), FakeCategorySource(), FakeTransactionStore(), FakeSettingsStore(), clock,
    )

    @Test
    fun selectedShareOpensOnlyAfterTheRefreshedPlanLoads() = runTest {
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate
        val vm = vm()
        assertFalse(vm.state.value.loaded)
        assertNull(vm.state.value.editing)
        plans.plans.value = listOf(plan)
        gate.complete(Unit)
        assertTrue(vm.state.value.loaded)
        assertEquals(PlanStep.SPLIT, vm.state.value.step)
        assertEquals(category, vm.state.value.editing)
        assertEquals(plan.shares.map { it.category }, vm.state.value.shares.map { it.category })
        assertTrue(plans.saved.isEmpty())
    }

    @Test
    fun everythingElseOpensTheSplitWithoutAnIndividualShare() = runTest {
        plans.plans.value = listOf(plan)
        val state = vm(handle(selected = "")).state.value
        assertEquals(PlanStep.SPLIT, state.step)
        assertNull(state.editing)
    }

    @Test
    fun missingCategoryIsNotRecreatedAndMissingPlanStartsAtTheTotal() = runTest {
        plans.plans.value = listOf(plan)
        val state = vm(handle(selected = "Removed")).state.value
        assertEquals(PlanStep.SPLIT, state.step)
        assertNull(state.editing)
        assertEquals(plan.shares.size, state.shares.size)
        plans.plans.value = emptyList()
        val absent = vm().state.value
        assertEquals(PlanStep.TOTAL, absent.step)
        assertNull(absent.editing)
    }

    @Test
    fun ordinaryEditorIgnoresTheSelectedCategoryAndKeepsTotalEntry() = runTest {
        plans.plans.value = listOf(plan)
        val state = vm(handle(split = false)).state.value
        assertEquals(PlanStep.TOTAL, state.step)
        assertNull(state.editing)
    }

    @Test
    fun closingTheShareIsRememberedAcrossViewModelRecreation() = runTest {
        plans.plans.value = listOf(plan)
        val original = handle()
        val originalVm = vm(original)
        originalVm.closeShare()
        // Copy the values Android restores, rather than sharing the original ViewModel.
        val restored = SavedStateHandle(original.keys().associateWith { original.get<Any?>(it) })
        val fresh = vm(restored)
        assertEquals(PlanStep.SPLIT, fresh.state.value.step)
        assertNull(fresh.state.value.editing)
        fresh.back()
        val restoredTotal = SavedStateHandle(restored.keys().associateWith { restored.get<Any?>(it) })
        assertEquals(PlanStep.TOTAL, vm(restoredTotal).state.value.step)
    }

    @Test
    fun anOpenShareRestoresOnlyIfItStillExistsInThePlan() = runTest {
        plans.plans.value = listOf(plan)
        val original = handle()
        vm(original)
        val restored = SavedStateHandle(original.keys().associateWith { original.get<Any?>(it) })
        assertEquals(category, vm(restored).state.value.editing)
        plans.plans.value = listOf(plan.copy(shares = plan.shares.filterNot { it.category == category }))
        val removed = SavedStateHandle(original.keys().associateWith { original.get<Any?>(it) })
        assertNull(vm(removed).state.value.editing)
    }
}
