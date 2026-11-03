package app.budgetwise.reminders

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.MainActivity
import app.budgetwise.ui.common.monthName
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notification as posted (board M1), its two actions, and what Remind me tonight schedules. */
@RunWith(RobolectricTestRunner::class)
class MonthEndNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidMonthEndNotifier(context)
    private val october = YearMonth.of(2026, 10)
    private val tag = AndroidMonthEndNotifier.tag("u1", october)

    /** The data of each worker WorkManager created. */
    private val created = mutableListOf<WorkerParameters>()

    private class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork() = Result.success()
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                            created += workerParameters
                            return NoopWorker(appContext, workerParameters)
                        }
                    },
                )
                .build(),
        )
    }

    private fun posted(): Notification? = shadowOf(manager).getNotification(tag, AndroidMonthEndNotifier.NOTIFICATION_ID)

    private val Notification.title get() = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    @Test
    fun theMorningPostHasTheBoardsWordsOnTheBudgetRemindersChannel() {
        notifier.post(MonthEndReminder("u1", october, 343_500, offerTonight = true), myanmarDigits = false)

        assertEquals("Budget reminders", manager.getNotificationChannel(AndroidMonthEndNotifier.CHANNEL_ID).name)
        val n = posted()!!
        assertEquals(AndroidMonthEndNotifier.CHANNEL_ID, n.channelId)
        assertEquals("October is over: 343,500 Ks left", n.title)
        assertEquals("Do you really have this much on hand? Check it and add it to November.", n.text)
        assertEquals(listOf("Check it", "Remind me tonight"), n.actions.map { it.title.toString() })
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun tappingItOrCheckItOpensMainActivityWithTheMonthAndAccount() {
        notifier.post(MonthEndReminder("u1", october, 343_500, offerTonight = true), myanmarDigits = false)
        val n = posted()!!

        for (pending in listOf(n.contentIntent, n.actions[0].actionIntent)) {
            val shadow = shadowOf(pending)
            assertTrue(shadow.isActivityIntent)
            assertEquals(MainActivity::class.java.name, shadow.savedIntent.component?.className)
            assertEquals(MonthEndLink("u1", october), MonthEndLink.opened(shadow.savedIntent))
            // A running app gets it in onNewIntent rather than a second activity on top.
            assertTrue(shadow.savedIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        }
        val tonight = shadowOf(n.actions[1].actionIntent)
        assertTrue(tonight.isBroadcastIntent)
        assertEquals(RemindTonightReceiver::class.java.name, tonight.savedIntent.component?.className)
        assertEquals(MonthEndLink("u1", october), MonthEndLink.from(tonight.savedIntent))
    }

    @Test
    fun overBudgetItSaysSoAndTonightsPostOffersNoThirdOne() {
        notifier.post(MonthEndReminder("u1", october, -56_500, offerTonight = false), myanmarDigits = false)

        val n = posted()!!
        assertEquals("October is over: 56,500 Ks over budget", n.title)
        assertEquals("Check what you really have on hand before you plan November.", n.text)
        assertEquals(listOf("Check it"), n.actions.map { it.title.toString() })
    }

    @Test
    @Config(qualifiers = "my")
    fun inBurmeseWithMyanmarDigits() {
        notifier.post(MonthEndReminder("u1", october, 343_500, offerTonight = true), myanmarDigits = true)

        val n = posted()!!
        val month = monthName(october, Locale.forLanguageTag("my"))
        assertEquals("${month}လ ကုန်ဆုံးပြီ၊ ၃၄၃,၅၀၀ Ks ကျန်သည်", n.title)
        assertEquals("ဘတ်ဂျက် သတိပေးချက်များ", manager.getNotificationChannel(AndroidMonthEndNotifier.CHANNEL_ID).name)
        assertEquals(listOf("စစ်မည်", "ဒီည ပြန်သတိပေးပါ"), n.actions.map { it.title.toString() })
    }

    @Test
    fun cancelRemovesOnlyThatAccountsMonth() {
        notifier.post(MonthEndReminder("u1", october, 343_500, offerTonight = true), myanmarDigits = false)
        notifier.post(MonthEndReminder("u2", october, 10_000, offerTonight = true), myanmarDigits = false)

        notifier.cancel("u1", october)

        assertNull(posted())
        assertNotNull(shadowOf(manager).getNotification(AndroidMonthEndNotifier.tag("u2", october), AndroidMonthEndNotifier.NOTIFICATION_ID))
    }

    @Test
    @Config(sdk = [33])
    fun fromAndroid13ItCanPostOnlyOnceAllowed() {
        assertFalse(notifier.canPost)

        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifier.canPost)

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifier.canPost)
    }

    @Test
    fun aLinkIsOnlyTakenFromItsOwnActionAndNotFromRecents() {
        val open = AndroidMonthEndNotifier.openIntent(context, MonthEndLink("u1", october))
        assertEquals(MonthEndLink("u1", october), MonthEndLink.opened(open))

        assertNull(MonthEndLink.opened(Intent(open).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertNull(MonthEndLink.opened(Intent(open).setAction(Intent.ACTION_MAIN)))
        assertNull(MonthEndLink.opened(Intent(open).putExtra("app.budgetwise.extra.MONTH", "October")))
        assertNull(MonthEndLink.opened(null))
    }

    @Test
    fun remindMeTonightRemovesTheNotificationAndSchedulesOneMorePost() {
        notifier.post(MonthEndReminder("u1", october, 343_500, offerTonight = true), myanmarDigits = false)
        val tonight = shadowOf(posted()!!.actions[1].actionIntent).savedIntent

        RemindTonightReceiver().onReceive(context, tonight)

        assertNull(posted())
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(MonthEndScheduler.TONIGHT_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(work.initialDelayMillis > 0)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(work.id)
        assertEquals(ReminderRun.Tonight("u1", october), MonthEndWorker.runOf(created.single().inputData))
    }
}
