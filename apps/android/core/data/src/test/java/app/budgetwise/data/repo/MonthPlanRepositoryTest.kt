package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.db.toEntity
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.BudgetDto
import app.budgetwise.data.net.EndCheckDto
import app.budgetwise.data.net.MonthPlanDto
import app.budgetwise.data.sync.SyncHarness
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonthPlanRepositoryTest {
    private lateinit var h: RepoHarness
    private lateinit var plans: MonthPlanRepository
    private val zoneBefore: TimeZone = TimeZone.getDefault()

    // The harness clock is 2026-10-21T06:00Z: October in every time zone.
    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)

    @Before
    fun setUp() {
        h = RepoHarness()
        plans = repo(h.sync.clock)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(zoneBefore)
        h.close()
    }

    private fun repo(clock: Clock) = MonthPlanRepository(h.sync.db, h.api, h.sync.session, clock, h.writes)

    private fun legacy(owner: String = SyncHarness.USER.id) = runBlocking {
        h.sync.db.budgetDao().observeAll(owner).first().map { it.toDomain() }
    }

    private fun seedLegacy(vararg budgets: Budget) = runBlocking {
        h.sync.db.budgetDao().insertAll(
            budgets.map { BudgetEntity(ownerId = SyncHarness.USER.id, category = it.category, limitKyat = it.limitKyat) },
        )
    }

    private fun planJson(
        month: String,
        newMoney: String = "2000000",
        carriedOver: String = "0",
        shares: String = "[]",
        endCheck: String = "null",
    ) = """{"month":"$month","newMoney":$newMoney,"carriedOver":$carriedOver,"shares":$shares,"endCheck":$endCheck,""" +
        """"updatedAt":"2026-10-21T06:00:00.000Z"}"""

    @Test
    fun totalsSplitAndUnsplit() {
        val plan = MonthPlan(nov, 2_000_000, 280_000, listOf(Budget("Housing", 500_000), Budget("Groceries", 1_100_000)))
        assertEquals(2_280_000, plan.totalKyat)
        assertEquals(1_600_000, plan.splitKyat)
        assertEquals(680_000, plan.unsplitKyat)
        assertEquals(-100_000, plan.copy(newMoneyKyat = 1_320_000, carriedOverKyat = 180_000).unsplitKyat)
    }

    @Test
    fun stalePlanAndEndCheckNeverUseTheNextAccountsToken() = runBlocking {
        val owner = h.sync.session.writableUserId!!
        h.sync.signInSecond()
        assertTrue(plans.save(MonthPlan(nov, 100, 0, emptyList()), owner) is ApiResult.Unauthorized)
        assertTrue(plans.saveEndCheck(oct, 100, owner) is ApiResult.Unauthorized)
        assertEquals(0, h.apiServer.requestCount)
    }

    @Test
    fun refreshCachesRoundedPlansAndReplacesOnlyThatRange() = runBlocking {
        val owner = SyncHarness.USER.id
        val july = YearMonth.of(2026, 7)
        val dec = MonthPlan(YearMonth.of(2026, 12), 5, 0, emptyList())
        h.sync.db.monthPlanDao().upsert(MonthPlan(july, 1, 0, emptyList()).toEntity(owner))
        h.sync.db.monthPlanDao().upsert(dec.toEntity(owner))
        val septShares = """[{"category":"Housing","limit":500000},{"category":"Bad","limit":-1},""" +
            """{"category":"Tiny","limit":0.4},{"category":"Housing","limit":9}]"""
        val septCheck = """{"onHand":280000,"checkedAt":"2026-10-01T02:30:00.000Z"}"""
        val octShares = """[{"category":"Transport","limit":20000},{"category":"Groceries","limit":300000}]"""
        val months = listOf(
            planJson("2026-08", newMoney = "0"),
            planJson("2026-09", newMoney = "1500000.5", shares = septShares, endCheck = septCheck),
            planJson("2026-10", carriedOver = "280000", shares = octShares),
            planJson("2027-01"),
        )
        h.json(200, """{"months":[${months.joinToString(",")}]}""")

        assertTrue(plans.refresh(july, oct) is ApiResult.Ok)

        val request = h.apiServer.takeRequest()
        assertEquals("/api/v1/budget-months?from=2026-07&to=2026-10", request.path)
        assertEquals("Bearer ${h.sync.server.token}", request.getHeader("Authorization"))
        assertEquals(
            listOf(
                MonthPlan(
                    YearMonth.of(2026, 9), 1_500_001, 0, listOf(Budget("Housing", 500_000)),
                    EndCheck(280_000, Instant.parse("2026-10-01T02:30:00Z")),
                ),
                // Share order is the plan's, not alphabetical.
                MonthPlan(oct, 2_000_000, 280_000, listOf(Budget("Transport", 20_000), Budget("Groceries", 300_000))),
            ),
            plans.observeRange(july, oct).first(),
        )
        assertEquals("outside the range is kept", dec, plans.observe(dec.month).first())
        assertNull("an answer outside the range is not cached", plans.observe(YearMonth.of(2027, 1)).first())
    }

    /**
     * The server answers every GET only once [release] opens (after counting down [arrived]), and every PUT at once with
     * [put], so a test can save while a refresh is on its way.
     */
    private fun holdGets(get: String, put: String, arrived: CountDownLatch, release: CountDownLatch) {
        fun reply(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
        h.apiServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "GET") {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                reply(get)
            } else {
                reply(put)
            }
        }
    }

    @Test
    fun aRefreshThatASaveOvertookDropsItsOlderAnswer() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        holdGets(
            get = """{"months":[${planJson("2026-10", newMoney = "1000000")}]}""",
            put = """{"month":${planJson("2026-10", newMoney = "2500000")}}""",
            arrived = arrived,
            release = release,
        )

        val refresh = async(Dispatchers.IO) { plans.refresh(oct, oct) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertTrue(plans.save(MonthPlan(oct, 2_500_000, 0, emptyList())) is ApiResult.Ok)
        release.countDown()

        assertEquals(ApiResult.Ok(Unit), refresh.await())
        assertEquals("the save is newer than the refresh's answer", 2_500_000L, plans.observe(oct).first()?.newMoneyKyat)

        // A refresh that starts after the save writes its answer as usual.
        h.apiServer.dispatcher = QueueDispatcher()
        h.json(200, """{"months":[${planJson("2026-10", newMoney = "1000000")}]}""")
        plans.refresh(oct, oct)
        assertEquals(1_000_000L, plans.observe(oct).first()?.newMoneyKyat)
    }

    @Test
    fun aRefreshThatAnEndCheckOvertookDropsItsOlderAnswer() = runBlocking {
        h.sync.db.monthPlanDao().upsert(MonthPlan(oct, 2_000_000, 0, emptyList()).toEntity(SyncHarness.USER.id))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val check = """{"onHand":280000,"checkedAt":"2026-11-01T00:00:00.000Z"}"""
        holdGets(
            get = """{"months":[${planJson("2026-10")}]}""",
            put = """{"month":${planJson("2026-10", endCheck = check)}}""",
            arrived = arrived,
            release = release,
        )

        val refresh = async(Dispatchers.IO) { plans.refresh(oct, oct) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertTrue(plans.saveEndCheck(oct, 280_000) is ApiResult.Ok)
        release.countDown()
        refresh.await()

        assertEquals(280_000L, plans.observe(oct).first()?.endCheck?.onHandKyat)
    }

    @Test
    fun aLegacyRefreshThatThisMonthsSaveOvertookDropsItsOlderAnswer() = runBlocking {
        val budgets = BudgetRepository(h.sync.db.budgetDao(), h.api, h.sync.session, h.writes)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        holdGets(
            get = """{"budgets":[{"category":"Groceries","limit":1}]}""",
            put = """{"month":${planJson("2026-10", shares = """[{"category":"Housing","limit":500000}]""")}}""",
            arrived = arrived,
            release = release,
        )

        val refresh = async(Dispatchers.IO) { budgets.refresh() }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertTrue(plans.save(MonthPlan(oct, 2_000_000, 0, listOf(Budget("Housing", 500_000)))) is ApiResult.Ok)
        release.countDown()

        assertEquals(ApiResult.Ok(Unit), refresh.await())
        assertEquals(listOf(Budget("Housing", 500_000)), legacy())
    }

    @Test
    fun aSaveOfAnotherMonthDoesNotStopALegacyRefresh() = runBlocking {
        val budgets = BudgetRepository(h.sync.db.budgetDao(), h.api, h.sync.session, h.writes)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        holdGets(
            get = """{"budgets":[{"category":"Groceries","limit":1}]}""",
            put = """{"month":${planJson("2026-11")}}""",
            arrived = arrived,
            release = release,
        )

        val refresh = async(Dispatchers.IO) { budgets.refresh() }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        assertTrue(plans.save(MonthPlan(nov, 2_000_000, 0, emptyList())) is ApiResult.Ok)
        release.countDown()
        refresh.await()

        // November's save never touches the legacy budgets, so the refresh's answer stands.
        assertEquals(listOf(Budget("Groceries", 1)), legacy())
    }

    @Test
    fun refreshFailureKeepsTheCache() = runBlocking {
        h.json(200, """{"months":[${planJson("2026-10")}]}""")
        plans.refresh(oct, oct)
        h.json(500, """{"error":{"code":"internal_error","message":"x"}}""")
        assertTrue(plans.refresh(oct, oct) is ApiResult.Failure)
        assertEquals(2_000_000L, plans.observe(oct).first()?.newMoneyKyat)
    }

    @Test
    fun savingThisMonthSendsThePlanCachesTheAnswerAndReplacesTheLegacyBudgets() = runBlocking {
        seedLegacy(Budget("Groceries", 1), Budget("Health", 2))
        val shares = """[{"category":"Housing","limit":500000},{"category":"Groceries","limit":150000}]"""
        val check = """{"onHand":100,"checkedAt":"2026-11-01T00:00:00.000Z"}"""
        h.json(200, """{"month":${planJson("2026-10", carriedOver = "280000", shares = shares, endCheck = check)}}""")
        val plan = MonthPlan(oct, 2_000_000, 280_000, listOf(Budget("Housing", 500_000), Budget("Groceries", 150_000)))

        val result = plans.save(plan)

        val expected = plan.copy(endCheck = EndCheck(100, Instant.parse("2026-11-01T00:00:00Z")))
        assertEquals(ApiResult.Ok(expected), result)
        val request = h.apiServer.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/budget-months/2026-10", request.path)
        assertEquals("Bearer ${h.sync.server.token}", request.getHeader("Authorization"))
        assertEquals(
            """{"newMoney":2000000,"carriedOver":280000,"shares":[{"category":"Housing","limit":500000},{"category":"Groceries","limit":150000}]}""",
            request.body.readUtf8(),
        )
        assertEquals("the server's answer is cached", expected, plans.observe(oct).first())
        assertEquals(listOf(Budget("Groceries", 150_000), Budget("Housing", 500_000)), legacy())
        assertEquals("never queued", 0, h.sync.pendingOps().size)
    }

    @Test
    fun savingAnotherMonthLeavesTheLegacyBudgets() = runBlocking {
        seedLegacy(Budget("Groceries", 1))
        h.json(200, """{"month":${planJson("2026-11", shares = """[{"category":"Housing","limit":500000}]""")}}""")

        assertTrue(plans.save(MonthPlan(nov, 2_000_000, 0, listOf(Budget("Housing", 500_000)))) is ApiResult.Ok)

        assertEquals(listOf(Budget("Housing", 500_000)), plans.observe(nov).first()?.shares)
        assertEquals(listOf(Budget("Groceries", 1)), legacy())
    }

    @Test
    fun thisMonthIsTheMonthInThePhonesTimeZoneAtTheTimeOfTheSave() = runBlocking {
        // 20:00 UTC on 31 October is already 1 November in Yangon (+06:30).
        val yangon = repo(Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"), ZoneOffset.UTC))
        seedLegacy(Budget("Groceries", 1))

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yangon"))
        h.json(200, """{"month":${planJson("2026-11", shares = """[{"category":"Housing","limit":500000}]""")}}""")
        yangon.save(MonthPlan(nov, 2_000_000, 0, listOf(Budget("Housing", 500_000))))
        assertEquals(listOf(Budget("Housing", 500_000)), legacy())

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        h.json(200, """{"month":${planJson("2026-11", shares = """[{"category":"Transport","limit":7000}]""")}}""")
        yangon.save(MonthPlan(nov, 2_000_000, 0, listOf(Budget("Transport", 7000))))
        assertEquals("still October in UTC", listOf(Budget("Housing", 500_000)), legacy())
    }

    @Test
    fun aRefusedSaveWritesNothing() = runBlocking {
        seedLegacy(Budget("Groceries", 1))
        h.json(422, """{"error":{"code":"split_over_total","message":"Split is over the total"}}""")

        val result = plans.save(MonthPlan(oct, 1000, 0, listOf(Budget("Housing", 5000))))

        assertEquals("split_over_total", (result as ApiResult.Failure).code)
        assertNull(plans.observe(oct).first())
        assertEquals(listOf(Budget("Groceries", 1)), legacy())
    }

    @Test
    fun offlineTheSaveFailsAndNothingIsQueued() = runBlocking {
        h.apiServer.shutdown()
        val result = plans.save(MonthPlan(oct, 1000, 0, emptyList()))
        assertTrue((result as ApiResult.Failure).retryable)
        assertNull(plans.observe(oct).first())
        assertEquals(0, h.sync.pendingOps().size)
    }

    @Test
    fun anAnswerForAnotherMonthOrWithABadTotalIsABadResponse() = runBlocking {
        h.json(200, """{"month":${planJson("2026-09")}}""")
        assertEquals("bad_response", (plans.save(MonthPlan(oct, 1000, 0, emptyList())) as ApiResult.Failure).code)
        h.json(200, """{"month":${planJson("2026-10", newMoney = "-5")}}""")
        assertEquals("bad_response", (plans.save(MonthPlan(oct, 1000, 0, emptyList())) as ApiResult.Failure).code)
        assertNull(plans.observe(YearMonth.of(2026, 9)).first())
        assertNull(plans.observe(oct).first())
    }

    @Test
    fun theEndCheckIsSentAndTheAnswerCached() = runBlocking {
        val sept = YearMonth.of(2026, 9)
        val check = """{"onHand":280000,"checkedAt":"2026-10-21T06:00:00.000Z"}"""
        h.json(200, """{"month":${planJson("2026-09", endCheck = check)}}""")

        val result = plans.saveEndCheck(sept, 280_000)

        val request = h.apiServer.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/budget-months/2026-09/end-check", request.path)
        assertEquals("""{"onHand":280000}""", request.body.readUtf8())
        val expected = EndCheck(280_000, Instant.parse("2026-10-21T06:00:00Z"))
        assertEquals(expected, (result as ApiResult.Ok).v.endCheck)
        assertEquals(expected, plans.observe(sept).first()?.endCheck)
    }

    @Test
    fun anEndCheckForAMonthWithoutAPlanFails() = runBlocking {
        h.json(404, """{"error":{"code":"not_found","message":"No plan"}}""")
        assertEquals("not_found", (plans.saveEndCheck(oct, 1) as ApiResult.Failure).code)
        h.json(422, """{"error":{"code":"month_not_over","message":"Not over"}}""")
        assertEquals("month_not_over", (plans.saveEndCheck(oct, 1) as ApiResult.Failure).code)
    }

    @Test
    fun a401ExpiresTheSessionThatSentIt() = runBlocking {
        h.json(401, """{"error":{"code":"unauthorized","message":"Token expired"}}""")
        assertTrue(plans.save(MonthPlan(oct, 1000, 0, emptyList())) is ApiResult.Unauthorized)
        assertEquals(SessionState.Expired, h.sync.session.state.value)
    }

    @Test
    fun withoutAValidTokenNothingIsSent() = runBlocking {
        h.sync.session.onUnauthorized()
        assertTrue(plans.refresh(oct, oct) is ApiResult.Unauthorized)
        assertTrue(plans.save(MonthPlan(oct, 1000, 0, emptyList())) is ApiResult.Unauthorized)
        assertTrue(plans.saveEndCheck(oct, 1) is ApiResult.Unauthorized)
        assertEquals(0, h.apiServer.requestCount)
    }

    @Test
    fun eachAccountHasItsOwnPlansFetchedWithItsOwnToken() = runBlocking {
        h.json(200, """{"months":[${planJson("2026-10", newMoney = "1000")}]}""")
        plans.refresh(oct, oct)
        h.apiServer.takeRequest()

        h.sync.signInSecond()
        assertNull(plans.observe(oct).first())
        h.json(200, """{"months":[${planJson("2026-10", newMoney = "2000")}]}""")
        plans.refresh(oct, oct)
        assertEquals("Bearer ${h.sync.server2.token}", h.apiServer.takeRequest().getHeader("Authorization"))
        assertEquals(2000L, plans.observe(oct).first()?.newMoneyKyat)

        h.sync.session.signIn(h.sync.server.token, h.sync.now.plusSeconds(3600), SyncHarness.USER)
        assertEquals(1000L, plans.observe(oct).first()?.newMoneyKyat)
    }

    @Test
    fun planOfRoundsAndDropsWhatThePhoneCannotUse() {
        fun dto(
            month: String = "2026-11",
            newMoney: Double = 1000.0,
            carriedOver: Double = 0.0,
            shares: List<BudgetDto> = emptyList(),
            endCheck: EndCheckDto? = null,
        ) = MonthPlanDto(month, newMoney, carriedOver, shares, endCheck)

        assertNull(MonthPlanRepository.planOf(dto(month = "2026-13")))
        assertNull(MonthPlanRepository.planOf(dto(month = "November")))
        assertNull(MonthPlanRepository.planOf(dto(newMoney = -1.0)))
        assertNull(MonthPlanRepository.planOf(dto(carriedOver = Double.NaN)))
        assertNull(MonthPlanRepository.planOf(dto(newMoney = 0.4)))
        assertEquals(1L, MonthPlanRepository.planOf(dto(newMoney = 0.0, carriedOver = 0.5))?.totalKyat)
        assertEquals(
            listOf(Budget("Housing", 2), Budget("Food", 1)),
            MonthPlanRepository.planOf(
                dto(shares = listOf(BudgetDto("Housing", 1.5), BudgetDto("Food", 0.5), BudgetDto("Zero", 0.0), BudgetDto("Housing", 7.0))),
            )?.shares,
        )
        assertNull(MonthPlanRepository.planOf(dto(endCheck = EndCheckDto(10.0, "yesterday")))!!.endCheck)
        assertNull(MonthPlanRepository.planOf(dto(endCheck = EndCheckDto(-10.0, "2026-12-01T00:00:00Z")))!!.endCheck)
    }
}
