package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.ScanBody
import app.budgetwise.data.net.ScanEntryDto
import app.budgetwise.data.net.ScanResponse
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the photo was: a shop receipt, one KPay payment slip, a screenshot of the KPay history, or none of these. */
enum class ScanKind { RECEIPT, PAYMENT, HISTORY, UNKNOWN }

data class ScanItem(val name: String, val amountKyat: Long)

/**
 * One entry read from the photo, for the user to check before it is saved. A receipt gives one expense per category,
 * with its line [items]; a KPay row carries the [clientId] the server derives from its transaction id.
 */
data class ScanEntry(
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    /** No date could be read, so [date] is today in Yangon. */
    val dateGuessed: Boolean,
    val clientId: String?,
    val items: List<ScanItem>,
)

/** [totalKyat] is the receipt's printed total, when it had one; the entries may add up to less. */
data class ScanReading(
    val kind: ScanKind,
    val merchant: String?,
    val date: LocalDate?,
    val totalKyat: Long?,
    val entries: List<ScanEntry>,
)

/**
 * Sends one photo to `POST /scan` with the active account's token. Nothing is saved: the review screen saves the
 * entries the user keeps. A reply the phone cannot use is never filled in: a bad entry is dropped.
 */
@Singleton
class ScanRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
) {
    /** [image] is JPEG, PNG or WebP bytes ([mediaType]), at most [MAX_IMAGE_BYTES]. */
    suspend fun scan(image: ByteArray, mediaType: String): ApiResult<ScanReading> {
        require(image.size <= MAX_IMAGE_BYTES) { "the image is larger than the server takes" }
        val owner = session.activeUserId.value ?: return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        val body = ScanBody(Base64.getEncoder().encodeToString(image), mediaType)
        return when (val r = safeApi { api.scan(authorization(token), body) }) {
            is ApiResult.Ok -> ApiResult.Ok(r.v.toDomain())
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    companion object {
        /** The server's limit on the decoded image. */
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        private val NOT_SIGNED_IN = ApiResult.Unauthorized("No valid session")
    }
}

internal fun ScanResponse.toDomain(): ScanReading {
    val read = entries.mapNotNull { it.toDomain() }
    return ScanReading(
        kind = ScanKind.entries.firstOrNull { it.name.lowercase() == kind }?.takeIf { read.isNotEmpty() } ?: ScanKind.UNKNOWN,
        merchant = merchant?.trim()?.ifEmpty { null },
        date = date?.toDateOrNull(),
        totalKyat = total?.takeIf { it > 0 },
        entries = read,
    )
}

private fun ScanEntryDto.toDomain(): ScanEntry? {
    val txType = TxType.entries.firstOrNull { it.name.lowercase() == type } ?: return null
    if (amount < 1) return null
    val day = date.toDateOrNull() ?: return null
    val name = category.trim().ifEmpty { return null }
    return ScanEntry(
        type = txType,
        amountKyat = amount,
        category = if (txType == TxType.INCOME) CategoryRepository.INCOME else name,
        description = description.trim().ifEmpty { name }.take(TransactionRepository.MAX_DESCRIPTION),
        date = day,
        dateGuessed = dateGuessed,
        clientId = clientId?.takeIf { it.isUuid() },
        items = items.filter { it.amount > 0 && it.name.isNotBlank() }.map { ScanItem(it.name.trim(), it.amount) },
    )
}

private fun String.isUuid() = try {
    UUID.fromString(this).toString() == lowercase()
} catch (_: IllegalArgumentException) {
    false
}

private fun String.toDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (_: DateTimeParseException) {
    null
}
