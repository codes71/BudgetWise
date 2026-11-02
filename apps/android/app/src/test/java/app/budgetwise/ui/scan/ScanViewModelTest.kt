package app.budgetwise.ui.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** The server never answers (a stalled upload): the read is only ended by its time limit or Cancel. */
    private var hang = false
    private val scanner = ReceiptScanner { image, _ ->
        sent += image
        if (hang) awaitCancellation()
        reply
    }
    private val network = FakeNetworkStatus(online = false)
    private val photos = FakeScanPhotos()
    private val camera = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = ScanViewModel(
        scanner, store, FakeCategorySource(), FakeSettingsStore(), network, photos, saved,
        Clock.fixed(today.atStartOfDay(zone).toInstant(), zone),
    )

    /** What the phone keeps when it closes BudgetWise: the saved state as it was, and the photo on disk. */
    private fun afterProcessDeath(saved: SavedStateHandle) =
        viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

    /** [ScanPhotos] in memory: the "disk" outlives a view model, as the cache file outlives the process. */
    private class FakeScanPhotos : ScanPhotos {
        var opened: OpenedPhoto? = null
        var dark = false
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var prepared = 0
        var disk: ByteArray? = null

        override suspend fun open(uri: Uri) = opened ?: OpenedPhoto.Photo(Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888))

        override suspend fun tooDark(bitmap: Bitmap) = dark

        override suspend fun prepare(bitmap: Bitmap): PreparedPhoto {
            prepared++
            gate?.await()
            failure?.let { throw it }
            return PreparedPhoto(byteArrayOf(9, 9), bitmap)
        }

        override suspend fun keep(bytes: ByteArray) {
            disk = bytes
        }

        override suspend fun kept() = disk?.let { PreparedPhoto(it, Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)) }

        override fun drop() {
            disk = null
        }
    }

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
            // None of these is the photo's fault: a reply the phone cannot read, a deployment without /scan, a
            // firewall's HTML page, a body the server refused.
            ApiResult.Failure("bad_response", "Unexpected server response", retryable = false) to ScanProblem.SERVER,
            ApiResult.Failure("http_404", "HTTP 404", retryable = false) to ScanProblem.SERVER,
            ApiResult.Failure("http_403", "HTTP 403", retryable = false) to ScanProblem.SERVER,
            ApiResult.Failure("validation_failed", "bad body", retryable = false) to ScanProblem.SERVER,
            ApiResult.Failure("invalid_image", "not a JPEG", retryable = false) to ScanProblem.UNREADABLE,
            ApiResult.Unauthorized("expired") to ScanProblem.SIGN_IN,
            // A photo of a computer screen: no receipt in it at all.
            ApiResult.Ok(ScanReading(ScanKind.UNKNOWN, null, null, null, emptyList(), notMoney = true)) to ScanProblem.NOT_A_RECEIPT,
            // A receipt too blurred to read, and an older server that gives no reason.
            ApiResult.Ok(ScanReading(ScanKind.UNKNOWN, null, null, null, emptyList())) to ScanProblem.UNREADABLE,
            // A receipt the server read, none of whose entries the phone could use.
            ApiResult.Ok(ScanReading(ScanKind.RECEIPT, "City Mart", null, 5000, emptyList())) to ScanProblem.UNREADABLE,
        )
        for ((result, problem) in cases) {
            reply = result
            vm.read(photo)
            assertEquals(ScanStage.PROBLEM, vm.state.value.stage)
            assertEquals(problem, vm.state.value.problem)
            if (problem == ScanProblem.SERVER) assertSame("kept for Try again", photo, vm.photo)
        }
    }

    @Test
    fun aDroppedConnectionOnAPhoneThatIsOnlineIsSlowNotOffline() = runTest {
        network.online.value = true
        reply = ApiResult.Failure(NETWORK_ERROR_CODE, "timeout", retryable = true)
        val vm = viewModel()
        vm.read(photo)

        assertEquals(ScanProblem.SLOW, vm.state.value.problem)
        assertSame(photo, vm.photo)
    }

    @Test
    fun aLongReadSaysSoAndEndsAfterItsLimitWithThePhotoKept() = runTest {
        hang = true
        val vm = viewModel()
        vm.read(photo)
        assertEquals(ScanStage.READING, vm.state.value.stage)
        assertFalse(vm.state.value.slow)

        advanceTimeBy(ScanViewModel.SLOW_NOTICE.inWholeMilliseconds + 1)
        assertTrue("still reading, and it can take a minute", vm.state.value.slow)
        assertEquals(ScanStage.READING, vm.state.value.stage)

        advanceTimeBy((ScanViewModel.READ_LIMIT - ScanViewModel.SLOW_NOTICE).inWholeMilliseconds)
        assertEquals(ScanProblem.SLOW, vm.state.value.problem)
        assertFalse(vm.state.value.slow)

        hang = false
        vm.tryAgain()
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
        assertEquals("the same photo, sent again", listOf(photo.bytes, photo.bytes), sent)
    }

    @Test
    fun tooManyScansCountsDownToTryAgain() = runTest {
        reply = ApiResult.Failure("rate_limited", "slow down", retryable = true, retryAfterSec = 90)
        val vm = viewModel()
        vm.read(photo)
        assertEquals(ScanProblem.TOO_MANY, vm.state.value.problem)
        assertEquals(2, vm.state.value.waitMinutes)

        advanceTimeBy(30_001)
        assertEquals(1, vm.state.value.waitMinutes)
        advanceTimeBy(60_000)
        assertNull("Try again is back", vm.state.value.waitMinutes)
        assertEquals(ScanProblem.TOO_MANY, vm.state.value.problem)
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
    fun aChosenPhotoThatCannotBeUsedSaysWhyAndSendsNothing() = runTest {
        val vm = viewModel()

        photos.opened = OpenedPhoto.TooLong
        vm.picked(Uri.EMPTY)
        assertEquals(ScanProblem.TOO_LONG, vm.state.value.problem)

        // Choose another photo, from the card.
        photos.opened = OpenedPhoto.CannotOpen
        vm.picked(Uri.EMPTY)
        assertEquals(ScanProblem.CANNOT_OPEN, vm.state.value.problem)

        photos.opened = OpenedPhoto.Photo(camera)
        photos.failure = OutOfMemoryError("a huge picture")
        vm.picked(Uri.EMPTY)
        assertEquals("out of memory is caught, not a crash", ScanProblem.CANNOT_OPEN, vm.state.value.problem)
        assertEquals(0, sent.size)

        photos.failure = null
        vm.picked(Uri.EMPTY)
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
        assertEquals(1, sent.size)
    }

    @Test
    fun aCameraPhotoTooDarkToReadIsNotSent() = runTest {
        photos.dark = true
        val vm = viewModel()

        vm.captured(camera)

        assertEquals(ScanProblem.TOO_DARK, vm.state.value.problem)
        assertNull(vm.photo)
        assertEquals(0, sent.size)
    }

    @Test
    fun aFailedCaptureSaysSoOnTheCamera() = runTest {
        val vm = viewModel()
        vm.captureFailed()
        assertEquals(ScanStage.CAMERA, vm.state.value.stage)
        assertTrue(vm.state.value.captureFailed)

        // The photo could not be encoded: back to the camera, which says so.
        photos.failure = java.io.IOException("could not encode")
        vm.captured(camera)
        assertEquals(ScanStage.CAMERA, vm.state.value.stage)
        assertTrue(vm.state.value.captureFailed)

        photos.failure = null
        vm.captured(camera)
        assertFalse(vm.state.value.captureFailed)
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
    }

    @Test
    fun onePhotoAtATimeAndCancelWhilePreparingSendsNothing() = runTest {
        photos.gate = CompletableDeferred()
        val vm = viewModel()

        vm.captured(camera)
        assertEquals(ScanStage.PREPARING, vm.state.value.stage)
        // A second tap's photo while the first is being prepared.
        vm.captured(camera)
        vm.picked(Uri.EMPTY)
        assertEquals(1, photos.prepared)

        vm.retake()
        photos.gate!!.complete(Unit)
        assertEquals(ScanStage.CAMERA, vm.state.value.stage)
        assertEquals("the finished preparation does not start a read", 0, sent.size)
    }

    @Test
    fun theReviewIsEditedUntilEveryChangeIsUndone() = runTest {
        val vm = viewModel()
        vm.read(photo)
        assertFalse(vm.state.value.edited)

        vm.setCount(0, 0, 3)
        assertTrue(vm.state.value.edited)
        vm.setCount(0, 0, 1)
        assertFalse("back to what was read", vm.state.value.edited)

        for (change in listOf<(ScanViewModel) -> Unit>({ it.setPrice(1, 0, 3000) }, { it.toggle(0) }, { it.move(2, "Groceries") })) {
            vm.retake()
            vm.read(photo)
            change(vm)
            assertTrue(vm.state.value.edited)
        }

        reply = ApiResult.Ok(receipt().copy(entries = receipt().entries.take(2)))
        vm.retake()
        vm.read(photo)
        vm.addShortAsOther()
        assertTrue(vm.state.value.edited)

        vm.retake()
        assertFalse(vm.state.value.edited)
    }

    @Test
    fun aReviewWithItsChangesComesBackAfterThePhoneClosedBudgetWise() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.captured(camera)
        vm.setCount(0, 0, 2)
        vm.setPrice(1, 1, 3000)
        vm.move(2, "Groceries")
        val before = vm.state.value

        val restored = afterProcessDeath(saved).state.value

        assertEquals(ScanStage.REVIEW, restored.stage)
        assertEquals(before.lines, restored.lines)
        assertEquals(before.readLines, restored.readLines)
        assertEquals(before.changedItems, restored.changedItems)
        assertEquals(before.shownTotal, restored.shownTotal)
        assertEquals("City Mart", restored.merchant)
        assertEquals(day, restored.date)
        assertTrue(restored.edited)
        assertNotNull(restored.photo)
    }

    @Test
    fun aReadThePhoneCutOffComesBackWithTryAgainNotSentAgain() = runTest {
        hang = true
        val saved = SavedStateHandle()
        viewModel(saved).captured(camera)
        assertEquals(1, sent.size)

        val vm = afterProcessDeath(saved)

        assertEquals(ScanStage.PROBLEM, vm.state.value.stage)
        assertEquals(ScanProblem.INTERRUPTED, vm.state.value.problem)
        assertEquals("each send counts toward the hour's 20", 1, sent.size)
        hang = false
        vm.tryAgain()
        assertEquals(ScanStage.REVIEW, vm.state.value.stage)
        assertEquals(listOf(9, 9), sent.last().map { it.toInt() })
    }

    @Test
    fun withoutItsPhotoACutOffReadStartsAgainAtTheCamera() = runTest {
        hang = true
        val saved = SavedStateHandle()
        viewModel(saved).captured(camera)
        photos.disk = null

        assertEquals(ScanStage.CAMERA, afterProcessDeath(saved).state.value.stage)
    }

    @Test
    fun nothingIsKeptOnceTheEntriesAreSavedOrTheScanIsDropped() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.captured(camera)
        assertNotNull(photos.disk)

        vm.addEntries { "City Mart, ${it.category}" }

        assertNull("the receipt photo is deleted", photos.disk)
        assertTrue("nothing to bring back", saved.keys().isEmpty())
        assertEquals(ScanStage.CAMERA, afterProcessDeath(saved).state.value.stage)

        val again = viewModel()
        again.captured(camera)
        again.retake()
        assertNull(photos.disk)
    }
}
