package app.budgetwise.data.repo

import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        h = RepoHarness()
        scan = ScanRepository(h.api, h.sync.session)
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
    fun nothingUsableIsUnknown() = runBlocking {
        h.json(200, """{"kind":"receipt","entries":[{"type":"expense","amount":-5,"category":"Other","description":"x","date":"2026-10-23"}]}""")
        assertEquals(ScanKind.UNKNOWN, (scan.scan(byteArrayOf(1), "image/jpeg") as ApiResult.Ok).v.kind)
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
