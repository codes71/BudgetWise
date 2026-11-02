package app.budgetwise.data.net

import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import java.math.BigDecimal
import java.math.RoundingMode
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
 * Thrown by [TransactionDto.toDomain] for a server row the device cannot represent (non-positive or
 * absurdly large amount, unknown enum value, bad date). The sync engine catches it per row, skips the
 * row and keeps syncing the rest.
 */
class InvalidWireDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `amount` is a [Double] on the read side only: the web form accepts decimals (12.50), and a `Long`
 * field would make kotlinx fail the whole list response. [toDomain] rounds to whole kyat, the unit the
 * device stores. Doubles are exact for integers up to 2^53, far beyond any kyat amount.
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
    val receiptId: String? = null,
    val merchant: String? = null,
    val items: List<ItemWire> = emptyList(),
)

/** A receipt line on the wire: [quantity] of [name] at [unitPrice] kyat each. */
@Serializable
data class ItemWire(val name: String, val quantity: Int, val unitPrice: Long)

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
    val receiptId: String? = null,
    val merchant: String? = null,
    /** Null rather than empty, so an entry with no lines sends no `items` (older servers reject unknown shapes). */
    val items: List<ItemWire>? = null,
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
        receiptId = receiptId,
        merchant = merchant,
        items = items.takeIf { it.isNotEmpty() }?.map { ItemWire(it.name, it.quantity, it.unitPriceKyat) },
    )
}

fun TransactionDto.toDomain(): Transaction {
    fun bad(what: String, cause: Throwable? = null): Nothing =
        throw InvalidWireDataException("Transaction clientId=$clientId id=$id: $what", cause)

    if (!amount.isFinite() || amount <= 0.0 || amount > MAX_AMOUNT) bad("amount out of range $amount")
    val txType = TxType.entries.firstOrNull { it.name.lowercase() == type } ?: bad("unknown type '$type'")
    val txSource = Source.entries.firstOrNull { it.wire == source } ?: bad("unknown source '$source'")
    return try {
        Transaction(
            clientId = clientId,
            serverId = id,
            type = txType,
            amountKyat = wholeKyat(amount),
            category = category,
            description = description,
            date = LocalDate.parse(date),
            source = txSource,
            transcript = transcript,
            updatedAt = Instant.parse(updatedAt),
            syncState = SyncState.SYNCED,
            receiptId = receiptId,
            merchant = merchant,
            // A line the phone cannot show (no count, a negative price) is left out; the entry itself still counts.
            items = items.filter { it.quantity > 0 && it.unitPrice >= 0 }.map { EntryItem(it.name, it.quantity, it.unitPrice) },
        )
    } catch (e: DateTimeParseException) {
        bad("bad date/timestamp", e)
    }
}

/**
 * Whole kyat, rounded half up like budget limits (12.5 becomes 13), so a decimal web entry still shows and
 * counts on the phone, off by under 1 Ks. A positive amount stays positive (0.3 becomes 1, not 0).
 */
private fun wholeKyat(amount: Double): Long =
    BigDecimal(amount).setScale(0, RoundingMode.HALF_UP).toLong().coerceAtLeast(1)

private const val MAX_AMOUNT = 9_007_199_254_740_991.0 // 2^53 - 1
