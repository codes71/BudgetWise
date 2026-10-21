package app.budgetwise.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wm get() = WorkManager.getInstance(context)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

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
    fun enqueueUsesUniqueSyncWorkAndQueuesASecondRequestBehindTheFirst() {
        val scheduler = SyncScheduler(context)
        scheduler.enqueue(fullPull = true)
        scheduler.enqueue(fullPull = false)

        val infos = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get()
        assertEquals("sync", SyncScheduler.UNIQUE_WORK)
        assertEquals(2, infos.size) // appended, not dropped (a full pull survives) and not replaced
        assertTrue(infos.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
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
}
