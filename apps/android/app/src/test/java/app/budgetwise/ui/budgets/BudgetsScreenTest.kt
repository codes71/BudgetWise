package app.budgetwise.ui.budgets

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.TestUser
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class BudgetsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private val october = YearMonth.of(2026, 10)
    private val september = YearMonth.of(2026, 9)

    /** Stands in for the Budgets back stack entry, which stops when another tab opens and starts when it comes back. */
    private class EntryLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun show(state: BudgetsUiState, onEditPlan: () -> Unit = {}, onCheckMonth: () -> Unit = {}) = compose.setContent {
        BudgetWiseTheme(darkTheme = true) {
            BudgetsScreen(
                state = state,
                chip = SyncChip.SYNCED,
                onChip = {},
                onSettings = {},
                onRefresh = {},
                onEditPlan = onEditPlan,
                onCheckMonth = onCheckMonth,
            )
        }
    }

    @Test
    fun withoutAPlanAsksForTheMonthsBudget() {
        var edits = 0
        show(BudgetsUiState(month = october, loading = false, lastMonthSpentKyat = 1_842_300), onEditPlan = { edits++ })

        compose.onNodeWithText(text(R.string.budgets_first_headline)).assertIsDisplayed()
        compose.onNodeWithText("You spent 1,842,300 Ks in September").assertIsDisplayed()
        // No plan to edit yet: no Edit plan in the header.
        compose.onNodeWithText(text(R.string.budgets_edit_plan)).assertDoesNotExist()

        compose.onNode(hasText("Set October's budget") and hasClickAction()).performClick()
        assertEquals(1, edits)
    }

    @Test
    fun aPlanShowsWhatIsLeftItsSharesAndEverythingElse() {
        var edits = 0
        val plan = MonthPlan(october, 2_000_000, 0, listOf(Budget("Transport", 150_000), Budget("Housing", 500_000)))
        show(
            BudgetsUiState(
                month = october,
                loading = false,
                plan = plan,
                spentKyat = 1_456_500,
                lines = listOf(BudgetLine("Transport", 150_000, 162_400), BudgetLine("Housing", 500_000, 500_000)),
                everythingElse = BudgetLine(EVERYTHING_ELSE, 1_350_000, 794_100),
            ),
            onEditPlan = { edits++ },
        )

        // The summary is one node for TalkBack, with the amounts in plain words.
        compose.onNodeWithContentDescription(
            "October budget, 2,000,000 kyat. 1,456,500 kyat spent of 2,000,000 kyat, 543,500 kyat left",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription("Transport, 162,400 kyat spent of 150,000 kyat, 12,400 kyat over budget").assertIsDisplayed()
        compose.onNodeWithContentDescription("Housing, 500,000 kyat spent of 500,000 kyat, 0 kyat left").assertIsDisplayed()
        compose.onNodeWithContentDescription("Everything else, 794,100 kyat spent of 1,350,000 kyat, 555,900 kyat left").assertIsDisplayed()

        compose.onNode(hasText(text(R.string.budgets_edit_plan)) and hasClickAction()).performClick()
        assertEquals(1, edits)
    }

    @Test
    fun aMonthOverItsTotalSaysOver() {
        show(
            BudgetsUiState(
                month = october,
                loading = false,
                plan = MonthPlan(october, 100_000, 0, emptyList()),
                spentKyat = 130_000,
                everythingElse = BudgetLine(EVERYTHING_ELSE, 100_000, 130_000),
            ),
        )

        compose.onNodeWithContentDescription(
            "October budget, 100,000 kyat. 130,000 kyat spent of 100,000 kyat, 30,000 kyat over budget",
        ).assertIsDisplayed()
    }

    @Test
    fun theHerosLabelSaysTheTotalInLakhOnlyFromOneLakhUp() {
        compose.setContent {
            BudgetWiseTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(monthBudgetLabel(october, 2_280_000, myanmarDigits = false))
                    androidx.compose.material3.Text(monthBudgetLabel(october, 50_000, myanmarDigits = false))
                }
            }
        }

        compose.onNodeWithText("October budget · 22.8 lakh").assertIsDisplayed()
        compose.onNodeWithText("October budget · 50,000 Ks").assertIsDisplayed()
    }

    @Test
    fun lastMonthsCheckCardOpensItsEndCheck() {
        var checks = 0
        show(
            BudgetsUiState(month = october, loading = false, toCheck = MonthToCheck(september, 343_500)),
            onCheckMonth = { checks++ },
        )

        // One button for TalkBack: the sentence and its action.
        compose.onNode(hasContentDescription("September ended with 343,500 Ks left. Check it") and hasClickAction()).assertIsDisplayed()
        // Also above the first-plan screen.
        compose.onNodeWithText(text(R.string.budgets_first_headline)).assertIsDisplayed()

        compose.onNodeWithContentDescription("September ended with 343,500 Ks left. Check it").performClick()
        assertEquals(1, checks)
    }

    @Test
    fun whileTheFirstFetchRunsAndNoPlanIsCachedItShowsTheSkeleton() {
        show(BudgetsUiState(month = october, loading = false, fetching = true))

        compose.onNodeWithContentDescription(text(R.string.budgets_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_first_headline)).assertDoesNotExist()
    }

    @Test
    fun reopeningTheTabRefreshesAgain() {
        val zone = ZoneId.of("Asia/Yangon")
        val clock = Clock.fixed(LocalDate.parse("2026-10-21").atTime(9, 30).atZone(zone).toInstant(), zone)
        val session = SessionManager(FakeTokenStore(StoredSession("tok", clock.instant().plus(Duration.ofDays(20)), TestUser)), clock)
            .also { runBlocking { it.load() } }
        val plans = FakeMonthPlanSource()
        val vm = BudgetsViewModel(plans, FakeBudgetSource(), FakeTransactionStore(), FakeSettingsStore(), session, clock)
        val entry = EntryLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED }
        var opened: YearMonth? = null
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                BudgetWiseTheme {
                    BudgetsRoute(chip = null, onChip = {}, onSettings = {}, onEditPlan = { opened = it }, onCheckMonth = {}, viewModel = vm)
                }
            }
        }
        compose.waitForIdle()
        val afterFirstOpen = plans.refreshes.size

        // The bottom bar keeps the ViewModel when the user leaves for another tab and comes back.
        compose.runOnIdle { entry.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { entry.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertEquals(afterFirstOpen + 1, plans.refreshes.size)

        // The first-plan screen opens the editor for this month.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Set October's budget"))
        compose.onNode(hasText("Set October's budget") and hasClickAction()).performClick()
        assertEquals(october, opened)
    }
}
