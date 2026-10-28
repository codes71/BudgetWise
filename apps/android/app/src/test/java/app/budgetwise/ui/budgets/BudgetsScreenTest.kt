package app.budgetwise.ui.budgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.TestUser
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
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

    /** Stands in for the Budgets back stack entry, which stops when another tab opens and starts when it comes back. */
    private class EntryLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun reopeningTheTabRefreshesAgain() {
        val zone = ZoneId.of("Asia/Yangon")
        val clock = Clock.fixed(LocalDate.parse("2026-10-21").atTime(9, 30).atZone(zone).toInstant(), zone)
        val session = SessionManager(FakeTokenStore(StoredSession("tok", clock.instant().plus(Duration.ofDays(20)), TestUser)), clock)
            .also { runBlocking { it.load() } }
        val budgets = FakeBudgetSource()
        val vm = BudgetsViewModel(budgets, FakeTransactionStore(), FakeSettingsStore(), session, clock)
        val entry = EntryLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides entry) { BudgetWiseTheme { BudgetsRoute(vm) } }
        }
        compose.waitForIdle()
        val afterFirstOpen = budgets.refreshes

        // The bottom bar keeps the ViewModel when the user leaves for another tab and comes back.
        compose.runOnIdle { entry.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { entry.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertEquals(afterFirstOpen + 1, budgets.refreshes)
    }
}
