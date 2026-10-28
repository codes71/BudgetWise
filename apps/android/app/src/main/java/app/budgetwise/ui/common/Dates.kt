package app.budgetwise.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.budgetwise.R
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * [value] in the phone's language, with that language's own order for the fields in [skeleton] (for example
 * "EEEEdMMMM" gives "Wednesday, 21 October" in English). Digits stay ASCII.
 */
@Composable
fun localizedDate(value: TemporalAccessor, skeleton: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale, skeleton) { DateFormat.getBestDateTimePattern(locale, skeleton) }
    return remember(value, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(value) }
}

/** "October 2026". */
@Composable
fun monthLabel(month: YearMonth): String = localizedDate(month, "MMMMy")

/** "Today", "Yesterday", else the weekday and date (with the year when it is not this year). */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.transactions_today)
    today.minusDays(1) -> stringResource(R.string.transactions_yesterday)
    else -> localizedDate(date, if (date.year == today.year) "EEEEdMMMM" else "EEEEdMMMMy")
}

/** The material date picker works in UTC midnights. */
fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun pickerMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
