package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.VoiceEntryDto
import app.budgetwise.data.net.VoiceParseBody
import app.budgetwise.data.net.VoiceParseResponse
import app.budgetwise.data.net.VoiceQuestionDto
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import app.budgetwise.nlu.TxType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/** One entry read from what was said, for the user to check before it is saved. */
data class VoiceEntry(
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    /** Nothing was said about when, so [date] is today. */
    val dateGuessed: Boolean,
)

enum class VoiceMetric { LIST, TOTAL, OVER_BUDGET }

/**
 * A question about the user's own spending, answered on the phone from Room: [from] to [to], both included and in
 * order. A null [category] is all spending.
 */
data class VoiceQuestion(val metric: VoiceMetric, val category: String?, val from: LocalDate, val to: LocalDate)

/** What the server made of the words. [heard] is what it understood was said, in Burmese. */
sealed interface VoiceParse {
    val heard: String

    data class Add(override val heard: String, val entries: List<VoiceEntry>) : VoiceParse

    data class Question(override val heard: String, val question: VoiceQuestion) : VoiceParse

    /** Not about money, too unclear, or an amount was missing. */
    data class Other(override val heard: String) : VoiceParse
}

/**
 * Sends the speech models' transcripts (text only) to `POST /voice/parse` with the active account's token. A reply the
 * phone cannot use is never filled in: a bad entry is dropped, and a reply with nothing left is [VoiceParse.Other].
 */
@Singleton
class VoiceRepository @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager,
) {
    suspend fun parse(transcripts: List<String>): ApiResult<VoiceParse> {
        val texts = transcripts.map { it.trim().take(MAX_TRANSCRIPT) }.filter { it.isNotEmpty() }.distinct().take(MAX_TRANSCRIPTS)
        require(texts.isNotEmpty()) { "nothing was heard" }
        val owner = session.activeUserId.value ?: return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        return when (val r = safeApi { api.parseVoice(authorization(token), VoiceParseBody(texts)) }) {
            is ApiResult.Ok -> ApiResult.Ok(r.v.toDomain())
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    companion object {
        /** The server's limits: up to three transcripts of up to 500 characters. */
        const val MAX_TRANSCRIPTS = 3
        const val MAX_TRANSCRIPT = 500
        private val NOT_SIGNED_IN = ApiResult.Unauthorized("No valid session")
    }
}

internal fun VoiceParseResponse.toDomain(): VoiceParse {
    val heard = heard.trim()
    return when (intent) {
        "add" -> entries.mapNotNull { it.toDomain() }.takeIf { it.isNotEmpty() }?.let { VoiceParse.Add(heard, it) }
        "question" -> question?.toDomain()?.let { VoiceParse.Question(heard, it) }
        else -> null
    } ?: VoiceParse.Other(heard)
}

private fun VoiceEntryDto.toDomain(): VoiceEntry? {
    val txType = TxType.entries.firstOrNull { it.name.lowercase() == type } ?: return null
    if (!amount.isFinite() || amount < 1.0 || amount > MAX_AMOUNT) return null
    val day = date.toDateOrNull() ?: return null
    val name = category.trim().ifEmpty { return null }
    return VoiceEntry(
        type = txType,
        amountKyat = BigDecimal(amount).setScale(0, RoundingMode.HALF_UP).toLong(),
        category = if (txType == TxType.INCOME) CategoryRepository.INCOME else name,
        description = description.trim().ifEmpty { name }.take(TransactionRepository.MAX_DESCRIPTION),
        date = day,
        dateGuessed = dateGuessed,
    )
}

private fun VoiceQuestionDto.toDomain(): VoiceQuestion? {
    val kind = VoiceMetric.entries.firstOrNull { it.name.lowercase() == metric } ?: return null
    val start = from.toDateOrNull() ?: return null
    val end = to.toDateOrNull() ?: return null
    return VoiceQuestion(kind, category?.trim()?.ifEmpty { null }, minOf(start, end), maxOf(start, end))
}

private fun String.toDateOrNull(): LocalDate? = try {
    LocalDate.parse(this)
} catch (_: DateTimeParseException) {
    null
}

private const val MAX_AMOUNT = 9_007_199_254_740_991.0 // 2^53 - 1, as for transactions
