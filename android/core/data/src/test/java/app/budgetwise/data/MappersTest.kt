package app.budgetwise.data

import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.net.InvalidWireDataException
import app.budgetwise.data.net.TransactionDto
import app.budgetwise.data.net.WireJson
import app.budgetwise.data.net.toDomain
import app.budgetwise.data.net.toInput
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MappersTest {
    private fun tx(amount: Long = 3000, transcript: String? = "kopi") = Transaction(
        clientId = "6f1c2b0e-8d57-4b9a-9a4e-2f7f4a1c9d10",
        serverId = null,
        type = TxType.EXPENSE,
        amountKyat = amount,
        category = "Food and Drink Item",
        description = "coffee",
        date = LocalDate.of(2026, 10, 19),
        source = Source.VOICE,
        transcript = transcript,
        updatedAt = Instant.parse("2026-10-19T03:00:00Z"),
        syncState = SyncState.PENDING,
    )

    private fun dtoJson(amount: String = "3000", type: String = "expense", source: String = "voice", extra: String = "") =
        """{"id":"srv1","clientId":"c1","type":"$type","amount":$amount,"category":"Other","description":"d",
        "date":"2026-10-19","source":"$source","createdAt":"2026-10-19T03:00:00.000Z",
        "updatedAt":"2026-10-19T03:00:01.000Z"$extra}"""

    @Test
    fun toInput_mapsEnumsToLowercaseWireValues() {
        val input = tx().toInput()
        assertEquals("expense", input.type)
        assertEquals("voice", input.source)
        assertEquals("2026-10-19", input.date)
        assertEquals(3000L, input.amount)
        assertEquals("kopi", input.transcript)
    }

    @Test
    fun toInput_omitsNullTranscriptFromJson() {
        val json = WireJson.encodeToString(tx(transcript = null).toInput())
        assertFalse(json.contains("transcript"))
        assertFalse(json.contains("\"id\""))
        assertFalse(json.contains("createdAt"))
    }

    @Test
    fun toDomain_roundTripsClientId() {
        val dto = WireJson.decodeFromString<TransactionDto>(dtoJson())
        val domain = dto.toDomain()
        assertEquals("c1", domain.clientId)
        assertEquals("srv1", domain.serverId)
        assertEquals(TxType.EXPENSE, domain.type)
        assertEquals(Source.VOICE, domain.source)
        assertEquals(SyncState.SYNCED, domain.syncState)
        assertEquals(LocalDate.of(2026, 10, 19), domain.date)
        assertEquals(Instant.parse("2026-10-19T03:00:01Z"), domain.updatedAt)
        assertNull(domain.transcript)
    }

    @Test
    fun toDomain_ignoresUnknownKeysAndReadsTranscript() {
        val dto = WireJson.decodeFromString<TransactionDto>(dtoJson(extra = ""","userId":"u","__v":0,"transcript":"hi" """))
        assertEquals("hi", dto.toDomain().transcript)
    }

    @Test
    fun toDomain_integerValuedDecimalAmountIsAccepted() {
        val dto = WireJson.decodeFromString<TransactionDto>(dtoJson(amount = "3000.0"))
        assertEquals(3000L, dto.toDomain().amountKyat)
    }

    @Test
    fun toDomain_decimalWebAmountRoundsHalfUpToWholeKyat() {
        fun kyat(amount: String) = WireJson.decodeFromString<TransactionDto>(dtoJson(amount = amount)).toDomain().amountKyat
        assertEquals(13L, kyat("12.5"))
        assertEquals(1500L, kyat("1500.49"))
        assertEquals(2L, kyat("1.99"))
        assertEquals(1L, kyat("0.3")) // a positive amount never becomes 0
    }

    @Test
    fun toDomain_outOfRangeAmountIsRejectedWithClearError() {
        val dto = WireJson.decodeFromString<TransactionDto>(dtoJson(amount = "1e16"))
        val e = assertThrows(InvalidWireDataException::class.java) { dto.toDomain() }
        assertEquals(true, e.message!!.contains("c1"))
    }

    @Test
    fun toDomain_nonPositiveAmountIsRejected() {
        val dto = WireJson.decodeFromString<TransactionDto>(dtoJson(amount = "0"))
        assertThrows(InvalidWireDataException::class.java) { dto.toDomain() }
    }

    @Test
    fun toDomain_unknownEnumValueIsRejected() {
        val dto = WireJson.decodeFromString<TransactionDto>(dtoJson(type = "transfer"))
        assertThrows(InvalidWireDataException::class.java) { dto.toDomain() }
        val dto2 = WireJson.decodeFromString<TransactionDto>(dtoJson(source = "fax"))
        assertThrows(InvalidWireDataException::class.java) { dto2.toDomain() }
    }

    @Test
    fun negativeAmountRejected() {
        assertThrows(IllegalArgumentException::class.java) { tx(amount = -5).toInput() }
        assertThrows(IllegalArgumentException::class.java) { tx(amount = 0).toInput() }
    }
}
