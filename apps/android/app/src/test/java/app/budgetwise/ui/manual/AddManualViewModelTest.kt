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
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
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
    ) = AddManualViewModel(transactions, categories, settings, sessionManager, clock, saved)

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
}
