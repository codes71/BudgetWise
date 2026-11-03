package app.budgetwise.ui.manual

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.Source
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The form on a phone the size of the boards (390 x 844), in English unless a test says otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class AddManualScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun text(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private val today = LocalDate.parse("2026-10-21")

    private val past = listOf(
        RecentEntry(TxType.EXPENSE, "Bus to Sule", 600, "Transport"),
        RecentEntry(TxType.EXPENSE, "Tea shop", 1_200, "Food and Drink Item"),
        RecentEntry(TxType.EXPENSE, "Top-up", 5_000, "Utilities"),
        RecentEntry(TxType.EXPENSE, "Mohinga", 1_500, "Food and Drink Item"),
    )

    private val picked = mutableListOf<RecentEntry>()
    private val dates = mutableListOf<LocalDate>()
    private val categories = mutableListOf<String>()
    private var deletes = 0
    private var savedAsNew = 0

    private fun show(state: AddManualUiState) = compose.setContent {
        BudgetWiseTheme {
            AddManualScreen(
                state = state,
                onClose = {},
                onTypeChange = {},
                onAmountChange = {},
                onCategoryChange = { categories += it },
                onDescriptionChange = {},
                onRecentPick = { picked += it },
                onDateChange = { dates += it },
                onSave = {},
                onDelete = { deletes++ },
                onSaveAsNew = { savedAsNew++ },
                focusAmount = false,
            )
        }
    }

    private fun filled(
        type: TxType = TxType.EXPENSE,
        description: String = "Mohinga with Ko Aung",
        date: LocalDate = today,
    ) = AddManualUiState(
        today = today,
        type = type,
        amountDigits = "4500",
        category = "Food and Drink Item",
        categoryFrom = "Mohinga",
        description = description,
        date = date,
        past = past,
    )

    /** How far the fields scroll: 0 when they all fit above Save. */
    private fun scrollRange(): Float = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    @Test
    fun theWholeFormFitsOnOneScreenWithSaveShowingTheAmount() {
        show(filled(description = ""))

        // Nothing scrolls at the boards' size, with three recent entries showing: the last chips and Save are on screen.
        assertEquals(0f, scrollRange(), 0f)
        compose.onNodeWithContentDescription("Top-up, ${text(R.string.kyat_amount, "5,000")}").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.category_other)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_day_other)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.manual_save_amount, "4,500")) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_title)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "my-rMM-w390dp-h844dp")
    fun theWholeFormFitsOnOneScreenInBurmeseToo() {
        show(filled(description = ""))

        assertEquals(0f, scrollRange(), 0f)
        compose.onNodeWithText(text(R.string.category_other)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_day_other)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.manual_save_amount, "4,500")) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun recentEntriesAreOfferedAndTappingOneHandsItOver() {
        show(filled(description = ""))

        // Three at most: the fourth is not offered.
        compose.onNodeWithContentDescription("Top-up, ${text(R.string.kyat_amount, "5,000")}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mohinga, ${text(R.string.kyat_amount, "1,500")}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tea shop, ${text(R.string.kyat_amount, "1,200")}").performClick()
        assertEquals(listOf(past[1]), picked)
    }

    @Test
    fun aPickedCategorySaysWhereItCameFrom() {
        show(filled())

        compose.onNodeWithText(text(R.string.manual_picked_from, "Mohinga")).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.draft3_entry_food_short)) and hasClickAction()).assertIsSelected()
    }

    @Test
    fun theDayChipsPickTodayYesterdayOrShowTheOtherDay() {
        show(filled(date = today.minusDays(5)))

        // Another day is shown on its chip, in place of "Other day".
        compose.onNodeWithText(text(R.string.manual_day_other)).assertDoesNotExist()
        compose.onNodeWithText("Fri, Oct 16").assertIsSelected()
        compose.onNodeWithText(text(R.string.transactions_yesterday)).performClick()
        compose.onNodeWithText(text(R.string.transactions_today)).performClick()
        assertEquals(listOf(today.minusDays(1), today), dates)
    }

    @Test
    fun changingAReceiptExpenseDateClearlyMovesTheWholeReceipt() {
        show(filled().copy(editing = true, editingReceipt = true, source = Source.SCAN))
        compose.onNodeWithContentDescription(text(R.string.manual_receipt_date)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_receipt_date_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_yesterday)).performClick()
        assertEquals(listOf(today.minusDays(1)), dates)
    }

    @Test
    fun convertingAReceiptEntryToIncomeExplainsThatItWillBeSavedSeparately() {
        show(filled(type = TxType.INCOME).copy(editing = true, editingReceipt = true, source = Source.SCAN))
        compose.onNodeWithText(text(R.string.manual_receipt_income_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_receipt_date_note)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.manual_receipt_date)).assertDoesNotExist()
    }

    @Test
    fun incomeDoesNotAskForACategory() {
        show(filled(type = TxType.INCOME))

        compose.onNodeWithText(text(R.string.manual_income_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.draft3_entry_food_short)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.manual_picked_from, "Mohinga")).assertDoesNotExist()
    }

    @Test
    fun moreKeepsCustomCategoriesReachableBeyondTheQuickChoices() {
        show(filled().copy(categories = CategoryRepository.BUILT_IN + "School fees"))

        compose.onNodeWithText(text(R.string.draft3_entry_more)).performClick()
        compose.onNodeWithText("School fees").performScrollTo().performClick()

        assertEquals(listOf("School fees"), categories)
        compose.onNodeWithText("School fees").assertDoesNotExist()
    }

    @Test
    fun anExpenseAsksForACategory() {
        show(filled())

        compose.onNodeWithText(text(R.string.category_groceries)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_income_note)).assertDoesNotExist()
    }

    @Test
    fun theCountShowsNearTheLimitInTheDigitsTheSettingAsksFor() {
        val long = "x".repeat(160)
        show(filled(description = long))
        compose.onNodeWithText("160/200").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "my-rMM-w390dp-h844dp")
    fun theCountFollowsTheMyanmarDigitsSetting() {
        show(filled(description = "x".repeat(160)).copy(myanmarDigits = true))
        compose.onNodeWithText("၁၆၀/၂၀၀").assertIsDisplayed()
    }

    @Test
    fun aShortDescriptionHasNoCount() {
        show(filled(description = "rice"))
        compose.onNodeWithText("4/200").assertDoesNotExist()
    }

    @Test
    fun anEntryBeingChangedSaysHowItWasAddedAndCanBeDeleted() {
        show(filled().copy(editing = true, source = Source.VOICE))

        compose.onNodeWithText(text(R.string.manual_edit_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.manual_source_voice)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.manual_save_changes)) and hasClickAction()).assertIsDisplayed()
        // A change offers no recent entries: the entry is filled in already.
        compose.onNodeWithText("Bus to Sule").assertDoesNotExist()
        compose.onNode(hasText(text(R.string.manual_delete)) and hasClickAction()).performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun anEntryDeletedElsewhereOffersToSaveItAsNew() {
        show(filled().copy(editing = true, source = Source.MANUAL, gone = true))

        compose.onNodeWithText(text(R.string.manual_gone)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.manual_save_changes)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText(text(R.string.manual_delete)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText(text(R.string.manual_save_as_new)) and hasClickAction()).performClick()
        assertEquals(1, savedAsNew)
    }

    @Test
    fun aCategoryTooLongForTheServerSaysToPickAnother() {
        val long = "Groceries and household things for the whole family this month"
        show(filled().copy(editing = true, category = long, categoryTouched = true, categoryFrom = null))

        compose.onNodeWithText(text(R.string.manual_category_error_too_long)).assertIsDisplayed()
    }

    @Test
    fun aRefusedFractionSaysTheAmountIsWholeKyat() {
        show(filled().copy(wholeKyatHint = true))

        compose.onNodeWithText(text(R.string.manual_amount_whole_kyat)).assertIsDisplayed()
    }

    @Test
    fun theDateSheetOffersAChangedEntrysOwnFutureDayAndNoOther() {
        val kept = today.plusDays(3)
        show(filled(date = kept).copy(editing = true, keptDate = kept))
        compose.onNodeWithText("Sat, Oct 24").performClick()

        compose.onNodeWithContentDescription("October 24").assertIsSelected()
        compose.onNodeWithContentDescription("October 24").assertIsEnabled()
        compose.onNodeWithContentDescription("October 22").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.action_ok)).performClick()
        assertEquals(listOf(kept), dates)
    }

    @Test
    fun anEntryNotReadYetCannotBeSavedOrDeleted() {
        show(AddManualUiState(today = today, editing = true, loaded = false))

        compose.onNode(hasText(text(R.string.manual_save_changes)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText(text(R.string.manual_delete)) and hasClickAction()).assertIsNotEnabled()
    }
}
