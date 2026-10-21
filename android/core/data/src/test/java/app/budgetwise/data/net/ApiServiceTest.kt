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
    private var token: String? = "tok-123"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(client: OkHttpClient = createOkHttpClient({ token }, NetLogging.None)): ApiService =
        createApiService(server.url("/api/v1/").toString(), client)

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun bearerHeaderAddedExceptOnAuthToken() = runBlocking {
        server.enqueue(json(200, """{"categories":["Groceries"]}"""))
        server.enqueue(json(200, """{"token":"t","expiresAt":"2026-11-20T00:00:00Z","user":{"id":"u1","email":"a@b.c","fullName":"A B"}}"""))

        api().categories()
        val login = api().login(LoginBody("a@b.c", "pw"))

        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))
        val loginRequest = server.takeRequest()
        assertEquals("/api/v1/auth/token", loginRequest.path)
        assertNull(loginRequest.getHeader("Authorization"))
        assertEquals("t", login.token)
        assertEquals("u1", login.user.id)
    }

    @Test
    fun noHeaderWhenProviderHasNoToken() = runBlocking {
        token = null
        server.enqueue(json(200, """{"categories":[]}"""))
        api().categories()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun http401MapsToUnauthorized() = runBlocking {
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"Token expired"}}"""))
        val result = safeApi { api().categories() }
        assertEquals(ApiResult.Unauthorized("Token expired"), result)
    }

    @Test
    fun errorEnvelopeParsedIntoFailure() = runBlocking {
        server.enqueue(json(422, """{"error":{"code":"validation_failed","message":"amount must be positive"}}"""))
        val result = safeApi { api().budgets() }
        assertEquals(ApiResult.Failure("validation_failed", "amount must be positive", retryable = false), result)
    }

    @Test
    fun http5xxAndIOExceptionAreRetryable() = runBlocking {
        server.enqueue(json(500, """{"error":{"code":"internal","message":"boom"}}"""))
        val serverError = safeApi { api().categories() } as ApiResult.Failure
        assertEquals("internal", serverError.code)
        assertTrue(serverError.retryable)

        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val gateway = safeApi { api().categories() } as ApiResult.Failure
        assertEquals("http_502", gateway.code)
        assertTrue(gateway.retryable)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val dropped = safeApi { api().categories() } as ApiResult.Failure
        assertEquals("network", dropped.code)
        assertTrue(dropped.retryable)

        val thrown = safeApi<Unit> { throw IOException("offline") } as ApiResult.Failure
        assertTrue(thrown.retryable)
    }

    @Test
    fun http400IsNotRetryable() = runBlocking {
        server.enqueue(json(400, """{"error":{"code":"bad_request","message":"bad body"}}"""))
        val result = safeApi { api().categories() } as ApiResult.Failure
        assertEquals("bad_request", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun http429IsRetryable() = runBlocking {
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"slow down"}}"""))
        val result = safeApi { api().categories() } as ApiResult.Failure
        assertTrue(result.retryable)
    }

    @Test
    fun malformedSuccessBodyIsNonRetryableFailure() = runBlocking {
        server.enqueue(json(200, """{"unexpected":true}"""))
        val result = safeApi { api().categories() } as ApiResult.Failure
        assertEquals("bad_response", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun deleteMapsNoContentAndNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ApiResult.Ok(Unit), safeApiResponse { api().delete("srv-1") })
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json(404, """{"error":{"code":"not_found","message":"no such transaction"}}"""))
        val missing = safeApiResponse { api().delete("srv-2") } as ApiResult.Failure
        assertEquals("not_found", missing.code)
        assertFalse(missing.retryable)

        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"expired"}}"""))
        assertEquals(ApiResult.Unauthorized("expired"), safeApiResponse { api().delete("srv-3") })
    }

    @Test
    fun transactionsQueryAndSaveRoundTrip() = runBlocking {
        server.enqueue(json(200, """{"transactions":[]}"""))
        api().transactions("2026-10-20T00:00:00Z")
        val withCursor = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/transactions", withCursor.encodedPath)
        assertEquals("2026-10-20T00:00:00Z", withCursor.queryParameter("updatedSince"))

        server.enqueue(json(200, """{"transactions":[]}"""))
        api().transactions(null)
        assertEquals("/api/v1/transactions", server.takeRequest().path)

        server.enqueue(json(200, """{"saved":[]}"""))
        val body = TransactionsRequest(
            listOf(TransactionInput("c1", "expense", 1500, "Groceries", "rice", "2026-10-21", "manual")),
        )
        api().saveTransactions(body)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertTrue(sent.body.readUtf8().contains(""""amount":1500"""))
    }

    @Test
    fun budgetsDecode() = runBlocking {
        server.enqueue(json(200, """{"budgets":[{"category":"Groceries","limit":50000,"extra":1}]}"""))
        val r = api().budgets()
        assertEquals("Groceries", r.budgets.single().category)
        assertEquals(50000.0, r.budgets.single().limit, 0.0)
    }

    @Test
    fun loggingNeverEmitsAuthorizationValue() = runBlocking {
        val lines = mutableListOf<String>()
        val client = createOkHttpClient({ token }, NetLogging.Debug(lines::add))
        server.enqueue(json(200, """{"categories":[]}"""))
        api(client).categories()
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.contains("tok-123") })
        assertTrue(lines.any { it.contains("Authorization: ██") })
    }

    @Test
    fun noLoggingInterceptorWhenNone() {
        val client = createOkHttpClient({ token }, NetLogging.None)
        assertTrue(client.interceptors.none { it is HttpLoggingInterceptor })
    }
}
