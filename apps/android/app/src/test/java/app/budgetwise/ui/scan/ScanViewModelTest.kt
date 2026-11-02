package app.budgetwise.ui.scan

import android.graphics.Bitmap
import app.budgetwise.ReceiptScanner
import app.budgetwise.data.model.Source
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.ScanEntry
import app.budgetwise.data.repo.ScanItem
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.data.repo.ScanReading
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScanViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-30")
    private val day = LocalDate.parse("2026-10-23")
    private val store = FakeTransactionStore(emptyList())
    private val photo = PreparedPhoto(byteArrayOf(1, 2, 3), Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

    private var reply: ApiResult<ScanReading> = ApiResult.Ok(receipt())
    private val sent = mutableListOf<ByteArray>()
    private val scanner = ReceiptScanner { image, _ ->
        sent += image
        reply
    }

    private fun viewModel() = ScanViewModel(
        scanner, store, FakeCategorySource(), FakeSettingsStore(), Clock.fixed(today.atStartOfDay(zone).toInstant(), zone),
    )

    private fun entry(category: String, amount: Long, items: Int) = ScanEntry(
        TxType.EXPENSE, amount, category, "City Mart", day, dateGuessed = false, clientId = null,
        items = List(items) { ScanItem("item $it", amount / items) },
    )

    private fun receipt(total: Long? = 20650) = ScanReading(
        ScanKind.RECEIPT, "City Mart", day, total,
        listOf(entry("Groceries", 13400, 2), entry("Food and Drink Item", 5700, 2), entry("Other", 1550, 1)),
    )

    private fun TestScope.collectEvents(vm: ScanViewModel): List<ScanEvent> {
        val events = mutableListOf<ScanEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    @Test
    fun aReceiptBecomesOneLinePerCategory() = runTest {
        val vm = viewModel()
        vm.read(photo)

        val s = vm.state.value
        assertEquals(ScanStage.REVIEW, s.stage)
        assertEquals(listOf("Groceries", "Food and Drink Item", "Other"), s.lines.map { it.category })
        assertEquals(20650L, s.shownTotal)
        assertNull("the lines add up to the total", s.shortBy)
        assertSame(photo.bytes, sent.single())
    }

    @Test
    fun movingALineJoinsTheLineAlreadyThere() = runTest {
        val vm = viewModel()
        vm.read(photo)

        vm.move(2, "Groceries")

        val lines = vm.state.value.lines
        assertEquals(listOf("Groceries", "Food and Drink Item"), lines.map { it.category })
        assertEquals(14950L, lines[0].amountKyat)
        assertEquals(3, lines[0].items.size)
    }

    @Test
    fun whatTheItemsMissCanGoUnderOther() = runTest {
        reply = ApiResult.Ok(receipt().copy(entries = receipt().entries.take(2)))
        val vm = viewModel()
        vm.read(photo)
        assertEquals(1550L, vm.state.value.shortBy)

        vm.addShortAsOther()

        assertNull(vm.state.value.shortBy)
        assertEquals(CategoryRepository.BUILT_IN.last(), vm.state.value.lines.last().category)
    }

    @Test
    fun eachLineIsSavedAsItsOwnEntryWithTheShopInItsDescription() = runTest {
        val vm = viewModel()
        val events = collectEvents(vm)
        vm.read(photo)

        vm.addEntries { "City Mart, ${it.category}" }

        assertEquals(listOf("City Mart, Groceries", "City Mart, Food and Drink Item", "City Mart, Other"), store.added.map { it.description })
        assertEquals(setOf(day), store.added.map { it.date }.toSet())
        val saved = events.single() as ScanEvent.Saved
        assertEquals(3, saved.clientIds.size)
        assertEquals("City Mart", saved.merchant)
    }

    @Test
    fun asOneEntryTheTotalGoesUnderTheLargestCategory() = runTest {
        val vm = viewModel()
        vm.read(photo)

        vm.addAsOne("City Mart")

        val one = store.added.single()
        assertEquals(20650L, one.amountKyat)
        assertEquals("Groceries", one.category)
        assertEquals("City Mart", one.description)
    }

    @Test
    fun changingCountsAndPricesMovesTheLinesAndTheTotal() = runTest {
        val vm = viewModel()
        vm.read(photo)

        // Groceries is two items at 6,700; Other one at 1,550.
        vm.setCount(0, 0, 2)
        assertEquals(20100L, vm.state.value.lines[0].amountKyat)
        assertEquals(1, vm.state.value.changedItems)

        vm.setPrice(0, 1, 6000)
        assertEquals(19400L, vm.state.value.lines[0].amountKyat)

        vm.setCount(2, 0, 0)
        val s = vm.state.value
        assertEquals(0L, s.lines[2].amountKyat)
        assertEquals(3, s.changedItems)
        assertEquals("the line left at 0 is not saved", listOf("Groceries", "Food and Drink Item"), s.keptLines.map { it.category })
        // The printed total gives way to what the lines now come to; what was scanned is still said.
        assertEquals(19400L + 5700L, s.shownTotal)
        assertEquals(20650L, s.scannedTotal)

        vm.setCount(1, 0, 1000)
        assertEquals(ReviewLine.MAX_COUNT, vm.state.value.lines[1].items[0].quantity)
        vm.setCount(1, 0, -1)
        assertEquals(0, vm.state.value.lines[1].items[0].quantity)
    }

    @Test
    fun countingBackToWhatWasReadIsNoChange() = runTest {
        val vm = viewModel()
        vm.read(photo)

        vm.setCount(0, 0, 3)
        vm.setCount(0, 0, 1)

        assertEquals(0, vm.state.value.changedItems)
        assertEquals(20650L, vm.state.value.shownTotal)
    }

    @Test
    fun aChangedItemPutsTheShortByNoticeAway() = runTest {
        reply = ApiResult.Ok(receipt().copy(entries = receipt().entries.take(2)))
        val vm = viewModel()
        vm.read(photo)
        assertEquals(1550L, vm.state.value.shortBy)

        vm.setPrice(0, 0, 7000)

        assertNull("the printed total no longer says what is missing", vm.state.value.shortBy)
    }

    @Test
    fun theEntriesAreSavedWithTheChangedItems() = runTest {
        val vm = viewModel()
        vm.read(photo)

        vm.setCount(0, 0, 2)
        vm.setPrice(1, 1, 3000)
        vm.setCount(2, 0, 0)
        vm.addEntries { "City Mart, ${it.category}" }

        assertEquals(listOf("Groceries", "Food and Drink Item"), store.added.map { it.category })
        assertEquals(listOf(20100L, 5850L), store.added.map { it.amountKyat })
        assertEquals(listOf(ScanItem("item 0", 2, 6700), ScanItem("item 1", 6700)), store.added[0].items)
        assertEquals(listOf(ScanItem("item 0", 2850), ScanItem("item 1", 3000)), store.added[1].items)
        assertEquals(setOf(Source.SCAN), store.added.map { it.source }.toSet())
        assertEquals("one receipt", 1, store.added.map { it.receiptId }.toSet().size)
    }

    @Test
    fun asOneEntryTheChangedTotalLeavesOutWhatIsAtZero() = runTest {
        val vm = viewModel()
        vm.read(photo)

        vm.setCount(2, 0, 0)
        vm.addAsOne("City Mart")

        val one = store.added.single()
        assertEquals(13400L + 5700L, one.amountKyat)
        assertEquals(4, one.items.size)
        assertEquals("City Mart", one.merchant)
    }

    @Test
    fun aKpayHistoryRowLeftOutIsNotSavedAndKeepsItsId() = runTest {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        reply = ApiResult.Ok(
            ScanReading(
                ScanKind.HISTORY, null, null, null,
                listOf(
                    ScanEntry(TxType.INCOME, 150000, CategoryRepository.INCOME, "ဦးမောင်မောင်", day, false, kpay, emptyList()),
                    ScanEntry(TxType.EXPENSE, 2500, "Other", "Yangon Bakehouse", day, false, null, emptyList()),
                ),
            ),
        )
        val vm = viewModel()
        vm.read(photo)

        vm.toggle(1)
        vm.addEntries { error("only receipts are described") }

        assertEquals(listOf("ဦးမောင်မောင်"), store.added.map { it.description })
        assertEquals(kpay, store.added.single().clientId)
    }

    @Test
    fun eachFailureHasItsCard() = runTest {
        val vm = viewModel()
        val cases = listOf(
            ApiResult.Failure(NETWORK_ERROR_CODE, "offline", retryable = true) to ScanProblem.NO_INTERNET,
            ApiResult.Failure("rate_limited", "slow down", retryable = true) to ScanProblem.TOO_MANY,
            ApiResult.Failure("scan_failed", "could not read", retryable = true) to ScanProblem.SERVER,
            ApiResult.Failure("http_503", "down", retryable = true) to ScanProblem.SERVER,
            ApiResult.Unauthorized("expired") to ScanProblem.SIGN_IN,
            ApiResult.Ok(ScanReading(ScanKind.UNKNOWN, null, null, null, emptyList())) to ScanProblem.UNREADABLE,
        )
        for ((result, problem) in cases) {
            reply = result
            vm.read(photo)
            assertEquals(ScanStage.PROBLEM, vm.state.value.stage)
            assertEquals(problem, vm.state.value.problem)
        }
    }

    @Test
    fun tryAgainSendsTheSamePhotoAndRetakeDropsIt() = runTest {
        reply = ApiResult.Failure(NETWORK_ERROR_CODE, "offline", retryable = true)
        val vm = viewModel()
        vm.read(photo)

        reply = ApiResult.Ok(receipt())
        vm.tryAgain()
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
        assertEquals(2, sent.size)

        vm.retake()
        assertEquals(ScanStage.CAMERA, vm.state.value.stage)
        assertNull(vm.photo)
    }

    @Test
    fun aPhotoThatCannotBeOpenedSaysSoAndSendsNothing() = runTest {
        val vm = viewModel()

        vm.cannotOpen()

        assertEquals(ScanStage.PROBLEM, vm.state.value.stage)
        assertEquals(ScanProblem.UNREADABLE, vm.state.value.problem)
        assertEquals(0, sent.size)
    }
}
