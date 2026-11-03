package app.budgetwise.ui.manual

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.DefaultTransactionStore
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.AuthBindingsModule
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.TestUser
import app.budgetwise.testing.testTransaction
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Form rules run against fakes. The offline save runs against the real Hilt graph (Room, outbox, WorkManager in test
 * mode); only the Keystore token store is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@UninstallModules(AuthBindingsModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AddManualViewModelTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val main = MainDispatcherRule()

    @BindValue @JvmField val tokenStore: TokenStore =
        FakeTokenStore(StoredSession("tok", Instant.now().plus(Duration.ofDays(20)), TestUser))

    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var session: SessionManager

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-21")
    private val clock = Clock.fixed(today.atTime(9, 30).atZone(zone).toInstant(), zone)
    private val store = FakeTransactionStore()
    private val categories = FakeCategorySource()
    private val settings = FakeSettingsStore()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        hilt.inject()
        runBlocking { session.load() }
    }

    private fun vm(
        transactions: TransactionStore = store,
        sessionManager: SessionManager = session,
        saved: SavedStateHandle = SavedStateHandle(),
        clock: Clock = this.clock,
    ) = AddManualViewModel(transactions, categories, settings, sessionManager, clock, main.dispatcher, saved)

    /** The phone's clock, moved on by a test (a form left open over midnight). */
    private class MovableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)

        override fun instant(): Instant = now
    }

    private fun TestScope.events(vm: AddManualViewModel): List<AddManualEvent> {
        val events = mutableListOf<AddManualEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    private fun AddManualViewModel.fill(
        amount: String = "5000",
        category: String = "Groceries",
        description: String = "rice",
    ) {
        onAmountChange(amount)
        onCategoryChange(category)
        onDescriptionChange(description)
    }

    @Test
    fun startsAsAnExpenseForTodayWithTheCachedCategories() = runTest {
        val vm = vm()
        val s = vm.state.value

        assertEquals(TxType.EXPENSE, s.type)
        assertEquals(today, s.date)
        assertEquals(today, s.today)
        assertEquals(CategoryRepository.BUILT_IN, s.categories)
        assertNull(s.category)
        assertFalse(s.canSave)
        // Nothing is red before the user has typed anything.
        assertNull(s.amountError)
        assertNull(s.descriptionError)
        assertNull(s.categoryError)
        // Signed in: the server's category list is fetched for next time.
        assertEquals(1, categories.refreshes)
    }

    @Test
    fun manual_rejectsZeroAmount() = runTest {
        val vm = vm()
        vm.onCategoryChange("Groceries")
        vm.onDescriptionChange("rice")
        assertFalse(vm.state.value.canSave)

        vm.onAmountChange("0")
        assertEquals("0", vm.state.value.amountDigits)
        assertEquals(AmountError.ZERO, vm.state.value.amountError)
        assertFalse(vm.state.value.canSave)

        vm.onAmountChange("")
        assertEquals(AmountError.MISSING, vm.state.value.amountError)
        assertFalse(vm.state.value.canSave)

        vm.save()
        assertTrue(store.added.isEmpty())

        vm.onAmountChange("5,000")
        assertNull(vm.state.value.amountError)
        assertEquals(5000L, vm.state.value.amount)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun amountTakesWholeKyatUpToTheLargestExactAmount() = runTest {
        val vm = vm()

        vm.onAmountChange("12a3")
        assertEquals("123", vm.state.value.amountDigits)
        vm.onAmountChange("0012")
        assertEquals("12", vm.state.value.amountDigits)
        vm.onAmountChange("၅၀၀၀") // a Burmese keyboard types Myanmar digits
        assertEquals("5000", vm.state.value.amountDigits)
        vm.onAmountChange("9007199254740991")
        assertEquals(9_007_199_254_740_991L, vm.state.value.amount)
        vm.onAmountChange("9007199254740992") // 2^53: rejected, the field keeps its value
        assertEquals("9007199254740991", vm.state.value.amountDigits)
        vm.onAmountChange("150")
        vm.onAmountChange("150.5") // no decimals
        assertEquals("150", vm.state.value.amountDigits)
    }

    @Test
    fun rejectsBlankDescriptionAndFutureDate() = runTest {
        val vm = vm()
        vm.fill(description = "   ")

        assertEquals(DescriptionError.BLANK, vm.state.value.descriptionError)
        assertFalse(vm.state.value.canSave)

        vm.onDescriptionChange("x".repeat(AddManualViewModel.MAX_DESCRIPTION + 1))
        assertEquals(DescriptionError.TOO_LONG, vm.state.value.descriptionError)
        assertFalse(vm.state.value.canSave)

        vm.onDescriptionChange("လက်ဖက်ရည်")
        assertNull(vm.state.value.descriptionError)
        assertTrue(vm.state.value.canSave)

        vm.onDateChange(today.plusDays(1))
        assertEquals(DateError.FUTURE, vm.state.value.dateError)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(store.added.isEmpty())

        vm.onDateChange(today.minusDays(3))
        assertNull(vm.state.value.dateError)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun theDescriptionLimitCountsTheConvertedText() = runTest {
        // Short as typed (Zawgyi), longer once converted to Unicode, which is what is stored and what the server counts.
        val zawgyi = "ဒီေန႔ ေဈးမွာ ဆန္ဝယ္ခဲ့တယ္" + "ၻ".repeat(100)
        assertTrue(zawgyi.trim().length <= AddManualViewModel.MAX_DESCRIPTION)
        val vm = vm()
        vm.fill(description = zawgyi)

        assertTrue(vm.state.value.descriptionLength > AddManualViewModel.MAX_DESCRIPTION)
        assertEquals(DescriptionError.TOO_LONG, vm.state.value.descriptionError)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(store.added.isEmpty())
    }

    @Test
    fun categoryIsRequired() = runTest {
        val vm = vm()
        vm.onAmountChange("5000")
        vm.onDescriptionChange("rice")

        // Everything else is filled in, so the one thing that blocks Save is shown.
        assertEquals(CategoryError.MISSING, vm.state.value.categoryError)
        assertFalse(vm.state.value.canSave)

        vm.onCategoryChange("Transport")
        assertNull(vm.state.value.categoryError)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun savesTheFormAsOneManualRow() = runTest {
        val vm = vm()
        val events = events(vm)
        vm.onTypeChange(TxType.INCOME)
        vm.fill(amount = "250000", category = "Other", description = "  salary  ")
        vm.onDateChange(today.minusDays(1))

        vm.save()
        vm.save() // a double tap saves once

        // Income always goes under "Income", as on the web, whatever chip was picked before.
        assertEquals(
            listOf(NewTransaction(TxType.INCOME, 250_000, CategoryRepository.INCOME, "salary", today.minusDays(1), Source.MANUAL, transcript = null)),
            store.added,
        )
        assertEquals(listOf(AddManualEvent.Saved), events)
    }

    @Test
    fun incomeGoesUnderIncomeWithoutAskingForACategory() = runTest {
        val vm = vm()
        vm.onTypeChange(TxType.INCOME)
        vm.onAmountChange("850000")
        vm.onDescriptionChange("salary")

        assertNull(vm.state.value.categoryError)
        assertTrue(vm.state.value.canSave)
        vm.save()

        assertEquals(CategoryRepository.INCOME, store.added.single().category)
    }

    @Test
    fun switchingToIncomeAndBackKeepsThePickedCategory() = runTest {
        val vm = vm()
        vm.fill(category = "Transport")
        vm.onTypeChange(TxType.INCOME)
        vm.onTypeChange(TxType.EXPENSE)
        vm.save()

        assertEquals("Transport", store.added.single().category)
    }

    @Test
    fun aFailedSaveKeepsTheFormAndSaysSo() = runTest {
        store.addFailure = IllegalStateException("disk full")
        val vm = vm()
        val events = events(vm)
        vm.fill()

        vm.save()

        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.saving)
        assertEquals("5000", vm.state.value.amountDigits)
        assertTrue(vm.state.value.canSave)
        assertTrue(events.isEmpty())
    }

    @Test
    fun withoutASessionTheCategoriesAreNotFetched() = runTest {
        val expired = SessionManager(FakeTokenStore(StoredSession("", Instant.EPOCH, TestUser)), Clock.systemUTC())
            .also { it.load() }

        vm(sessionManager = expired)

        assertEquals(0, categories.refreshes)
    }

    @Test
    fun aHalfFilledFormSurvivesProcessDeath() = runTest {
        val saved = SavedStateHandle()
        val before = vm(saved = saved)
        before.onTypeChange(TxType.INCOME)
        before.onAmountChange("12500")
        before.onDescriptionChange("salary")
        before.onDateChange(today.minusDays(2))

        // The system keeps only what the handle holds (Bundle values) and builds a new ViewModel from it.
        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val after = vm(saved = restoredHandle).state.value

        assertEquals(TxType.INCOME, after.type)
        assertEquals("12500", after.amountDigits)
        assertEquals("salary", after.description)
        assertEquals(today.minusDays(2), after.date)
        assertNull(after.category)
        assertTrue(after.amountTouched)
        assertTrue(after.descriptionTouched)
        assertFalse(after.categoryTouched)
        // Income needs no category, so the restored form can be saved as it is.
        assertNull(after.categoryError)
        assertTrue(after.canSave)
    }

    @Test
    fun aFailedCategoryRefreshIsIgnored() = runTest {
        // Writing the fetched list to DataStore can throw an IOException; the form keeps the cached list.
        categories.refreshFailure = java.io.IOException("disk full")

        val vm = vm()

        assertEquals(1, categories.refreshes)
        assertEquals(CategoryRepository.BUILT_IN, vm.state.value.categories)
    }

    @Test
    fun manual_savesOfflineWithPendingState() = runTest {
        val vm = vm(transactions = DefaultTransactionStore(repository))
        vm.fill(amount = "5000", category = "Food and Drink Item", description = " လက်ဖက်ရည် ")

        vm.save()

        assertEquals(AddManualEvent.Saved, vm.events.first())
        val row = repository.observe().first().single()
        assertEquals(SyncState.PENDING, row.syncState)
        assertEquals(Source.MANUAL, row.source)
        assertEquals(4, UUID.fromString(row.clientId).version())
        assertNull(row.serverId)
        assertEquals(TxType.EXPENSE, row.type)
        assertEquals(5000L, row.amountKyat)
        assertEquals("Food and Drink Item", row.category)
        assertEquals("လက်ဖက်ရည်", row.description)
        assertEquals(today, row.date)
        assertNull(row.transcript)
        // No network in the test: the sync waits in WorkManager for a connection instead of running.
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get()
        assertEquals(listOf(WorkInfo.State.ENQUEUED), work.map { it.state })
    }

    /** The user's ledger, newest first as Room lists it. */
    private val history = listOf(
        testTransaction("Tea shop", 1_200, today, category = "Food and Drink Item"),
        testTransaction("Bus to Sule", 600, today, category = "Transport"),
        // An older entry of the same description: only the newest one is offered.
        testTransaction("tea  shop", 1_500, today.minusDays(1), category = "Other"),
        testTransaction("Top-up", 5_000, today.minusDays(2), category = "Utilities"),
        testTransaction("Mohinga", 1_500, today.minusDays(3), category = "Food and Drink Item"),
        testTransaction("Salary", 850_000, today.minusDays(4), type = TxType.INCOME, category = CategoryRepository.INCOME),
    )

    private fun AddManualUiState.recentDescriptions() = recent.map { it.description }

    @Test
    fun recentEntriesAreTheNewestDescriptionsOfTheTypeMatchingWhatIsTyped() = runTest {
        store.rows.value = history
        val vm = vm()

        assertEquals(listOf("Tea shop", "Bus to Sule", "Top-up"), vm.state.value.recentDescriptions())

        // Case does not matter, and only entries with what is typed in them are left.
        vm.onDescriptionChange("U")
        assertEquals(listOf("Bus to Sule", "Top-up"), vm.state.value.recentDescriptions())

        vm.onDescriptionChange("")
        vm.onTypeChange(TxType.INCOME)
        assertEquals(listOf("Salary"), vm.state.value.recentDescriptions())
    }

    @Test
    fun aDescriptionUsedBeforePicksItsCategoryUntilTheUserPicksOne() = runTest {
        store.rows.value = history
        val vm = vm()

        vm.onDescriptionChange(" mohinga ")
        assertEquals("Food and Drink Item", vm.state.value.category)
        assertEquals("Mohinga", vm.state.value.categoryFrom)

        // The pick stays while the user goes on typing; another match replaces it.
        vm.onDescriptionChange("Mohinga with Ko Aung")
        assertEquals("Food and Drink Item", vm.state.value.category)
        assertEquals("Mohinga", vm.state.value.categoryFrom)
        vm.onDescriptionChange("bus to SULE")
        assertEquals("Transport", vm.state.value.category)
        assertEquals("Bus to Sule", vm.state.value.categoryFrom)

        // The user's tap wins, now and for whatever is typed next.
        vm.onCategoryChange("Other")
        assertNull(vm.state.value.categoryFrom)
        vm.onDescriptionChange("Tea shop")
        assertEquals("Other", vm.state.value.category)
        assertNull(vm.state.value.categoryFrom)
    }

    @Test
    fun aRecentEntryFillsInTheForm() = runTest {
        store.rows.value = history
        val vm = vm()

        vm.onRecentPick(vm.state.value.recent.single { it.description == "Bus to Sule" })

        val s = vm.state.value
        assertEquals("Bus to Sule", s.description)
        assertEquals("600", s.amountDigits)
        assertEquals("Transport", s.category)
        assertEquals("Bus to Sule", s.categoryFrom)
        assertTrue(s.canSave)
        // Filled in already: it is not offered again.
        assertTrue(s.recent.isEmpty())
        vm.save()
        assertEquals(NewTransaction(TxType.EXPENSE, 600, "Transport", "Bus to Sule", today, Source.MANUAL, transcript = null), store.added.single())
    }

    @Test
    fun addIncomeStartsTheFormAsIncome() = runTest {
        val vm = vm(saved = SavedStateHandle(mapOf(AddManualViewModel.ARG_INCOME to true)))

        assertEquals(TxType.INCOME, vm.state.value.type)
        assertFalse(vm.state.value.editing)
    }

    private val lunch = testTransaction("Lunch with Ko Aung", 3_600, today.minusDays(2), category = "Food and Drink Item")
        .copy(source = Source.VOICE, transcript = "နေ့လယ်စာ သုံးထောင့်ခြောက်ရာ")

    private fun editing(entry: Transaction, saved: SavedStateHandle = SavedStateHandle(mapOf(AddManualViewModel.ARG_CLIENT_ID to entry.clientId))): AddManualViewModel {
        store.rows.value = listOf(entry) + history
        return vm(saved = saved)
    }

    @Test
    fun anEntryOpensInTheFormAsItIs() = runTest {
        val s = editing(lunch).state.value

        assertTrue(s.editing)
        assertTrue(s.loaded)
        assertEquals(Source.VOICE, s.source)
        assertEquals(TxType.EXPENSE, s.type)
        assertEquals("3600", s.amountDigits)
        assertEquals("Food and Drink Item", s.category)
        assertEquals("Lunch with Ko Aung", s.description)
        assertEquals(today.minusDays(2), s.date)
        assertTrue(s.canSave)
        // Nothing is offered over an entry that is filled in, and its description picks no category.
        assertTrue(s.recent.isEmpty())
        assertNull(s.categoryFrom)
    }

    @Test
    fun savingAChangeReplacesTheEntryAndKeepsHowItWasAdded() = runTest {
        val scanned = testTransaction("Ocean Supercenter, Groceries", 46_307, today, category = "Groceries").copy(
            source = Source.SCAN,
            receiptId = "receipt-1",
            merchant = "Ocean Supercenter",
            items = listOf(EntryItem("Rice", 2, 15_000), EntryItem("Eggs", 16_307)),
        )
        val vm = editing(scanned)
        val events = events(vm)

        vm.onAmountChange("46000")
        vm.onCategoryChange("Food and Drink Item")
        vm.onDateChange(today.minusDays(1))
        vm.save()

        val changed = NewTransaction(
            type = TxType.EXPENSE,
            amountKyat = 46_000,
            category = "Food and Drink Item",
            description = "Ocean Supercenter, Groceries",
            date = today.minusDays(1),
            source = Source.SCAN,
            transcript = null,
            receiptId = "receipt-1",
            merchant = "Ocean Supercenter",
            items = scanned.items,
        )
        assertEquals(listOf(listOf(scanned.clientId) to listOf(changed)), store.replaced)
        assertTrue(store.added.isEmpty())
        assertEquals(listOf(AddManualEvent.Saved), events)
    }

    @Test
    fun aSaidEntryKeepsItsTranscriptAndCanBecomeIncome() = runTest {
        val vm = editing(lunch)
        vm.onTypeChange(TxType.INCOME)
        vm.save()

        val (old, new) = store.replaced.single()
        assertEquals(listOf(lunch.clientId), old)
        assertEquals(
            NewTransaction(TxType.INCOME, 3_600, CategoryRepository.INCOME, lunch.description, lunch.date, Source.VOICE, transcript = lunch.transcript),
            new.single(),
        )
    }

    @Test
    fun savingWithNothingChangedLeavesTheEntryAlone() = runTest {
        val vm = editing(lunch)
        val events = events(vm)

        vm.save()

        assertTrue(store.replaced.isEmpty())
        assertEquals(listOf(AddManualEvent.Saved), events)
    }

    @Test
    fun deleteHandsTheEntryToTheListForUndo() = runTest {
        val vm = editing(lunch)
        val events = events(vm)

        vm.delete()
        vm.delete() // a double tap hands it over once

        // The list deletes it, with its notice and Undo; the form deletes nothing itself.
        assertEquals(listOf(AddManualEvent.Delete(lunch.clientId)), events)
        assertTrue(store.deleted.isEmpty())
        assertTrue(vm.state.value.deleting)
    }

    @Test
    fun withNoListToHandItToDeleteNowDeletesTheEntryAndCloses() = runTest {
        val vm = editing(lunch)
        val events = events(vm)

        vm.delete()
        vm.deleteNow()

        assertEquals(listOf(lunch.clientId), store.deleted)
        assertEquals(listOf(AddManualEvent.Delete(lunch.clientId), AddManualEvent.Gone), events)
    }

    private val webLunch = testTransaction("Lunch ", 3_600, today.minusDays(1), category = "Food and Drink Item")
        .copy(source = Source.WEB)

    @Test
    fun aWebEntryChangedHereIsSavedAsTyped() = runTest {
        val vm = editing(webLunch)
        assertEquals(Source.WEB, vm.state.value.source)

        vm.onAmountChange("4200")
        vm.save()

        // The server takes no "web" source from the phone, so it would refuse the change for good.
        val new = store.replaced.single().second.single()
        assertEquals(Source.MANUAL, new.source)
        assertEquals(4_200L, new.amountKyat)
        assertEquals("Lunch", new.description)
    }

    @Test
    fun savingAWebEntryWhoseDescriptionHasSpacesWritesNothing() = runTest {
        // The web stores "Lunch " as typed; the form shows it as it is and saves it trimmed.
        val vm = editing(webLunch)
        val events = events(vm)

        vm.save()

        assertTrue(store.replaced.isEmpty())
        assertEquals(listOf(AddManualEvent.Saved), events)
    }

    @Test
    fun aWebCategoryTooLongForTheServerIsChangedBeforeSaving() = runTest {
        val long = "Groceries and household things for the whole family this month" // 62 characters
        assertTrue(long.length > AddManualViewModel.MAX_CATEGORY)
        val vm = editing(webLunch.copy(category = long))
        vm.onAmountChange("4200")

        assertEquals(CategoryError.TOO_LONG, vm.state.value.categoryError)
        assertTrue(long in vm.state.value.categoryChoices)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(store.replaced.isEmpty())

        vm.onCategoryChange("Groceries")
        assertNull(vm.state.value.categoryError)
        vm.save()
        assertEquals("Groceries", store.replaced.single().second.single().category)
    }

    @Test
    fun aKPayEntryKeepsItsIdWhenChanged() = runTest {
        // Scanned with no receipt: a KPay payment, whose id lets a later scan of the same history find it.
        val payment = testTransaction("KPay to Ko Aung", 150_000, today, category = "Other").copy(source = Source.SCAN)
        val vm = editing(payment)

        vm.onCategoryChange("Transport")
        vm.save()

        assertEquals(payment.clientId, store.replaced.single().second.single().clientId)
    }

    @Test
    fun aFutureDatedEntryCanBeChangedWithoutMovingItsDate() = runTest {
        // Rent dated next week on the web: the form keeps that day, and still refuses any other future one.
        val rent = testTransaction("Rent", 300_000, today.plusDays(3), category = "Other").copy(source = Source.WEB)
        val vm = editing(rent)
        assertNull(vm.state.value.dateError)
        assertTrue(vm.state.value.canSave)

        vm.onDateChange(today.plusDays(1))
        assertEquals(DateError.FUTURE, vm.state.value.dateError)
        vm.onDateChange(today.plusDays(3))
        vm.onAmountChange("320000")
        vm.save()

        assertEquals(today.plusDays(3), store.replaced.single().second.single().date)
    }

    @Test
    fun aFutureDatedEntryKeepsItsDayAfterProcessDeath() = runTest {
        val rent = testTransaction("Rent", 300_000, today.plusDays(3), category = "Other")
        val saved = SavedStateHandle(mapOf(AddManualViewModel.ARG_CLIENT_ID to rent.clientId))
        editing(rent, saved).onAmountChange("320000")

        val restored = vm(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals(today.plusDays(3), restored.state.value.date)
        assertNull(restored.state.value.dateError)
        assertTrue(restored.state.value.canSave)
    }

    @Test
    fun anEntryDeletedElsewhereWhileOpenIsNotBroughtBackOnItsOwn() = runTest {
        val vm = editing(lunch)
        val events = events(vm)
        store.rows.value = history // deleted on the web, and pulled away while the form was open

        vm.onAmountChange("4200")
        vm.save()

        assertTrue(vm.state.value.gone)
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.canSave)
        assertTrue(store.replaced.isEmpty())
        assertTrue(store.added.isEmpty())
        assertTrue(events.isEmpty())

        // The user decides: Save as new keeps the change as a new entry.
        vm.saveAsNew()
        val new = store.added.single()
        assertEquals(4_200L, new.amountKyat)
        assertEquals(lunch.description, new.description)
        assertEquals(Source.VOICE, new.source)
        assertEquals(listOf(AddManualEvent.Saved), events)
    }

    @Test
    fun aPastedBankAmountIsTakenAndAFractionSaysWholeKyatOnly() = runTest {
        val vm = vm()

        vm.onAmountChange("-15,000.00 Ks")
        assertEquals("15000", vm.state.value.amountDigits)
        vm.onAmountChange("Ks. 3,000")
        assertEquals("3000", vm.state.value.amountDigits)

        vm.onAmountChange("3000.50")
        assertEquals("3000", vm.state.value.amountDigits)
        assertTrue(vm.state.value.wholeKyatHint)
        // A dot typed after "30" would cut the amount to 30.
        vm.onAmountChange("30.00")
        assertEquals("3000", vm.state.value.amountDigits)

        vm.onAmountChange("30000")
        assertFalse(vm.state.value.wholeKyatHint)
    }

    @Test
    fun aFormLeftOpenOverMidnightSavesUnderTheNewDay() = runTest {
        val moving = MovableClock(today.atTime(23, 55).atZone(zone).toInstant(), zone)
        val vm = vm(clock = moving)
        vm.fill()

        moving.now = today.plusDays(1).atTime(0, 10).atZone(zone).toInstant()
        vm.refreshToday() // the screen comes back
        assertEquals(today.plusDays(1), vm.state.value.today)
        assertEquals(today.plusDays(1), vm.state.value.date)
        vm.save()

        assertEquals(today.plusDays(1), store.added.single().date)
    }

    @Test
    fun aPickedDayStaysOverMidnight() = runTest {
        val moving = MovableClock(today.atTime(23, 55).atZone(zone).toInstant(), zone)
        val vm = vm(clock = moving)
        vm.fill()
        vm.onDateChange(today.minusDays(1))

        moving.now = today.plusDays(1).atTime(0, 10).atZone(zone).toInstant()
        vm.save() // no resume in between: Save reads the clock too

        assertEquals(today.minusDays(1), store.added.single().date)
    }

    @Test
    fun aNewEntryRestoredAfterMidnightIsForTheNewToday() = runTest {
        val saved = SavedStateHandle()
        vm(saved = saved).fill() // the form saves its date with every change: today, not picked

        val tomorrow = Clock.fixed(today.plusDays(1).atTime(0, 10).atZone(zone).toInstant(), zone)
        val after = vm(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), clock = tomorrow)

        assertEquals(today.plusDays(1), after.state.value.date)
        assertEquals("5000", after.state.value.amountDigits)
    }

    @Test
    fun anEntryThatIsGoneClosesTheForm() = runTest {
        val vm = vm(saved = SavedStateHandle(mapOf(AddManualViewModel.ARG_CLIENT_ID to "gone")))
        val events = events(vm)

        assertEquals(listOf(AddManualEvent.Gone), events)
        assertFalse(vm.state.value.loaded)
        assertFalse(vm.state.value.canSave)
        vm.delete()
        vm.save()
        assertTrue(store.deleted.isEmpty())
        assertTrue(store.replaced.isEmpty())
    }

    @Test
    fun aChangeInProgressSurvivesProcessDeath() = runTest {
        val saved = SavedStateHandle(mapOf(AddManualViewModel.ARG_CLIENT_ID to lunch.clientId))
        val before = editing(lunch, saved)
        before.onAmountChange("4200")

        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val after = vm(saved = restoredHandle)

        // The user's change stays; the entry is read again only for what the form does not show.
        assertEquals("4200", after.state.value.amountDigits)
        assertEquals(Source.VOICE, after.state.value.source)
        after.save()
        assertNotNull(store.replaced.singleOrNull())
        assertEquals(4_200L, store.replaced.single().second.single().amountKyat)
        assertEquals(lunch.transcript, store.replaced.single().second.single().transcript)
    }
}
