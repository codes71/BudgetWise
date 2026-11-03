package app.budgetwise.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.budgetwise.DefaultLanguageStore
import app.budgetwise.MainActivity
import app.budgetwise.R
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.ui.budgets.PlanEditorViewModel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.monthName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.abs

/** What a month-end notification opens: [month]'s end check, for [account]. */
data class MonthEndLink(val account: String, val month: YearMonth) {
    fun putInto(intent: Intent): Intent = intent.putExtra(EXTRA_ACCOUNT, account).putExtra(EXTRA_MONTH, month.toString())

    /** One string for saved state: "2026-10/u1". The month comes first and never holds a "/". */
    fun encode(): String = "$month/$account"

    companion object {
        /** [encode]'s string back; null when it does not read. */
        fun decode(text: String): MonthEndLink? {
            val month = PlanEditorViewModel.parseMonth(text.substringBefore('/')) ?: return null
            val account = text.substringAfter('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: return null
            return MonthEndLink(account, month)
        }

        /** [MainActivity]'s action for a tap on the notification or on its Check it. */
        const val ACTION_OPEN = "app.budgetwise.action.OPEN_MONTH_END"
        private const val EXTRA_ACCOUNT = "app.budgetwise.extra.ACCOUNT"
        private const val EXTRA_MONTH = "app.budgetwise.extra.MONTH"

        /** The link in [intent]'s extras; null when one is missing or the month does not read. */
        fun from(intent: Intent?): MonthEndLink? {
            val account = intent?.getStringExtra(EXTRA_ACCOUNT) ?: return null
            val month = intent.getStringExtra(EXTRA_MONTH)?.let(PlanEditorViewModel::parseMonth) ?: return null
            return MonthEndLink(account, month)
        }

        /**
         * The link [MainActivity] was opened with. Not one opened again from Recents: Android hands back the intent
         * the task started with, which would open an end check the user already left.
         */
        fun opened(intent: Intent?): MonthEndLink? =
            intent?.takeIf { it.action == ACTION_OPEN && it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 }?.let(::from)
    }
}

/** Posts and removes the month-end notification. An interface so the worker's tests can see what it would post. */
interface MonthEndNotifier {
    /** Notifications may be shown: allowed (Android 13 and later), and neither the app nor its channel turned off. */
    val canPost: Boolean

    fun post(reminder: MonthEndReminder, myanmarDigits: Boolean)

    fun cancel(account: String, month: YearMonth)
}

/**
 * The notification is M1 on the board: "{Month} is over: X Ks left", why to check, then Check it (the end check) and,
 * on the daily post only, Remind me tonight ([RemindTonightReceiver]). One per account and month (its tag), so a post
 * tonight replaces this morning's. Tapping it does what Check it does and removes it.
 */
class AndroidMonthEndNotifier @Inject constructor(@ApplicationContext private val context: Context) : MonthEndNotifier {
    override val canPost: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

    override fun post(reminder: MonthEndReminder, myanmarDigits: Boolean) {
        // Below Android 13 the app context is in the phone's language; this one is in the app's.
        val local = DefaultLanguageStore.wrap(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(local, manager)
        manager.notify(tag(reminder.account, reminder.month), NOTIFICATION_ID, build(local, reminder, myanmarDigits))
    }

    override fun cancel(account: String, month: YearMonth) {
        context.getSystemService(NotificationManager::class.java).cancel(tag(account, month), NOTIFICATION_ID)
    }

    private fun build(local: Context, reminder: MonthEndReminder, myanmarDigits: Boolean): Notification {
        val link = MonthEndLink(reminder.account, reminder.month)
        val code = tag(reminder.account, reminder.month).hashCode()
        val open = PendingIntent.getActivity(context, code, openIntent(context, link), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = textOf(local, reminder)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(titleOf(local, reminder, myanmarDigits))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, local.getString(R.string.budgets_check_it), open)
        if (reminder.offerTonight) {
            val tonight = PendingIntent.getBroadcast(
                context,
                code,
                link.putInto(Intent(context, RemindTonightReceiver::class.java)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, local.getString(R.string.reminders_remind_tonight), tonight)
        }
        return builder.build()
    }

    /** Creates the channel before the first post, or renames it after the app's language changed. */
    private fun createChannel(local: Context, manager: NotificationManager) {
        val name = local.getString(R.string.reminders_channel)
        if (manager.getNotificationChannel(CHANNEL_ID)?.name?.toString() != name) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    companion object {
        const val CHANNEL_ID = "budget_reminders"
        internal const val NOTIFICATION_ID = 0x6d31

        internal fun tag(account: String, month: YearMonth) = "month_end/$account/$month"

        /**
         * Opens [MainActivity] with [link]: the running one when there is one (onNewIntent), so the app is not started
         * twice over itself; a new task from a cold start.
         */
        fun openIntent(context: Context, link: MonthEndLink): Intent = link.putInto(
            Intent(context, MainActivity::class.java)
                .setAction(MonthEndLink.ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )

        /** "October is over: 343,500 Ks left", or "… 12,000 Ks over budget" when spending went past the total. */
        internal fun titleOf(local: Context, reminder: MonthEndReminder, myanmarDigits: Boolean): String {
            val month = monthName(reminder.month, local.resources.configuration.locales[0])
            val amount = formatKyat(abs(reminder.leftKyat), myanmarDigits)
            return local.getString(if (reminder.over) R.string.reminders_title_over else R.string.reminders_title_left, month, amount)
        }

        /** Why to check, naming the next month. Over budget there is nothing to add to it, only what is on hand to count. */
        internal fun textOf(local: Context, reminder: MonthEndReminder): String {
            val next = monthName(reminder.month.plusMonths(1), local.resources.configuration.locales[0])
            return local.getString(if (reminder.over) R.string.reminders_text_over else R.string.reminders_text, next)
        }
    }
}

/** What the screens tell the month-end reminder. */
fun interface MonthEndReminders {
    /** [month]'s end check is saved: its notification goes. The plan now has its check, so no run posts for it again. */
    fun checked(month: YearMonth)
}

/** The check was saved with the active account's token, so its notification is that account's. */
class DefaultMonthEndReminders @Inject constructor(
    private val session: SessionManager,
    private val notifier: MonthEndNotifier,
) : MonthEndReminders {
    override fun checked(month: YearMonth) {
        session.activeUserId.value?.let { notifier.cancel(it, month) }
    }
}
