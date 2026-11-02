package app.budgetwise.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.budgetwise.R
import app.budgetwise.nlu.TxType

/** The largest amount the API takes: 2^53 - 1, the largest integer a JSON number holds exactly. */
const val MAX_KYAT: Long = 9_007_199_254_740_991L

/** U+2212, wider than a hyphen and drawn by both bundled fonts. */
private const val MINUS = '−'
private const val MYANMAR_ZERO = '၀'

/**
 * "5,000", or "၅,၀၀၀" with [myanmarDigits]. Thousands are always grouped with commas, whatever the phone's locale
 * (the Burmese locale would otherwise switch digits on its own), so an amount reads the same everywhere.
 */
fun groupDigits(amount: Long, myanmarDigits: Boolean = false): String {
    val digits = amount.toString().removePrefix("-")
    val grouped = buildString(digits.length + digits.length / 3 + 1) {
        if (amount < 0) append(MINUS)
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) append(',')
            append(c)
        }
    }
    return if (myanmarDigits) toMyanmarDigits(grouped) else grouped
}

/** "5,000 Ks". A negative amount (a balance) gets a minus sign. */
fun formatKyat(amount: Long, myanmarDigits: Boolean = false): String = "${groupDigits(amount, myanmarDigits)} Ks"

/** An entry's amount with its direction written out, so color is never the only cue: "−5,000 Ks", "+5,000 Ks". */
fun formatSignedKyat(amount: Long, type: TxType, myanmarDigits: Boolean = false): String {
    val sign = if (type == TxType.EXPENSE) MINUS else '+'
    return "$sign${formatKyat(amount, myanmarDigits)}"
}

/** A net amount (a day's total, a change) with its sign written out: "+841,800", "−4,100", "0". */
fun signedDigits(amount: Long, myanmarDigits: Boolean = false): String =
    if (amount > 0) "+${groupDigits(amount, myanmarDigits)}" else groupDigits(amount, myanmarDigits)

fun toMyanmarDigits(text: String): String =
    String(CharArray(text.length) { i -> text[i].let { if (it in '0'..'9') MYANMAR_ZERO + (it - '0') else it } })

/** Only the digits of [text], with Myanmar digits (a Burmese keyboard) turned into ASCII ones. */
fun asciiDigits(text: String): String = buildString {
    for (c in text) {
        when (c) {
            in '0'..'9' -> append(c)
            in MYANMAR_ZERO..'၉' -> append('0' + (c - MYANMAR_ZERO))
        }
    }
}

/**
 * What an amount field keeps of [text] as typed: its digits (Myanmar ones made ASCII, grouping commas dropped) without
 * leading zeros, or null when the field must refuse it and keep what it had (a decimal point, or above [MAX_KYAT]).
 */
fun typedKyatDigits(text: String): String? {
    if ('.' in text) return null
    val raw = asciiDigits(text)
    val digits = if (raw.isEmpty()) "" else raw.trimStart('0').ifEmpty { "0" }
    if (digits.length > MAX_KYAT_DIGITS || (digits.isNotEmpty() && digits.toLong() > MAX_KYAT)) return null
    return digits
}

private val MAX_KYAT_DIGITS = MAX_KYAT.toString().length

/**
 * What TalkBack reads for an entry's amount: "5,000 kyat spent" or "5,000 kyat received", never "minus 5,000 Ks".
 * ASCII digits, which every speech engine reads, also when the screen shows Myanmar digits.
 */
@Composable
fun spokenAmount(amount: Long, type: TxType): String =
    stringResource(if (type == TxType.EXPENSE) R.string.kyat_spent else R.string.kyat_received, groupDigits(amount))

/** "5,000 kyat", for TalkBack. */
@Composable
fun spokenKyat(amount: Long): String = stringResource(R.string.kyat_amount, groupDigits(amount))

/**
 * Shows a field that holds plain digits ("5000") grouped ("5,000"), in Myanmar digits when asked. The field's value
 * stays plain digits, so the cursor maps around each inserted comma.
 */
class KyatGroupingTransformation(private val myanmarDigits: Boolean = false) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return TransformedText(text, OffsetMapping.Identity)
        val grouped = groupDigits(digits.toLong(), myanmarDigits)
        val n = digits.length
        // A comma goes before original index i when (n - i) is a multiple of 3.
        fun commasBefore(offset: Int) = (1 until offset.coerceIn(0, n + 1)).count { i -> i < n && (n - i) % 3 == 0 }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + commasBefore(offset)

            override fun transformedToOriginal(offset: Int): Int {
                var original = 0
                while (original < n && originalToTransformed(original + 1) <= offset) original++
                return original
            }
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }

    override fun equals(other: Any?) = other is KyatGroupingTransformation && other.myanmarDigits == myanmarDigits

    override fun hashCode() = myanmarDigits.hashCode()
}
