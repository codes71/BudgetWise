package app.budgetwise.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.NetLogging
import app.budgetwise.data.net.createApiService
import app.budgetwise.data.net.createOkHttpClient
import app.budgetwise.data.prefs.DataStoreSettingsStore
import app.budgetwise.data.sync.SyncHarness
import app.budgetwise.data.sync.SyncScheduler
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Real Room, session, sync engine and WorkManager (test mode) from [SyncHarness], a real DataStore on a temp file,
 * and a second MockWebServer ([api]) whose answers each test queues for login, budgets and categories.
 */
class RepoHarness : Closeable {
    val sync = SyncHarness()
    val context: Context = ApplicationProvider.getApplicationContext()
    val apiServer = MockWebServer().apply { start() }
    val api: ApiService = createApiService(
        "http://127.0.0.1:${apiServer.port}/api/v1/",
        createOkHttpClient({ sync.session.bearer() }, NetLogging.None),
    )

    private val dir: File = Files.createTempDirectory("repo-harness").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") }
    val settings = DataStoreSettingsStore(dataStore)

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    val scheduler = SyncScheduler(context)
    val transactions = TransactionRepository(sync.db, sync.engine, scheduler, sync.clock)

    fun work(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    fun json(code: Int, body: String) = apiServer.enqueue(
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body),
    )

    override fun close() {
        scope.cancel()
        dir.deleteRecursively()
        apiServer.shutdown()
        sync.close()
    }
}
