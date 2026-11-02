package app.budgetwise.ui.transactions

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeModelManager
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.FakeVoiceAvailability
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.voice.VoiceSetupViewModel
import app.budgetwise.ui.common.SessionNotice
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real screen and ViewModel over an in-memory ledger, on a phone-sized screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class TransactionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-21")
    private val clock = Clock.fixed(today.atTime(9, 30).atZone(zone).toInstant(), zone)

    private fun text(@StringRes id: Int) = context.getString(id)

    private lateinit var vm: TransactionsViewModel

    private fun show(
        rows: List<Transaction>,
        onAddManual: () -> Unit = {},
        sessionNotice: SessionNotice? = null,
        onSignIn: () -> Unit = {},
        voice: FakeModelManager = FakeModelManager(),
        onOpenVoice: () -> Unit = {},
        onOpenScan: () -> Unit = {},
        scanAdded: ScanAdded? = null,
        onOpenEntry: (String) -> Unit = {},
        onOpenReceipt: (String) -> Unit = {},
        voiceSupported: Boolean = true,
        onWifi: Boolean = true,
    ): FakeTransactionStore {
        val store = FakeTransactionStore(rows)
        // The delete outlives the screen in the app; here it runs straight away.
        vm = TransactionsViewModel(
            store, FakeSyncControls(), FakeSettingsStore(), FakeNetworkStatus(), clock, CoroutineScope(Dispatchers.Unconfined),
        )
        val voiceSetup = VoiceSetupViewModel(voice, FakeSettingsStore(), FakeNetworkStatus(unmetered = onWifi), FakeVoiceAvailability(voiceSupported), clock)
        compose.setContent {
            BudgetWiseTheme {
                TransactionsRoute(
                    chip = SyncChip.SYNCED,
                    sessionNotice = sessionNotice,
                    onChip = {},
                    onSettings = {},
                    onSignIn = onSignIn,
                    onAddManual = onAddManual,
                    onOpenVoice = onOpenVoice,
                    onOpenScan = onOpenScan,
                    onOpenEntry = onOpenEntry,
                    onOpenReceipt = onOpenReceipt,
                    scanAdded = scanAdded,
                    onScanAddedShown = {},
                    viewModel = vm,
                    voiceSetup = voiceSetup,
                )
            }
        }
        return store
    }

    @Test
    fun rowsShowTheirCategoryAndNoSyncMarker() {
        show(
            listOf(
                testTransaction("Tea", 1500, today, syncState = SyncState.PENDING),
                testTransaction("Rice", 20000, today, syncState = SyncState.SYNCED),
                testTransaction("Taxi", 4000, today.minusDays(1), category = "Transport", syncState = SyncState.FAILED),
            ),
        )

        // The header's chip is the only sync signal: no row says it is waiting or was refused.
        compose.onNodeWithText("Not synced", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Couldn't sync", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Tea") and hasText(text(R.string.category_groceries))).assertIsDisplayed()
        compose.onNode(hasText("Taxi") and hasText(text(R.string.category_transport))).assertIsDisplayed()
        // Day headers with the day's net, and the amount read the way it is meant: "1,500 kyat spent".
        // The day header is drawn in capitals and read as written.
        compose.onNodeWithContentDescription(text(R.string.transactions_today)).assertIsDisplayed()
        compose.onNodeWithText("−21,500 Ks").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.transactions_yesterday)).assertIsDisplayed()
        compose.onNode(hasText("Tea") and hasContentDescription("1,500 kyat spent")).assertIsDisplayed()
    }

    @Test
    fun swipeDeleteUndoKeepsRow() {
        val rice = testTransaction("Rice", 20000, today)
        val store = show(listOf(rice))

        compose.onNodeWithText("Rice").performTouchInput { swipeLeft() }

        compose.onNodeWithText(text(R.string.transactions_deleted)).assertIsDisplayed()
        compose.onNodeWithText("Rice, 20,000 Ks").assertIsDisplayed()

        compose.onNodeWithText(text(R.string.transactions_undo)).performClick()
        // The click resumes the notice's coroutine, which runs when the test clock moves on.
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Rice").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        assertTrue(store.deleted.isEmpty())
        assertEquals(listOf(rice), store.rows.value)
    }

    @Test
    fun swipeDeleteWithoutUndoDeletesWhenTheNoticeEnds() {
        val rice = testTransaction("Rice", 20000, today)
        val store = show(listOf(rice))

        compose.onNodeWithText("Rice").performTouchInput { swipeLeft() }
        assertTrue("nothing is deleted while Undo is offered", store.deleted.isEmpty())

        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()

        assertEquals(listOf(rice.clientId), store.deleted)
        compose.onNodeWithText("Rice").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_deleted)).assertDoesNotExist()
    }

    @Test
    fun rotatingKeepsTheUndoWindowOpen() {
        val rice = testTransaction("Rice", 20000, today)
        val store = show(listOf(rice))
        compose.onNodeWithText("Rice").performTouchInput { swipeLeft() }
        compose.onNodeWithText(text(R.string.transactions_deleted)).assertIsDisplayed()

        // A configuration change destroys the activity and the composition; the ViewModel stays, and the new screen
        // shows the notice again for the same row.
        compose.activityRule.scenario.recreate()

        assertTrue("a rotation must not end the Undo window", store.deleted.isEmpty())
        assertEquals(rice.clientId, vm.state.value.pendingDelete)
    }

    private fun receiptEntry(category: String, amount: Long, receiptId: String = "r1", merchant: String? = "Ocean Supercenter") =
        testTransaction("$merchant, $category", amount, today, category = category)
            .copy(source = Source.SCAN, receiptId = receiptId, merchant = merchant)

    @Test
    fun eachRowSaysHowItWasAdded() {
        show(
            listOf(
                testTransaction("Bus to Sule", 600, today, category = "Transport").copy(source = Source.VOICE),
                testTransaction("Yangon Bakehouse", 2500, today, category = "Other").copy(source = Source.SCAN),
                testTransaction("Rice", 20000, today),
            ),
        )

        val byVoice = text(R.string.transactions_by_voice)
        val scanned = text(R.string.scan_scanned)
        compose.onNode(hasText("Bus to Sule") and hasContentDescription(byVoice)).assertIsDisplayed()
        compose.onNode(hasText("Yangon Bakehouse") and hasContentDescription(scanned)).assertIsDisplayed()
        // Typed in: no mark.
        compose.onNode(hasText("Rice") and (hasContentDescription(byVoice) or hasContentDescription(scanned))).assertDoesNotExist()
        compose.onNode(hasText("Rice") and hasText(text(R.string.category_groceries))).assertIsDisplayed()
    }

    @Test
    fun aReceiptIsOneRowThatOpensTheReceipt() {
        val rice = testTransaction("Rice", 3600, today)
        val opened = mutableListOf<String>()
        val entries = mutableListOf<String>()
        show(
            listOf(receiptEntry("Groceries", 16307), receiptEntry("Other", 30000), rice),
            onOpenReceipt = { opened += it },
            onOpenEntry = { entries += it },
        )

        compose.onNode(hasText("Ocean Supercenter") and hasText("Receipt · 2 entries")).assertIsDisplayed()
        compose.onNodeWithText("−46,307 Ks").assertIsDisplayed()
        compose.onNodeWithText("Ocean Supercenter, Groceries").assertDoesNotExist()
        // The day's total still counts every entry.
        compose.onNodeWithText("−49,907 Ks").assertIsDisplayed()

        compose.onNodeWithText("Ocean Supercenter").performClick()
        compose.onNodeWithText("Rice").performClick()

        assertEquals(listOf("r1"), opened)
        assertEquals(listOf(rice.clientId), entries)
    }

    @Test
    fun aReceiptScannedAgainAsksIfItWasScannedTwice() {
        show(
            listOf(
                receiptEntry("Groceries", 16307).copy(updatedAt = clock.instant()),
                receiptEntry("Other", 30000).copy(updatedAt = clock.instant()),
                receiptEntry("Groceries", 46307, receiptId = "r2").copy(updatedAt = clock.instant().plusSeconds(60)),
            ),
        )

        compose.onAllNodesWithText("Ocean Supercenter").assertCountEquals(2)
        compose.onNodeWithText(text(R.string.receipt_scanned_twice)).assertIsDisplayed()
        compose.onNodeWithText("Receipt · 2 entries").assertIsDisplayed()
        compose.onNodeWithText("Receipt · 1 entry").assertDoesNotExist()
    }

    @Test
    fun swipingAReceiptAwayUndoBringsBackAllItsEntries() {
        val rows = listOf(receiptEntry("Groceries", 16307), receiptEntry("Other", 30000))
        val store = show(rows)

        compose.onNodeWithText("Ocean Supercenter").performTouchInput { swipeLeft() }

        compose.onNodeWithText(text(R.string.receipt_deleted)).assertIsDisplayed()
        compose.onNodeWithText("Ocean Supercenter, 46,307 Ks").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_undo)).performClick()
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Ocean Supercenter").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        assertTrue(store.deleted.isEmpty())
        assertEquals(rows, store.rows.value)
    }

    @Test
    fun swipingAReceiptAwayDeletesAllItsEntriesWhenTheNoticeEnds() {
        val rows = listOf(receiptEntry("Groceries", 16307), receiptEntry("Other", 30000))
        val store = show(rows)

        compose.onNodeWithText("Ocean Supercenter").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()

        assertEquals(rows.map { it.clientId }.toSet(), store.deleted.toSet())
        compose.onNodeWithText("Ocean Supercenter").assertDoesNotExist()
    }

    @Test
    fun monthHeadShowsWhatIsLeftAndTheSpendingSoFar() {
        show(
            listOf(
                testTransaction("Rice", 20000, today),
                testTransaction("Salary", 500000, today.withDayOfMonth(1), type = TxType.INCOME),
            ),
        )

        compose.onNode(hasContentDescription("Left in October, 480,000 kyat")).assertIsDisplayed()
        // With no September entries there is nothing to compare: the verdict and the chart say what was spent.
        compose.onNodeWithText("20,000 Ks spent so far this month").assertIsDisplayed()
        compose.onNode(hasContentDescription("Spent so far in October: 20,000 kyat.")).assertIsDisplayed()
        // The day's net in its header and the entry itself.
        compose.onAllNodesWithText("+500,000 Ks")[0].assertIsDisplayed()
    }

    @Test
    fun theChartComparesWithLastMonthByTheSameDay() {
        show(
            listOf(
                testTransaction("Rice", 20000, today),
                testTransaction("Taxi", 6000, today.withDayOfMonth(2), category = "Transport"),
                testTransaction("Market", 30000, today.minusMonths(1).withDayOfMonth(5)),
                // After the 21st: not part of "this time in September".
                testTransaction("Rent", 90000, today.minusMonths(1).withDayOfMonth(28), category = "Housing"),
            ),
        )

        compose.onNodeWithText("4,000 Ks less than this time in September").assertIsDisplayed()
        compose.onNode(hasContentDescription("Spent so far in October: 26,000 kyat. By the same day in September: 30,000 kyat."))
            .assertIsDisplayed()

        compose.onNodeWithText(text(R.string.spending_by_category)).performClick()
        compose.onNode(hasContentDescription("Groceries, 20,000 kyat so far, 30,000 kyat by this time in September")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Transport, 6,000 kyat so far, 0 kyat by this time in September")).assertIsDisplayed()
    }

    @Test
    fun aSessionEndingSoonSaysSoOnceWithSignIn() {
        var signIns = 0
        show(listOf(testTransaction("Rice", 20000, today)), sessionNotice = SessionNotice.ExpiringSoon(3), onSignIn = { signIns++ })

        compose.onNodeWithText("You'll be signed out in 3 days").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_sign_in)).performClick()
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(1, signIns)
    }

    @Test
    fun emptyStateAddsTheFirstExpense() {
        var opened = 0
        show(emptyList(), onAddManual = { opened++ })

        compose.onNodeWithText(text(R.string.transactions_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_add_first)).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun anIncomeAloneOffersTheFirstExpenseInsteadOfTheChart() {
        var opened = 0
        show(listOf(testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income")), onAddManual = { opened++ })

        compose.onNode(hasContentDescription("Left in October, 850,000 kyat")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.spending_by_category)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.first_expense_type)).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theFirstDayOffersSayItWhichOpensVoiceOnceItIsOnThePhone() {
        val income = listOf(testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income"))
        var opened = 0
        show(income, voice = FakeModelManager(app.budgetwise.asr.ModelState.Ready("/voice")), onOpenVoice = { opened++ })

        compose.onNodeWithText(text(R.string.first_expense_say_detail)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_say_it)).performClick()

        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun aPhoneWithoutTheMemoryForVoiceOffersTypingOnly() {
        val income = listOf(testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income"))
        show(income, voiceSupported = false)

        compose.onNodeWithText(text(R.string.first_expense_type)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_say_it)).assertDoesNotExist()
    }

    @Test
    fun sayItBeforeTheDownloadOffersItAndShowsItsProgress() {
        val income = listOf(testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income"))
        val voice = FakeModelManager()
        var opened = 0
        show(income, voice = voice, onOpenVoice = { opened++ }, onWifi = false)

        compose.onNodeWithText(text(R.string.voice_say_it)).performClick()
        compose.onNodeWithText(text(R.string.voice_setup_title)).assertIsDisplayed()
        compose.onNodeWithText("Download 1.1 GB").performClick()

        compose.onNodeWithText(text(R.string.voice_downloading_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_waiting_wifi)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("on Wi-Fi only", listOf(false), voice.downloads) }
        // Finished while the card is open: voice opens.
        voice.state.value = app.budgetwise.asr.ModelState.Ready("/voice")
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithText(text(R.string.voice_downloading_title)).assertDoesNotExist()
    }

    @Test
    fun theFirstExpenseRowGoesOnceThereIsAnExpense() {
        show(
            listOf(
                testTransaction("Salary", 850000, today.withDayOfMonth(1), type = TxType.INCOME, category = "Income"),
                // Last month's: any expense, in any month, means the user has added one.
                testTransaction("Rice", 20000, today.minusMonths(1)),
            ),
        )

        compose.onNodeWithText(text(R.string.first_expense_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.spending_by_category)).assertIsDisplayed()
    }

    @Test
    fun theFirstDayOffersToScanAReceipt() {
        var scans = 0
        show(listOf(testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income")), onOpenScan = { scans++ })

        compose.onNodeWithText(text(R.string.scan_entry)).performClick()

        assertEquals(1, scans)
    }

    @Test
    fun undoOnAddedDeletesWhatTheScanSaved() {
        val rows = listOf(
            testTransaction("City Mart, Groceries", 13400, today, clientId = "a"),
            testTransaction("City Mart, Other", 1550, today, clientId = "b"),
        )
        val store = show(rows, scanAdded = ScanAdded(listOf("a", "b"), "City Mart"))

        compose.onNodeWithText("Added 2 entries").assertIsDisplayed()
        compose.onNodeWithText("From your City Mart receipt").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_undo)).performClick()
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(listOf("a", "b"), store.deleted)
    }

    @Test
    fun withoutVoiceTheFirstDayStillOffersScanning() {
        show(listOf(testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income")), voiceSupported = false)

        compose.onNodeWithText(text(R.string.scan_entry)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_say_it)).assertDoesNotExist()
    }
}
