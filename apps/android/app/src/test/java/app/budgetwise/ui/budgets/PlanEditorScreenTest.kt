package app.budgetwise.ui.budgets

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class PlanEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-11-01").atTime(9, 30).atZone(zone).toInstant(), zone)
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val plans = FakeMonthPlanSource()
    private val store = FakeTransactionStore()
    private var saves = 0
    private var closes = 0

    private fun show(month: YearMonth = november) {
        val vm = PlanEditorViewModel(
            SavedStateHandle(mapOf(PlanEditorViewModel.ARG_MONTH to month.toString())),
            plans, FakeBudgetSource(), FakeCategorySource(), store, FakeSettingsStore(), clock,
        )
        compose.setContent {
            BudgetWiseTheme(darkTheme = true) { PlanEditorRoute(onClose = { closes++ }, onSaved = { saves++ }, viewModel = vm) }
        }
    }

    private val octoberPlan = MonthPlan(
        october,
        2_000_000,
        0,
        listOf(Budget("Housing", 500_000), Budget("Groceries", 350_000)),
        EndCheck(280_000, Instant.parse("2026-11-01T02:30:00Z")),
    )

    @Test
    fun stepOneAddsLastMonthsCheckedLeftoverToTheNewMoney() {
        plans.plans.value = listOf(octoberPlan)
        show()

        compose.onNodeWithText("November budget").assertIsDisplayed()
        compose.onNodeWithText("Add what's left from October").assertIsDisplayed()
        compose.onNodeWithText("You checked it on November 1").assertIsDisplayed()
        compose.onNodeWithText("2,280,000 Ks").assertIsDisplayed()
        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).assertIsEnabled()

        // Unticking starts the month with the new money only.
        compose.onNode(hasText("Add what's left from October") and hasClickAction()).assertIsOn().performClick()
        compose.onNode(hasText("Add what's left from October") and hasClickAction()).assertIsOff()
        compose.onNodeWithText("2,000,000 Ks").assertIsDisplayed()
        compose.onNode(hasText("Next: split 20 lakh") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun withoutLastMonthsPlanStepOneShowsWhatWasSpentBefore() {
        store.rows.value = listOf(
            testTransaction("rice", 1_842_300, LocalDate.parse("2026-10-10")),
            testTransaction("rice", 1_910_450, LocalDate.parse("2026-09-10")),
        )
        show()

        compose.onNodeWithText(text(R.string.plan_how_much_this_month), substring = true, ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("1,842,300 Ks").assertIsDisplayed()
        compose.onNodeWithText("1,910,450 Ks").assertIsDisplayed()
        compose.onNodeWithText("About 18.8 lakh a month. You set a new budget each month.").assertIsDisplayed()
        // Nothing typed yet: Next waits for an amount.
        compose.onNode(hasText(text(R.string.plan_next, "0 Ks")) and hasClickAction()).assertIsNotEnabled()

        compose.onNode(hasText("15 lakh") and hasClickAction()).performClick()
        compose.onNode(hasText("Next: split 15 lakh") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun theSplitGoesBackToTheTotalAndSavesTheMonth() {
        plans.plans.value = listOf(octoberPlan)
        show()

        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).performClick()
        compose.onNodeWithText("Split 22.8 lakh").assertIsDisplayed()
        // 2,280,000 minus the 850,000 split.
        compose.onNodeWithContentDescription("Left to split 1,430,000 kyat, of 2,280,000 kyat").assertIsDisplayed()
        compose.onNodeWithText("Started from October's split. Change any share.").assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        compose.onNodeWithText("November budget").assertIsDisplayed()

        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).performClick()
        compose.onNode(hasText("Save November budget") and hasClickAction()).performClick()

        assertEquals(1, saves)
        assertEquals(MonthPlan(november, 2_000_000, 280_000, octoberPlan.shares), plans.saved.single())
    }

    @Test
    fun aSplitOverTheMonthBlocksSaveUntilTheMonthIsRaised() {
        plans.plans.value = listOf(octoberPlan.copy(endCheck = null))
        show()
        compose.onNode(hasText("15 lakh") and hasClickAction()).performClick()
        compose.onNode(hasText("Next: split 15 lakh") and hasClickAction()).performClick()

        // A share opens its sheet; typing there changes the split behind it.
        compose.onNodeWithContentDescription("Housing, share 500,000 kyat, Nothing in October").performClick()
        compose.onNodeWithContentDescription("Housing share").performTextReplacement("1300000")
        compose.onNode(hasText(text(R.string.plan_done)) and hasClickAction()).performClick()

        compose.onNodeWithContentDescription("Split is over the month by 150,000 kyat").assertIsDisplayed()
        compose.onNode(hasText("Save November budget") and hasClickAction()).assertIsNotEnabled()

        compose.onNode(hasText("Raise the month to 16.5 lakh") and hasClickAction()).performClick()
        compose.onNode(hasText("Save November budget") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun theShareSheetOffersLastMonthTheAverageAndAllThatIsLeft() {
        plans.plans.value = listOf(octoberPlan)
        store.rows.value = listOf(
            testTransaction("rice", 382_000, LocalDate.parse("2026-10-10")),
            testTransaction("rice", 348_400, LocalDate.parse("2026-09-10")),
        )
        show()
        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).performClick()

        compose.onNodeWithContentDescription("Groceries, share 350,000 kyat, 382,000 kyat in October").performClick()

        compose.onNodeWithContentDescription("October, 382,000 kyat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Average, 365,000 kyat").assertIsDisplayed()
        // All that's left: this share plus the 1,430,000 not split.
        compose.onNodeWithContentDescription("All that's left, 1,780,000 kyat").assertIsDisplayed()

        compose.onNodeWithContentDescription("October, 382,000 kyat").performClick()
        compose.onNodeWithText("Leaves 1,398,000 to split").assertIsDisplayed()

        compose.onNode(hasText("Take Groceries out of the split") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Groceries, share 382,000 kyat, 382,000 kyat in October").assertDoesNotExist()
    }

    @Test
    fun aFailedSaveShowsALineUnderTheButtonAndStays() {
        plans.plans.value = listOf(octoberPlan)
        plans.onSave = { ApiResult.Failure("network", "offline", retryable = true) }
        show()
        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).performClick()

        compose.onNode(hasText("Save November budget") and hasClickAction()).performClick()

        compose.onNodeWithText(text(R.string.plan_save_failed_network)).assertIsDisplayed()
        compose.onNodeWithText("Split 22.8 lakh").assertIsDisplayed()
        assertEquals(0, saves)
    }

    @Test
    fun addACategoryListsTheOnesWithoutAShare() {
        plans.plans.value = listOf(octoberPlan)
        store.rows.value = listOf(testTransaction("doctor", 32_500, LocalDate.parse("2026-10-03"), category = "Health"))
        show()
        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).performClick()

        compose.onNode(hasText(text(R.string.plan_add_category)) and hasClickAction()).performClick()
        compose.onNodeWithText("Its share comes out of the 1,430,000 left to split.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Health, 32,500 in October").performClick()

        // Its sheet opens, with no amount yet.
        compose.onNodeWithContentDescription("Health share").assertIsDisplayed()
        compose.onNode(hasText(text(R.string.plan_done)) and hasClickAction()).performClick()
        compose.onNodeWithText("Give Health an amount, or take it out of the split.").assertIsDisplayed()
        compose.onNode(hasText("Save November budget") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun aPlanFetchThatFailedIsSaidAboveTheForm() {
        plans.plans.value = listOf(octoberPlan)
        plans.refreshResult = ApiResult.Failure("network", "offline", retryable = true)
        show()

        compose.onNodeWithText(text(R.string.plan_stale)).assertIsDisplayed()
        compose.onNodeWithText("November budget").assertIsDisplayed()
    }

    @Test
    fun aCategoryTheServerNoLongerHasIsNamed() {
        plans.plans.value = listOf(octoberPlan)
        plans.onSave = { ApiResult.Failure("unknown_category", "Unknown category: Groceries", retryable = false) }
        show()
        compose.onNode(hasText("Next: split 22.8 lakh") and hasClickAction()).performClick()

        compose.onNode(hasText("Save November budget") and hasClickAction()).performClick()

        compose.onNodeWithText("Groceries isn't one of your categories any more. Take it out of the split and try again.").assertIsDisplayed()
    }

    @Test
    fun closingStepOneLeavesTheEditor() {
        show()

        compose.onNodeWithContentDescription(text(R.string.action_close)).performClick()

        assertEquals(1, closes)
    }
}
