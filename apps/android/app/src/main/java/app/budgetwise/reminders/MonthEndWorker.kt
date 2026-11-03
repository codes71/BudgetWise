package app.budgetwise.reminders

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.budgetwise.MonthPlanSource
import app.budgetwise.NetworkStatus
import app.budgetwise.SyncControls
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.di.DeviceClock
import app.budgetwise.ui.budgets.PlanEditorViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * One month-end reminder run ([MonthEndScheduler]): the daily one, or tonight's when its data names an account and a
 * month. Always succeeds: a run that failed (Room, DataStore) is not retried, the next day's run looks again. The
 * daily run schedules the next one as it ends, whatever happened; a run stopped by the system is not finished, so it
 * schedules nothing (WorkManager runs it again, and that run does).
 */
@HiltWorker
class MonthEndWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val check: MonthEndCheck,
    private val scheduler: MonthEndScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val run = runOf(inputData)
        try {
            check.run(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: the message could carry stored data.
            Log.w(TAG, "Month-end reminder failed: ${e.javaClass.simpleName}")
        }
        if (run == ReminderRun.Daily) scheduler.nextDaily()
        return Result.success()
    }

    companion object {
        private const val TAG = "BudgetWise"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_MONTH = "month"

        fun tonightData(account: String, month: YearMonth): Data = workDataOf(KEY_ACCOUNT to account, KEY_MONTH to month.toString())

        internal fun runOf(data: Data): ReminderRun {
            val account = data.getString(KEY_ACCOUNT)
            val month = data.getString(KEY_MONTH)?.let(PlanEditorViewModel::parseMonth)
            return if (account != null && month != null) ReminderRun.Tonight(account, month) else ReminderRun.Daily
        }
    }
}

/**
 * What a run does, for the active account only. Online, it refreshes the month's plan and, when that plan still waits
 * for its end check, pulls the ledger the way the end check does ([SyncControls.syncAndWait]), so the amount counts
 * what was entered on the web; offline, or when either fails, the cache answers. Then it decides with
 * [monthEndReminder], posts, and remembers the post. Without permission to post, or outside the run's hours, it stops
 * first and remembers nothing, so a later run can still post.
 */
class MonthEndCheck @Inject constructor(
    private val session: SessionManager,
    private val plans: MonthPlanSource,
    private val transactions: TransactionStore,
    private val sync: SyncControls,
    private val network: NetworkStatus,
    private val settings: SettingsStore,
    private val store: ReminderStore,
    private val notifier: MonthEndNotifier,
    @DeviceClock private val clock: Clock,
) {
    suspend fun run(run: ReminderRun) {
        if (!notifier.canPost) return
        val account = session.activeUserId.value ?: return
        val now = LocalDateTime.now(clock)
        if (!run.postsAt(now.toLocalTime())) return
        val month = run.month(now.toLocalDate())
        val posted = store.posted(account, month)
        // Posted already: no need to ask the server.
        if (posted.has(run)) return
        val online = network.online.first()
        if (online) refresh(month)
        // The plan and ledger read below are the active account's; another account signed in meanwhile gets nothing.
        if (session.activeUserId.value != account) return
        val plan = plans.observe(month).first()
        // The full pull only when there may be something to post: without a plan, or once checked, there is not.
        if (online && plan != null && plan.endCheck == null) {
            pull()
            if (session.activeUserId.value != account) return
        }
        val reminder = monthEndReminder(run, account, now, plan, transactions.observe().first(), posted) ?: return
        notifier.post(reminder, settings.myanmarDigits.first())
        store.markPosted(account, month, run)
    }

    /** A failed refresh (server, network, or writing the cache) leaves the cache, which answers instead. */
    private suspend fun refresh(month: YearMonth) {
        try {
            plans.refresh(month, month)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Whatever the outcome, the ledger in Room is used: pulled when the run got that far, else as it was. */
    private suspend fun pull() {
        try {
            sync.syncAndWait()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
