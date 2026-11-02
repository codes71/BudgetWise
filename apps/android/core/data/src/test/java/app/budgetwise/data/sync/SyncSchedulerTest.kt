package app.budgetwise.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wm get() = WorkManager.getInstance(context)
    private lateinit var h: SyncHarness
    private lateinit var driver: TestDriver

    @Before
    fun setUp() {
        h = SyncHarness()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                            SyncWorker(appContext, workerParameters, h.engine)
                    },
                )
                .build(),
        )
        driver = WorkManagerTestInitHelper.getTestDriver(context)!!
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun oneTimeSyncNeedsNetworkAndBacksOffExponentiallyFrom30s() {
        val spec = SyncScheduler.oneTimeRequest(fullPull = true).workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        assertTrue(spec.input.getBoolean(SyncWorker.KEY_FULL_PULL, false))
        assertFalse(SyncScheduler.oneTimeRequest(fullPull = false).workSpec.input.getBoolean(SyncWorker.KEY_FULL_PULL, true))
    }

    @Test
    fun incrementalRequestsShareUniqueSyncWorkAndQueueBehindEachOther() {
        val scheduler = SyncScheduler(context)
        scheduler.enqueue(fullPull = false)
        scheduler.enqueue(fullPull = false)

        val infos = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get()
        assertEquals("sync", SyncScheduler.UNIQUE_WORK)
        assertEquals(2, infos.size) // appended, not dropped and not replaced
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), infos.map { it.state }.toSet())
        assertTrue(infos.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
    }

    @Test
    fun writesWhileASyncIsQueuedAddNoMoreWork() = runBlocking {
        val scheduler = SyncScheduler(context)
        repeat(10) { scheduler.requestAfterWrite() } // ten entries saved offline

        val infos = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get()
        assertEquals(1, infos.size) // one queued sync pushes them all
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun aWaitingPeriodicSyncDoesNotHoldBackAnAfterWriteSync() = runBlocking {
        val scheduler = SyncScheduler(context)
        scheduler.periodic() // ENQUEUED for hours: it must not count as a queued after-write sync

        scheduler.requestAfterWrite()

        assertEquals(1, wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().size)
    }

    @Test
    fun aWriteDuringARunningSyncQueuesOneMoreBehindIt() = runBlocking {
        val scheduler = SyncScheduler(context)
        val release = CountDownLatch(1)
        h.server.duringGet = { release.await(10, TimeUnit.SECONDS) } // the run holds in its pull, past its push
        scheduler.requestAfterWrite()
        val running = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().single()
        driver.setAllConstraintsMet(running.id)
        await(running.id) { it.state == WorkInfo.State.RUNNING }

        scheduler.requestAfterWrite()

        val infos = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get()
        release.countDown()
        assertEquals(2, infos.size)
        assertEquals(setOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), infos.map { it.state }.toSet())
        await(running.id) { it.state == WorkInfo.State.SUCCEEDED }
    }

    @Test
    fun aWriteDuringARunningSyncQueuesOneMore() {
        // A running sync may be past its push, so a write then needs a run of its own; a queued one does not.
        assertTrue(SyncScheduler.needsNewRun(emptyList()))
        assertTrue(SyncScheduler.needsNewRun(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)))
        assertTrue(SyncScheduler.needsNewRun(listOf(WorkInfo.State.RUNNING)))
        assertFalse(SyncScheduler.needsNewRun(listOf(WorkInfo.State.ENQUEUED)))
        assertFalse(SyncScheduler.needsNewRun(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)))
    }

    @Test
    fun fullPullReplacesAHeadThatIsWaitingOutBackoff() {
        val scheduler = SyncScheduler(context)
        h.server.getStatus = 503 // server unreachable through adb reverse: RetryLater

        scheduler.enqueue(fullPull = false)
        val head = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().single()
        driver.setAllConstraintsMet(head.id)
        await(head.id) { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 } // now in backoff
        scheduler.enqueue(fullPull = false) // an after-write sync would wait behind it
        assertTrue(wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().any { it.state == WorkInfo.State.BLOCKED })

        h.server.getStatus = null // server back
        scheduler.enqueue(fullPull = true) // pull-to-refresh

        val queued = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().filter { !it.state.isFinished }
        val full = queued.single() // the head and the blocked request are gone
        assertEquals(WorkInfo.State.ENQUEUED, full.state)
        driver.setAllConstraintsMet(full.id)
        await(full.id) { it.state == WorkInfo.State.SUCCEEDED }
        assertNull(h.server.gets.last()) // it ran now, as a full pull
    }

    @Test
    fun periodicIsUniqueAndNeedsNetwork() {
        val scheduler = SyncScheduler(context)
        scheduler.periodic()
        scheduler.periodic()

        val infos = wm.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        val spec = SyncScheduler.periodicRequest().workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
    }

    /** CoroutineWorker runs off the test thread, so wait for the state change. */
    private fun await(id: UUID, done: (WorkInfo) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            ShadowLooper.idleMainLooper()
            val info = wm.getWorkInfoById(id).get()!!
            if (done(info)) return
            check(System.currentTimeMillis() < deadline) { "work $id stuck in ${info.state}" }
            Thread.sleep(10)
        }
    }
}
