package app.budgetwise.ui.receipt

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.Transaction
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
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
class ReceiptScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.parse("2026-11-02")

    private fun text(@StringRes id: Int) = context.getString(id)

    private fun entry(category: String, vararg items: EntryItem) =
        testTransaction("Ocean Supercenter, $category", items.sumOf { it.amountKyat }, day, category = category)
            .copy(source = Source.SCAN, receiptId = "r1", merchant = "Ocean Supercenter", items = items.toList())

    private var closed = 0

    private val ocean = listOf(
        entry("Groceries", EntryItem("Fresh milk 1L", 2, 4500), EntryItem("Bread", 2200)),
        entry("Other", EntryItem("Dish soap 2L", 12000)),
    )

    /** The receipt [rows]; [onDelete] stands for the list under it (none by default, as when opened elsewhere). */
    private fun show(
        rows: List<Transaction> = ocean,
        myanmarDigits: Boolean = false,
        onDelete: (String, List<String>) -> Boolean = { _, _ -> false },
    ): FakeTransactionStore {
        val store = FakeTransactionStore(rows)
        val settings = FakeSettingsStore().apply { this.myanmarDigits.value = myanmarDigits }
        val vm = ReceiptViewModel(store, FakeCategorySource(), settings, SavedStateHandle(mapOf(ReceiptViewModel.RECEIPT_ID to "r1")))
        compose.setContent {
            BudgetWiseTheme { ReceiptRoute(onClose = { closed++ }, onDelete = onDelete, viewModel = vm) }
        }
        return store
    }

    /** The system back, once the screen has caught up (its back handler follows the state it last drew). */
    private fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun showsTheShopTheTotalAndEachEntryWithItsItems() {
        show()

        compose.onNodeWithText("Ocean Supercenter").assertIsDisplayed()
        compose.onNode(hasContentDescription("Total, 23,200 kyat")).assertIsDisplayed()
        compose.onNodeWithContentDescription("2 entries from this receipt").assertIsDisplayed()
        // The entry is one target: its category, amount and items.
        compose.onNode(hasText(text(R.string.category_groceries)) and hasText("11,200 Ks") and hasText("Fresh milk 1L")).assertIsDisplayed()
        compose.onNode(hasText("Fresh milk 1L") and hasText("2 × 4,500") and hasText("9,000 Ks")).assertIsDisplayed()
        compose.onNode(hasText("Bread") and hasText("2,200 Ks")).assertIsDisplayed()
    }

    @Test
    fun deleteHandsEveryEntryToTheListForItsUndo() {
        val handed = mutableListOf<Pair<String, List<String>>>()
        val store = show(onDelete = { receiptId, ids -> handed += receiptId to ids; true })

        compose.onNodeWithText(text(R.string.receipt_delete)).performClick()

        compose.runOnIdle {
            assertEquals(listOf("r1" to ocean.map { it.clientId }.toSet()), handed.map { (id, ids) -> id to ids.toSet() })
            assertEquals("nothing is deleted before the list's Undo", ocean.toSet(), store.rows.value.toSet())
        }
    }

    @Test
    fun withoutTheListDeleteTakesEveryEntryAndGoesBack() {
        val store = show()

        compose.onNodeWithText(text(R.string.receipt_delete)).performClick()

        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(emptyList<Any>(), store.rows.value)
        }
    }

    @Test
    fun aReceiptWithoutItemsOffersNoChangeItems() {
        show(ocean.map { it.copy(items = emptyList()) })

        compose.onNodeWithText(text(R.string.receipt_delete)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receipt_change_items)).assertDoesNotExist()
    }

    @Test
    fun countsFollowTheMyanmarDigitsSetting() {
        show(myanmarDigits = true)

        compose.onNodeWithContentDescription("၂ entries from this receipt").assertIsDisplayed()
    }

    @Test
    fun backFromChangeItemsWithNothingChangedGoesStraightBack() {
        show()
        compose.onNodeWithText(text(R.string.receipt_change_items)).performClick()

        back()

        compose.onNodeWithText(text(R.string.receipt_discard_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.receipt_delete)).assertIsDisplayed()
    }

    @Test
    fun openingChangeItemsCanBeReversedBeforeTheReceiptFadeFinishes() {
        show()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.receipt_change_items)).performClick()
        compose.mainClock.advanceTimeBy(64)

        // The outgoing receipt and incoming editor keep their own state and actions during the overlap.
        compose.onNodeWithContentDescription("2 entries from this receipt").assertExists()
        compose.onNodeWithText(text(R.string.receipt_save)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.action_close)).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription("2 entries from this receipt").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receipt_save)).assertDoesNotExist()
        compose.onNode(hasContentDescription("Total, 23,200 kyat")).assertIsDisplayed()
        assertEquals(0, closed)
    }

    @Test
    fun backFromChangeItemsWithAChangeAsksFirst() {
        show()
        compose.onNodeWithText(text(R.string.receipt_change_items)).performClick()
        compose.onAllNodesWithContentDescription(text(R.string.item_fewer))[1].performClick()

        back()
        compose.onNodeWithText(text(R.string.receipt_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receipt_discard_keep)).performClick()
        compose.onNodeWithText("was 2").assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.action_close)).performClick()
        compose.onNodeWithText(text(R.string.action_discard)).performClick()
        compose.onNodeWithText(text(R.string.receipt_delete)).assertIsDisplayed()
        compose.onNodeWithText("was 2").assertDoesNotExist()
        assertEquals("still on the receipt", 0, closed)
    }

    @Test
    fun everyItemAtZeroOffersToDeleteTheReceipt() {
        var deletes = 0
        show(onDelete = { _, _ -> deletes++; true })
        compose.onNodeWithText(text(R.string.receipt_change_items)).performClick()
        compose.onNodeWithText(text(R.string.receipt_all_left_out)).assertDoesNotExist()

        // Soap 1 to 0, milk 2 to 0, bread 1 to 0.
        repeat(4) { compose.onAllNodesWithContentDescription(text(R.string.item_fewer)).filter(isEnabled()).onFirst().performClick() }

        compose.onNodeWithText(text(R.string.receipt_all_left_out)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receipt_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.receipt_delete)).performClick()
        compose.runOnIdle { assertTrue(deletes == 1) }
    }

    @Test
    fun restoringAnItemDisablesTheExitingDeleteActionAndRejectsItsRetainedCallback() {
        var deletes = 0
        val store = show(onDelete = { _, _ -> deletes++; true })
        compose.onNodeWithText(text(R.string.receipt_change_items)).performClick()
        repeat(4) { compose.onAllNodesWithContentDescription(text(R.string.item_fewer)).filter(isEnabled()).onFirst().performClick() }
        val delete = compose.onNodeWithText(text(R.string.receipt_delete))
        val retainedDelete = delete.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false

        compose.onAllNodesWithContentDescription(text(R.string.item_more)).onFirst().performClick()
        compose.mainClock.advanceTimeByFrame()
        // It still exists for the exit, but its old semantics action cannot delete restored items.
        delete.assertExists().assertIsNotEnabled()
        compose.runOnIdle { retainedDelete() }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText(text(R.string.receipt_delete)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, deletes)
            assertEquals(ocean.toSet(), store.rows.value.toSet())
        }
    }

    @Test
    fun changeItemsSavesTheNewCounts() {
        val store = show()

        compose.onNodeWithText(text(R.string.receipt_change_items)).performClick()
        compose.onNodeWithText(text(R.string.receipt_save)).assertIsNotEnabled()
        // Largest entry first: the soap, then the milk, 2 to 1 here.
        compose.onAllNodesWithContentDescription(text(R.string.item_fewer))[1].performClick()
        compose.onNodeWithText("was 2").assertIsDisplayed()
        compose.onNodeWithText("Saved as 23,200 Ks. You changed 1 item.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receipt_save)).assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf(12000L, 6700L), store.replaced.single().second.map { it.amountKyat })
        }
        compose.onNode(hasContentDescription("Total, 18,700 kyat")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receipt_delete)).assertIsDisplayed()
    }
}
