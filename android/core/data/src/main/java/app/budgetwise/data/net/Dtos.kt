package app.budgetwise.data.net

import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared by Retrofit (NetModule) and tests. Server may add fields; absent optionals are not written. */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Thrown by [TransactionDto.toDomain] for a server row the device cannot represent (non-integer or
 * non-positive amount, unknown enum value, bad date). The sync engine catches it per row, skips the
 * row and keeps syncing the rest.
 */
class InvalidWireDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `amount` is a [Double] on the read side only: rows created on the web can hold a legacy
 * non-integer, and a `Long` field would make kotlinx fail the whole list response. Decoding as
 * Double lets one bad row be rejected individually in [toDomain]. Doubles are exact for integers
 * up to 2^53, far beyond any kyat amount.
 */
@Serializable
data class TransactionDto(
    val id: String,
    val clientId: String,
    val type: String,
    val amount: Double,
    val category: String,
    val description: String,
    val date: String,
    val source: String,
    val transcript: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TransactionInput(
    val clientId: String,
    val type: String,
    val amount: Long,
    val category: String,
    val description: String,
    val date: String,
    val source: String,
    val transcript: String? = null,
)

@Serializable
data class TransactionsResponse(val transactions: List<TransactionDto>)

@Serializable
data class TransactionsRequest(val transactions: List<TransactionInput>)

@Serializable
data class SavedResponse(val saved: List<TransactionDto>)

fun Transaction.toInput(): TransactionInput {
    require(amountKyat > 0) { "amountKyat must be positive, was $amountKyat" }
    return TransactionInput(
        clientId = clientId,
        type = type.name.lowercase(),
        amount = amountKyat,
        category = category,
        description = description,
        date = date.toString(),
        source = source.wire,
        transcript = transcript,
    )
}

fun TransactionDto.toDomain(): Transaction {
    fun bad(what: String, cause: Throwable? = null): Nothing =
        throw InvalidWireDataException("Transaction clientId=$clientId id=$id: $what", cause)

    if (amount % 1.0 != 0.0) bad("non-integer amount $amount")
    if (amount <= 0.0 || amount > MAX_AMOUNT) bad("amount out of range $amount")
    val txType = TxType.entries.firstOrNull { it.name.lowercase() == type } ?: bad("unknown type '$type'")
    val txSource = Source.entries.firstOrNull { it.wire == source } ?: bad("unknown source '$source'")
    return try {
        Transaction(
            clientId = clientId,
            serverId = id,
            type = txType,
            amountKyat = amount.toLong(),
            category = category,
            description = description,
            date = LocalDate.parse(date),
            source = txSource,
            transcript = transcript,
            updatedAt = Instant.parse(updatedAt),
            syncState = SyncState.SYNCED,
        )
    } catch (e: DateTimeParseException) {
        bad("bad date/timestamp", e)
    }
}

private const val MAX_AMOUNT = 9_007_199_254_740_991.0 // 2^53 - 1
