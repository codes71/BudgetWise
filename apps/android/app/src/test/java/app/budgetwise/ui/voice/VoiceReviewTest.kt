package app.budgetwise.ui.voice

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.data.repo.VoiceEntry
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class VoiceReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.parse("2026-11-02")
    private val entry = VoiceEntry(TxType.EXPENSE, 2000, "Food and Drink", "Mohinga", today.minusDays(1), dateGuessed = false)

    @Test
    fun unavailableAiOffersManualEntryAndCloseWithoutNetworkRetry() {
        var manual = 0
        var retries = 0
        var closed = 0
        compose.setContent {
            BudgetWiseTheme {
                VoiceScreen(VoiceUiState(today, VoicePhase.Problem(VoiceProblem.AiUnavailable)),
                    VoiceActions(onTypeInstead = { manual++ }, onRetry = { retries++ }, onClose = { closed++ }))
            }
        }
        compose.onNodeWithText(context.getString(R.string.cloud_ai_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cloud_ai_unavailable_body)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_try_again)).assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.voice_type_instead)).onLast().performClick()
        compose.onNodeWithText(context.getString(R.string.action_close)).performClick()
        compose.runOnIdle { assertEquals(1, manual); assertEquals(1, closed); assertEquals(0, retries) }
    }

    @Test
    @Config(qualifiers = "my-rMM-w411dp-h914dp")
    fun declinedAiExplainsManualEntryWithoutNetworkRetry() {
        var manual = 0
        var retries = 0
        compose.setContent {
            BudgetWiseTheme {
                VoiceScreen(VoiceUiState(today, VoicePhase.Problem(VoiceProblem.AiDeclined)),
                    VoiceActions(onTypeInstead = { manual++ }, onRetry = { retries++ }))
            }
        }
        compose.onNodeWithText(context.getString(R.string.cloud_ai_declined_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cloud_ai_declined_body)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_try_again)).assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.voice_type_instead)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, manual); assertEquals(0, retries) }
    }

    @Test
    fun anEntryIsChangedOnAFormWithTheDayChipsAndKeepsTheChange() {
        val edits = mutableListOf<Pair<Int, VoiceEntry>>()
        val state = VoiceUiState(today = today, phase = VoicePhase.Review(ReviewState(heard = "mohinga two thousand", entries = listOf(entry))))
        compose.setContent {
            BudgetWiseTheme {
                VoiceScreen(state, VoiceActions(onEditEntry = { index, edited -> edits += index to edited }))
            }
        }

        compose.onNodeWithText("Mohinga", substring = true).performClick()

        compose.onNodeWithText(context.getString(R.string.voice_edit_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.transactions_yesterday)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.transactions_today)).performClick()
        compose.onNodeWithText(context.getString(R.string.voice_done)).performClick()

        assertEquals(listOf(0 to entry.copy(date = today)), edits)
    }

    @Test
    fun reviewPayloadUpdatesKeepAnOpenEditorAndItsUnsavedInput() {
        val edits = mutableListOf<VoiceEntry>()
        val review = ReviewState(heard = "mohinga two thousand", entries = listOf(entry))
        val state = mutableStateOf(VoiceUiState(today, VoicePhase.Review(review)))
        compose.setContent {
            BudgetWiseTheme {
                VoiceScreen(state.value, VoiceActions(onEditEntry = { _, edited -> edits += edited }))
            }
        }
        compose.onNodeWithText("Mohinga", substring = true).performClick()
        compose.onNodeWithText("Mohinga").performTextReplacement("Tea")
        compose.onNodeWithText(context.getString(R.string.transactions_today)).performClick()

        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.Review(review.copy(saveFailed = true))) }

        compose.onNodeWithText(context.getString(R.string.voice_edit_title)).assertIsDisplayed()
        compose.onNodeWithText("Tea").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.transactions_today)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.voice_done)).performClick()
        assertEquals(listOf(entry.copy(description = "Tea", date = today)), edits)
    }

}
