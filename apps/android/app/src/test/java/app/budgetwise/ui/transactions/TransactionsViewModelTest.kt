package app.budgetwise.ui.transactions

import app.budgetwise.data.model.Source
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.sync.SyncStatus
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.testTransaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-21")
    private val clock = Clock.fixed(today.atTime(9, 30).atZone(zone).toInstant(), zone)
    private val store = FakeTransactionStore()
    private val sync = FakeSyncControls()
    private val settings = FakeSettingsStore()
    private val network = FakeNetworkStatus()

    private fun d(s: String) = LocalDate.parse(s)

    private fun TestScope.vm() = TransactionsViewModel(store, sync, settings, network, clock, backgroundScope).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    private fun TestScope.events(vm: TransactionsViewModel): List<TransactionsEvent> {
        val events = mutableListOf<TransactionsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    private fun TransactionsUiState.visibleIds() = days.flatMap { day -> day.rows.map { it.clientId } }

    @Test
    fun theChartFollowsRoomAndLeavesOutARowWaitingForUndo() = runTest {
        val rice = testTransaction("rice", 20_000, today)
        val taxi = testTransaction("taxi", 4_000, today.minusDays(1), category = "Transport")
        val market = testTransaction("market", 30_000, d("2026-09-05"))
        val salary = testTransaction("salary", 500_000, today, type = TxType.INCOME, category = "Income")
        store.rows.value = listOf(rice, taxi, market, salary)
        val vm = vm()

        assertEquals(24_000L, vm.state.value.pace.spent)
        assertEquals(30_000L, vm.state.value.pace.lastBySameDay)
        assertEquals(listOf("Groceries", "Transport"), vm.state.value.categories.top.map { it.category })

        // Swiped away: gone from the chart at once, and the notice can name it.
        vm.delete(DayLine.Entry(rice))
        assertEquals(4_000L, vm.state.value.pace.spent)
        assertEquals(listOf("Transport"), vm.state.value.categories.top.map { it.category })
        assertEquals(listOf(rice), vm.state.value.pendingDeleteRows)

        vm.undoDelete(rice.clientId)
        assertEquals(24_000L, vm.state.value.pace.spent)
        assertTrue(vm.state.value.pendingDeleteRows.isEmpty())
    }

    @Test
    fun groupsRowsByDayNewestFirst() = runTest {
        val tea = testTransaction("tea", 1500, today)
        val rice = testTransaction("rice", 20000, today)
        val taxi = testTransaction("taxi", 4000, d("2026-10-20"))
        val rent = testTransaction("rent", 300000, d("2026-10-03"))
        store.rows.value = listOf(taxi, tea, rent, rice)

        val state = vm().state.value

        assertFalse(state.loading)
        assertEquals(listOf(today, d("2026-10-20"), d("2026-10-03")), state.days.map { it.date })
        assertEquals(setOf(tea, rice), state.days[0].rows.toSet())
        assertEquals(today, state.today)
    }

    @Test
    fun monthSummaryCountsOnlyThisMonth() = runTest {
        store.rows.value = listOf(
            testTransaction("tea", 5000, today),
            testTransaction("bus", 2000, d("2026-10-01")),
            testTransaction("salary", 100000, d("2026-10-10"), type = TxType.INCOME),
            testTransaction("last month", 9000, d("2026-09-30")),
            testTransaction("next month", 7, d("2026-11-01"), type = TxType.INCOME),
            testTransaction("last year", 1000, d("2025-10-21")),
        )

        val summary = vm().state.value.summary

        assertEquals(MonthSummary(YearMonth.of(2026, 10), spent = 7000, income = 100000), summary)
        assertEquals(93000, summary.balance)
    }

    @Test
    fun deleteHidesTheRowAndWaitsForTheSnackbar() = runTest {
        val tea = testTransaction("tea", 1500, today)
        val rice = testTransaction("rice", 20000, today)
        store.rows.value = listOf(tea, rice)
        val vm = vm()

        vm.delete(DayLine.Entry(tea))
        runCurrent()

        assertEquals(listOf(rice.clientId), vm.state.value.visibleIds())
        assertEquals(tea.clientId, vm.state.value.pendingDelete)
        assertTrue("nothing is deleted while Undo is offered", store.deleted.isEmpty())

        vm.commitDelete(tea.clientId)
        runCurrent()

        assertEquals(listOf(tea.clientId), store.deleted)
        assertNull(vm.state.value.pendingDelete)
        assertEquals(listOf(rice.clientId), vm.state.value.visibleIds())
    }

    @Test
    fun undoKeepsTheRow() = runTest {
        val tea = testTransaction("tea", 1500, today)
        store.rows.value = listOf(tea)
        val vm = vm()

        vm.delete(DayLine.Entry(tea))
        vm.undoDelete(tea.clientId)
        // A late "dismissed" for the same row (the snackbar closing) must not delete it after Undo.
        vm.commitDelete(tea.clientId)
        vm.commitPendingDelete()
        runCurrent()

        assertEquals(listOf(tea.clientId), vm.state.value.visibleIds())
        assertNull(vm.state.value.pendingDelete)
        assertTrue(store.deleted.isEmpty())
        // The restored row gets a new list key, so its swipe starts from rest instead of where it was dismissed.
        assertEquals(mapOf(tea.clientId to 1), vm.state.value.restored)
    }

    @Test
    fun aSecondDeleteCommitsTheFirst() = runTest {
        val tea = testTransaction("tea", 1500, today)
        val rice = testTransaction("rice", 20000, today)
        store.rows.value = listOf(tea, rice)
        val vm = vm()

        vm.delete(DayLine.Entry(tea))
        vm.delete(DayLine.Entry(rice))
        runCurrent()

        assertEquals(listOf(tea.clientId), store.deleted)
        assertEquals(rice.clientId, vm.state.value.pendingDelete)
        assertTrue(vm.state.value.visibleIds().isEmpty())
    }

    @Test
    fun leavingTheScreenCommitsThePendingDelete() = runTest {
        val tea = testTransaction("tea", 1500, today)
        store.rows.value = listOf(tea)
        val vm = vm()

        vm.delete(DayLine.Entry(tea))
        vm.commitPendingDelete()
        runCurrent()

        assertEquals(listOf(tea.clientId), store.deleted)
    }

    /** An entry saved from receipt [receiptId], changed at [hour] o'clock today. */
    private fun scanned(
        description: String,
        amount: Long,
        receiptId: String,
        merchant: String?,
        hour: Int,
        category: String = "Groceries",
        date: LocalDate = today,
    ) = testTransaction(description, amount, date, category = category)
        .copy(source = Source.SCAN, receiptId = receiptId, merchant = merchant, updatedAt = at(hour))

    private fun at(hour: Int): Instant = today.atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun aReceiptsEntriesAreOneLineWhereItsNewestEntryIs() = runTest {
        val milk = scanned("Ocean Supercenter, Groceries", 16307, "r1", "Ocean Supercenter", hour = 9)
        val soap = scanned("Ocean Supercenter, Other", 30000, "r1", "Ocean Supercenter", hour = 7, category = "Other")
        val tea = testTransaction("tea", 1500, today).copy(updatedAt = at(8))
        val bus = testTransaction("bus", 600, today).copy(updatedAt = at(6))
        store.rows.value = listOf(tea, soap, bus, milk)

        val day = vm().state.value.days.single()

        assertEquals(listOf("r1", tea.clientId, bus.clientId), day.lines.map { it.key })
        val receipt = day.lines[0] as DayLine.Receipt
        assertEquals("Ocean Supercenter", receipt.merchant)
        assertEquals(46307L, receipt.amountKyat)
        assertEquals("largest first", listOf(soap, milk), receipt.entries)
        assertFalse(receipt.scannedTwice)
        // The day keeps every entry, so its total is what it was.
        assertEquals(4, day.rows.size)
    }

    @Test
    fun aLaterReceiptWithTheSameShopAndTotalMayBeScannedTwice() {
        val first = listOf(
            scanned("a", 16307, "r1", "Ocean Supercenter", hour = 7),
            scanned("b", 30000, "r1", "Ocean Supercenter", hour = 7, category = "Other"),
        )
        // The same paper again, saved as one entry, the shop read in other letters.
        val again = scanned("c", 46307, "r2", "OCEAN SUPERCENTER ", hour = 9)
        val otherTotal = scanned("d", 46000, "r3", "Ocean Supercenter", hour = 10)
        val noShop = listOf(scanned("e", 500, "r4", null, hour = 11), scanned("f", 500, "r5", null, hour = 12))
        // Yesterday's receipt is not compared with today's.
        val yesterday = scanned("g", 46307, "r6", "Ocean Supercenter", hour = 8, date = today.minusDays(1))

        val days = groupByDay(first + again + otherTotal + noShop + yesterday)

        val twice = days.flatMap { it.lines }.filterIsInstance<DayLine.Receipt>().associate { it.receiptId to it.scannedTwice }
        assertEquals(mapOf("r1" to false, "r2" to true, "r3" to false, "r4" to false, "r5" to false, "r6" to false), twice)
    }

    @Test
    fun swipingAReceiptDeletesAllItsEntriesAndUndoBringsThemBack() = runTest {
        val milk = scanned("Ocean Supercenter, Groceries", 16307, "r1", "Ocean Supercenter", hour = 9)
        val soap = scanned("Ocean Supercenter, Other", 30000, "r1", "Ocean Supercenter", hour = 9, category = "Other")
        val tea = testTransaction("tea", 1500, today)
        store.rows.value = listOf(milk, soap, tea)
        val vm = vm()
        val receipt = vm.state.value.days.single().lines.first { it is DayLine.Receipt }

        vm.delete(receipt)
        runCurrent()

        assertEquals(listOf(tea.clientId), vm.state.value.visibleIds())
        assertEquals("r1", vm.state.value.pendingDelete)
        assertEquals(setOf(milk, soap), vm.state.value.pendingDeleteRows.toSet())
        assertEquals("the chart leaves the receipt out too", 1_500L, vm.state.value.pace.spent)

        vm.undoDelete("r1")
        assertEquals(setOf(milk.clientId, soap.clientId, tea.clientId), vm.state.value.visibleIds().toSet())
        assertEquals(mapOf("r1" to 1), vm.state.value.restored)
        assertTrue(store.deleted.isEmpty())

        vm.delete(receipt)
        vm.commitDelete("r1")
        runCurrent()

        assertEquals(setOf(milk.clientId, soap.clientId), store.deleted.toSet())
        assertEquals(listOf(tea.clientId), vm.state.value.visibleIds())
    }

    @Test
    fun aSyncThatRunsOnItsOwnShowsNoSpinner() = runTest {
        val vm = vm()

        sync.start() // the launch sync, or the one after a save
        assertFalse(vm.state.value.refreshing)

        sync.finish(SyncOutcome.Success(pushed = 1, pulled = 0))
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun refreshRunsAFullSyncAndSpinsUntilItEnds() = runTest {
        val vm = vm()
        val events = events(vm)

        vm.refresh()

        assertEquals(1, sync.syncNowCalls)
        assertTrue(vm.state.value.refreshing)

        sync.start()
        assertTrue(vm.state.value.refreshing)

        sync.finish(SyncOutcome.Success(pushed = 0, pulled = 3))
        assertFalse(vm.state.value.refreshing)
        assertTrue(events.isEmpty())
    }

    @Test
    fun refreshThatCannotReachTheServerSaysSo() = runTest {
        val vm = vm()
        val events = events(vm)

        vm.refresh()
        sync.start()
        sync.finish(SyncOutcome.RetryLater)

        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(TransactionsEvent.Unreachable), events)
    }

    @Test
    fun refreshDuringARunningSyncReportsTheReplacingRunsResult() = runTest {
        sync.start() // the launch sync is still running
        val vm = vm()
        val events = events(vm)

        vm.refresh()
        sync.stop(SyncStatus.Idle) // the refresh replaced it: no result, the old status comes back
        assertTrue("spinner between runs", vm.state.value.refreshing)
        sync.start()
        sync.finish(SyncOutcome.RetryLater)

        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(TransactionsEvent.Unreachable), events)
    }

    @Test
    fun refreshDoesNotReportAnOldFailureThatAStoppedRunPutsBack() = runTest {
        sync.start()
        val old = sync.finish(SyncOutcome.RetryLater)
        sync.start()
        val vm = vm()
        val events = events(vm)

        vm.refresh()
        sync.stop(old) // replaced: the old failure is the status again, but it is not this refresh's result
        assertTrue(events.isEmpty())
        sync.start()
        sync.finish(SyncOutcome.Success(pushed = 0, pulled = 1))

        assertFalse(vm.state.value.refreshing)
        assertTrue(events.isEmpty())
    }

    @Test
    fun refreshWhoseRunIsStoppedAndNotRestartedWaitsForNetwork() = runTest {
        val vm = vm()
        val events = events(vm)

        vm.refresh()
        sync.start()
        sync.stop(SyncStatus.Idle) // the connection dropped: WorkManager re-queues the run for later
        assertTrue(vm.state.value.refreshing)
        advanceTimeBy(TransactionsViewModel.START_TIMEOUT_MS + 1)

        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(TransactionsEvent.WaitingForNetwork), events)
    }

    @Test
    fun refreshThatNeverStartsStopsSpinning() = runTest {
        // No connection: WorkManager holds the sync until there is one, so it never reports Running.
        val vm = vm()
        val events = events(vm)

        vm.refresh()
        advanceTimeBy(TransactionsViewModel.START_TIMEOUT_MS + 1)

        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(TransactionsEvent.WaitingForNetwork), events)
    }

    @Test
    fun amountsFollowTheMyanmarDigitsSetting() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.myanmarDigits)

        settings.myanmarDigits.value = true

        assertTrue(vm.state.value.myanmarDigits)
    }

    @Test
    fun theOfflineChipFollowsTheNetwork() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.offline)

        network.online.value = false
        assertTrue(vm.state.value.offline)

        network.online.value = true
        assertFalse(vm.state.value.offline)
    }
}
