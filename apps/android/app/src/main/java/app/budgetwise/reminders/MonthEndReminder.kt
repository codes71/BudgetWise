package app.budgetwise.reminders

import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Transaction
import app.budgetwise.ui.budgets.spentIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** One run of the month-end reminder: the daily one, or the one more post that "Remind me tonight" asked for. */
sealed interface ReminderRun {
    /** The month this run reminds of, on [today]. */
    fun month(today: LocalDate): YearMonth

    /** Whether this run may post at [time] (the phone's local time). */
    fun postsAt(time: LocalTime): Boolean

    /**
     * Every day from 9:00, about last month. Only in the daytime ([DAY_START] to [DAY_END]): Doze can hold a run for
     * hours, and one that wakes at night waits for the next morning instead of posting then.
     */
    data object Daily : ReminderRun {
        override fun month(today: LocalDate): YearMonth = YearMonth.from(today).minusMonths(1)

        override fun postsAt(time: LocalTime): Boolean = !time.isBefore(DAY_START) && time.isBefore(DAY_END)
    }

    /**
     * At 20:00, about the [month] whose notification [account] asked to see again. Only until [TONIGHT_END]: one that
     * Doze held later, or past midnight, is dropped (no post, nothing remembered, nothing scheduled).
     */
    data class Tonight(val account: String, val month: YearMonth) : ReminderRun {
        override fun month(today: LocalDate): YearMonth = month

        override fun postsAt(time: LocalTime): Boolean = !time.isBefore(DAY_START) && time.isBefore(TONIGHT_END)
    }

    companion object {
        /** The daily run is aimed here, and posts from here. */
        val DAY_START: LocalTime = LocalTime.of(9, 0)

        /** A daily run that wakes from here on (or before [DAY_START]) does not post. */
        val DAY_END: LocalTime = LocalTime.of(21, 0)

        /** Tonight's run posts until here. */
        val TONIGHT_END: LocalTime = LocalTime.of(22, 0)
    }
}

/** What has been posted for one account and month: at most the daily post and one tonight. */
data class Posted(val daily: Boolean = false, val tonight: Boolean = false) {
    /** [run]'s post is already out, so the run has nothing to do. */
    fun has(run: ReminderRun): Boolean = when (run) {
        ReminderRun.Daily -> daily
        is ReminderRun.Tonight -> tonight
    }
}

/** A notification to post for [account]: [month] ended with [leftKyat] left, below 0 when it went over budget. */
data class MonthEndReminder(val account: String, val month: YearMonth, val leftKyat: Long, val offerTonight: Boolean) {
    val over: Boolean get() = leftKyat < 0
}

/**
 * Whether [run] posts [now] (the phone's local time), and what: only for the active [account] (null when signed out),
 * only at a time [run] posts at ([ReminderRun.postsAt]), only once [run]'s month is over (from the 1st of the next
 * one), only when that month has a [plan] without an end check, and only when this account and month have not had
 * [run]'s post yet ([posted]). Tonight's post is for the account that asked for it, and is offered only from the daily
 * post, so a month gets two posts at most.
 *
 * The leftover is the end check's: the plan's total minus every EXPENSE in the month ([spentIn]), from [rows], the
 * active account's ledger.
 */
fun monthEndReminder(
    run: ReminderRun,
    account: String?,
    now: LocalDateTime,
    plan: MonthPlan?,
    rows: List<Transaction>,
    posted: Posted,
): MonthEndReminder? {
    if (account == null) return null
    if (run is ReminderRun.Tonight && run.account != account) return null
    if (!run.postsAt(now.toLocalTime())) return null
    val today = now.toLocalDate()
    val month = run.month(today)
    if (month >= YearMonth.from(today)) return null
    if (plan == null || plan.month != month || plan.endCheck != null) return null
    if (posted.has(run)) return null
    return MonthEndReminder(
        account = account,
        month = month,
        leftKyat = plan.totalKyat - spentIn(rows, month),
        offerTonight = run == ReminderRun.Daily && !posted.tonight,
    )
}
