package app.budgetwise.ui.manual

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "my-rMM-w411dp-h914dp")
class AddManualScreenTest {
    @get:Rule val compose = createComposeRule()

    private val categoryHeading = ApplicationProvider.getApplicationContext<Context>().getString(R.string.manual_category)

    private fun show(myanmarDigits: Boolean, type: TxType = TxType.EXPENSE) = compose.setContent {
        BudgetWiseTheme {
            AddManualScreen(
                state = AddManualUiState(today = LocalDate.parse("2026-10-21"), description = "rice", myanmarDigits = myanmarDigits, type = type),
                onClose = {},
                onTypeChange = {},
                onAmountChange = {},
                onCategoryChange = {},
                onDescriptionChange = {},
                onDateChange = {},
                onSave = {},
                onSayIt = {},
                onScan = {},
                focusAmount = false,
            )
        }
    }

    @Test
    fun descriptionCounterUsesAsciiDigitsEvenInTheBurmeseLocale() {
        show(myanmarDigits = false)
        compose.onNodeWithText("4/200").assertIsDisplayed()
    }

    @Test
    fun descriptionCounterFollowsTheMyanmarDigitsSetting() {
        show(myanmarDigits = true)
        compose.onNodeWithText("၄/၂၀၀").assertIsDisplayed()
    }

    @Test
    fun anExpenseAsksForACategory() {
        show(myanmarDigits = false)
        compose.onNodeWithText(categoryHeading).assertIsDisplayed()
    }

    @Test
    fun incomeDoesNotAskForACategory() {
        show(myanmarDigits = false, type = TxType.INCOME)
        compose.onNodeWithText(categoryHeading).assertDoesNotExist()
    }
}
