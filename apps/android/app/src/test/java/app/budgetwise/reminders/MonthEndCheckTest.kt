package app.budgetwise.reminders

import app.budgetwise.MonthPlanSource
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.TestUser
import app.budgetwise.testing.testTransaction
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One run of the worker, with the app's fakes: what it refreshes, posts and remembers. */
class MonthEndCheckTest {
    private val zone = ZoneId.of("Asia/Yangon")
    private val clock = Clock.fixed(LocalDate.parse("2026-11-01").atTime(9, 0).atZone(zone).toInstant(), zone)
    private val october = YearMonth.of(2026, 10)
    private val plan = MonthPlan(october, 2_000_000, 0, listOf(Budget("Housing", 500_000)))
    private val plans = FakeMonthPlanSource(listOf(plan))
    private val ledger = FakeTransactionStore(
        listOf(
            testTransaction("rent", 500_000, LocalDate.parse("2026-10-01"), category = "Housing"),
            testTransaction("food", 1_156_500, LocalDate.parse("2026-10-20"), category = "Food and Drink Item"),
        ),
    )
    private val sync = FakeSyncControls()
    private val network = FakeNetworkStatus(online = true)
    private val settings = FakeSettingsStore()
    private val store = FakeReminderStore()
    private val notifier = FakeNotifier()

    private suspend fun check(
        session: StoredSession? = StoredSession("tok", Instant.now().plusSeconds(86_400 * 20L), TestUser),
        source: MonthPlanSource = plans,
        clock: Clock = this.clock,
    ): MonthEndCheck {
        val manager = SessionManager(FakeTokenStore(session), Clock.systemUTC()).apply { load() }
        return MonthEndCheck(manager, source, ledger, sync, network, settings, store, notifier, clock)
    }

    @Test
    fun theLedgerIsPulledBeforeTheAmountIsWorkedOut() = runTest {
        // An expense entered on the web in October arrives with the pull.
        sync.onSyncAndWait = {
            ledger.rows.value = ledger.rows.value + testTransaction("web", 43_500, LocalDate.parse("2026-10-30"))
            SyncOutcome.Success(pushed = 0, pulled = 1)
        }

        check().run(ReminderRun.Daily)

        assertEquals(1, sync.syncAndWaitCalls)
        assertEquals(300_000L, notifier.posted.single().first.leftKyat)
    }

    @Test
    fun aFailedPullStillPostsFromTheCache() = runTest {
        sync.onSyncAndWait = { SyncOutcome.RetryLater }
        check().run(ReminderRun.Daily)
        assertEquals(343_500L, notifier.posted.single().first.leftKyat)

        // A pull that throws: the same.
        store.clear()
        sync.onSyncAndWait = { throw IOException("unreachable") }
        check().run(ReminderRun.Daily)
        assertEquals(listOf(343_500L, 343_500L), notifier.posted.map { it.first.leftKyat })
    }

    @Test
    fun noPullOfflineOrWhenThereIsNothingToPost() = runTest {
        network.online.value = false
        check().run(ReminderRun.Daily)
        assertEquals(0, sync.syncAndWaitCalls)
        assertEquals(1, notifier.posted.size)

        // Online, but October has no plan, or is checked already.
        network.online.value = true
        store.clear()
        plans.plans.value = emptyList()
        check().run(ReminderRun.Daily)
        plans.plans.value = listOf(plan.copy(endCheck = EndCheck(343_500, Instant.EPOCH)))
        check().run(ReminderRun.Daily)
        assertEquals(0, sync.syncAndWaitCalls)
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun aLateTonightRunIsDroppedAndCountsForNothing() = runTest {
        check().run(ReminderRun.Daily)
        val late = Clock.fixed(LocalDate.parse("2026-11-01").atTime(22, 30).atZone(zone).toInstant(), zone)
        val afterMidnight = Clock.fixed(LocalDate.parse("2026-11-02").atTime(1, 0).atZone(zone).toInstant(), zone)

        for (clock in listOf(late, afterMidnight)) check(clock = clock).run(ReminderRun.Tonight(TestUser.id, october))

        // Only the morning's post; the late runs asked nothing of the server and were not remembered.
        assertEquals(1, notifier.posted.size)
        assertEquals(1, plans.refreshes.size)
        assertEquals(1, sync.syncAndWaitCalls)
        assertEquals(Posted(daily = true), store.posted(TestUser.id, october))
    }

    @Test
    fun aDailyRunAtNightDoesNothingAtAll() = runTest {
        val night = Clock.fixed(LocalDate.parse("2026-11-01").atTime(23, 0).atZone(zone).toInstant(), zone)

        check(clock = night).run(ReminderRun.Daily)

        assertTrue(notifier.posted.isEmpty())
        assertTrue(plans.refreshes.isEmpty())
        assertEquals(0, sync.syncAndWaitCalls)
        assertEquals(Posted(), store.posted(TestUser.id, october))
    }

    @Test
    fun theDailyRunRefreshesLastMonthPostsItsLeftoverAndRemembersIt() = runTest {
        val check = check()

        check.run(ReminderRun.Daily)

        assertEquals(listOf(october to october), plans.refreshes)
        assertEquals(listOf(MonthEndReminder(TestUser.id, october, 343_500, offerTonight = true) to false), notifier.posted)
        assertEquals(Posted(daily = true), store.posted(TestUser.id, october))

        // The next days: posted already, so not even the server is asked.
        check.run(ReminderRun.Daily)
        assertEquals(1, notifier.posted.size)
        assertEquals(1, plans.refreshes.size)
    }

    @Test
    fun offlineTheCachedPlanAnswers() = runTest {
        network.online.value = false

        check().run(ReminderRun.Daily)

        assertTrue(plans.refreshes.isEmpty())
        assertEquals(343_500L, notifier.posted.single().first.leftKyat)
    }

    @Test
    fun aFailedRefreshFallsBackToTheCache() = runTest {
        plans.refreshResult = ApiResult.Failure("network", "unreachable", retryable = true)
        check().run(ReminderRun.Daily)
        assertEquals(1, notifier.posted.size)

        // Writing the cache failed (Room): the same.
        val throwing = object : MonthPlanSource by plans {
            override suspend fun refresh(from: YearMonth, to: YearMonth): ApiResult<Unit> = throw IOException("disk")
        }
        store.clear()
        check(source = throwing).run(ReminderRun.Daily)
        assertEquals(2, notifier.posted.size)
    }

    @Test
    fun aCheckSavedElsewhereArrivesWithTheRefreshAndNothingIsPosted() = runTest {
        // Checked on the website: the refresh brings the end check into the cache.
        val refreshing = object : MonthPlanSource by plans {
            override suspend fun refresh(from: YearMonth, to: YearMonth): ApiResult<Unit> {
                plans.plans.value = listOf(plan.copy(endCheck = EndCheck(343_500, Instant.EPOCH)))
                return ApiResult.Ok(Unit)
            }
        }

        check(source = refreshing).run(ReminderRun.Daily)

        assertTrue(notifier.posted.isEmpty())
        assertEquals(Posted(), store.posted(TestUser.id, october))
    }

    @Test
    fun withoutPermissionNothingIsPostedOrRemembered() = runTest {
        notifier.canPost = false
        val check = check()

        check.run(ReminderRun.Daily)

        assertTrue(notifier.posted.isEmpty())
        assertTrue(plans.refreshes.isEmpty())
        assertEquals(Posted(), store.posted(TestUser.id, october))

        // Allowed later: the next run posts.
        notifier.canPost = true
        check.run(ReminderRun.Daily)
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun signedOutNothing() = runTest {
        check(session = null).run(ReminderRun.Daily)

        assertTrue(notifier.posted.isEmpty())
        assertTrue(plans.refreshes.isEmpty())
    }

    @Test
    fun tonightPostsOnceMoreAndRemembersThatToo() = runTest {
        val check = check()
        check.run(ReminderRun.Daily)

        check.run(ReminderRun.Tonight(TestUser.id, october))
        check.run(ReminderRun.Tonight(TestUser.id, october))

        assertEquals(listOf(true, false), notifier.posted.map { it.first.offerTonight })
        assertEquals(Posted(daily = true, tonight = true), store.posted(TestUser.id, october))
    }

    @Test
    fun amountsFollowTheDigitSetting() = runTest {
        settings.myanmarDigits.value = true

        check().run(ReminderRun.Daily)

        assertEquals(true, notifier.posted.single().second)
    }
}

class FakeReminderStore : ReminderStore {
    private val posts = mutableMapOf<Pair<String, YearMonth>, Posted>()
    override val notificationsAsked = MutableStateFlow(false)

    override suspend fun posted(account: String, month: YearMonth): Posted = posts[account to month] ?: Posted()

    override suspend fun markPosted(account: String, month: YearMonth, run: ReminderRun) {
        val now = posted(account, month)
        posts[account to month] = when (run) {
            ReminderRun.Daily -> now.copy(daily = true)
            is ReminderRun.Tonight -> now.copy(tonight = true)
        }
    }

    override suspend fun setNotificationsAsked() {
        notificationsAsked.value = true
    }

    fun clear() = posts.clear()
}

/** Each post with its Myanmar digits flag, and each cancel. */
class FakeNotifier : MonthEndNotifier {
    override var canPost = true
    val posted = mutableListOf<Pair<MonthEndReminder, Boolean>>()
    val cancelled = mutableListOf<Pair<String, YearMonth>>()

    override fun post(reminder: MonthEndReminder, myanmarDigits: Boolean) {
        posted += reminder to myanmarDigits
    }

    override fun cancel(account: String, month: YearMonth) {
        cancelled += account to month
    }
}
