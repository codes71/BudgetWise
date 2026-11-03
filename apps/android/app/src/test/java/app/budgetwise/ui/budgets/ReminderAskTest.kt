package app.budgetwise.ui.budgets

import android.Manifest
import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.reminders.FakeReminderStore
import app.budgetwise.ui.theme.BudgetWiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The one-line reason for budget reminders, after the first plan save, and that any answer is final. */
@RunWith(RobolectricTestRunner::class)
class ReminderAskTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = FakeReminderStore()

    private fun text(@StringRes id: Int) = app.getString(id)

    private fun show(): () -> Unit {
        val vm = ReminderAskViewModel(store, CoroutineScope(Dispatchers.Unconfined))
        lateinit var ask: () -> Unit
        compose.setContent { BudgetWiseTheme { ask = rememberAskReminders(vm) } }
        compose.waitForIdle()
        return ask
    }

    @Test
    fun theReasonShowsOnceAndNotNowIsFinal() {
        val ask = show()

        compose.runOnIdle { ask() }
        compose.onNodeWithText(text(R.string.reminders_ask_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reminders_ask_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reminders_ask_not_now)).performClick()

        compose.onNodeWithText(text(R.string.reminders_ask_title)).assertDoesNotExist()
        assertTrue(store.notificationsAsked.value)
        // The next save asks nothing.
        compose.runOnIdle { ask() }
        compose.onNodeWithText(text(R.string.reminders_ask_title)).assertDoesNotExist()
    }

    @Test
    fun allowHandsOverToAndroidAndIsFinalToo() {
        val ask = show()

        compose.runOnIdle { ask() }
        compose.onNodeWithText(text(R.string.reminders_ask_allow)).performClick()

        compose.onNodeWithText(text(R.string.reminders_ask_title)).assertDoesNotExist()
        assertTrue(store.notificationsAsked.value)
    }

    @Test
    fun alreadyAllowedNothingIsShown() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val ask = show()

        compose.runOnIdle { ask() }

        compose.onNodeWithText(text(R.string.reminders_ask_title)).assertDoesNotExist()
        assertFalse(store.notificationsAsked.value)
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13NothingIsAsked() {
        val ask = show()

        compose.runOnIdle { ask() }

        compose.onNodeWithText(text(R.string.reminders_ask_title)).assertDoesNotExist()
    }
}
