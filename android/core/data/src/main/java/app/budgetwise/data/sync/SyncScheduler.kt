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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Schedules [SyncWorker]. Every request needs a network connection and backs off exponentially from 30 s.
 *
 * One-time syncs share the unique work [UNIQUE_WORK]:
 * - An incremental request (`fullPull = false`) uses APPEND_OR_REPLACE: it queues behind a sync that runs or
 *   waits, instead of being dropped (a write that lands during a run's pull still gets pushed) or cancelling it.
 * - [requestAfterWrite] adds nothing while a sync is already queued and has not started: that run pushes the
 *   whole outbox, this write included. Ten entries saved offline wait as one sync, not ten.
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
     * Call with `fullPull = true` on app launch and on pull-to-refresh (that one replaces queued or backed-off
     * work and runs as soon as there is a network). After a local write, call [requestAfterWrite] instead.
     */
    fun enqueue(fullPull: Boolean = false) {
        val policy = if (fullPull) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, policy, oneTimeRequest(fullPull))
    }

    /**
     * After a local write, once it is committed. A sync that is queued but not started (waiting for a connection,
     * for backoff, or behind a running one) reads the outbox when it starts, so it will push this write: nothing
     * is added. Otherwise (none queued, or one running that may be past its push) an incremental sync is queued.
     */
    suspend fun requestAfterWrite() {
        val states = try {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_WORK).get().map { it.state }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // cannot tell what is queued: queue one more, as before
        }
        if (states == null || needsNewRun(states)) enqueue(fullPull = false)
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

        /** False when a sync under [UNIQUE_WORK] is queued and has not started yet; see [requestAfterWrite]. */
        internal fun needsNewRun(states: List<WorkInfo.State>): Boolean =
            states.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }

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
