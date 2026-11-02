package app.budgetwise

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.di.ApplicationScope
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process and UI start. [start] (from `Application.onCreate`) loads the stored accounts off the main thread, before
 * anything can reach the network ([SessionLoader]: rows saved before several accounts go to the account in the saved
 * session then). The first frame ([MainViewModel]) and every worker ([SessionAwareWorkerFactory]) wait for that load,
 * so neither sees the "signed out" state that [SessionManager] holds until it finishes, nor rows without an owner.
 */
@Singleton
class AppStartup @Inject constructor(
    private val session: SessionManager,
    private val loader: SessionLoader,
    private val scheduler: SyncScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val loaded = CompletableDeferred<Unit>()

    val isSessionLoaded: Boolean get() = loaded.isCompleted

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                loader.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The store already clears an unreadable blob; anything else leaves the user signed out. Class name
                // only: the message could carry stored data.
                Log.w(TAG, "Startup load failed: ${e.javaClass.simpleName}")
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitSession() = loaded.await()

    /**
     * A UI launch (not a background wake-up for a worker): a signed-in session gets the periodic sync and a full pull
     * (spec amendment 1: the full pull is what brings web deletes to the phone). Also loads the Zawgyi detector now,
     * so the first save does not pay for it.
     */
    suspend fun onUiLaunch() {
        awaitSession()
        if (session.state.value is SessionState.SignedIn) {
            scheduler.periodic()
            scheduler.enqueue(fullPull = true)
        }
        withContext(Dispatchers.Default) { BurmeseTextNormalizer.looksLikeZawgyi(WARM_UP_TEXT) }
    }

    private companion object {
        const val TAG = "BudgetWise"
        const val WARM_UP_TEXT = "ကော်ဖီ ၅၀၀၀"
    }
}

/**
 * WorkManager can start the process just to run a sync. The worker must not run before [AppStartup] has the token in
 * memory, or it would find no session and skip the sync, so creating a worker waits for the session load (one
 * DataStore read and one Keystore decrypt). WorkManager calls this on its own background thread, never the main one.
 */
class SessionAwareWorkerFactory(
    private val startup: AppStartup,
    private val delegate: WorkerFactory,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
        if (!startup.isSessionLoaded) runBlocking { withTimeoutOrNull(SESSION_WAIT_MS) { startup.awaitSession() } }
        return delegate.createWorker(appContext, workerClassName, workerParameters)
    }

    private companion object {
        /** A load that takes longer is stuck; the worker then runs and reports NeedsLogin instead of hanging. */
        const val SESSION_WAIT_MS = 10_000L
    }
}
