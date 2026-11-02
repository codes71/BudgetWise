package app.budgetwise.ui.receipt

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Source
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
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

    private fun show(): FakeTransactionStore {
        val store = FakeTransactionStore(
            listOf(
                entry("Groceries", EntryItem("Fresh milk 1L", 2, 4500), EntryItem("Bread", 2200)),
                entry("Other", EntryItem("Dish soap 2L", 12000)),
            ),
        )
        val vm = ReceiptViewModel(store, FakeCategorySource(), FakeSettingsStore(), SavedStateHandle(mapOf(ReceiptViewModel.RECEIPT_ID to "r1")))
        compose.setContent {
            BudgetWiseTheme { ReceiptRoute(onClose = { closed++ }, viewModel = vm) }
        }
        return store
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
    fun deleteTakesEveryEntryAndGoesBack() {
        val store = show()

        compose.onNodeWithText(text(R.string.receipt_delete)).performClick()

        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(emptyList<Any>(), store.rows.value)
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
