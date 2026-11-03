package app.budgetwise.ui.budgets

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Source
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.transactions.DayLine
import app.budgetwise.ui.transactions.DaySection
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class BudgetTransactionsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.parse("2026-10-21")
    private val month = YearMonth.from(today)
    private val rice = testTransaction("Rice", 10_000, today, clientId = "rice").copy(source = Source.SCAN, receiptId = "receipt")
    private val fruit = testTransaction("Fruit", 5_000, today.minusDays(1), clientId = "fruit")
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var budgetEdits = 0

    private fun state() = BudgetTransactionsUiState(
        month = month, category = "Groceries", today = today, loading = false,
        line = BudgetLine("Groceries", 50_000, 15_000),
        days = listOf(DaySection(today, listOf(rice), listOf(DayLine.Entry(rice))), DaySection(fruit.date, listOf(fruit))),
    )

    private fun show(state: BudgetTransactionsUiState = state(), dark: Boolean = false, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            BudgetWiseTheme(darkTheme = dark) {
                BudgetTransactionsScreen(
                    state, {}, { opened += it }, { deleted += it }, remember { SnackbarHostState() },
                    onEditBudget = { budgetEdits++ },
                )
            }
        }
    }

    @Test
    fun showsTheMonthAndBudgetAmountsAndOpensEachReceiptEntryIndividually() {
        show()
        compose.onNodeWithContentDescription("October budget").assertIsDisplayed()
        compose.onNodeWithText("35,000").assertIsDisplayed()
        compose.onNodeWithText("15,000 of 50,000 Ks spent").assertIsDisplayed()
        compose.onNodeWithText("2 expenses · Tap to edit, swipe to delete").assertIsDisplayed()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        compose.onNode(hasText("Fruit") and hasClickAction()).performClick()
        assertEquals(listOf("rice", "fruit"), opened)
    }

    @Test
    fun editingTheBudgetAndEditingAReceiptEntryRemainSeparateActions() {
        show()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget))
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        val labelBounds = compose.onNodeWithContentDescription("October budget").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).fetchSemanticsNode().boundsInRoot
        assertTrue("The normal-size Paper header keeps its month and action side by side", labelBounds.top < actionBounds.bottom && actionBounds.top < labelBounds.bottom)
        assertEquals(1, budgetEdits)
        assertEquals(emptyList<String>(), opened)
        assertEquals(emptyList<String>(), deleted)
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        assertEquals(listOf("rice"), opened)
        assertEquals(1, budgetEdits)
    }

    @Test
    fun aRemovedCategoryKeepsEntryActionsButDoesNotOfferToEditAMissingShare() {
        show(state().copy(line = null))
        compose.onNodeWithText(context.getString(R.string.budget_transactions_plan_changed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_split)).assertDoesNotExist()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        assertEquals(listOf("rice"), opened)
    }

    @Test
    fun talkBackCanDeleteOneReceiptEntryWithTheSameActionAsHome() {
        show(dark = true)
        compose.runOnIdle {
            val actions = compose.onNode(hasText("Rice") and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            actions.single { it.label == context.getString(R.string.transactions_delete) }.action()
        }
        assertEquals(listOf("rice"), deleted)
    }

    @Test
    fun everythingElseHasItsOwnNameAndShowsTheUnsplitBudget() {
        show(state().copy(category = EVERYTHING_ELSE, line = BudgetLine(EVERYTHING_ELSE, 30_000, 15_000)))
        compose.onNodeWithText(context.getString(R.string.budgets_everything_else)).assertIsDisplayed()
        compose.onNodeWithText("15,000 of 30,000 Ks spent").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_split))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).assertDoesNotExist()
        assertEquals(1, budgetEdits)
    }

    @Test
    fun aCategoryWithNoExpensesExplainsWhichEntriesWillAppear() {
        show(state().copy(line = BudgetLine("Groceries", 50_000, 0), days = emptyList()))
        compose.onNodeWithText(context.getString(R.string.budget_transactions_empty_title)).assertIsDisplayed()
        compose.onNodeWithText("50,000").assertIsDisplayed()
        compose.onNodeWithText("0 of 50,000 Ks spent").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).assertIsDisplayed()
    }

    @Test
    fun loadingWaitsForTheLedgerBeforeShowingAnEmptyCategory() {
        show(state().copy(loading = true, days = emptyList(), line = null))
        compose.onNodeWithContentDescription(context.getString(R.string.budgets_loading)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.budget_transactions_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_split)).assertDoesNotExist()
    }

    @Test
    fun loadingDoesNotExposeACachedBudgetEditAction() {
        show(state().copy(loading = true))
        compose.onNodeWithContentDescription(context.getString(R.string.budgets_loading)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_split)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "my-rMM-w390dp-h844dp")
    fun burmeseCopyAndDigitsKeepEntryActionsAvailable() {
        show(state().copy(myanmarDigits = true), dark = true)
        compose.onNodeWithText("၃၅,၀၀၀").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).assertIsDisplayed().performClick()
        assertEquals(1, budgetEdits)
        compose.onNodeWithText(context.getString(R.string.budgets_spent_of_total, "၁၅,၀၀၀", "၅၀,၀၀၀")).assertIsDisplayed()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        assertEquals(listOf("rice"), opened)
    }

    @Test
    @Config(qualifiers = "w360dp-h844dp")
    fun largeTextKeepsTheCategoryMonthFullWidthAboveItsEditAction() {
        show(fontScale = 2f)
        assertMonthAboveAction(
            context.getString(R.string.budget_transactions_month, "October"),
            context.getString(R.string.budget_category_edit_budget),
        )
        compose.onNodeWithText(context.getString(R.string.budget_category_edit_budget)).performClick()
        assertEquals(1, budgetEdits)
    }

    @Test
    @Config(qualifiers = "w360dp-h844dp")
    fun largeTextKeepsTheOverviewMonthFullWidthAboveEditPlan() {
        val plan = MonthPlan(month, 100_000, 0, listOf(Budget("Groceries", 50_000)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                BudgetWiseTheme {
                    BudgetsScreen(
                        state = BudgetsUiState(month, loading = false, plan = plan, spentKyat = 15_000),
                        chip = SyncChip.SYNCED, onChip = {}, onSettings = {}, onRefresh = {},
                        onEditPlan = { budgetEdits++ }, onCheckMonth = {},
                    )
                }
            }
        }
        assertMonthAboveAction(
            context.getString(R.string.plan_month_budget, "October"),
            context.getString(R.string.budgets_edit_plan),
        )
        compose.onNodeWithText(context.getString(R.string.budgets_edit_plan)).performClick()
        assertEquals(1, budgetEdits)
    }

    private fun assertMonthAboveAction(monthLabel: String, action: String) {
        val labelBounds = compose.onNodeWithContentDescription(monthLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithText(action).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The action must sit below the complete month label at large font scale", actionBounds.top >= labelBounds.bottom)
        assertTrue("The month label must retain the action's full horizontal lane", labelBounds.left <= actionBounds.left && labelBounds.right >= actionBounds.right)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun longAmountsKeepEveryDigitOnANarrowPhoneWithLargeText() {
        show(state().copy(line = BudgetLine("Groceries", 9_007_199_254_740_991, 15_000)), fontScale = 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("9,007,199,254,725,991").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse("All amount digits must fit without horizontal clipping", layouts.single().didOverflowWidth)
        assertFalse("All amount digits must fit without vertical clipping", layouts.single().didOverflowHeight)
    }

    @Test
    fun tappingAnOverviewCategoryOrEverythingElsePassesTheMatchingBudget() {
        val plan = MonthPlan(month, 100_000, 0, listOf(Budget("Groceries", 50_000)))
        compose.setContent {
            BudgetWiseTheme {
                BudgetsScreen(
                    state = BudgetsUiState(month, loading = false, plan = plan, spentKyat = 15_000,
                        lines = listOf(BudgetLine("Groceries", 50_000, 10_000)), everythingElse = BudgetLine(EVERYTHING_ELSE, 50_000, 5_000)),
                    chip = SyncChip.SYNCED, onChip = {}, onSettings = {}, onRefresh = {}, onEditPlan = {}, onCheckMonth = {},
                    onOpenCategory = { opened += it },
                )
            }
        }
        compose.onNodeWithContentDescription("Groceries, 10,000 kyat spent of 50,000 kyat, 40,000 kyat left").performClick()
        compose.onNodeWithContentDescription("Everything else, 5,000 kyat spent of 50,000 kyat, 45,000 kyat left").performClick()
        assertEquals(listOf("Groceries", EVERYTHING_ELSE), opened)
    }
}
