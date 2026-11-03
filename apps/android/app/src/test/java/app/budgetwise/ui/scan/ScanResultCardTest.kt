package app.budgetwise.ui.scan

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
@Config(qualifiers = "w411dp-h914dp")
class ScanResultCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var retries = 0
    private var retakes = 0
    private var picks = 0
    private var manual = 0
    private var reviews = 0

    private fun show(problem: ScanProblem, total: Long? = null, currency: String? = null, myanmarDigits: Boolean = false) {
        compose.setContent {
            BudgetWiseTheme {
                Box(Modifier.padding(24.dp)) {
                    ProblemCard(
                        problem = problem,
                        waitMinutes = null,
                        myanmarDigits = myanmarDigits,
                        onTryAgain = { retries++ },
                        onRetake = { retakes++ },
                        onPick = { picks++ },
                        onEnterYourself = { manual++ },
                        onSignIn = {},
                        onReviewTotal = { reviews++ },
                        totalKyat = total,
                        currency = currency,
                    )
                }
            }
        }
    }

    @Test
    fun multipleReceiptsExplainTheProblemAndRetakeInsteadOfRetryingTheSamePhoto() {
        show(ScanProblem.MULTIPLE_RECEIPTS)
        compose.onNodeWithText(context.getString(R.string.scan_multiple_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_multiple_detail)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_one_receipt)).performClick()
        compose.onNodeWithText(context.getString(R.string.scan_enter_yourself)).performClick()
        compose.runOnIdle {
            assertEquals(1, retakes)
            assertEquals(1, manual)
            assertEquals(0, retries)
        }
    }

    @Test
    fun unsupportedWalletExplainsSupportedScreensAndOffersAnotherPhoto() {
        show(ScanProblem.UNSUPPORTED_WALLET)
        compose.onNodeWithText(context.getString(R.string.scan_unsupported_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_unsupported_detail)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_choose_another)).performClick()
        compose.onNodeWithText(context.getString(R.string.scan_enter_yourself)).performClick()
        compose.runOnIdle {
            assertEquals(1, picks)
            assertEquals(1, manual)
            assertEquals(0, retries)
        }
    }

    @Test
    fun foreignCurrencyNamesTheCurrencyAndOffersManualKyatEntry() {
        show(ScanProblem.FOREIGN_CURRENCY, currency = "THB")
        compose.onNodeWithText(context.getString(R.string.scan_foreign_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_foreign_currency_detail, "THB")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_enter_yourself)).performClick()
        compose.onNodeWithText(context.getString(R.string.scan_retake)).performClick()
        compose.runOnIdle {
            assertEquals(1, manual)
            assertEquals(1, retakes)
            assertEquals(0, retries + reviews)
        }
    }

    @Test
    fun foreignCurrencyWithoutACurrencyCodeStillExplainsKyatOnly() {
        show(ScanProblem.FOREIGN_CURRENCY)
        compose.onNodeWithText(context.getString(R.string.scan_foreign_detail)).assertIsDisplayed()
    }

    @Test
    fun totalOnlyShowsTheAmountAndRequiresReviewBeforeSaving() {
        show(ScanProblem.TOTAL_ONLY, total = 5000)
        compose.onNodeWithText(context.getString(R.string.scan_total_only_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_total_only_detail)).assertIsDisplayed()
        compose.onNodeWithText("5,000 Ks").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_review_total)).performClick()
        compose.onNodeWithText(context.getString(R.string.scan_retake)).performClick()
        compose.onNodeWithText(context.getString(R.string.scan_enter_yourself)).performClick()
        compose.runOnIdle {
            assertEquals(1, reviews)
            assertEquals(1, retakes)
            assertEquals(1, manual)
            assertEquals(0, retries)
        }
    }

    @Test
    fun totalOnlyRespectsTheMyanmarDigitSetting() {
        show(ScanProblem.TOTAL_ONLY, total = 5000, myanmarDigits = true)
        compose.onNodeWithText("၅,၀၀၀ Ks").assertIsDisplayed()
    }

    @Test
    fun connectionProblemsStillRetryTheKeptPhoto() {
        show(ScanProblem.SERVER)
        compose.onNodeWithText(context.getString(R.string.action_try_again)).performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, retakes + picks)
        }
    }
}
