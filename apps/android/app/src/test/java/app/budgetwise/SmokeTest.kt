package app.budgetwise

import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
    @Test
    fun moduleGraphResolves() {
        assertTrue(Class.forName("app.budgetwise.BudgetWiseApp") != null)
    }
}
