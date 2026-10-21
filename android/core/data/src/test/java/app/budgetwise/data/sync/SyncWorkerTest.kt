package app.budgetwise.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.budgetwise.data.model.SyncState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var h: SyncHarness

    @Before
    fun setUp() {
        h = SyncHarness()
    }

    @After
    fun tearDown() = h.close()

    private fun worker(fullPull: Boolean = false): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(ApplicationProvider.getApplicationContext<Context>())
            .setInputData(workDataOf(SyncWorker.KEY_FULL_PULL to fullPull))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        SyncWorker(appContext, workerParameters, h.engine)
                },
            )
            .build()

    @Test
    fun retryLaterMapsToRetry() = runTest {
        h.server.getStatus = 503
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun needsLoginMapsToSuccessSoThereIsNoRetryStorm() = runTest {
        h.session.signOut()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, h.web.requestCount)
    }

    @Test
    fun permanentFailureMapsToSuccessWithTheRowFailedInRoom() = runTest {
        val bad = h.addLocal(1).single()
        h.server.invalidClientIds = setOf(bad)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(SyncState.FAILED, h.tx.byClientId(bad)!!.syncState)
    }

    @Test
    fun fullPullFlagReachesTheEngine() = runTest {
        h.cursor.setLastPulledAt(Instant.parse("2026-10-21T05:00:00Z"))
        assertEquals(ListenableWorker.Result.success(), worker(fullPull = true).doWork())
        assertNull(h.server.gets.single())
    }
}
