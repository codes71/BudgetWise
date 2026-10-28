package app.budgetwise.di

import app.budgetwise.BuildConfig
import app.budgetwise.data.net.NetConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** :app owns BuildConfig, so it supplies the base URL and logging switch that :core:data's NetModule consumes. */
@Module
@InstallIn(SingletonComponent::class)
object NetConfigModule {
    @Provides
    fun netConfig(): NetConfig = NetConfig(BuildConfig.API_BASE_URL, debugLogging = BuildConfig.DEBUG)
}
