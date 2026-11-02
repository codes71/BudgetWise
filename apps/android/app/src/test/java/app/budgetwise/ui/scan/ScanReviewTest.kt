package app.budgetwise.ui.scan

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.budgetwise.data.repo.ScanItem
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class ScanReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
                ScanReview(state, onClose = {}, onMove = { _, _ -> }, onAddShort = {}, onToggle = {}, onAddEntries = {}, onAddAsOne = {})
            }
        }

        compose.onNodeWithText("Fresh milk 1L").assertIsDisplayed()
        compose.onNodeWithText("4,500 Ks").assertIsDisplayed()
        compose.onNodeWithText("Eggs x10").assertIsDisplayed()
        compose.onNodeWithText("Dish soap").assertIsDisplayed()
        compose.onNodeWithText("16,307 Ks").assertIsDisplayed()
    }
}
