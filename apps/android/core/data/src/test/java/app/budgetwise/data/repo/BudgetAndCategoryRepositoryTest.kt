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
        budgets = BudgetRepository(h.sync.db.budgetDao(), h.api, h.sync.session)
        categories = CategoryRepository(h.dataStore, h.api, h.sync.session)
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
    fun a401OnRefreshExpiresTheSessionThatSentIt() = runBlocking {
        h.json(401, """{"error":{"code":"unauthorized","message":"Token expired"}}""")
        assertTrue(budgets.refresh() is ApiResult.Unauthorized)
        assertEquals(app.budgetwise.data.auth.SessionState.Expired, h.sync.session.state.value)
    }

    @Test
    fun a401OnCategoryRefreshExpiresTheSession() = runBlocking {
        h.json(401, """{"error":{"code":"unauthorized","message":"Token expired"}}""")
        assertTrue(categories.refresh() is ApiResult.Unauthorized)
        assertEquals(app.budgetwise.data.auth.SessionState.Expired, h.sync.session.state.value)
    }

    @Test
    fun eachAccountHasItsOwnBudgetsAndCategoriesFetchedWithItsOwnToken() = runBlocking {
        h.json(200, """{"budgets":[{"category":"Groceries","limit":1000}]}""")
        h.json(200, """{"categories":["Rent"]}""")
        budgets.refresh()
        categories.refresh()
        assertEquals("Bearer ${h.sync.server.token}", h.apiServer.takeRequest().getHeader("Authorization"))
        h.apiServer.takeRequest()

        h.sync.signInSecond()
        assertTrue(budgets.observe().first().isEmpty())
        assertEquals(CategoryRepository.BUILT_IN, categories.categories.first())
        h.json(200, """{"budgets":[{"category":"Groceries","limit":2000}]}""")
        budgets.refresh()
        assertEquals("Bearer ${h.sync.server2.token}", h.apiServer.takeRequest().getHeader("Authorization"))
        assertEquals(listOf(Budget("Groceries", 2000)), budgets.observe().first())

        h.sync.session.signIn(h.sync.server.token, h.sync.now.plusSeconds(3600), app.budgetwise.data.sync.SyncHarness.USER)
        assertEquals(listOf(Budget("Groceries", 1000)), budgets.observe().first())
        assertEquals(listOf("Rent"), categories.categories.first())
    }

    @Test
    fun withoutAValidTokenNothingIsSent() = runBlocking {
        h.sync.session.onUnauthorized()
        assertTrue(budgets.refresh() is ApiResult.Unauthorized)
        assertTrue(categories.refresh() is ApiResult.Unauthorized)
        assertEquals(0, h.apiServer.requestCount)
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
