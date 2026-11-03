package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.ScanBody
import app.budgetwise.data.net.ScanEntryDto
import app.budgetwise.data.net.ScanItemDto
import app.budgetwise.data.net.ScanResponse
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import app.budgetwise.nlu.TxType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the photo was: a shop receipt, one KPay payment slip, a screenshot of the KPay history, or none of these. */
enum class ScanKind { RECEIPT, PAYMENT, HISTORY, UNKNOWN }

/** Why the server returned no usable money; unrecognized future reasons use the unreadable fallback. */
enum class ScanReason { NOT_MONEY, UNCLEAR, MULTIPLE, UNSUPPORTED, FOREIGN_CURRENCY }

/** A receipt line as read: a count and a price each, which the review lets the user change. */
typealias ScanItem = EntryItem

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

/**
 * [totalKyat] is the receipt's printed total, when it had one; the entries may add up to less. [kind] is what the
 * server saw, even when none of its entries could be used. [notMoney]: the server found no receipt or KPay screen in
 * the photo at all (kind [ScanKind.UNKNOWN]), as opposed to one it could not read.
 */
data class ScanReading(
    val kind: ScanKind,
    val merchant: String?,
    val date: LocalDate?,
    val totalKyat: Long?,
    val entries: List<ScanEntry>,
    val notMoney: Boolean = false,
    val reason: ScanReason? = if (notMoney) ScanReason.NOT_MONEY else null,
    val currency: String? = null,
)

/**
 * Sends one photo to `POST /scan` with the active account's token. Nothing is saved: the review screen saves the
 * entries the user keeps. A reply the phone cannot use is never filled in: a bad entry is dropped.
 *
 * After the server's 429, each account's scans are refused here until its Retry-After has passed, so a Try again, a
 * new photo or a reopened scan screen does not upload a photo only to be refused again.
 */
@Singleton
class ScanRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
    private val clock: Clock,
) {
    private val blockedUntil = ConcurrentHashMap<String, Instant>()

    /** [image] is JPEG, PNG or WebP bytes ([mediaType]), at most [MAX_IMAGE_BYTES]. */
    suspend fun scan(image: ByteArray, mediaType: String): ApiResult<ScanReading> {
        require(image.size <= MAX_IMAGE_BYTES) { "the image is larger than the server takes" }
        val owner = session.activeUserId.value ?: return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        blocked(owner)?.let { return it }
        // Off the main thread: the base64 copy, and the JSON body Retrofit writes while it starts the call, are each
        // a few MB for a busy photo.
        val body = withContext(Dispatchers.Default) { ScanBody(Base64.getEncoder().encodeToString(image), mediaType) }
        return when (val r = safeApi { withContext(Dispatchers.IO) { api.scan(authorization(token), body) } }) {
            is ApiResult.Ok -> ApiResult.Ok(r.v.toDomain())
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> {
                if (r.isRateLimit()) blockedUntil[owner] = clock.instant().plusSeconds(r.retryAfterSec ?: DEFAULT_WAIT_SECONDS)
                r
            }
        }
    }

    /** The 429 this account had, with the seconds still to wait, while its Retry-After has not passed. */
    private fun blocked(owner: String): ApiResult.Failure? {
        val until = blockedUntil[owner] ?: return null
        val left = Duration.between(clock.instant(), until).seconds
        if (left <= 0) {
            blockedUntil.remove(owner, until)
            return null
        }
        return ApiResult.Failure(RATE_LIMITED, "Too many scans", retryable = true, retryAfterSec = left)
    }

    private fun ApiResult.Failure.isRateLimit() = code == RATE_LIMITED || code == "http_429"

    companion object {
        /** The server's limit on the decoded image. */
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

        /** The server's code for its 20 scans an hour. */
        const val RATE_LIMITED = "rate_limited"

        /** A 429 without a Retry-After: the server's window is an hour. */
        private const val DEFAULT_WAIT_SECONDS = 3600L
        private val NOT_SIGNED_IN = ApiResult.Unauthorized("No valid session")
    }
}

/**
 * The kind stays what the server saw, even when no entry passes: a receipt it read but could not use is not the same
 * as a photo with no receipt in it.
 */
internal fun ScanResponse.toDomain(): ScanReading {
    val read = entries.mapNotNull { it.toDomain() }
    val seen = ScanKind.entries.firstOrNull { it.name.lowercase() == kind } ?: ScanKind.UNKNOWN
    return ScanReading(
        kind = seen,
        merchant = merchant?.trim()?.ifEmpty { null },
        date = date?.toDateOrNull(),
        totalKyat = total?.takeIf { it > 0 },
        entries = read,
        notMoney = seen == ScanKind.UNKNOWN && reason == "not_money",
        reason = if (seen == ScanKind.UNKNOWN) ScanReason.entries.firstOrNull { it.name.lowercase() == reason } else null,
        currency = currency?.trim()?.take(12)?.ifEmpty { null }?.takeIf { seen == ScanKind.UNKNOWN && reason == "foreign_currency" },
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
        items = items.filter { it.amount > 0 && it.name.isNotBlank() }.map { it.toItem() },
    )
}

/**
 * The count and price each as read, when they make the line's amount; otherwise one unit at the line's amount (an older
 * server sends no count, and a discount can break the product).
 */
private fun ScanItemDto.toItem(): ScanItem {
    val name = name.trim()
    val each = unitPrice
    return if (quantity in 1..MAX_QUANTITY && each != null && each >= 0 && quantity * each == amount) {
        ScanItem(name, quantity, each)
    } else {
        ScanItem(name, amount)
    }
}

private const val MAX_QUANTITY = 999

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
