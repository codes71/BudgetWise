package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.testTransaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
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
class PlanEditorViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-11-01").atTime(9, 30).atZone(zone).toInstant(), zone)
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val plans = FakeMonthPlanSource()
    private val budgets = FakeBudgetSource()
    private val categories = FakeCategorySource()
    private val store = FakeTransactionStore()
    private val settings = FakeSettingsStore()

    private fun d(s: String) = LocalDate.parse(s)

    private fun vm(month: YearMonth = november, tickCarry: Boolean = false) = PlanEditorViewModel(
        SavedStateHandle(mapOf(PlanEditorViewModel.ARG_MONTH to month.toString(), PlanEditorViewModel.ARG_TICK_CARRY to tickCarry)),
        plans, budgets, categories, store, settings, clock,
    )

    private fun TestScope.savedEvents(vm: PlanEditorViewModel): List<PlanEditorEvent> =
        mutableListOf<PlanEditorEvent>().also { events -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) } }

    private val octoberPlan = MonthPlan(
        october,
        newMoneyKyat = 2_000_000,
        carriedOverKyat = 0,
        shares = listOf(Budget("Housing", 500_000), Budget("Food and Drink Item", 400_000)),
    )

    @Test
    fun aPlanOpenedByOneAccountCannotBeSavedForAnother() = runTest {
        val vm = vm()
        vm.onNewMoneyChange("1000")
        store.writableOwner = "another-owner"
        plans.writableOwner = "another-owner"
        vm.save()
        assertTrue(plans.saved.isEmpty())
        assertEquals(SaveError.SIGN_IN, vm.state.value.saveError)
    }

    @Test
    fun startsFromTheMonthsOwnPlan() = runTest {
        val saved = MonthPlan(november, 1_500_000, 280_000, listOf(Budget("Transport", 100_000)))
        plans.plans.value = listOf(octoberPlan.copy(endCheck = EndCheck(280_000, Instant.EPOCH)), saved)

        val state = vm().state.value

        assertTrue(state.loaded)
        assertEquals(StartedFrom.PLAN, state.startedFrom)
        assertEquals("1500000", state.newMoneyDigits)
        assertEquals(listOf(ShareDraft("Transport", "100000")), state.shares)
        // The plan's own carry, which is also what October's check said.
        assertEquals(Carry(october, 280_000, LocalDate.parse("1970-01-01"), saved = true), state.carry)
        assertTrue(state.carryTicked)
        assertEquals(1_780_000, state.totalKyat)
    }

    @Test
    fun aSavedPlanKeepsWhatItCarriesEvenAfterLastMonthWasChecked() = runTest {
        plans.plans.value = listOf(
            octoberPlan.copy(endCheck = EndCheck(280_000, Instant.EPOCH)),
            MonthPlan(november, 1_500_000, 200_000, listOf(Budget("Transport", 100_000))),
        )

        val state = vm().state.value

        assertEquals(Carry(october, 200_000, checkedOn = null, saved = true), state.carry)
        assertTrue(state.carryTicked)
        assertEquals(1_700_000, state.totalKyat)

        // Straight from the end check, the checked amount is carried instead.
        val fromCheck = vm(tickCarry = true).state.value
        assertEquals(Carry(october, 280_000, LocalDate.parse("1970-01-01")), fromCheck.carry)
        assertTrue(fromCheck.carryTicked)
        assertEquals(1_780_000, fromCheck.totalKyat)
    }

    @Test
    fun theStartingValuesWaitForTheServersPlans() = runTest {
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate
        budgets.budgets.value = listOf(Budget("Groceries", 350_000))
        val vm = vm()

        assertFalse(vm.state.value.loaded)
        assertEquals(listOf(october to november), plans.refreshes)
        assertEquals(1, budgets.refreshes)

        // The server has a November plan the phone had not seen yet.
        plans.plans.value = listOf(MonthPlan(november, 1_500_000, 0, listOf(Budget("Transport", 100_000))))
        gate.complete(Unit)

        val state = vm.state.value
        assertTrue(state.loaded)
        assertFalse(state.stale)
        assertEquals(StartedFrom.PLAN, state.startedFrom)
        assertEquals("1500000", state.newMoneyDigits)
    }

    @Test
    fun withoutTheServerItStartsFromThePhonesCopyAndSaysItMayBeOld() = runTest {
        plans.plans.value = listOf(octoberPlan)
        plans.refreshResult = ApiResult.Failure("network", "offline", retryable = true)

        val state = vm().state.value

        assertTrue(state.loaded)
        assertTrue(state.stale)
        assertEquals(StartedFrom.PREVIOUS, state.startedFrom)
        assertEquals("2000000", state.newMoneyDigits)
    }

    @Test
    fun theLegacyBudgetsMatterOnlyWhenTheEditorStartsFromThem() = runTest {
        budgets.result = ApiResult.Failure("network", "offline", retryable = true)
        plans.plans.value = listOf(octoberPlan)
        assertFalse(vm().state.value.stale)

        plans.plans.value = emptyList()
        budgets.budgets.value = listOf(Budget("Groceries", 350_000))
        val state = vm().state.value
        assertEquals(StartedFrom.LEGACY, state.startedFrom)
        assertTrue(state.stale)
    }

    @Test
    fun withoutItsOwnPlanStartsFromLastMonthsNewMoneyAndShares() = runTest {
        plans.plans.value = listOf(octoberPlan)
        budgets.budgets.value = listOf(Budget("Groceries", 1))

        val state = vm().state.value

        assertEquals(StartedFrom.PREVIOUS, state.startedFrom)
        assertEquals("2000000", state.newMoneyDigits)
        assertEquals(listOf("Housing", "Food and Drink Item"), state.shares.map { it.category })
        assertEquals(listOf(500_000L, 400_000L), state.shares.map { it.amountKyat })
    }

    @Test
    fun withoutAnyPlanStartsFromTheLegacyBudgetsWithNoNewMoney() = runTest {
        budgets.budgets.value = listOf(Budget("Groceries", 350_000), Budget("Utilities", 0), Budget("Income", 5))

        val state = vm().state.value

        assertEquals(StartedFrom.LEGACY, state.startedFrom)
        assertEquals("", state.newMoneyDigits)
        // A legacy limit of 0 could not be saved as a share, and income is never budgeted.
        assertEquals(listOf(ShareDraft("Groceries", "350000")), state.shares)
        assertNull(state.carry)
        assertFalse(state.canGoOn)
    }

    @Test
    fun aCheckedLastMonthIsCarriedTicked() = runTest {
        plans.plans.value = listOf(octoberPlan.copy(endCheck = EndCheck(280_000, Instant.parse("2026-11-01T02:30:00Z"))))

        val state = vm().state.value

        // Checked at 02:30 UTC on 1 November: 09:00 in Yangon, the same day.
        assertEquals(Carry(october, 280_000, LocalDate.parse("2026-11-01")), state.carry)
        assertTrue(state.carryTicked)
        assertEquals(2_280_000, state.totalKyat)
    }

    @Test
    fun anUncheckedLastMonthOffersItsComputedLeftoverUnticked() = runTest {
        plans.plans.value = listOf(octoberPlan)
        store.rows.value = listOf(
            testTransaction("rent", 500_000, d("2026-10-01"), category = "Housing"),
            testTransaction("food", 1_156_500, d("2026-10-15"), category = "Food and Drink Item"),
            testTransaction("salary", 2_000_000, d("2026-10-01"), type = TxType.INCOME, category = "Income"),
        )

        val vm = vm()

        assertEquals(Carry(october, 343_500, checkedOn = null), vm.state.value.carry)
        assertFalse(vm.state.value.carryTicked)
        assertEquals(2_000_000, vm.state.value.totalKyat)

        vm.onCarryTicked(true)

        assertEquals(2_343_500, vm.state.value.totalKyat)
    }

    @Test
    fun aLastMonthThatWentOverCarriesNothing() = runTest {
        plans.plans.value = listOf(octoberPlan)
        store.rows.value = listOf(testTransaction("rent", 2_100_000, d("2026-10-01"), category = "Housing"))

        assertNull(vm().state.value.carry)
    }

    @Test
    fun straightAfterTheEndCheckTheCarryIsTickedEvenOnASavedPlan() = runTest {
        plans.plans.value = listOf(
            octoberPlan.copy(endCheck = EndCheck(250_000, Instant.EPOCH)),
            MonthPlan(november, 1_500_000, 0, emptyList()),
        )

        assertFalse(vm().state.value.carryTicked)
        val state = vm(tickCarry = true).state.value
        assertTrue(state.carryTicked)
        assertEquals(1_750_000, state.totalKyat)
    }

    @Test
    fun noCarryWithoutLastMonthsPlan() = runTest {
        assertNull(vm().state.value.carry)
    }

    @Test
    fun theLakhChipsSetTheNewMoneyAndNextNeedsATotal() = runTest {
        val vm = vm()

        vm.next()
        assertEquals(PlanStep.TOTAL, vm.state.value.step)

        vm.onPickLakh(20)
        assertEquals("2000000", vm.state.value.newMoneyDigits)

        vm.next()
        assertEquals(PlanStep.SPLIT, vm.state.value.step)

        // Back from the split goes to the total; back from the total closes the editor.
        assertTrue(vm.back())
        assertEquals(PlanStep.TOTAL, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun typedNewMoneyRefusesADecimalPoint() = runTest {
        val vm = vm()

        vm.onNewMoneyChange("1,500,000")
        assertEquals("1500000", vm.state.value.newMoneyDigits)
        vm.onNewMoneyChange("1500000.5")
        assertEquals("1500000", vm.state.value.newMoneyDigits)
    }

    @Test
    fun raisingTheMonthSetsTheNewMoneySoTheTotalEqualsTheSplit() = runTest {
        plans.plans.value = listOf(octoberPlan.copy(endCheck = EndCheck(280_000, Instant.EPOCH)))
        val vm = vm()
        vm.onNewMoneyChange("1000000")
        vm.next()

        vm.onShareChange("Housing", "900000")
        vm.onShareChange("Food and Drink Item", "600000")

        assertTrue(vm.state.value.over)
        assertEquals(1_500_000, vm.state.value.splitKyat)
        assertEquals(-220_000, vm.state.value.unsplitKyat)
        assertFalse(vm.state.value.canSave)
        assertEquals("Food and Drink Item", vm.state.value.lastChanged)

        vm.raiseMonth()

        // New money 1,220,000 plus the 280,000 carried is the 1,500,000 split.
        assertEquals("1220000", vm.state.value.newMoneyDigits)
        assertEquals(1_500_000, vm.state.value.totalKyat)
        assertFalse(vm.state.value.over)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun aShareWithoutAnAmountBlocksSave() = runTest {
        plans.plans.value = listOf(octoberPlan)
        val vm = vm()

        vm.onShareChange("Housing", "")
        assertEquals(listOf("Housing"), vm.state.value.missingShares)
        assertFalse(vm.state.value.canSave)

        vm.onShareChange("Housing", "0")
        assertEquals(listOf("Housing"), vm.state.value.missingShares)
        assertFalse(vm.state.value.canSave)

        vm.save()
        assertTrue(plans.saved.isEmpty())

        vm.onShareChange("Housing", "1")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun addingACategoryOpensItsSheetWithNoAmountYet() = runTest {
        plans.plans.value = listOf(octoberPlan)
        store.rows.value = listOf(
            testTransaction("doctor", 32_500, d("2026-10-03"), category = "Health"),
            testTransaction("bus", 1_000, d("2026-10-03"), category = "Transport"),
        )
        categories.categories.value = listOf("Housing", "Health", "Transport", "Income", "Health")
        val vm = vm()

        // Categories without a share; income is never budgeted.
        assertEquals(listOf("Health", "Transport"), vm.state.value.unshared)
        assertEquals(32_500L, vm.state.value.lastMonthSpent["Health"])

        vm.openAddCategory()
        assertTrue(vm.state.value.addingCategory)
        vm.addCategory("Health")

        assertFalse(vm.state.value.addingCategory)
        assertEquals("Health", vm.state.value.editing)
        assertEquals(ShareDraft("Health", ""), vm.state.value.shares.last())
        assertEquals(listOf("Transport"), vm.state.value.unshared)
        assertFalse(vm.state.value.canSave)

        vm.onShareAmount("Health", 32_500)
        vm.closeShare()
        assertNull(vm.state.value.editing)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun takingAShareOutClosesItsSheet() = runTest {
        plans.plans.value = listOf(octoberPlan)
        val vm = vm()
        vm.openShare("Housing")

        vm.removeShare("Housing")

        assertNull(vm.state.value.editing)
        assertEquals(listOf("Food and Drink Item"), vm.state.value.shares.map { it.category })
    }

    @Test
    fun savingSendsTheMonthWithItsCarryAndSharesInOrder() = runTest {
        plans.plans.value = listOf(octoberPlan.copy(endCheck = EndCheck(280_000, Instant.EPOCH)))
        val vm = vm()
        val events = savedEvents(vm)
        vm.onPickLakh(20)
        vm.next()
        vm.openAddCategory()
        vm.addCategory("Transport")
        vm.onShareChange("Transport", "150000")

        vm.save()

        assertEquals(
            listOf(
                MonthPlan(
                    november,
                    newMoneyKyat = 2_000_000,
                    carriedOverKyat = 280_000,
                    shares = listOf(Budget("Housing", 500_000), Budget("Food and Drink Item", 400_000), Budget("Transport", 150_000)),
                ),
            ),
            plans.saved,
        )
        assertEquals(listOf<PlanEditorEvent>(PlanEditorEvent.Saved), events)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun anUntickedCarryIsSavedAsNothingCarried() = runTest {
        plans.plans.value = listOf(octoberPlan.copy(endCheck = EndCheck(280_000, Instant.EPOCH)))
        val vm = vm()
        vm.onCarryTicked(false)

        vm.save()

        assertEquals(0L, plans.saved.single().carriedOverKyat)
        assertEquals(2_000_000L, plans.saved.single().totalKyat)
    }

    @Test
    fun aFailedSaveKeepsEverythingTypedAndSaysWhy() = runTest {
        plans.plans.value = listOf(octoberPlan)
        val vm = vm()
        val events = savedEvents(vm)
        vm.onNewMoneyChange("2100000")
        vm.next()
        vm.onShareChange("Housing", "550000")
        val gate = CompletableDeferred<Unit>()
        plans.saveGate = gate
        plans.onSave = { ApiResult.Failure("network", "offline", retryable = true) }

        vm.save()
        assertTrue(vm.state.value.saving)
        assertFalse(vm.state.value.canSave)
        gate.complete(Unit)

        val state = vm.state.value
        assertEquals(SaveError.NETWORK, state.saveError)
        assertFalse(state.saving)
        assertEquals(PlanStep.SPLIT, state.step)
        assertEquals("2100000", state.newMoneyDigits)
        assertEquals(ShareDraft("Housing", "550000"), state.shares.first())
        assertTrue(events.isEmpty())
        // Nothing was cached for November.
        assertNull(plans.plans.value.firstOrNull { it.month == november })

        // Changing anything clears the line; saving again works.
        vm.onShareChange("Housing", "560000")
        assertNull(vm.state.value.saveError)
        plans.saveGate = null
        plans.onSave = { ApiResult.Ok(it) }
        vm.save()
        assertEquals(listOf<PlanEditorEvent>(PlanEditorEvent.Saved), events)
    }

    @Test
    fun anUnknownCategoryIsNamedAndStaysMarkedUntilItIsTakenOut() = runTest {
        plans.plans.value = listOf(octoberPlan)
        plans.onSave = { ApiResult.Failure("unknown_category", "Unknown category: Food and Drink Item", retryable = false) }
        val vm = vm()

        vm.save()

        assertEquals(SaveError.UNKNOWN_CATEGORY, vm.state.value.saveError)
        assertEquals("Food and Drink Item", vm.state.value.unknownCategory)

        // Another change keeps it: that share still has to go.
        vm.onShareChange("Housing", "510000")
        assertEquals(SaveError.UNKNOWN_CATEGORY, vm.state.value.saveError)
        assertEquals("Food and Drink Item", vm.state.value.unknownCategory)

        vm.removeShare("Food and Drink Item")
        assertNull(vm.state.value.saveError)
        assertNull(vm.state.value.unknownCategory)

        plans.onSave = { ApiResult.Ok(it) }
        vm.save()
        assertEquals(listOf("Housing"), plans.saved.last().shares.map { it.category })
    }

    @Test
    fun theUnknownCategoryIsReadFromTheServersMessage() {
        val shares = listOf(ShareDraft("Health", "1"), ShareDraft("My shop", "2"))
        assertEquals("Health", PlanEditorViewModel.unknownCategoryIn("Unknown category: Health", shares))
        assertEquals("My shop", PlanEditorViewModel.unknownCategoryIn("Unknown category:   My shop ", shares))
        // A name that is not in the split, or a message in another shape, names nothing.
        assertNull(PlanEditorViewModel.unknownCategoryIn("Unknown category: Pets", shares))
        assertNull(PlanEditorViewModel.unknownCategoryIn("Validation failed", shares))
    }

    @Test
    fun theServersRefusalsBecomeTheirOwnMessages() = runTest {
        assertEquals(SaveError.SPLIT_OVER_TOTAL, PlanEditorViewModel.saveErrorFor("split_over_total"))
        assertEquals(SaveError.UNKNOWN_CATEGORY, PlanEditorViewModel.saveErrorFor("unknown_category"))
        assertEquals(SaveError.OTHER, PlanEditorViewModel.saveErrorFor("validation_failed"))
        assertEquals(SaveError.NETWORK, PlanEditorViewModel.saveErrorFor("network"))

        plans.plans.value = listOf(octoberPlan)
        plans.onSave = { ApiResult.Unauthorized("expired") }
        val vm = vm()
        vm.save()
        assertEquals(SaveError.SIGN_IN, vm.state.value.saveError)
    }

    @Test
    fun spendingHintsCountOnlyMonthsWithSpending() {
        val rows = listOf(
            testTransaction("a", 382_000, d("2026-10-05"), category = "Groceries"),
            testTransaction("b", 348_400, d("2026-09-05"), category = "Groceries"),
            testTransaction("c", 18_200, d("2026-10-07"), category = "Other"),
            testTransaction("d", 999, d("2026-09-07"), category = "Health"),
            testTransaction("income", 900_000, d("2026-08-01"), type = TxType.INCOME, category = "Income"),
            testTransaction("too old", 5_000, d("2026-07-31")),
            testTransaction("this month", 7_000, d("2026-11-01")),
        )

        val stats = spendStats(rows, november)

        assertEquals(mapOf("Groceries" to 382_000L, "Other" to 18_200L), stats.lastMonth)
        // August had income only, so the average is over September and October, to the nearest 1,000 from 1,000 up.
        assertEquals(365_000L, stats.average["Groceries"])
        assertEquals(9_000L, stats.average["Other"])
        assertEquals(500L, stats.average["Health"])
        assertEquals(listOf(MonthSpent(october, 400_200), MonthSpent(YearMonth.of(2026, 9), 349_399)), stats.before)

        // One month with spending gives no average: it would only repeat that month.
        assertTrue(spendStats(rows.filter { it.date.monthValue == 10 }, november).average.isEmpty())
    }
}
