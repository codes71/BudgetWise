package app.budgetwise.ui.scan

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class ScanMotionTest {
    private val durationScale = object : MotionDurationScale { override val scaleFactor = 1f }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = durationScale)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun preparingKeepsTheCameraAndProblemsKeepThePhoto() {
        assertEquals(scanStageKey(ScanStage.CAMERA), scanStageKey(ScanStage.PREPARING))
        assertEquals(scanStageKey(ScanStage.READING), scanStageKey(ScanStage.PROBLEM))
        assertNotEquals(scanStageKey(ScanStage.CAMERA), scanStageKey(ScanStage.READING))
        assertNotEquals(scanStageKey(ScanStage.READING), scanStageKey(ScanStage.REVIEW))
    }

    @Test
    fun replacingAProblemKeepsDistinctSnapshotsDuringOverlapAndCanBeInterrupted() {
        var state by mutableStateOf(
            ScanUiState(today = LocalDate.parse("2026-11-02"), stage = ScanStage.PROBLEM, problem = ScanProblem.TOO_DARK),
        )
        compose.setContent {
            BudgetWiseTheme {
                ScanPhotoCard(state, onRetake = {}, onTryAgain = {}, onPick = {}, onEnterYourself = {}, onSignIn = {}, onReviewTotal = {})
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(problem = ScanProblem.FOREIGN_CURRENCY, currency = "USD") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()

        // Checking both trees catches an outer-state capture or a shared "problem present" identity.
        compose.onNodeWithText(context.getString(R.string.scan_too_dark_title), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.scan_foreign_title), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.scan_foreign_currency_detail, "USD"), useUnmergedTree = true).assertExists()

        compose.runOnIdle { state = state.copy(problem = ScanProblem.SERVER) }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText(context.getString(R.string.login_error_server_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.scan_too_dark_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.scan_foreign_title)).assertDoesNotExist()
    }

    @Test
    fun theExitingSlowReadingActionCannotCancelTheCurrentRead() {
        var state by mutableStateOf(
            ScanUiState(today = LocalDate.parse("2026-11-02"), stage = ScanStage.READING, slow = true),
        )
        var retakes = 0
        var manual = 0
        compose.setContent {
            BudgetWiseTheme {
                ScanPhotoCard(
                    state,
                    onRetake = { retakes++ },
                    onTryAgain = {},
                    onPick = {},
                    onEnterYourself = { manual++ },
                    onSignIn = {},
                    onReviewTotal = {},
                    currentState = { state },
                )
            }
        }
        val action = compose.onNodeWithText(context.getString(R.string.scan_enter_yourself))
        val retainedEnter = action.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(slow = false) }
        compose.mainClock.advanceTimeByFrame()
        action.assertExists()

        compose.runOnIdle { retainedEnter() }
        assertEquals(0, retakes)
        assertEquals(0, manual)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        action.assertDoesNotExist()
    }
}
