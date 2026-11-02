package app.budgetwise.data.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiServiceTest {
    private lateinit var server: MockWebServer
    private val auth = authorization("tok-123")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(client: OkHttpClient = createOkHttpClient(NetLogging.None)): ApiService =
        createApiService(server.url("/api/v1/").toString(), client)

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun eachCallSendsTheTokenItIsGivenAndLoginSendsNone() = runBlocking {
        server.enqueue(json(200, """{"categories":["Groceries"]}"""))
        server.enqueue(json(200, """{"saved":[]}"""))
        server.enqueue(json(200, """{"token":"t","expiresAt":"2026-11-20T00:00:00Z","user":{"id":"u1","email":"a@b.c","fullName":"A B"}}"""))

        api().categories(auth)
        api().saveTransactions(authorization("other-account-token"), TransactionsRequest(emptyList()))
        val login = api().login(LoginBody("a@b.c", "pw"))

        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer other-account-token", server.takeRequest().getHeader("Authorization"))
        val loginRequest = server.takeRequest()
        assertEquals("/api/v1/auth/token", loginRequest.path)
        assertNull(loginRequest.getHeader("Authorization"))
        assertEquals("t", login.token)
        assertEquals("u1", login.user.id)
    }

    @Test
    fun http401MapsToUnauthorized() = runBlocking {
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"Token expired"}}"""))
        val result = safeApi { api().categories(auth) }
        assertEquals(ApiResult.Unauthorized("Token expired"), result)
    }

    @Test
    fun errorEnvelopeParsedIntoFailure() = runBlocking {
        server.enqueue(json(422, """{"error":{"code":"validation_failed","message":"amount must be positive"}}"""))
        val result = safeApi { api().budgets(auth) }
        assertEquals(ApiResult.Failure("validation_failed", "amount must be positive", retryable = false), result)
    }

    @Test
    fun http5xxAndIOExceptionAreRetryable() = runBlocking {
        server.enqueue(json(500, """{"error":{"code":"internal","message":"boom"}}"""))
        val serverError = safeApi { api().categories(auth) } as ApiResult.Failure
        assertEquals("internal", serverError.code)
        assertTrue(serverError.retryable)

        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val gateway = safeApi { api().categories(auth) } as ApiResult.Failure
        assertEquals("http_502", gateway.code)
        assertTrue(gateway.retryable)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val dropped = safeApi { api().categories(auth) } as ApiResult.Failure
        assertEquals("network", dropped.code)
        assertTrue(dropped.retryable)

        val thrown = safeApi<Unit> { throw IOException("offline") } as ApiResult.Failure
        assertTrue(thrown.retryable)
    }

    @Test
    fun http400IsNotRetryable() = runBlocking {
        server.enqueue(json(400, """{"error":{"code":"bad_request","message":"bad body"}}"""))
        val result = safeApi { api().categories(auth) } as ApiResult.Failure
        assertEquals("bad_request", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun http429IsRetryable() = runBlocking {
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"slow down"}}"""))
        val result = safeApi { api().categories(auth) } as ApiResult.Failure
        assertTrue(result.retryable)
    }

    @Test
    fun malformedSuccessBodyIsNonRetryableFailure() = runBlocking {
        server.enqueue(json(200, """{"unexpected":true}"""))
        val result = safeApi { api().categories(auth) } as ApiResult.Failure
        assertEquals("bad_response", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun deleteMapsNoContentAndNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ApiResult.Ok(Unit), safeApiResponse { api().delete(auth, "srv-1") })
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json(404, """{"error":{"code":"not_found","message":"no such transaction"}}"""))
        val missing = safeApiResponse { api().delete(auth, "srv-2") } as ApiResult.Failure
        assertEquals("not_found", missing.code)
        assertFalse(missing.retryable)

        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"expired"}}"""))
        assertEquals(ApiResult.Unauthorized("expired"), safeApiResponse { api().delete(auth, "srv-3") })
    }

    @Test
    fun transactionsQueryAndSaveRoundTrip() = runBlocking {
        server.enqueue(json(200, """{"transactions":[]}"""))
        api().transactions(auth, "2026-10-20T00:00:00Z")
        val withCursor = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/transactions", withCursor.encodedPath)
        assertEquals("2026-10-20T00:00:00Z", withCursor.queryParameter("updatedSince"))

        server.enqueue(json(200, """{"transactions":[]}"""))
        api().transactions(auth, null)
        assertEquals("/api/v1/transactions", server.takeRequest().path)

        server.enqueue(json(200, """{"saved":[]}"""))
        val body = TransactionsRequest(
            listOf(TransactionInput("c1", "expense", 1500, "Groceries", "rice", "2026-10-21", "manual")),
        )
        api().saveTransactions(auth, body)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertTrue(sent.body.readUtf8().contains(""""amount":1500"""))
    }

    @Test
    fun budgetsDecode() = runBlocking {
        server.enqueue(json(200, """{"budgets":[{"category":"Groceries","limit":50000,"extra":1}]}"""))
        val r = api().budgets(auth)
        assertEquals("Groceries", r.budgets.single().category)
        assertEquals(50000.0, r.budgets.single().limit, 0.0)
    }

    @Test
    fun loggingNeverEmitsAuthorizationValue() = runBlocking {
        val lines = mutableListOf<String>()
        val client = createOkHttpClient(NetLogging.Debug(lines::add))
        server.enqueue(json(200, """{"categories":[]}"""))
        api(client).categories(auth)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains("tok-123") })
        assertTrue(lines.any { it.contains("Authorization: ██") })
    }

    @Test
    fun noLoggingInterceptorWhenNone() {
        val client = createOkHttpClient(NetLogging.None)
        assertTrue(client.interceptors.none { it is HttpLoggingInterceptor })
    }

    @Test
    fun http408IsRetryable() = runBlocking {
        server.enqueue(json(408, """{"error":{"code":"timeout","message":"slow"}}"""))
        val result = safeApi { api().categories(auth) } as ApiResult.Failure
        assertTrue(result.retryable)
    }

    @Test
    fun truncatedErrorBodyStillMapsToRetryableFailure() = runBlocking {
        fun truncated() = MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"code":"internal","message":"${"x".repeat(100_000)}"}}""")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

        server.enqueue(truncated())
        val viaSafeApi = safeApi { api().categories(auth) }
        assertTrue(viaSafeApi is ApiResult.Failure && viaSafeApi.retryable)

        server.enqueue(truncated())
        val viaResponse = safeApiResponse { api().delete(auth, "srv-1") }
        assertTrue(viaResponse is ApiResult.Failure && viaResponse.retryable)
    }

    @Test
    fun errorBodyReadFailureStillClassifiesByStatus() = runBlocking {
        val broken = object : okhttp3.ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): okio.BufferedSource = throw IOException("connection reset")
        }
        val response = retrofit2.Response.error<Unit>(401, broken)
        assertEquals(ApiResult.Unauthorized("HTTP 401"), safeApiResponse { response })
        val server500 = retrofit2.Response.error<Unit>(503, broken)
        val r = safeApiResponse { server500 } as ApiResult.Failure
        assertEquals("http_503", r.code)
        assertTrue(r.retryable)
        val timeout = safeApiResponse { retrofit2.Response.error<Unit>(408, broken) } as ApiResult.Failure
        assertTrue(timeout.retryable)
    }

    @Test
    fun loggingRedactsCookies() = runBlocking {
        val lines = mutableListOf<String>()
        val client = createOkHttpClient(NetLogging.Debug(lines::add))
        server.enqueue(json(200, """{"categories":[]}""").setHeader("Set-Cookie", "sid=secret-sess"))
        val request = okhttp3.Request.Builder().url(server.url("/api/v1/categories"))
            .header("Cookie", "sid=secret-cookie").build()
        client.newCall(request).execute().close()
        assertTrue(lines.none { it.contains("secret-sess") || it.contains("secret-cookie") })
    }
}
