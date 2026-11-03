package app.budgetwise.ui.manual

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class AddSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun text(@StringRes id: Int) = context.getString(id)

    private val picked = mutableListOf<String>()

    private fun show(voice: Boolean = true, explainNoVoice: Boolean = false) = compose.setContent {
        BudgetWiseTheme {
            AddSheet(
                onDismiss = { picked += "dismiss" },
                onScan = { picked += "scan" },
                onSayIt = if (voice) ({ picked += "say" }) else null,
                onTypeIt = { picked += "type" },
                onAddIncome = { picked += "income" },
                explainNoVoice = explainNoVoice,
                onNoVoiceExplained = { picked += "explained" },
            )
        }
    }

    private fun tap(@StringRes id: Int) = compose.onNode(hasText(text(id)) and hasClickAction()).performClick()

    @Test
    fun everyWayToAddOpensItsOwnScreen() {
        show()

        compose.onNodeWithText(text(R.string.add_scan_detail)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.add_say_it_example)).assertIsDisplayed()
        tap(R.string.scan_entry)
        tap(R.string.voice_say_it)
        tap(R.string.add_type_it)
        tap(R.string.add_income)
        assertEquals(listOf("scan", "say", "type", "income"), picked)
    }

    @Test
    fun withoutVoiceThereIsNoSayIt() {
        show(voice = false)

        compose.onNodeWithText(text(R.string.voice_say_it)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.add_type_it)).assertIsDisplayed()
    }

    @Test
    fun withoutVoiceTheSheetSaysWhyOnce() {
        show(voice = false, explainNoVoice = true)

        compose.onNodeWithText(text(R.string.voice_unsupported_title)).assertIsDisplayed()
    }
}
