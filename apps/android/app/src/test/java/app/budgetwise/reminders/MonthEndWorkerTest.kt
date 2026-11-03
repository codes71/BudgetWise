package app.budgetwise.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.data.auth.AuthBindingsModule
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.auth.TokenStore
import app.budgetwise.testing.FakeTokenStore
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app's worker factory builds the reminder's worker with the real graph (only the Keystore store is faked). */
@HiltAndroidTest
@UninstallModules(AuthBindingsModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MonthEndWorkerTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    /** Signed out: the run stops before the network, so the test never reaches a dev server. */
    @BindValue @JvmField val tokenStore: TokenStore = FakeTokenStore(null as StoredSession?)

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var session: SessionManager

    @Test
    fun hiltBuildsTheWorkerAndASignedOutRunPostsNothingButSchedulesTheNext() {
        hilt.inject()
        runBlocking { session.load() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // HiltTestApplication does not configure WorkManager; the daily run enqueues the next through it.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

        val worker = TestListenableWorkerBuilder<MonthEndWorker>(app).setWorkerFactory(workerFactory).build()

        assertTrue(worker is MonthEndWorker)
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        val next = WorkManager.getInstance(app).getWorkInfosForUniqueWork(MonthEndScheduler.DAILY_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
    }
}
