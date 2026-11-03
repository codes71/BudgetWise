package app.budgetwise.data.repo

import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.nlu.TxType
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanRepositoryTest {
    private lateinit var h: RepoHarness
    private lateinit var scan: ScanRepository
    private val clock = TestClock(Instant.parse("2026-10-30T04:00:00Z"))

    private class TestClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Before
    fun setUp() {
        h = RepoHarness()
        scan = ScanRepository(h.api, h.sync.session, clock)
    }

    @After fun tearDown() { h.close() }

    @Test
    fun sendsThePhotoAsBase64WithTheActiveAccountsToken() = runBlocking {
        h.json(
            200,
            """{"kind":"receipt","merchant":"City Mart","date":"2026-10-23","total":20650,"entries":[
              {"type":"expense","amount":13400,"category":"Groceries","description":"City Mart","date":"2026-10-23",
               "items":[{"name":"ဆန် ၅ ကီလို","amount":9800},{"name":"ကြက်ဥ ၁၀ လုံး","amount":3600}]}]}""",
        )
        val r = scan.scan(byteArrayOf(1, 2, 3), "image/jpeg")
        val request = h.apiServer.takeRequest()
        assertEquals("/api/v1/scan", request.path)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("""{"image":"AQID","mediaType":"image/jpeg"}""", request.body.readUtf8())
        val reading = (r as ApiResult.Ok).v
        assertEquals(ScanKind.RECEIPT, reading.kind)
        assertEquals("City Mart", reading.merchant)
        assertEquals(LocalDate.parse("2026-10-23"), reading.date)
        assertEquals(20650L, reading.totalKyat)
        assertEquals(listOf(ScanItem("ဆန် ၅ ကီလို", 9800), ScanItem("ကြက်ဥ ၁၀ လုံး", 3600)), reading.entries.single().items)
    }

    @Test
    fun aReplyThePhoneCannotUseIsDroppedNotFilledIn() = runBlocking {
        h.json(
            200,
            """{"kind":"history","entries":[
              {"type":"income","amount":150000,"category":"Groceries","description":"ဦးမောင်မောင်","date":"2026-10-25",
               "reference":"01003456789012345678","clientId":"8757c8d9-1367-4ee0-b09b-80b464aba7a0"},
              {"type":"expense","amount":0,"category":"Other","description":"zero","date":"2026-10-24"},
              {"type":"refund","amount":500,"category":"Other","description":"odd","date":"2026-10-24"},
              {"type":"expense","amount":2500,"category":"Other","description":"no date","date":"yesterday"},
              {"type":"expense","amount":2500,"category":"Other","description":"Yangon Bakehouse","date":"2026-10-23","clientId":"not-a-uuid"}]}""",
        )
        val reading = (scan.scan(byteArrayOf(1), "image/png") as ApiResult.Ok).v
        assertEquals(2, reading.entries.size)
        // Income is always filed under Income, whatever the reply said.
        assertEquals(CategoryRepository.INCOME, reading.entries[0].category)
        assertEquals(TxType.INCOME, reading.entries[0].type)
        assertEquals("8757c8d9-1367-4ee0-b09b-80b464aba7a0", reading.entries[0].clientId)
        assertNull("a clientId that is not a UUID is not used", reading.entries[1].clientId)
    }

    @Test
    fun aReceiptWithNothingUsableIsStillAReceipt() = runBlocking {
        h.json(200, """{"kind":"receipt","entries":[{"type":"expense","amount":-5,"category":"Other","description":"x","date":"2026-10-23"}]}""")
        val reading = (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v
        assertEquals("a receipt it could not use, not a photo of nothing", ScanKind.RECEIPT, reading.kind)
        assertTrue(reading.entries.isEmpty())
        assertFalse(reading.notMoney)
    }

    @Test
    fun aPhotoWithNoReceiptInItSaysSo() = runBlocking {
        h.json(200, """{"kind":"unknown","reason":"not_money","entries":[]}""")
        h.json(200, """{"kind":"unknown","reason":"unclear","entries":[]}""")
        // An older server sends no reason.
        h.json(200, """{"kind":"unknown","entries":[]}""")
        val readings = List(3) { (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v }
        assertEquals(listOf(ScanKind.UNKNOWN), readings.map { it.kind }.distinct())
        assertEquals(listOf(true, false, false), readings.map { it.notMoney })
    }

    @Test
    fun multipleReceiptsKeepTheirReasonThroughTheApiClient() = runBlocking {
        h.json(200, """{"kind":"unknown","reason":"multiple","entries":[]}""")
        val reading = (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v
        assertEquals(ScanKind.UNKNOWN, reading.kind)
        assertEquals(ScanReason.MULTIPLE, reading.reason)
        assertFalse(reading.notMoney)
        assertTrue(reading.entries.isEmpty())
    }

    @Test
    fun unsupportedWalletKeepsItsOwnReason() = runBlocking {
        h.json(200, """{"kind":"unknown","reason":"unsupported","entries":[]}""")
        val reading = (scan.scan(byteArrayOf(1), "image/png") as ApiResult.Ok).v
        assertEquals(ScanReason.UNSUPPORTED, reading.reason)
        assertNull(reading.currency)
        assertTrue(reading.entries.isEmpty())
    }

    @Test
    fun foreignCurrencyKeepsTheCurrencyWithoutAnAmountInKyat() = runBlocking {
        h.json(200, """{"kind":"unknown","reason":"foreign_currency","currency":"THB","entries":[]}""")
        val reading = (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v
        assertEquals(ScanReason.FOREIGN_CURRENCY, reading.reason)
        assertEquals("THB", reading.currency)
        assertNull(reading.totalKyat)
        assertTrue(reading.entries.isEmpty())
    }

    @Test
    fun receiptTotalWithoutEntriesKeepsThePrintedTotalAndShop() = runBlocking {
        h.json(200, """{"kind":"receipt","merchant":"City Mart","date":"2026-10-23","total":5000,"entries":[]}""")
        val reading = (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v
        assertEquals(ScanKind.RECEIPT, reading.kind)
        assertEquals(5000L, reading.totalKyat)
        assertEquals("City Mart", reading.merchant)
        assertEquals(LocalDate.parse("2026-10-23"), reading.date)
        assertTrue(reading.entries.isEmpty())
    }

    @Test
    fun unfamiliarReasonsKeepTheUnreadableFallback() = runBlocking {
        h.json(200, """{"kind":"unknown","reason":"new_server_reason","entries":[]}""")
        val reading = (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v
        assertNull(reading.reason)
        assertFalse(reading.notMoney)
    }

    @Test
    fun afterTooManyScansNoPhotoIsSentUntilRetryAfterHasPassed() = runBlocking {
        h.apiServer.enqueue(
            MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setHeader("Retry-After", "600")
                .setBody("""{"error":{"code":"rate_limited","message":"Too many requests"}}"""),
        )
        val first = scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Failure
        assertEquals(600L, first.retryAfterSec)

        clock.now = clock.now.plusSeconds(60)
        val again = scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Failure
        assertEquals("rate_limited", again.code)
        assertEquals("the minutes still to wait", 540L, again.retryAfterSec)
        assertEquals("refused before any upload", 1, h.apiServer.requestCount)

        clock.now = clock.now.plusSeconds(541)
        h.json(200, """{"kind":"unknown","entries":[]}""")
        assertTrue(scan.scan(byteArrayOf(1), "image/jpeg") is ApiResult.Ok)
        assertEquals(2, h.apiServer.requestCount)
    }

    @Test
    fun aTooManyWithoutRetryAfterWaitsTheServersHour() = runBlocking {
        h.json(429, """{"error":{"code":"rate_limited","message":"Too many requests"}}""")
        scan.scan(byteArrayOf(1), "image/jpeg")
        val again = scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Failure
        assertEquals(3600L, again.retryAfterSec)
        assertEquals(1, h.apiServer.requestCount)
    }

    @Test
    fun thePhotoIsSentOffTheCallersThread() = runBlocking {
        var sentOn: Thread? = null
        val watched = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            if (method.name == "scan") sentOn = Thread.currentThread()
            method.invoke(h.api, *(args ?: emptyArray()))
        } as ApiService
        h.json(200, """{"kind":"unknown","entries":[]}""")

        ScanRepository(watched, h.sync.session, clock).scan(byteArrayOf(1), "image/jpeg")

        // Retrofit writes the JSON body on the thread that makes the call; on the main thread that is a few MB.
        assertNotSame(Thread.currentThread(), sentOn)
    }

    @Test
    fun aRateLimitIsARetryableFailure() = runBlocking {
        h.json(429, """{"error":{"code":"rate_limited","message":"Too many requests"}}""")
        val r = scan.scan(byteArrayOf(1), "image/jpeg")
        assertTrue(r is ApiResult.Failure && r.code == "rate_limited" && r.retryable)
    }

    @Test
    fun noServerIsTheNetworkCode() = runBlocking {
        h.apiServer.shutdown()
        val r = scan.scan(byteArrayOf(1), "image/jpeg")
        assertTrue(r is ApiResult.Failure && r.code == NETWORK_ERROR_CODE)
    }
}
