package app.budgetwise.data.repo

import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.VoiceEntryDto
import app.budgetwise.data.net.VoiceParseResponse
import app.budgetwise.data.net.VoiceQuestionDto
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceRepositoryTest {
    private lateinit var h: RepoHarness
    private lateinit var voice: VoiceRepository

    @Before
    fun setUp() {
        h = RepoHarness()
        voice = VoiceRepository(h.api, h.sync.session)
    }

    @After fun tearDown() { h.close() }

    @Test
    fun sendsOnlyTheTextWithTheActiveAccountsToken() = runBlocking {
        h.json(
            200,
            """{"intent":"add","heard":"ကော်ဖီ သုံးထောင်","entries":[{"type":"expense","amount":3000,"category":"Food and Drink Item","description":"ကော်ဖီ","date":"2026-10-30","dateGuessed":true}],"question":null}""",
        )
        val r = voice.parse(listOf(" ကော်ဖီ သုံးထောင် ", "", "ကော်ဖီ သုံးထောင်", "ကော်ဖီ ၃၀၀၀"))
        val request = h.apiServer.takeRequest()
        assertEquals("/api/v1/voice/parse", request.path)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("""{"transcripts":["ကော်ဖီ သုံးထောင်","ကော်ဖီ ၃၀၀၀"]}""", request.body.readUtf8())
        val entry = VoiceEntry(TxType.EXPENSE, 3000, "Food and Drink Item", "ကော်ဖီ", LocalDate.parse("2026-10-30"), dateGuessed = true)
        assertEquals(ApiResult.Ok(VoiceParse.Add("ကော်ဖီ သုံးထောင်", listOf(entry))), r)
    }

    @Test
    fun a401ExpiresTheSession() = runBlocking {
        h.json(401, """{"error":{"code":"unauthorized","message":"Token expired"}}""")
        assertTrue(voice.parse(listOf("x")) is ApiResult.Unauthorized)
        assertEquals(app.budgetwise.data.auth.SessionState.Expired, h.sync.session.state.value)
    }

    @Test
    fun aServerFailureIsAFailure() = runBlocking {
        h.json(502, """{"error":{"code":"parse_failed","message":"Could not understand that"}}""")
        val r = voice.parse(listOf("x"))
        assertTrue(r is ApiResult.Failure && r.code == "parse_failed" && r.retryable)
    }

    @Test
    fun badEntriesAreDroppedNeverFilledIn() {
        fun entry(type: String = "expense", amount: Double = 5000.0, date: String = "2026-10-29", category: String = "Transport") =
            VoiceEntryDto(type, amount, category, "တက္ကစီ", date)
        val reply = VoiceParseResponse(
            intent = "add",
            heard = "h",
            entries = listOf(
                entry(),
                entry(type = "refund"),
                entry(amount = 0.0),
                entry(amount = Double.NaN),
                entry(date = "yesterday"),
                entry(type = "income", amount = 12.5, category = "Salary"),
            ),
        )
        val parsed = reply.toDomain() as VoiceParse.Add
        assertEquals(listOf(5000L, 13L), parsed.entries.map { it.amountKyat })
        assertEquals("income is always filed under Income", CategoryRepository.INCOME, parsed.entries[1].category)
        assertEquals(VoiceParse.Other("h"), reply.copy(entries = listOf(entry(amount = -1.0))).toDomain())
    }

    @Test
    fun questionsAndUnknownIntents() {
        val q = VoiceQuestionDto("total", null, "2026-10-26", "2026-10-30")
        assertEquals(
            VoiceParse.Question("h", VoiceQuestion(VoiceMetric.TOTAL, null, LocalDate.parse("2026-10-26"), LocalDate.parse("2026-10-30"))),
            VoiceParseResponse("question", "h", question = q).toDomain(),
        )
        assertEquals(VoiceParse.Other("h"), VoiceParseResponse("question", "h", question = q.copy(metric = "average")).toDomain())
        assertEquals(VoiceParse.Other("h"), VoiceParseResponse("question", "h", question = null).toDomain())
        assertEquals(VoiceParse.Other("h"), VoiceParseResponse("joke", "h").toDomain())
    }
}
