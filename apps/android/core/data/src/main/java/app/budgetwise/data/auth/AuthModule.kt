package app.budgetwise.data.auth

import app.budgetwise.data.net.BearerProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindingsModule {
    @Binds
    abstract fun tokenStore(impl: KeystoreTokenStore): TokenStore
}

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    fun bearerProvider(session: SessionManager): BearerProvider = BearerProvider { session.bearer() }
}
