package app.budgetwise.ui.budgets

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.testing.testTransaction
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.transactions.DaySection
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class BudgetMotionTest {
    @get:Rule val compose = createComposeRule()
    private val november = YearMonth.of(2026, 11)

    @Test
    fun stepOverlapKeepsEachSnapshotsIdentityAndCanReverseBeforeSettling() {
        val state = mutableStateOf(PlanEditorUiState(november, loaded = true, newMoneyDigits = "2000000"))
        var nextCalls = 0
        var saveCalls = 0
        compose.setContent {
            BudgetWiseTheme {
                PlanEditorScreen(state.value, PlanEditorActions(onNext = { nextCalls++ }, onSave = { saveCalls++ }))
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(step = PlanStep.SPLIT, newMoneyDigits = "1750000") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()

        // Both trees must exist during overlap, and the outgoing tree retains its old amount.
        compose.onNodeWithText("November budget", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Next: split 20 lakh", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Split 17.5 lakh", useUnmergedTree = true).assertExists()
        // A direct semantic action must also be inert, independently of pointer/accessibility shielding.
        compose.onNode(hasAnyDescendant(hasText("Next: split 20 lakh")) and hasClickAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, nextCalls)

        compose.runOnIdle { state.value = state.value.copy(step = PlanStep.TOTAL) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNode(hasAnyDescendant(hasText("Save November budget")) and hasClickAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, saveCalls)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Split 17.5 lakh").assertDoesNotExist()
        compose.onNodeWithText("Next: split 17.5 lakh").assertIsEnabled()
    }

    @Test
    fun reversingTheOnHandRevealRetainsTheDraftAndRestoresNativeFocus() {
        val state = mutableStateOf(
            MonthEndUiState(
                november.minusMonths(1), loaded = true, plan = MonthPlan(november.minusMonths(1), 500_000, 0, emptyList()),
                ledger = LedgerPull.DONE, answer = OnHand.NO, onHandDigits = "280000",
            ),
        )
        compose.setContent {
            BudgetWiseTheme { MonthEndScreen(state.value, {}, {}, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("I have").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(answer = OnHand.YES) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.runOnIdle { state.value = state.value.copy(answer = OnHand.NO) }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true

        val field = compose.onNodeWithContentDescription("I have").assertIsEnabled().assertIsFocused()
        assertEquals("280000", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.replace(",", ""))
        compose.onNodeWithText("Next: plan November").assertIsEnabled()
    }

    @Test
    fun undoRestoresOneInteractiveRowWhileOtherRowsAreStillMoving() {
        val today = LocalDate.of(2026, 11, 3)
        val rice = testTransaction("Rice", 10_000, today, clientId = "rice")
        val fruit = testTransaction("Fruit", 5_000, today, clientId = "fruit")
        val state = mutableStateOf(
            BudgetTransactionsUiState(
                november, "Groceries", today, loading = false,
                line = BudgetLine("Groceries", 50_000, 15_000),
                days = listOf(DaySection(today, listOf(rice, fruit))),
            ),
        )
        val opened = mutableListOf<String>()
        compose.setContent {
            BudgetWiseTheme {
                BudgetTransactionsScreen(state.value, {}, { opened += it }, {}, remember { SnackbarHostState() })
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(days = listOf(DaySection(today, listOf(fruit)))) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Rice").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(days = listOf(DaySection(today, listOf(rice, fruit))), restored = mapOf("rice" to 1))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNode(hasText("Rice") and hasClickAction()).performClick()
        assertEquals(listOf("rice"), opened)
    }
}
