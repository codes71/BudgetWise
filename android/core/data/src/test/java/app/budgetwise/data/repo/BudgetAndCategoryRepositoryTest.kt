package app.budgetwise.data.repo

import app.budgetwise.data.model.Budget
import app.budgetwise.data.net.ApiResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BudgetAndCategoryRepositoryTest {
    private lateinit var h: RepoHarness
    private lateinit var budgets: BudgetRepository
    private lateinit var categories: CategoryRepository

    @Before
    fun setUp() {
        h = RepoHarness()
        budgets = BudgetRepository(h.sync.db.budgetDao(), h.api)
        categories = CategoryRepository(h.dataStore, h.api)
    }

    @After fun tearDown() { h.close() }

    @Test
    fun budgetRefreshCachesRoundedKyatAndReplacesOldRows() = runBlocking {
        h.json(200, """{"budgets":[{"category":"Groceries","limit":150000},{"category":"Transport","limit":2500.5},{"category":"Other","limit":0.4}]}""")
        assertTrue(budgets.refresh() is ApiResult.Ok)
        assertEquals(
            listOf(Budget("Groceries", 150000), Budget("Other", 0), Budget("Transport", 2501)),
            budgets.observe().first(),
        )
        h.json(200, """{"budgets":[{"category":"Housing","limit":9000}]}""")
        budgets.refresh()
        assertEquals(listOf(Budget("Housing", 9000)), budgets.observe().first())
    }

    @Test
    fun budgetRefreshFailureKeepsCache() = runBlocking {
        h.json(200, """{"budgets":[{"category":"Housing","limit":9000}]}""")
        budgets.refresh()
        h.json(500, """{"error":{"code":"internal_error","message":"x"}}""")
        assertTrue(budgets.refresh() is ApiResult.Failure)
        assertEquals(listOf(Budget("Housing", 9000)), budgets.observe().first())
    }

    @Test
    fun toKyatRoundsHalfUpAndRejectsBadValues() {
        assertEquals(1L, BudgetRepository.toKyat(0.5))
        assertEquals(2L, BudgetRepository.toKyat(1.5))
        assertEquals(1500L, BudgetRepository.toKyat(1499.5))
        assertNull(BudgetRepository.toKyat(-1.0))
        assertNull(BudgetRepository.toKyat(Double.NaN))
        assertNull(BudgetRepository.toKyat(1e300))
    }

    @Test
    fun categoriesFallBackToEightBuiltInsWhenOfflineAndCacheEmpty() = runBlocking {
        h.apiServer.shutdown()
        assertTrue(categories.refresh() is ApiResult.Failure)
        val list = categories.categories.first()
        assertEquals(8, list.size)
        assertEquals(
            listOf("Groceries", "Utilities", "Entertainment", "Transport", "Housing", "Health", "Food and Drink Item", "Other"),
            list,
        )
    }

    @Test
    fun categoriesCacheServerListAndSurviveOffline() = runBlocking {
        h.json(200, """{"categories":["Rent","Coffee","Other"]}""")
        assertTrue(categories.refresh() is ApiResult.Ok)
        assertEquals(listOf("Rent", "Coffee", "Other"), categories.categories.first())
        h.apiServer.shutdown()
        categories.refresh()
        assertEquals(listOf("Rent", "Coffee", "Other"), categories.categories.first())
    }
}
