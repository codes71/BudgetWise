package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Source
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.nlu.TxType
import app.budgetwise.reminders.MonthEndReminders
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.TestUser
import app.budgetwise.testing.testTransaction
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
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
class MonthEndViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-11-01").atTime(9, 30).atZone(zone).toInstant(), zone)
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val plans = FakeMonthPlanSource(listOf(MonthPlan(october, 2_000_000, 0, listOf(Budget("Housing", 500_000)))))
    private val store = FakeTransactionStore(
        listOf(
            testTransaction("rent", 500_000, LocalDate.parse("2026-10-01"), category = "Housing"),
            testTransaction("food", 1_156_500, LocalDate.parse("2026-10-20"), category = "Food and Drink Item"),
            testTransaction("salary", 2_000_000, LocalDate.parse("2026-10-01"), type = TxType.INCOME, category = "Income"),
            testTransaction("today", 9_000, LocalDate.parse("2026-11-01")),
        ),
    )
    private val settings = FakeSettingsStore()

    /** The months whose reminder was told they are checked. */
    private val checked = mutableListOf<YearMonth>()

    private val sync = FakeSyncControls()
    private val session = SessionManager(FakeTokenStore(StoredSession("tok", clock.instant().plus(Duration.ofDays(20)), TestUser)), clock)
        .also { runBlocking { it.load() } }

    private fun vm(month: YearMonth = october) =
        MonthEndViewModel(
            SavedStateHandle(mapOf(MonthEndViewModel.ARG_MONTH to month.toString())),
            plans,
            store,
            settings,
            clock,
            MonthEndReminders { checked += it },
            sync,
            session,
        )

    private fun TestScope.events(vm: MonthEndViewModel): List<MonthEndEvent> =
        mutableListOf<MonthEndEvent>().also { list -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(list) } }

    @Test
    fun anEndCheckOpenedByOneAccountCannotBeSentForTheNextAccount() = runTest {
        val vm = vm()
        vm.onAnswer(OnHand.YES)
        session.signIn("new-token", clock.instant().plus(Duration.ofDays(20)), TestUser.copy(id = "another-owner", email = "another@example.com"))
        store.writableOwner = "another-owner"
        plans.writableOwner = "another-owner"
        vm.next("Not recorded")
        assertTrue(plans.endChecks.isEmpty())
        assertTrue(store.added.isEmpty())
        assertEquals(EndCheckError.ACCOUNT_CHANGED, vm.state.value.error)
    }

    @Test
    fun showsWhatIsLeftOfTheMonthAndAsksTheServerForItsPlan() = runTest {
        val state = vm().state.value

        assertTrue(state.loaded)
        assertEquals(2_000_000, state.totalKyat)
        assertEquals(1_656_500, state.spentKyat)
        assertEquals(343_500, state.leftKyat)
        assertEquals(listOf(october to october), plans.refreshes)
        // Nothing chosen yet: Next waits for an answer.
        assertFalse(state.canGoOn)
    }

    @Test
    fun yesSavesTheLeftoverAndGoesOnToPlanTheNextMonth() = runTest {
        val vm = vm()
        val events = events(vm)

        vm.onAnswer(OnHand.YES)
        assertEquals(343_500L, vm.state.value.onHandKyat)
        assertEquals(0, vm.state.value.gapKyat)
        vm.next("Not recorded")

        assertEquals(listOf(october to 343_500L), plans.endChecks)
        assertTrue(store.added.isEmpty())
        assertEquals(listOf<MonthEndEvent>(MonthEndEvent.Checked(november)), events)
        // The month-end notification for October goes.
        assertEquals(listOf(october), checked)
    }

    @Test
    fun lessOnHandAddsTheGapAsAnExpenseOnlyAfterTheCheckIsSaved() = runTest {
        val vm = vm()
        val events = events(vm)
        vm.onAnswer(OnHand.NO)
        assertFalse(vm.state.value.canGoOn)
        vm.onOnHandChange("280,000")

        assertEquals(280_000L, vm.state.value.onHandKyat)
        assertEquals(63_500, vm.state.value.gapKyat)
        assertTrue(vm.state.value.addGap)

        val gate = CompletableDeferred<Unit>()
        plans.saveGate = gate
        vm.next("Not recorded")

        // The check is on its way to the server; the entry waits for its answer.
        assertTrue(vm.state.value.saving)
        assertEquals(listOf(october to 280_000L), plans.endChecks)
        assertTrue(store.added.isEmpty())
        assertTrue(events.isEmpty())

        gate.complete(Unit)

        assertEquals(
            listOf(NewTransaction(TxType.EXPENSE, 63_500, "Other", "Not recorded", LocalDate.parse("2026-10-31"), Source.MANUAL, transcript = null)),
            store.added,
        )
        assertEquals(listOf<MonthEndEvent>(MonthEndEvent.Checked(november)), events)
    }

    @Test
    fun untickedTheGapIsNotAdded() = runTest {
        val vm = vm()
        vm.onAnswer(OnHand.NO)
        vm.onOnHandChange("280000")
        vm.onAddGap(false)

        vm.next("Not recorded")

        assertEquals(listOf(october to 280_000L), plans.endChecks)
        assertTrue(store.added.isEmpty())
    }

    @Test
    fun moreOnHandThanExpectedAddsNothing() = runTest {
        val vm = vm()
        vm.onAnswer(OnHand.NO)
        vm.onOnHandChange("400000")

        assertEquals(-56_500, vm.state.value.gapKyat)
        vm.next("Not recorded")

        assertEquals(listOf(october to 400_000L), plans.endChecks)
        assertTrue(store.added.isEmpty())
    }

    @Test
    fun aFailedCheckAddsNoEntryKeepsTheAnswerAndSaysWhy() = runTest {
        val vm = vm()
        val events = events(vm)
        vm.onAnswer(OnHand.NO)
        vm.onOnHandChange("280000")
        plans.onEndCheck = { _, _ -> ApiResult.Failure("network", "offline", retryable = true) }

        vm.next("Not recorded")

        assertEquals(EndCheckError.NETWORK, vm.state.value.error)
        assertFalse(vm.state.value.saving)
        assertEquals("280000", vm.state.value.onHandDigits)
        assertTrue(store.added.isEmpty())
        assertTrue(events.isEmpty())
        assertNull(plans.plans.value.single { it.month == october }.endCheck)
        // Not checked: its notification stays.
        assertTrue(checked.isEmpty())

        plans.onEndCheck = { _, _ -> ApiResult.Failure("month_not_over", "not over", retryable = false) }
        vm.next("Not recorded")
        assertEquals(EndCheckError.NOT_OVER, vm.state.value.error)
        assertTrue(store.added.isEmpty())
    }

    @Test
    fun anEntryThatCouldNotBeAddedIsRetriedWithTheCheck() = runTest {
        val vm = vm()
        val events = events(vm)
        vm.onAnswer(OnHand.NO)
        vm.onOnHandChange("280000")
        store.addFailure = IOException("disk full")

        vm.next("Not recorded")

        assertEquals(EndCheckError.ENTRY_FAILED, vm.state.value.error)
        assertTrue(events.isEmpty())

        store.addFailure = null
        vm.next("Not recorded")

        assertEquals(2, plans.endChecks.size)
        assertEquals(1, store.added.size)
        assertEquals(listOf<MonthEndEvent>(MonthEndEvent.Checked(november)), events)
    }

    @Test
    fun theLeftoverWaitsForThisScreensPullOfTheLedger() = runTest {
        val gate = CompletableDeferred<Unit>()
        sync.syncGate = gate
        val vm = vm()
        vm.onAnswer(OnHand.YES)

        assertEquals(LedgerPull.RUNNING, vm.state.value.ledger)
        assertEquals(1, sync.syncAndWaitCalls)
        assertFalse(vm.state.value.counted)
        assertFalse(vm.state.value.canGoOn)

        gate.complete(Unit)

        assertEquals(LedgerPull.DONE, vm.state.value.ledger)
        assertTrue(vm.state.value.canGoOn)
    }

    @Test
    fun spendingEnteredOnTheWebIsCountedNotAddedAgainAsNotRecorded() = runTest {
        // The web has 63,500 Ks of October spending the phone has not pulled yet.
        sync.onSyncAndWait = {
            store.rows.value += testTransaction("market", 63_500, LocalDate.parse("2026-10-28"))
            SyncOutcome.Success(pushed = 0, pulled = 1)
        }
        val vm = vm()
        vm.onAnswer(OnHand.NO)
        vm.onOnHandChange("280000")

        assertEquals(1_720_000, vm.state.value.spentKyat)
        assertEquals(280_000, vm.state.value.expectedKyat)
        assertEquals(0, vm.state.value.gapKyat)
        vm.next("Not recorded")

        assertTrue(store.added.isEmpty())
        assertEquals(listOf(october to 280_000L), plans.endChecks)
    }

    @Test
    fun aFailedPullBlocksTheCheckUntilATryAgainGoesThrough() = runTest {
        sync.onSyncAndWait = { SyncOutcome.RetryLater }
        val vm = vm()
        vm.onAnswer(OnHand.YES)

        assertEquals(LedgerPull.FAILED, vm.state.value.ledger)
        assertFalse(vm.state.value.canGoOn)
        vm.next("Not recorded")
        assertTrue(plans.endChecks.isEmpty())

        sync.onSyncAndWait = { SyncOutcome.PermanentFailure(listOf("rejected"), pushed = 0, pulled = 0) }
        vm.retryPull()

        // A row the server refused does not stop the pull: the ledger is current.
        assertEquals(2, sync.syncAndWaitCalls)
        assertEquals(LedgerPull.DONE, vm.state.value.ledger)
        assertTrue(vm.state.value.canGoOn)
    }

    @Test
    fun aPullThatNeedsASignInSaysSoAndTryAgainWaitsForIt() = runTest {
        sync.onSyncAndWait = { SyncOutcome.NeedsLogin }
        val vm = vm()

        assertEquals(LedgerPull.SIGN_IN, vm.state.value.ledger)
        vm.retryPull()
        assertEquals(1, sync.syncAndWaitCalls)
    }

    @Test
    fun theGapEntryIsNeverWrittenToAnotherAccount() = runTest {
        val vm = vm()
        val events = events(vm)
        vm.onAnswer(OnHand.NO)
        vm.onOnHandChange("280000")
        val gate = CompletableDeferred<Unit>()
        plans.saveGate = gate

        vm.next("Not recorded")
        // Another account signs in while the check is on its way.
        session.signIn("tok2", clock.instant().plus(Duration.ofDays(20)), UserInfo(id = "u2", email = "mya@example.com", fullName = "Mya"))
        gate.complete(Unit)

        assertEquals(listOf(october to 280_000L), plans.endChecks)
        assertTrue(store.added.isEmpty())
        assertEquals(EndCheckError.ACCOUNT_CHANGED, vm.state.value.error)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aMonthWithoutAPlanSaysSoOnceTheServerHasAnswered() = runTest {
        plans.plans.value = emptyList()
        val gate = CompletableDeferred<Unit>()
        plans.refreshGate = gate
        val vm = vm()

        assertFalse(vm.state.value.loaded)

        gate.complete(Unit)

        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.plan)
        vm.onAnswer(OnHand.YES)
        assertFalse(vm.state.value.canGoOn)
    }

    @Test
    fun aMonthThatWentOverExpectsNothingOnHand() = runTest {
        store.rows.value += testTransaction("tv", 500_000, LocalDate.parse("2026-10-25"), category = "Entertainment")
        val vm = vm()

        assertEquals(-156_500, vm.state.value.leftKyat)
        vm.onAnswer(OnHand.YES)
        assertEquals(0L, vm.state.value.onHandKyat)
    }
}
