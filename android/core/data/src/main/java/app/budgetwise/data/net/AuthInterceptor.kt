package app.budgetwise.data.net

import okhttp3.Interceptor
import okhttp3.Response

/** Synchronous, in-memory source of the bearer token. Implemented by the session layer. */
fun interface BearerProvider {
    fun bearer(): String?
}

/** Adds `Authorization: Bearer <token>` to every request except the login call. */
class AuthInterceptor(private val provider: BearerProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith(AUTH_TOKEN_PATH)) return chain.proceed(request)
        val token = provider.bearer() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    private companion object {
        const val AUTH_TOKEN_PATH = "/auth/token"
    }
}
