package app.budgetwise.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one [SyncEngine.syncOnce]; scheduled by [SyncScheduler].
 *
 * Only [SyncOutcome.RetryLater] asks WorkManager to retry (exponential backoff). [SyncOutcome.NeedsLogin]
 * finishes with success so a signed-out phone does not retry in a loop ([SessionManager][app.budgetwise.data.auth.SessionManager]
 * already shows the expired state), and [SyncOutcome.PermanentFailure] finishes with success because the
 * rejected rows are FAILED in Room, where the UI shows them.
 *
 * :app wiring (Task 11). WorkManager must build workers through Hilt:
 * ```
 * @HiltAndroidApp
 * class BudgetWiseApp : Application(), Configuration.Provider {
 *     @Inject lateinit var workerFactory: HiltWorkerFactory
 *     override val workManagerConfiguration: Configuration
 *         get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
 * }
 * ```
 * and the manifest removes the default initializer:
 * ```
 * <provider android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup" tools:node="merge">
 *     <meta-data android:name="androidx.work.WorkManagerInitializer"
 *         android:value="androidx.startup" tools:node="remove" />
 * </provider>
 * ```
 * :app also needs `androidx.hilt:hilt-work` on its own classpath for `HiltWorkerFactory`.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (engine.syncOnce(fullPull = inputData.getBoolean(KEY_FULL_PULL, false))) {
            SyncOutcome.RetryLater -> Result.retry()
            is SyncOutcome.Success, SyncOutcome.NeedsLogin, is SyncOutcome.PermanentFailure -> Result.success()
        }

    companion object {
        const val KEY_FULL_PULL = "fullPull"
    }
}
