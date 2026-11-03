package app.budgetwise.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.budgetwise.ui.theme.BudgetWiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class MotionTest {
    private val durationScale = object : MotionDurationScale { override var scaleFactor = 1f }
    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    @Test
    fun disabledAnimationsShowFinalContentAndControlsImmediately() {
        durationScale.scaleFactor = 0f
        val stage = mutableStateOf("Before")
        val visible = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            BudgetWiseTheme {
                Column {
                    MotionContent(stage.value, label = "disabledContent") { shown -> Text(shown) }
                    MotionReveal(visible.value) {
                        Button(onClick = { clicks++ }) { Text("Continue") }
                    }
                }
            }
        }
        compose.runOnIdle { stage.value = "After"; visible.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("Before").assertDoesNotExist()
        compose.onNodeWithText("After").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun interruptedContentChangeEndsOnLatestTargetWithWorkingControls() {
        val stage = mutableStateOf("First")
        val clicks = mutableListOf<String>()
        compose.setContent {
            BudgetWiseTheme {
                MotionContent(stage.value) { shown ->
                    Button(onClick = { clicks += shown }, modifier = Modifier.testTag(shown)) { Text(shown) }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stage.value = "Second" }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { stage.value = "Third" }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("Third").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("First").assertDoesNotExist()
        compose.onNodeWithTag("Second").assertDoesNotExist()
        compose.onNodeWithTag("Third").assertIsDisplayed()
        assertEquals(listOf("Third"), clicks)
    }

    @Test
    fun backgroundSyncMotionStopsWhileItsWorkingLabelRemains() {
        compose.setContent {
            BudgetWiseTheme {
                Column {
                    QuietSyncIndicator(Color.Black)
                    Text("Syncing")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("sync-motion").assertExists()
        compose.mainClock.advanceTimeBy(BudgetMotion.BACKGROUND_ROTATION_MS + 100)
        compose.waitForIdle()
        compose.onNodeWithTag("sync-motion").assertDoesNotExist()
        compose.onNodeWithText("Syncing").assertIsDisplayed()
    }

}
