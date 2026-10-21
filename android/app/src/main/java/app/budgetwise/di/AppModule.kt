package app.budgetwise.di

import app.budgetwise.AccountActions
import app.budgetwise.DefaultAccountActions
import app.budgetwise.DefaultSyncControls
import app.budgetwise.SyncControls
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A scope that lives as long as the process, for work no screen owns (the session load at start). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    abstract fun accountActions(impl: DefaultAccountActions): AccountActions

    @Binds
    abstract fun syncControls(impl: DefaultSyncControls): SyncControls
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
