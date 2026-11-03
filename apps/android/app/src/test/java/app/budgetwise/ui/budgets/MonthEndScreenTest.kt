package app.budgetwise.ui.budgets

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
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
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.reminders.MonthEndReminders
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.TestUser
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class MonthEndScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-11-01").atTime(9, 30).atZone(zone).toInstant(), zone)
    private val october = YearMonth.of(2026, 10)
    private val plans = FakeMonthPlanSource(listOf(MonthPlan(october, 2_000_000, 0, listOf(Budget("Housing", 500_000)))))
    private val store = FakeTransactionStore(listOf(testTransaction("rent", 1_656_500, LocalDate.parse("2026-10-01"), category = "Housing")))
    private val checked = mutableListOf<YearMonth>()
    private val sync = FakeSyncControls()

    private fun show() {
        val vm = MonthEndViewModel(
            SavedStateHandle(mapOf(MonthEndViewModel.ARG_MONTH to october.toString())),
            plans,
            store,
            FakeSettingsStore(),
            clock,
            MonthEndReminders { },
            sync,
            SessionManager(FakeTokenStore(StoredSession("tok", clock.instant().plus(Duration.ofDays(20)), TestUser)), clock)
                .also { runBlocking { it.load() } },
        )
        compose.setContent {
            BudgetWiseTheme(darkTheme = true) { MonthEndRoute(onClose = {}, onChecked = { checked += it }, viewModel = vm) }
        }
    }

    @Test
    fun yesSavesTheLeftoverAndGoesOnToNovember() {
        show()

        compose.onNodeWithText("End of October").assertIsDisplayed()
        compose.onNodeWithContentDescription("Left in October's budget 343,500 kyat, 1,656,500 kyat spent of 2,000,000 kyat").assertIsDisplayed()
        compose.onNode(hasText("Next: plan November") and hasClickAction()).assertIsNotEnabled()

        compose.onNode(hasText("Yes, about 343,500") and hasClickAction()).performClick().assertIsSelected()
        compose.onNode(hasText("Next: plan November") and hasClickAction()).assertIsEnabled().performClick()

        assertEquals(listOf(october to 343_500L), plans.endChecks)
        assertEquals(listOf(YearMonth.of(2026, 11)), checked)
    }

    @Test
    fun lessOnHandOffersToRecordTheDifference() {
        show()

        compose.onNode(hasText(text(R.string.month_end_no)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(text(R.string.month_end_i_have)).performTextReplacement("280000")

        compose.onNodeWithContentDescription("63,500 Ks went somewhere that wasn't recorded.").assertIsDisplayed()
        compose.onNode(hasText("Add it to October as “Not recorded”") and hasClickAction()).assertIsOn()

        compose.onNode(hasText("Next: plan November") and hasClickAction()).performClick()

        assertEquals(listOf(october to 280_000L), plans.endChecks)
        assertEquals(63_500L, store.added.single().amountKyat)
        assertEquals("Not recorded", store.added.single().description)
        assertEquals(listOf(YearMonth.of(2026, 11)), checked)
    }

    @Test
    fun moreOnHandSaysSoAndOffersNoEntry() {
        show()

        compose.onNode(hasText(text(R.string.month_end_no)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(text(R.string.month_end_i_have)).performTextReplacement("400000")

        compose.onNodeWithText("That's 56,500 Ks more than expected.").assertIsDisplayed()
        compose.onNodeWithText("Add it to October as “Not recorded”").assertDoesNotExist()
    }

    @Test
    fun theLeftoverWaitsForTheLatestEntries() {
        val gate = CompletableDeferred<Unit>()
        sync.syncGate = gate
        show()

        compose.onNodeWithContentDescription(text(R.string.month_end_counting)).assertIsDisplayed()
        compose.onNodeWithText("Yes, about 343,500").assertDoesNotExist()
        compose.onNode(hasText("Next: plan November") and hasClickAction()).assertIsNotEnabled()

        gate.complete(Unit)

        compose.onNode(hasText("Yes, about 343,500") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun withoutTheLatestEntriesItOffersTryAgain() {
        sync.onSyncAndWait = { SyncOutcome.RetryLater }
        show()

        compose.onNodeWithText(text(R.string.month_end_pull_failed)).assertIsDisplayed()
        compose.onNode(hasText("Next: plan November") and hasClickAction()).assertIsNotEnabled()

        sync.onSyncAndWait = { SyncOutcome.Success(pushed = 0, pulled = 0) }
        compose.onNode(hasText(text(R.string.action_try_again)) and hasClickAction()).performClick()

        compose.onNode(hasText("Yes, about 343,500") and hasClickAction()).assertIsDisplayed()
        assertEquals(2, sync.syncAndWaitCalls)
    }

    @Test
    fun aFailedCheckShowsALineAndStays() {
        plans.onEndCheck = { _, _ -> ApiResult.Failure("network", "offline", retryable = true) }
        show()

        compose.onNode(hasText("Yes, about 343,500") and hasClickAction()).performClick()
        compose.onNode(hasText("Next: plan November") and hasClickAction()).performClick()

        compose.onNodeWithText(text(R.string.plan_save_failed_network)).assertIsDisplayed()
        assertTrue(checked.isEmpty())
    }
}
