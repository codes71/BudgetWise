package app.budgetwise.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.budgetwise.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * [value] in the phone's language, with that language's own order for the fields in [skeleton] (for example
 * "EEEEdMMMM" gives "Wednesday, 21 October" in English). Digits are ASCII unless [myanmarDigits] (the setting that
 * also governs amounts).
 */
@Composable
fun localizedDate(value: TemporalAccessor, skeleton: String, myanmarDigits: Boolean = false): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale, skeleton) { DateFormat.getBestDateTimePattern(locale, skeleton) }
    val text = remember(value, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(value) }
    return if (myanmarDigits) toMyanmarDigits(text) else text
}

/** The month's name on its own: "October". */
@Composable
fun monthName(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(month, locale) { DateTimeFormatter.ofPattern("LLLL", locale).format(month) }
}

/** "Today", "Yesterday", else the weekday and date (with the year when it is not this year). */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate, myanmarDigits: Boolean = false): String = when (date) {
    today -> stringResource(R.string.transactions_today)
    today.minusDays(1) -> stringResource(R.string.transactions_yesterday)
    else -> localizedDate(date, if (date.year == today.year) "EEEEdMMMM" else "EEEEdMMMMy", myanmarDigits)
}
