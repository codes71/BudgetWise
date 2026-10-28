package app.budgetwise

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager is initialized on demand from [workManagerConfiguration] (the manifest removes its startup
 * initializer), so SyncWorker is built through Hilt, and only once the session is loaded.
 */
@HiltAndroidApp
class BudgetWiseApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var startup: AppStartup

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SessionAwareWorkerFactory(startup, workerFactory))
            .build()

    override fun onCreate() {
        super.onCreate()
        startup.start()
    }
}
