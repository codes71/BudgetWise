package app.budgetwise.data.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Invocation
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * App-level network configuration, provided by :app (it owns BuildConfig).
 * [debugLogging] is true only for debug builds; release builds add no logging interceptor at all.
 */
data class NetConfig(val baseUrl: String, val debugLogging: Boolean)

sealed interface NetLogging {
    data object None : NetLogging

    /** Request and response lines (no bodies). The Authorization header is always redacted. */
    class Debug(val sink: (String) -> Unit = { android.util.Log.d("BudgetWiseNet", it) }) : NetLogging
}

/**
 * No interceptor adds a token: each authenticated [ApiService] call names the token of the account it is for, so a
 * call made for one account can never go out with another account's token.
 */
fun createOkHttpClient(logging: NetLogging): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(SlowCalls)
        .apply {
            if (logging is NetLogging.Debug) {
                addInterceptor(
                    HttpLoggingInterceptor { logging.sink(it) }.apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                        redactHeader("Authorization")
                        redactHeader("Cookie")
                        redactHeader("Set-Cookie")
                    },
                )
            }
        }
        .build()

/** Gives an [ApiService] method marked [SlowCall] its longer read timeout. */
private val SlowCalls = Interceptor { chain ->
    val slow = chain.request().tag(Invocation::class.java)?.method()?.getAnnotation(SlowCall::class.java)
    val next = if (slow == null) chain else chain.withReadTimeout(slow.readSeconds, TimeUnit.SECONDS)
    next.proceed(chain.request())
}

fun createApiService(baseUrl: String, client: OkHttpClient, json: Json = WireJson): ApiService =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)

@Module
@InstallIn(SingletonComponent::class)
object NetModule {
    @Provides
    @Singleton
    fun okHttpClient(config: NetConfig): OkHttpClient =
        createOkHttpClient(if (config.debugLogging) NetLogging.Debug() else NetLogging.None)

    @Provides
    @Singleton
    fun apiService(client: OkHttpClient, config: NetConfig): ApiService =
        createApiService(config.baseUrl, client)
}
