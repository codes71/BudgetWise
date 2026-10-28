package app.budgetwise.ui.budgets

import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetOrderTest {
    @Test
    fun overBudgetComesFirstThenTheMostSpent() {
        // The kit's sample, in the server's order.
        val lines = listOf(
            BudgetLine("Food and Drink Item", 60_000, 48_500),
            BudgetLine("Groceries", 80_000, 62_200),
            BudgetLine("Transport", 20_000, 21_251),
            BudgetLine("Utilities", 50_000, 24_500),
            BudgetLine("Entertainment", 15_000, 0),
        )

        assertEquals(
            listOf("Transport", "Groceries", "Food and Drink Item", "Utilities", "Entertainment"),
            orderedForScreen(lines).map { it.category },
        )
    }
}
