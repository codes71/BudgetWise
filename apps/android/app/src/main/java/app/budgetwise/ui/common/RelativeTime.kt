package app.budgetwise.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import app.budgetwise.R
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** "just now" under a minute (or for a time ahead of the clock), else the platform's localized "5 minutes ago". */
@Composable
fun relativeTime(at: Instant, now: Instant): String {
    val justNow = stringResource(R.string.time_just_now)
    if (Duration.between(at, now) < Duration.ofMinutes(1)) return justNow
    return DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** The current time, refreshed every [period] while on screen, so relative times do not go stale. */
@Composable
fun rememberTickingNow(period: Duration = Duration.ofSeconds(30)): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(period.toMillis())
            value = Instant.now()
        }
    }
    return now
}
