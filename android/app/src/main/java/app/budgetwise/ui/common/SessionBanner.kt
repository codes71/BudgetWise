package app.budgetwise.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.data.auth.SessionState
import java.time.Duration
import java.time.Instant

/** Why the transactions screen asks the user to sign in again. */
sealed interface SessionNotice {
    /** Token gone: local data stays visible, sync is paused until sign-in. */
    data object Expired : SessionNotice

    /** The token ends within three days ([daysLeft] rounded up, at least 1). */
    data class ExpiringSoon(val daysLeft: Int) : SessionNotice
}

fun sessionNoticeFor(session: SessionState, now: Instant): SessionNotice? = when (session) {
    SessionState.Expired -> SessionNotice.Expired
    is SessionState.SignedIn -> if (session.expiringSoon) SessionNotice.ExpiringSoon(daysLeft(session.expiresAt, now)) else null
    // Signed in on the phone: the snackbar after sign-in already said the entries sync once online.
    is SessionState.SignedInOffline, SessionState.SignedOut -> null
}

private fun daysLeft(expiresAt: Instant, now: Instant): Int {
    val left = Duration.between(now, expiresAt)
    if (left <= Duration.ZERO) return 1
    val days = left.toDays()
    return (if (left > Duration.ofDays(days)) days + 1 else days).toInt()
}

@Composable
fun SessionBanner(notice: SessionNotice, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // Expired is the coral attention color; the early warning is the calmer blue. Text uses the matching on-roles.
    val (container, content) = when (notice) {
        SessionNotice.Expired -> colors.tertiaryContainer to colors.onTertiaryContainer
        is SessionNotice.ExpiringSoon -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val message = when (notice) {
        SessionNotice.Expired -> stringResource(R.string.session_expired_message)
        is SessionNotice.ExpiringSoon ->
            pluralStringResource(R.plurals.session_expiring_message, notice.daysLeft, notice.daysLeft)
    }
    val icon = if (notice == SessionNotice.Expired) R.drawable.ic_lock_clock else R.drawable.ic_schedule
    Surface(modifier = modifier, color = container, contentColor = content, shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onSignIn, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                Text(stringResource(R.string.session_sign_in), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
