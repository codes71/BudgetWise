package app.budgetwise.data.sync

import app.budgetwise.data.net.SavedResponse
import app.budgetwise.data.net.TransactionDto
import app.budgetwise.data.net.TransactionInput
import app.budgetwise.data.net.TransactionsRequest
import app.budgetwise.data.net.TransactionsResponse
import app.budgetwise.data.net.WireJson
import java.time.Instant
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/**
 * In-memory stand-in for the WS1 mobile API on MockWebServer. Like the real `saveBatch`, POST is idempotent
 * on clientId (an existing row is returned unchanged) and a batch with any invalid item is refused whole.
 */
class FakeServer : Dispatcher() {
    val token = "test-token-not-a-secret"

    /** Server rows by clientId, mirroring the unique (userId, clientId) index. */
    val rows = LinkedHashMap<String, TransactionDto>()
    val posts = mutableListOf<List<String>>()
    val gets = mutableListOf<String?>()
    val deletes = mutableListOf<String>()

    /**
     * A POST containing any of these clientIds is refused whole with [invalidStatus] and the code WS1 sends for
     * it: `validation_failed` for 422 (zod), `invalid_json` for 400.
     */
    var invalidClientIds: Set<String> = emptySet()
    var invalidStatus = 422

    /** 1-based index of the POST that answers 401 (token revoked mid-sync). */
    var unauthorizedOnPost: Int? = null

    /** 1-based index of the POST that is stored but whose response never arrives. */
    var dropResponseOnPost: Int? = null

    /** POSTs answer this status with an HTML page and no error envelope (captive portal, proxy, firewall). */
    var htmlStatusOnPost: Int? = null

    /** Runs while a POST is in flight, after the server stored the rows (a user write racing the push). */
    var duringPost: (() -> Unit)? = null

    var getStatus: Int? = null

    /** Runs while a GET is in flight, before its response is built (a user write racing the pull). */
    var duringGet: (() -> Unit)? = null

    private var nextId = 1
    private var stamp = Instant.parse("2026-10-21T05:00:00Z")

    @Synchronized
    fun put(
        clientId: String,
        amount: Double = 1000.0,
        category: String = "Other",
        description: String = "tea",
    ): TransactionDto {
        val at = nextStamp()
        val dto = TransactionDto(
            id = "srv-${nextId++}", clientId = clientId, type = "expense", amount = amount, category = category,
            description = description, date = "2026-10-20", source = "web", createdAt = at, updatedAt = at,
        )
        rows[clientId] = dto
        return dto
    }

    /** A web edit: the change plus a new updatedAt. */
    @Synchronized
    fun edit(clientId: String, change: (TransactionDto) -> TransactionDto) {
        rows[clientId] = change(rows.getValue(clientId)).copy(updatedAt = nextStamp())
    }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != "Bearer $token") return error(401, "unauthorized", "Missing token")
        val url = request.requestUrl!!
        val path = url.encodedPath
        return when {
            request.method == "POST" && path == "/api/v1/transactions" -> post(request)
            request.method == "GET" && path == "/api/v1/transactions" -> get(url.queryParameter("updatedSince"))
            request.method == "DELETE" && path.startsWith("/api/v1/transactions/") -> delete(path.substringAfterLast('/'))
            else -> error(404, "not_found", "No route")
        }
    }

    private fun post(request: RecordedRequest): MockResponse {
        val body = WireJson.decodeFromString(TransactionsRequest.serializer(), request.body.readUtf8())
        val ids = body.transactions.map { it.clientId }
        posts += ids
        val n = posts.size
        if (n == unauthorizedOnPost) return error(401, "unauthorized", "Token expired")
        htmlStatusOnPost?.let {
            return MockResponse().setResponseCode(it).setHeader("Content-Type", "text/html").setBody("<html>Blocked</html>")
        }
        if (ids.any { it in invalidClientIds }) {
            return error(invalidStatus, if (invalidStatus == 400) "invalid_json" else "validation_failed", "Invalid request")
        }
        val saved = body.transactions.map { input -> rows.getOrPut(input.clientId) { input.toServerRow() } }
        duringPost?.invoke()
        if (n == dropResponseOnPost) return lostResponse()
        return json(200, WireJson.encodeToString(SavedResponse.serializer(), SavedResponse(saved)))
    }

    private fun get(since: String?): MockResponse {
        gets += since
        duringGet?.invoke()
        getStatus?.let { return error(it, "internal_error", "Server error") }
        val from = since?.let(Instant::parse)
        val list = rows.values
            .filter { from == null || !Instant.parse(it.updatedAt).isBefore(from) }
            .sortedBy { Instant.parse(it.updatedAt) }
        return json(200, WireJson.encodeToString(TransactionsResponse.serializer(), TransactionsResponse(list)))
    }

    private fun delete(id: String): MockResponse {
        deletes += id
        val key = rows.entries.firstOrNull { it.value.id == id }?.key
            ?: return error(404, "not_found", "Transaction not found")
        rows.remove(key)
        return MockResponse().setResponseCode(204)
    }

    private fun TransactionInput.toServerRow(): TransactionDto {
        val at = nextStamp()
        return TransactionDto(
            id = "srv-${nextId++}", clientId = clientId, type = type, amount = amount.toDouble(), category = category,
            description = description, date = date, source = source, transcript = transcript, createdAt = at, updatedAt = at,
        )
    }

    private fun nextStamp(): String {
        stamp = stamp.plusSeconds(60)
        return stamp.toString()
    }

    /**
     * The rows are stored but the client never gets a usable reply: the body is cut short, then the server shuts
     * its output so the client reads EOF. SHUTDOWN_OUTPUT_AT_END lets MockWebServer count the request first, so it
     * logs nothing (DISCONNECT_AFTER_REQUEST and DISCONNECT_AT_END warn the connection "didn't make a request").
     */
    private fun lostResponse() = json(200, "{\"saved\":[")
        .setHeader("Content-Length", 4096)
        .setSocketPolicy(SocketPolicy.SHUTDOWN_OUTPUT_AT_END)

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun error(code: Int, errorCode: String, message: String) =
        json(code, """{"error":{"code":"$errorCode","message":"$message"}}""")
}
