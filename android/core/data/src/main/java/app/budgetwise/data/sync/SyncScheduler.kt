package app.budgetwise.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules [SyncWorker]. Every request needs a network connection and backs off exponentially from 30 s.
 *
 * One-time syncs share the unique work [UNIQUE_WORK] with APPEND_OR_REPLACE: a request made while a sync runs
 * or waits for the network queues behind it instead of being dropped (a write that lands during a run's pull
 * still gets pushed, and a full pull is never lost) or cancelling it. The periodic sync is separate unique work.
 */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** After every local write (`fullPull = false`); on app launch and pull-to-refresh (`fullPull = true`). */
    fun enqueue(fullPull: Boolean = false) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, oneTimeRequest(fullPull))
    }

    /** Incremental background sync every [PERIODIC_INTERVAL]. Idempotent; call at every app start. */
    fun periodic() {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest())
    }

    companion object {
        const val UNIQUE_WORK = "sync"
        const val PERIODIC_WORK = "sync-periodic"
        val PERIODIC_INTERVAL: Duration = Duration.ofHours(6)
        private const val BACKOFF_SECONDS = 30L

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        internal fun oneTimeRequest(fullPull: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(SyncWorker.KEY_FULL_PULL to fullPull))
                .build()

        internal fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
