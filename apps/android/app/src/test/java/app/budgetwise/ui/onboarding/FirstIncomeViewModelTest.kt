package app.budgetwise.ui.onboarding

import app.budgetwise.data.model.Source
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
class FirstIncomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-30")
    private val clock = Clock.fixed(today.atTime(9, 0).atZone(zone).toInstant(), zone)
    private val store = FakeTransactionStore(emptyList())

    private fun viewModel() = FirstIncomeViewModel(store, FakeSettingsStore(), clock)

    private fun TestScope.collectEvents(vm: FirstIncomeViewModel): List<FirstIncomeEvent> {
        val events = mutableListOf<FirstIncomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    @Test
    fun theIncomeIsSavedAsOneIncomeEntryForToday() = runTest {
        val vm = viewModel()
        val events = collectEvents(vm)

        vm.onAmountChange("850,000")
        vm.onSourceChange(IncomeSource.SHOP)
        vm.save("Shop")

        val saved = NewTransaction(TxType.INCOME, 850_000, CategoryRepository.INCOME, "Shop", today, Source.MANUAL, null)
        assertEquals(listOf(saved), store.added)
        assertEquals(listOf(FirstIncomeEvent.Saved), events)
    }

    @Test
    fun nothingIsSavedWithoutAnAmountAboveZero() = runTest {
        val vm = viewModel()

        assertFalse(vm.state.value.canSave)
        vm.onAmountChange("000")
        assertNull("zero is not an income", vm.state.value.amount)
        vm.save("Salary")

        assertTrue(store.added.isEmpty())
    }

    @Test
    fun theAmountFollowsTheAddFormsRules() {
        val vm = viewModel()

        vm.onAmountChange("၈၅၀၀၀၀")
        assertEquals("Burmese keyboard digits become ASCII", "850000", vm.state.value.amountDigits)
        vm.onAmountChange("850000.5")
        assertEquals("a decimal point is refused", "850000", vm.state.value.amountDigits)
    }

    @Test
    fun aFailedSaveStaysOnTheScreenForAnotherTry() = runTest {
        store.addFailure = IOException("disk full")
        val vm = viewModel()
        val events = collectEvents(vm)

        vm.onAmountChange("500000")
        vm.save("Salary")

        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.saving)
        assertTrue(events.isEmpty())
        vm.onAmountChange("5000000")
        assertFalse("editing clears the notice", vm.state.value.saveFailed)
    }
}
