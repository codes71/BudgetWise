package app.budgetwise.ui.budgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class BudgetTransactionsLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class EntryLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class MovableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)
        override fun instant(): Instant = now
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun resumingTheCategoryAfterMidnightChangesTodayToYesterdayWithoutAnExpenseWrite() {
        val date = LocalDate.of(2026, 10, 31)
        val month = YearMonth.from(date)
        val zone = ZoneId.of("Asia/Yangon")
        val clock = MovableClock(date.atTime(23, 55).atZone(zone).toInstant(), zone)
        val rows = listOf(testTransaction("Rice", 30_000, date))
        val transactions = FakeTransactionStore(rows)
        val vm = BudgetTransactionsViewModel(
            SavedStateHandle(mapOf(BudgetTransactionsViewModel.ARG_MONTH to month.toString(), BudgetTransactionsViewModel.ARG_CATEGORY to "Groceries")),
            FakeMonthPlanSource(listOf(MonthPlan(month, 100_000, 0, listOf(Budget("Groceries", 100_000))))),
            transactions, FakeSettingsStore(), clock, appScope,
        )
        val entry = EntryLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) {
                BudgetWiseTheme { BudgetTransactionsRoute({}, {}, viewModel = vm) }
            }
        }
        compose.onNodeWithContentDescription("Today").assertIsDisplayed()

        compose.runOnIdle {
            entry.registry.currentState = Lifecycle.State.CREATED
            clock.now = date.plusDays(1).atTime(8, 0).atZone(zone).toInstant()
            entry.registry.currentState = Lifecycle.State.RESUMED
        }

        compose.onNodeWithContentDescription("Yesterday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Today").assertDoesNotExist()
        compose.onNodeWithContentDescription("October budget").assertIsDisplayed()
        compose.onNodeWithText("30,000 of 100,000 Ks spent").assertIsDisplayed()
        org.junit.Assert.assertEquals(rows, transactions.rows.value)
    }
}
