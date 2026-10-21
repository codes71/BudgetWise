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
 * One-time syncs share the unique work [UNIQUE_WORK]:
 * - An incremental request (`fullPull = false`) uses APPEND_OR_REPLACE: it queues behind a sync that runs or
 *   waits, instead of being dropped (a write that lands during a run's pull still gets pushed) or cancelling it.
 * - A full pull (`fullPull = true`) uses REPLACE: it cancels whatever is queued or running under [UNIQUE_WORK]
 *   and starts now. It pushes the whole outbox before pulling, so it subsumes any queued incremental work, and
 *   it must not sit BLOCKED behind a head that is waiting out exponential backoff (up to about an hour after the
 *   server was unreachable for a while). Cancelling a run mid-way is safe: every row and op change is its own
 *   transaction and a resent POST is idempotent.
 *
 * The periodic sync is separate unique work.
 */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * Call with `fullPull = false` after every local write, and with `fullPull = true` on app launch and on
     * pull-to-refresh (that one replaces queued or backed-off work and runs as soon as there is a network).
     */
    fun enqueue(fullPull: Boolean = false) {
        val policy = if (fullPull) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, policy, oneTimeRequest(fullPull))
    }

    /** Incremental background sync every [PERIODIC_INTERVAL]. Idempotent; call at every app start. */
    fun periodic() {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest())
    }

    /** Cancels queued and running syncs and the periodic sync. Used on sign-out so no work outlives the session. */
    fun cancelAll() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_WORK)
        wm.cancelUniqueWork(PERIODIC_WORK)
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
