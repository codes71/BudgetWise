package app.budgetwise.ui.voice

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onLast
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
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
class VoiceMotionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun outgoingAndIncomingProblemsKeepTheirOwnHeardWordsDuringTheFade() {
        val state = mutableStateOf(
            VoiceUiState(LocalDate.parse("2026-11-02"), VoicePhase.Problem(VoiceProblem.NoAmount("coffee"))),
        )
        var retries = 0
        compose.setContent {
            BudgetWiseTheme { VoiceScreen(state.value, VoiceActions(onSayAgain = { retries++ })) }
        }
        compose.onNodeWithText(context.getString(R.string.voice_no_amount_body, "coffee")).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(phase = VoicePhase.Problem(VoiceProblem.NoAmount("tea"))) }
        // Commit the recomposition and Android measure/layout before measuring the overlap. Advancing the Compose
        // clock alone does not guarantee that a newly inserted Android semantics subtree has been laid out.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()

        // Assert both payloads while they overlap, rather than only inspecting the settled destination.
        compose.onNodeWithText(context.getString(R.string.voice_no_amount_body, "coffee"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.voice_no_amount_body, "tea"), useUnmergedTree = true).assertExists()
        // AnimatedContent appends the incoming tree after its outgoing tree, which remains in the unmerged tree.
        compose.onAllNodesWithText(context.getString(R.string.voice_say_again), useUnmergedTree = true).onLast().performClick()
        assertEquals(1, retries)

        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(context.getString(R.string.voice_no_amount_body, "tea")).assertIsDisplayed()
    }
}
