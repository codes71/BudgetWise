package app.budgetwise.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.budgetwise.di.DeviceClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Schedules [MonthEndWorker]. No network constraint: offline, a run uses the cached plan.
 *
 * - The daily run is unique one-time work [DAILY_WORK] at the next 9:00, and each run schedules the next one
 *   ([nextDaily]). Working the delay out again every day keeps it at 9:00 local through Doze delays, summer time and a
 *   change of time zone, which a 24-hour periodic request would let drift. A launch enqueues it with KEEP, so a waiting
 *   run is never moved; nothing cancels it (a signed-out run posts nothing).
 * - "Remind me tonight" is unique one-time work [TONIGHT_WORK] at 20:00 that day, or an hour from now when it is
 *   20:00 or later. REPLACE: one such post waits at a time.
 *
 * Times are in [clock]'s zone, the phone's ([DeviceClock]).
 */
class MonthEndScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    @DeviceClock private val clock: Clock,
) {
    /** At launch: the next daily run, unless one waits already. Also drops the periodic request of earlier builds. */
    fun daily() {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        work.enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.KEEP, dailyRequest(ZonedDateTime.now(clock)))
    }

    /**
     * From the daily run itself: the next one, at the next 9:00. Chained after the running one (REPLACE would cancel
     * it); a chain whose last run failed or was cancelled starts again with this one.
     */
    fun nextDaily() {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, dailyRequest(ZonedDateTime.now(clock)))
    }

    fun tonight(account: String, month: YearMonth) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TONIGHT_WORK, ExistingWorkPolicy.REPLACE, tonightRequest(account, month, ZonedDateTime.now(clock)))
    }

    companion object {
        const val DAILY_WORK = "month-end-daily"
        const val TONIGHT_WORK = "month-end-reminder-tonight"

        /** The 24-hour periodic request of earlier builds, which drifted; cancelled at launch. */
        const val LEGACY_PERIODIC_WORK = "month-end-reminder"
        val DAILY_AT: LocalTime = ReminderRun.DAY_START
        val TONIGHT_AT: LocalTime = LocalTime.of(20, 0)
        private val LATE_DELAY: Duration = Duration.ofHours(1)

        /**
         * From [now] to the next [at] in [now]'s zone: later today, else tomorrow. As a wall-clock time, so across a
         * change to or from summer time the wait is an hour shorter or longer.
         */
        internal fun untilNext(at: LocalTime, now: ZonedDateTime): Duration {
            val today = now.with(at)
            return Duration.between(now, if (today.isAfter(now)) today else now.plusDays(1).with(at))
        }

        /** To 20:00 today; an hour when it is 20:00 or later, so tonight still means tonight. */
        internal fun untilTonight(now: ZonedDateTime): Duration =
            if (now.toLocalTime().isBefore(TONIGHT_AT)) Duration.between(now, now.with(TONIGHT_AT)) else LATE_DELAY

        internal fun dailyRequest(now: ZonedDateTime): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MonthEndWorker>()
                .setInitialDelay(untilNext(DAILY_AT, now))
                .build()

        internal fun tonightRequest(account: String, month: YearMonth, now: ZonedDateTime): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MonthEndWorker>()
                .addTag("month-end-account:$account")
                .setInitialDelay(untilTonight(now))
                .setInputData(MonthEndWorker.tonightData(account, month))
                .build()
    }
}
