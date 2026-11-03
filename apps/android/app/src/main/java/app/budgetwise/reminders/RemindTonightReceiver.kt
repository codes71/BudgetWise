package app.budgetwise.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Clock

/**
 * "Remind me tonight" on the morning's notification: it goes, and one more post is scheduled for the evening
 * ([MonthEndScheduler.tonight]). That post re-checks everything, so a month checked in between gets nothing. Not
 * exported: only the notification's PendingIntent reaches it.
 */
class RemindTonightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = MonthEndLink.from(intent) ?: return
        val app = context.applicationContext
        AndroidMonthEndNotifier(app).cancel(link.account, link.month)
        MonthEndScheduler(app, Clock.systemDefaultZone()).tonight(link.account, link.month)
    }
}
