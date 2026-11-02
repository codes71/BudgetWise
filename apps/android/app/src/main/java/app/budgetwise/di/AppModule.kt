package app.budgetwise.di

import app.budgetwise.AccountActions
import app.budgetwise.BudgetSource
import app.budgetwise.CategorySource
import app.budgetwise.DefaultAccountActions
import app.budgetwise.DefaultBudgetSource
import app.budgetwise.DefaultCategorySource
import app.budgetwise.DefaultLanguageStore
import app.budgetwise.DefaultNetworkStatus
import app.budgetwise.DefaultReceiptScanner
import app.budgetwise.DefaultSessionLoader
import app.budgetwise.DefaultSyncControls
import app.budgetwise.DefaultTransactionStore
import app.budgetwise.LanguageStore
import app.budgetwise.NetworkStatus
import app.budgetwise.ReceiptScanner
import app.budgetwise.SessionLoader
import app.budgetwise.SyncControls
import app.budgetwise.TransactionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope that lives as long as the process, for work no screen owns (the session load at start, a delete that
 * commits as the user leaves the list).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The phone's clock in the phone's time zone: what "today" and "this month" mean to the user. The unqualified
 * [Clock] from :core:data is UTC, for timestamps.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceClock

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    abstract fun accountActions(impl: DefaultAccountActions): AccountActions

    @Binds
    abstract fun sessionLoader(impl: DefaultSessionLoader): SessionLoader

    @Binds
    abstract fun syncControls(impl: DefaultSyncControls): SyncControls

    @Binds
    abstract fun transactionStore(impl: DefaultTransactionStore): TransactionStore

    @Binds
    abstract fun budgetSource(impl: DefaultBudgetSource): BudgetSource

    @Binds
    abstract fun categorySource(impl: DefaultCategorySource): CategorySource

    @Binds
    abstract fun networkStatus(impl: DefaultNetworkStatus): NetworkStatus

    @Binds
    abstract fun languageStore(impl: DefaultLanguageStore): LanguageStore

    @Binds
    abstract fun receiptScanner(impl: DefaultReceiptScanner): ReceiptScanner
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Not a singleton: each screen reads the zone when it opens, so a changed time zone applies from then on. */
    @Provides
    @DeviceClock
    fun deviceClock(): Clock = Clock.systemDefaultZone()
}
