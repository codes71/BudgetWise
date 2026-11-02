package app.budgetwise.ui.scan

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.repo.ScanItem
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class ScanReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.parse("2026-11-02")

    private fun line(category: String, vararg items: ScanItem) = ReviewLine(
        TxType.EXPENSE, items.sumOf { it.amountKyat }, category, "Ocean Supercenter", day, clientId = null, items = items.toList(),
    )

    @Test
    fun eachItemShowsUnderItsCategoryWithItsPrice() {
        val state = ScanUiState(
            today = day,
            stage = ScanStage.REVIEW,
            kind = ScanKind.RECEIPT,
            merchant = "Ocean Supercenter",
            date = day,
            totalKyat = 46307,
            lines = listOf(
                line("Groceries", ScanItem("Fresh milk 1L", 4500), ScanItem("Eggs x10", 11807)),
                line("Other", ScanItem("Dish soap", 30000)),
            ),
        )
        compose.setContent {
            BudgetWiseTheme {
                ScanReview(
                    state, onClose = {}, onRetake = {}, onMove = { _, _ -> }, onAddShort = {}, onToggle = {}, onCount = { _, _, _ -> },
                    onPrice = { _, _, _ -> }, onAddEntries = {}, onAddAsOne = {},
                )
            }
        }

        compose.onNodeWithText("Fresh milk 1L").assertIsDisplayed()
        compose.onNodeWithText("4,500 Ks").assertIsDisplayed()
        compose.onNodeWithText("Eggs x10").assertIsDisplayed()
        compose.onNodeWithText("Dish soap").assertIsDisplayed()
        compose.onNodeWithText("16,307 Ks").assertIsDisplayed()
    }

    /** The review over [lines], with its count and price changes applied as the ViewModel applies them. */
    private fun showEditable(vararg lines: ReviewLine) {
        var state by mutableStateOf(
            ScanUiState(
                today = day,
                stage = ScanStage.REVIEW,
                kind = ScanKind.RECEIPT,
                merchant = "Ocean Supercenter",
                date = day,
                totalKyat = lines.sumOf { it.amountKyat },
                lines = lines.toList(),
            ),
        )
        fun edit(index: Int, change: (ReviewLine) -> ReviewLine) {
            state = state.copy(lines = state.lines.toMutableList().also { it[index] = change(it[index]) })
        }
        compose.setContent {
            BudgetWiseTheme {
                ScanReview(
                    state, onClose = {}, onRetake = {}, onMove = { _, _ -> }, onAddShort = {}, onToggle = {},
                    onCount = { line, item, count -> edit(line) { it.withCount(item, count) } },
                    onPrice = { line, item, price -> edit(line) { it.withPrice(item, price) } },
                    onAddEntries = {}, onAddAsOne = {},
                )
            }
        }
    }

    @Test
    fun theStepperChangesTheCountAndSaysWhatItWas() {
        showEditable(line("Groceries", ScanItem("Fresh milk 1L", 2, 4500), ScanItem("Bread", 2200)))
        compose.onNodeWithText("You changed", substring = true).assertDoesNotExist()

        compose.onAllNodesWithContentDescription(context.getString(R.string.item_fewer))[0].performClick()

        compose.onNodeWithText("was 2").assertIsDisplayed()
        compose.onNodeWithText("Scanned 11,200 Ks. You changed 1 item.").assertIsDisplayed()
        // The item, its category and the big total all follow.
        compose.onAllNodesWithText("4,500 Ks").assertCountEquals(1)
        compose.onNodeWithText("6,700 Ks").assertIsDisplayed()
        compose.onNode(hasContentDescription("Total, 6,700 kyat")).assertIsDisplayed()

        compose.onAllNodesWithContentDescription(context.getString(R.string.item_fewer))[1].performClick()

        compose.onNodeWithText("not added, was 1").assertIsDisplayed()
        compose.onNodeWithText("Scanned 11,200 Ks. You changed 2 items.").assertIsDisplayed()
    }

    @Test
    fun aPriceTypedInIsThePriceEach() {
        showEditable(line("Groceries", ScanItem("Instant noodles", 3, 802)))

        compose.onNode(hasContentDescription(context.getString(R.string.item_price, "Instant noodles"))).performTextReplacement("800")

        compose.onNodeWithText("was 802 each").assertIsDisplayed()
        compose.onAllNodesWithText("2,400 Ks").assertCountEquals(2)
    }

    /** A receipt being checked, as the scan screen shows it, counting how often each way out was taken. */
    private var retakes = 0
    private var closes = 0

    private fun showLeaving(saving: Boolean = false) {
        val state = ScanUiState(
            today = day,
            stage = ScanStage.REVIEW,
            kind = ScanKind.RECEIPT,
            merchant = "Ocean Supercenter",
            date = day,
            totalKyat = 6700,
            lines = listOf(line("Groceries", ScanItem("Fresh milk 1L", 4500), ScanItem("Bread", 2200))),
            saving = saving,
        )
        compose.setContent {
            BudgetWiseTheme {
                ScanReview(
                    state, onClose = { closes++ }, onRetake = { retakes++ }, onMove = { _, _ -> }, onAddShort = {}, onToggle = {},
                    onCount = { _, _, _ -> }, onPrice = { _, _, _ -> }, onAddEntries = {}, onAddAsOne = {},
                )
            }
        }
    }

    /** The system back, once the screen has caught up (its back handler follows the state it last drew). */
    private fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun backAsksBeforeTheReadIsDropped() {
        showLeaving()

        back()
        compose.onNodeWithText(context.getString(R.string.scan_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_discard_keep)).performClick()

        compose.onNodeWithText(context.getString(R.string.scan_discard_title)).assertDoesNotExist()
        compose.onNodeWithText("Fresh milk 1L").assertIsDisplayed()
        assertEquals(0, retakes)

        back()
        compose.onNodeWithText(context.getString(R.string.action_discard)).performClick()
        compose.runOnIdle { assertEquals("back to the camera", 1, retakes) }
        assertEquals(0, closes)
    }

    @Test
    fun theXAsksBeforeLeavingScanning() {
        showLeaving()

        compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_discard)).performClick()

        compose.runOnIdle { assertEquals(1, closes) }
        assertEquals(0, retakes)
    }

    @Test
    fun whileSavingBackAndTheXDoNothing() {
        showLeaving(saving = true)

        back()
        compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()

        compose.onNodeWithText(context.getString(R.string.scan_discard_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, retakes + closes) }
    }

    @Test
    fun itemsOverThePrintedTotalSaySo() {
        val state = ScanUiState(
            today = day,
            stage = ScanStage.REVIEW,
            kind = ScanKind.RECEIPT,
            merchant = "Ocean Supercenter",
            date = day,
            totalKyat = 6000,
            lines = listOf(line("Groceries", ScanItem("Fresh milk 1L", 4500), ScanItem("Bread", 2200))),
        )
        compose.setContent {
            BudgetWiseTheme {
                ScanReview(
                    state, onClose = {}, onRetake = {}, onMove = { _, _ -> }, onAddShort = {}, onToggle = {}, onCount = { _, _, _ -> },
                    onPrice = { _, _, _ -> }, onAddEntries = {}, onAddAsOne = {},
                )
            }
        }

        // The big total is what Add saves; the notice says what the receipt printed.
        compose.onNode(hasContentDescription("Total, 6,700 kyat")).assertIsDisplayed()
        compose.onNodeWithText("Items come to 6,700 Ks").assertIsDisplayed()
        compose.onNodeWithText("The receipt total is 6,000 Ks.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_short_add)).assertDoesNotExist()
    }
}
