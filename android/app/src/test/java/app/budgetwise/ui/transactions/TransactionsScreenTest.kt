package app.budgetwise.ui.transactions

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.testTransaction
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
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-21")
    private val clock = Clock.fixed(today.atTime(9, 30).atZone(zone).toInstant(), zone)

    private fun text(@StringRes id: Int) = context.getString(id)

    private fun show(rows: List<Transaction>, onAddManual: () -> Unit = {}): FakeTransactionStore {
        val store = FakeTransactionStore(rows)
        // The delete outlives the screen in the app; here it runs straight away.
        val vm = TransactionsViewModel(store, FakeSyncControls(), FakeSettingsStore(), clock, CoroutineScope(Dispatchers.Unconfined))
        compose.setContent {
            BudgetWiseTheme {
                TransactionsRoute(sessionNotice = null, onSignIn = {}, onAddManual = onAddManual, viewModel = vm)
            }
        }
        return store
    }

    @Test
    fun transactionsList_showsPendingBadgeForUnsyncedRow() {
        show(
            listOf(
                testTransaction("Tea", 1500, today, syncState = SyncState.PENDING),
                testTransaction("Rice", 20000, today, syncState = SyncState.SYNCED),
                testTransaction("Taxi", 4000, today.minusDays(1), category = "Transport", syncState = SyncState.FAILED),
            ),
        )

        val notSynced = text(R.string.transactions_not_synced)
        compose.onAllNodesWithText(notSynced).assertCountEquals(1)
        compose.onNode(hasText("Tea") and hasText(notSynced)).assertIsDisplayed()
        compose.onNode(hasText("Taxi") and hasText(text(R.string.transactions_sync_failed))).assertIsDisplayed()
        compose.onNodeWithText("Rice").assertIsDisplayed()
        // Day headers, and the amount read the way it is meant: "1,500 kyat spent", not "minus 1,500 Ks".
        compose.onNodeWithText(text(R.string.transactions_today)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_yesterday)).assertIsDisplayed()
        compose.onNode(hasText("Tea") and hasContentDescription("1,500 kyat spent")).assertIsDisplayed()
    }

    @Test
    fun swipeDeleteUndoKeepsRow() {
        val rice = testTransaction("Rice", 20000, today)
        val store = show(listOf(rice))

        compose.onNodeWithText("Rice").performTouchInput { swipeLeft() }

        compose.onNodeWithText(text(R.string.transactions_deleted)).assertIsDisplayed()
        compose.onNodeWithText("Rice").assertDoesNotExist()

        compose.onNodeWithText(text(R.string.transactions_undo)).performClick()
        // The click resumes the snackbar's coroutine, which runs when the test clock moves on.
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Rice").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        assertTrue(store.deleted.isEmpty())
        assertEquals(listOf(rice), store.rows.value)
    }

    @Test
    fun swipeDeleteWithoutUndoDeletesWhenTheSnackbarEnds() {
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
    fun monthSummaryShowsSpentIncomeAndBalance() {
        show(
            listOf(
                testTransaction("Rice", 20000, today),
                testTransaction("Salary", 500000, today.withDayOfMonth(1), type = TxType.INCOME),
            ),
        )

        compose.onNodeWithText("480,000 Ks").assertIsDisplayed()
        compose.onNodeWithText("20,000 Ks").assertIsDisplayed()
        compose.onNodeWithText("500,000 Ks").assertIsDisplayed()
    }

    @Test
    fun emptyStateAddsTheFirstExpense() {
        var opened = 0
        show(emptyList(), onAddManual = { opened++ })

        compose.onNodeWithText(text(R.string.transactions_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_add_first)).performClick()

        assertEquals(1, opened)
    }
}
