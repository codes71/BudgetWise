package app.budgetwise.nav

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.HiltTestActivity
import app.budgetwise.R
import app.budgetwise.VoiceAvailability
import app.budgetwise.data.auth.AuthBindingsModule
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.toEntity
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Source
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.di.VoiceAvailabilityModule
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeVoiceAvailability
import app.budgetwise.testing.TestUser
import app.budgetwise.ui.budgets.BudgetTransactionsViewModel
import app.budgetwise.ui.theme.BudgetWiseTheme
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real navigation, editor, Room and outbox. No network or physical device is needed. */
@HiltAndroidTest
@UninstallModules(AuthBindingsModule::class, VoiceAvailabilityModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w390dp-h844dp")
class BudgetTransactionsNavTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<HiltTestActivity>()

    @BindValue @JvmField val tokenStore: TokenStore = FakeTokenStore(StoredSession("", Instant.EPOCH, TestUser))
    @BindValue @JvmField val voice: VoiceAvailability = FakeVoiceAvailability(supported = false)

    @Inject lateinit var session: SessionManager
    @Inject lateinit var db: BudgetWiseDb
    @Inject lateinit var transactions: TransactionRepository

    private val today = LocalDate.now()
    private val month = YearMonth.from(today)
    private val receipt = UUID.randomUUID().toString()
    private lateinit var groceries: String
    private lateinit var transport: String

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Context>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        hilt.inject()
        runBlocking {
            session.load()
            db.monthPlanDao().upsert(MonthPlan(month, 100_000, 0, listOf(Budget("Groceries", 50_000), Budget("Transport", 20_000))).toEntity(TestUser.id))
            val ids = transactions.addAll(
                listOf(
                    input("Rice", "Groceries", 10_000),
                    input("Taxi", "Transport", 3_000),
                    input("Previous month rice", "Groceries", 5_000).copy(date = month.atDay(1).minusDays(1), receiptId = UUID.randomUUID().toString()),
                    input("Income", "Groceries", 25_000).copy(type = TxType.INCOME, receiptId = null, items = emptyList()),
                ),
            )
            groceries = ids[0]
            transport = ids[1]
        }
    }

    private fun input(description: String, category: String, amount: Long) = NewTransaction(
        type = TxType.EXPENSE, amountKyat = amount, category = category, description = description,
        date = today, source = Source.SCAN, transcript = null, receiptId = receipt, merchant = "City Mart",
        items = listOf(EntryItem(description, amount)),
    )

    private fun openGroceries(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            BudgetWiseTheme { AppNav(session = session.state.collectAsState().value, navController = nav) }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.nav_budgets)).performClick()
        val summary = "Groceries, 10,000 kyat spent of 50,000 kyat, 40,000 kyat left"
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(summary)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription(summary) and hasClickAction()).performClick()
        waitForText("Rice")
        assertEquals(Routes.BUDGET_TRANSACTIONS, compose.runOnIdle { nav.currentDestination?.route })
        assertEquals(month.toString(), compose.runOnIdle { nav.currentBackStackEntry?.arguments?.getString(BudgetTransactionsViewModel.ARG_MONTH) })
        assertEquals("Groceries", compose.runOnIdle { nav.currentBackStackEntry?.arguments?.getString(BudgetTransactionsViewModel.ARG_CATEGORY) })
        compose.onNodeWithText("Taxi").assertDoesNotExist()
        compose.onNodeWithText("Previous month rice").assertDoesNotExist()
        compose.onNodeWithText("Income").assertDoesNotExist()
        return nav
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun categoryEntryUsesTheExistingEditorAndItsSavedAmountUpdatesTheBudget() {
        val nav = openGroceries()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        waitForText(compose.activity.getString(R.string.manual_edit_title))
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.manual_amount)) and hasSetTextAction())
            .assertIsDisplayed().performTextReplacement("16000")
        compose.onNode(hasText(compose.activity.getString(R.string.manual_save_changes)) and hasClickAction()).performClick()

        waitForText("16,000 of 50,000 Ks spent")
        assertEquals(Routes.BUDGET_TRANSACTIONS, compose.runOnIdle { nav.currentDestination?.route })
        assertEquals(16_000L, runBlocking { transactions.observeReceipt(receipt).first().single { it.description == "Rice" }.amountKyat })
        assertNotNull(runBlocking { transactions.get(transport) })

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_back)).performClick()
        val summary = "Groceries, 16,000 kyat spent of 50,000 kyat, 34,000 kyat left"
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(summary)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(summary).assertIsDisplayed()
    }

    @Test
    fun deletingFromTheEditorReturnsToTheCategoryWithVisibleUndoAndKeepsOtherReceiptEntries() {
        val nav = openGroceries()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        val delete = compose.activity.getString(R.string.manual_delete)
        waitForText(delete)
        compose.onNode(hasText(delete) and hasClickAction()).performClick()

        val undo = compose.activity.getString(R.string.transactions_undo)
        waitForText(undo)
        assertEquals(Routes.BUDGET_TRANSACTIONS, compose.runOnIdle { nav.currentDestination?.route })
        compose.onNodeWithText("0 of 50,000 Ks spent").assertIsDisplayed()
        compose.onNode(hasText("Rice") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText(undo) and hasClickAction()).performClick()
        waitForText("10,000 of 50,000 Ks spent")
        compose.onNode(hasText("Rice") and hasClickAction()).assertIsDisplayed()
        assertNotNull(runBlocking { transactions.get(groceries) })
        assertNotNull(runBlocking { transactions.get(transport) })

        // A second delete is committed when leaving the detail, with only this category's entry affected.
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        waitForText(delete)
        compose.onNode(hasText(delete) and hasClickAction()).performClick()
        waitForText(undo)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_back)).performClick()
        val summary = "Groceries, 0 kyat spent of 50,000 kyat, 50,000 kyat left"
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(summary)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(summary).assertIsDisplayed()
        assertNotNull(runBlocking { transactions.get(transport) })
        assertEquals(null, runBlocking { transactions.get(groceries) })
    }

    @Test
    fun aRestoredEditorCanDeleteBeforeTheBudgetDetailHasEverBeenComposed() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            BudgetWiseTheme { AppNav(session = session.state.collectAsState().value, navController = nav) }
        }
        // Restore the editor's back stack together, before the category destination can collect its ViewModel.
        compose.runOnIdle {
            nav.navigate(Routes.BUDGETS)
            nav.navigate(Routes.budgetTransactions(month, "Groceries"))
            nav.navigate(Routes.editEntry(groceries))
        }
        val delete = compose.activity.getString(R.string.manual_delete)
        waitForText(delete)
        compose.onNode(hasText(delete) and hasClickAction()).performClick()

        val undo = compose.activity.getString(R.string.transactions_undo)
        waitForText(undo)
        assertEquals(Routes.BUDGET_TRANSACTIONS, compose.runOnIdle { nav.currentDestination?.route })
        compose.onNodeWithText("0 of 50,000 Ks spent").assertIsDisplayed()
        compose.onNode(hasText(undo) and hasClickAction()).performClick()
        waitForText("10,000 of 50,000 Ks spent")
        compose.onNode(hasText("Rice") and hasClickAction()).assertIsDisplayed()
        assertNotNull(runBlocking { transactions.get(groceries) })
        assertNotNull(runBlocking { transactions.get(transport) })
    }

    @Test
    fun changingOneReceiptEntryToIncomeShowsItSeparatelyOnHome() {
        val nav = openGroceries()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        waitForText(compose.activity.getString(R.string.manual_edit_title))
        compose.onNode(hasText(compose.activity.getString(R.string.manual_income)) and hasClickAction()).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.manual_save_changes)) and hasClickAction()).performClick()
        waitForText("0 of 50,000 Ks spent")

        val rows = runBlocking { transactions.observe().first() }
        val income = rows.single { it.description == "Rice" }
        assertEquals(TxType.INCOME, income.type)
        assertNull(income.receiptId)
        assertEquals("City Mart", income.merchant)
        assertEquals(listOf(EntryItem("Rice", 10_000)), income.items)
        val receiptRows = runBlocking { transactions.observeReceipt(receipt).first() }
        assertEquals(listOf(transport), receiptRows.map { it.clientId })
        assertEquals(3_000L, receiptRows.sumOf { it.amountKyat })

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_back)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.nav_transactions)).performClick()
        waitForText("Rice")
        assertEquals(Routes.TRANSACTIONS, compose.runOnIdle { nav.currentDestination?.route })
        compose.onNode(hasText("Rice") and hasText("+10,000 Ks") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("City Mart") and hasText("−3,000 Ks") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun changingTheReceiptDateMovesEveryCategoryTogetherAndUpdatesBudgetTotals() {
        openGroceries()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        waitForText(compose.activity.getString(R.string.manual_edit_title))
        compose.onNodeWithText(compose.activity.getString(R.string.manual_receipt_date_note)).assertIsDisplayed()
        compose.onNode(hasText(compose.activity.getString(R.string.transactions_yesterday)) and hasClickAction()).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.manual_save_changes)) and hasClickAction()).performClick()
        val newDate = today.minusDays(1)
        val currentMonth = YearMonth.from(newDate) == month
        val spent = if (currentMonth) "10,000" else "0"
        waitForText("$spent of 50,000 Ks spent")

        val receiptRows = runBlocking { transactions.observeReceipt(receipt).first() }
        assertEquals(2, receiptRows.size)
        assertTrue(receiptRows.all { it.date == newDate })
        assertEquals(setOf("Groceries", "Transport"), receiptRows.map { it.category }.toSet())
        assertEquals(13_000L, receiptRows.sumOf { it.amountKyat })
        assertEquals(listOf(EntryItem("Rice", 10_000)), receiptRows.single { it.category == "Groceries" }.items)
        assertEquals(listOf(EntryItem("Taxi", 3_000)), receiptRows.single { it.category == "Transport" }.items)
        assertEquals(month.atDay(1).minusDays(1), runBlocking { transactions.observe().first().single { it.description == "Previous month rice" }.date })

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_back)).performClick()
        val transportSpent = if (currentMonth) "3,000" else "0"
        val transportLeft = if (currentMonth) "17,000" else "20,000"
        val summary = "Transport, $transportSpent kyat spent of 20,000 kyat, $transportLeft kyat left"
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(summary)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(summary).assertIsDisplayed()
    }
}
