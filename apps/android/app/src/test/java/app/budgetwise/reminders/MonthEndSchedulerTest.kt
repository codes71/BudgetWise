package app.budgetwise.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.testing.FakeMonthPlanSource
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSyncControls
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.FakeTransactionStore
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The daily run as WorkManager holds it: one waiting run, which schedules the next at 9:00 as it ends. */
@RunWith(RobolectricTestRunner::class)
class MonthEndSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wm get() = WorkManager.getInstance(context)
    private val zone = ZoneId.of("Asia/Yangon")

    /** 9:00:30 on the 1st, just after the run was due. */
    private val clock = Clock.fixed(LocalDate.parse("2026-11-01").atTime(9, 0, 30).atZone(zone).toInstant(), zone)
    private val scheduler = MonthEndScheduler(context, clock)

    private class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork() = Result.success()
    }

    @Before
    fun setUp() {
        // A signed-out phone: each run does nothing but schedule the next.
        val check = MonthEndCheck(
            SessionManager(FakeTokenStore(null as StoredSession?), Clock.systemUTC()),
            FakeMonthPlanSource(),
            FakeTransactionStore(),
            FakeSyncControls(),
            FakeNetworkStatus(),
            FakeSettingsStore(),
            FakeReminderStore(),
            FakeNotifier(),
            clock,
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                            if (workerClassName == MonthEndWorker::class.java.name) {
                                MonthEndWorker(appContext, workerParameters, check, scheduler)
                            } else {
                                NoopWorker(appContext, workerParameters)
                            }
                    },
                )
                .build(),
        )
    }

    private fun daily(): List<WorkInfo> = wm.getWorkInfosForUniqueWork(MonthEndScheduler.DAILY_WORK).get()

    /** A CoroutineWorker finishes on its own thread; waits for [id] to reach [state]. */
    private fun awaitState(id: UUID, state: WorkInfo.State) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (wm.getWorkInfoById(id).get()?.state != state) {
            check(System.nanoTime() < until) { "work $id never reached $state" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aLaunchDropsTheOldPeriodicRequestAndKeepsTheWaitingRun() {
        // What earlier builds left on test phones.
        val legacy = PeriodicWorkRequestBuilder<NoopWorker>(1, TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(MonthEndScheduler.LEGACY_PERIODIC_WORK, androidx.work.ExistingPeriodicWorkPolicy.KEEP, legacy).result.get()

        scheduler.daily()
        val first = daily().single()
        scheduler.daily()

        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(legacy.id).get()!!.state)
        assertEquals(first.id, daily().single().id)
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        // A one-time request aimed at tomorrow's 9:00.
        assertEquals(null, first.periodicityInfo)
        assertEquals(Duration.ofHours(24).minusSeconds(30).toMillis(), first.initialDelayMillis)
    }

    @Test
    fun eachDailyRunSchedulesTheNextAtNineAgain() {
        scheduler.daily()
        val first = daily().single()

        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(first.id)
        awaitState(first.id, WorkInfo.State.SUCCEEDED)

        val next = daily().single { it.id != first.id }
        assertNotEquals(first.id, next.id)
        awaitState(next.id, WorkInfo.State.ENQUEUED)
        assertEquals(Duration.ofHours(24).minusSeconds(30).toMillis(), next.initialDelayMillis)
        // Only the next one waits.
        assertEquals(1, daily().count { !it.state.isFinished })
    }

    @Test
    fun tonightsRunSchedulesNoDailyOne() {
        scheduler.tonight("u1", YearMonth.of(2026, 10))
        val tonight = wm.getWorkInfosForUniqueWork(MonthEndScheduler.TONIGHT_WORK).get().single()
        assertTrue(tonight.initialDelayMillis > 0)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(tonight.id)
        awaitState(tonight.id, WorkInfo.State.SUCCEEDED)

        assertTrue(daily().isEmpty())
    }
}
