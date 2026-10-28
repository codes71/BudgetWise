package app.budgetwise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.data.sync.SyncWorker
import app.budgetwise.testing.FakeTokenStore
import app.budgetwise.testing.TestUser
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

@RunWith(RobolectricTestRunner::class)
class AppStartupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wm get() = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob())

    /** fullPull flag of every worker WorkManager created, in order, and the last parameters it was given. */
    private val createdFullPull = mutableListOf<Boolean>()
    private var lastParams: WorkerParameters? = null

    private class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork() = Result.success()
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                            createdFullPull += workerParameters.inputData.getBoolean(SyncWorker.KEY_FULL_PULL, false)
                            lastParams = workerParameters
                            return NoopWorker(appContext, workerParameters)
                        }
                    },
                )
                .build(),
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun startup(store: FakeTokenStore): Pair<AppStartup, SessionManager> {
        val session = SessionManager(store, Clock.systemUTC())
        return AppStartup(session, SyncScheduler(context), scope) to session
    }

    private fun stored(expiresIn: Duration) = StoredSession("tok", Instant.now().plus(expiresIn), TestUser)

    @Test
    fun signedInLaunchSchedulesPeriodicSyncAndAFullPull() = runBlocking {
        val (startup, session) = startup(FakeTokenStore(stored(Duration.ofDays(20))))

        startup.start()
        startup.onUiLaunch()

        assertTrue(session.state.value is SessionState.SignedIn)
        val periodic = wm.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get()
        assertEquals(listOf(WorkInfo.State.ENQUEUED), periodic.map { it.state })
        val oneTime = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(oneTime.id)
        assertEquals(listOf(true), createdFullPull)
    }

    @Test
    fun expiredOrSignedOutLaunchSchedulesNothing() = runBlocking {
        for (store in listOf(FakeTokenStore(stored(Duration.ofSeconds(-1))), FakeTokenStore(null))) {
            val (startup, session) = startup(store)
            startup.start()
            startup.onUiLaunch()
            assertFalse(session.state.value is SessionState.SignedIn)
        }

        assertTrue(wm.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK).get().isEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK).get().isEmpty())
    }

    @Test
    fun workerCreationWaitsForTheSessionLoad() {
        val store = FakeTokenStore(stored(Duration.ofDays(20))).apply { gate = CompletableDeferred() }
        val (startup, session) = startup(store)
        var stateAtCreation: SessionState? = null
        val delegate = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                stateAtCreation = session.state.value
                return null
            }
        }
        val factory = SessionAwareWorkerFactory(startup, delegate)
        val params = fakeParams()
        startup.start()

        // A cold start for a background sync: WorkManager asks for the worker while the session is still loading.
        val created = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            factory.createWorker(context, SyncWorker::class.java.name, params)
            created.countDown()
        }
        assertFalse(created.await(300, TimeUnit.MILLISECONDS))
        assertNull(stateAtCreation)

        store.gate!!.complete(Unit)

        assertTrue(created.await(5, TimeUnit.SECONDS))
        assertTrue(stateAtCreation is SessionState.SignedIn)
        pool.shutdown()
    }

    /** Real parameters from a throwaway worker run; the delegate under test never reads them. */
    private fun fakeParams(): WorkerParameters {
        wm.enqueue(OneTimeWorkRequestBuilder<NoopWorker>().build()).result.get()
        return lastParams!!
    }
}
