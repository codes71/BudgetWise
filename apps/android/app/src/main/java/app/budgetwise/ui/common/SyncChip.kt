package app.budgetwise.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.sync.SyncStatus
import app.budgetwise.ui.settings.SyncPhase
import app.budgetwise.ui.settings.syncSummary
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.medium

/** What the header's sync chip says. It is the only sync signal on a screen. */
enum class SyncChip { SYNCED, SYNCING, OFFLINE, NOT_SYNCED, SIGN_IN, COULD_NOT_SYNC }

/**
 * The chip for the shell's state, or null when signed out (no header shows one then). A session that needs signing in
 * outranks everything, then entries the server refused (they need the user), then no internet; otherwise the chip
 * follows the sync summary Settings shows ([syncSummary]): queued work is about to run and reads as syncing, and a
 * process that has not synced yet reads as synced only when nothing waits.
 */
fun syncChipFor(session: SessionState, online: Boolean, status: SyncStatus, queued: Boolean, unsynced: Int): SyncChip? {
    if (session == SessionState.SignedOut) return null
    val summary = syncSummary(status, queued, expired = session == SessionState.Expired)
    return when {
        summary.phase == SyncPhase.NEEDS_SIGN_IN -> SyncChip.SIGN_IN
        summary.phase == SyncPhase.REJECTED -> SyncChip.COULD_NOT_SYNC
        !online -> SyncChip.OFFLINE
        else -> when (summary.phase) {
            SyncPhase.SYNCING, SyncPhase.WAITING -> SyncChip.SYNCING
            SyncPhase.OFFLINE -> SyncChip.NOT_SYNCED
            SyncPhase.SYNCED -> SyncChip.SYNCED
            SyncPhase.NEVER -> if (unsynced > 0) SyncChip.NOT_SYNCED else SyncChip.SYNCED
            SyncPhase.NEEDS_SIGN_IN, SyncPhase.REJECTED -> error("handled above")
        }
    }
}

/**
 * The header's chip: a 32 dp outlined pill with an icon and one word. Sign in opens sign-in; every other variant opens
 * Settings, which says more (which entries were refused, when it last synced).
 */
@Composable
fun SyncChipView(chip: SyncChip, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val (content: Color, border: Color) = when (chip) {
        SyncChip.SYNCED, SyncChip.SYNCING -> colors.muted to colors.chipBorder
        SyncChip.OFFLINE -> colors.blue to colors.blueBorder
        SyncChip.NOT_SYNCED, SyncChip.SIGN_IN -> colors.coralText to colors.coralBorder
        SyncChip.COULD_NOT_SYNC -> colors.error to colors.errorBorder
    }
    val label = stringResource(
        when (chip) {
            SyncChip.SYNCED -> R.string.chip_synced
            SyncChip.SYNCING -> R.string.chip_syncing
            SyncChip.OFFLINE -> R.string.chip_offline
            SyncChip.NOT_SYNCED -> R.string.chip_not_synced
            SyncChip.SIGN_IN -> R.string.chip_sign_in
            SyncChip.COULD_NOT_SYNC -> R.string.chip_could_not_sync
        },
    )
    val spoken = stringResource(
        when (chip) {
            SyncChip.SYNCED -> R.string.chip_synced_description
            SyncChip.SYNCING -> R.string.chip_syncing_description
            SyncChip.OFFLINE -> R.string.chip_offline_description
            SyncChip.NOT_SYNCED -> R.string.chip_not_synced_description
            SyncChip.SIGN_IN -> R.string.chip_sign_in_description
            SyncChip.COULD_NOT_SYNC -> R.string.chip_could_not_sync_description
        },
    )
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(CircleShape)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (chip) {
            SyncChip.SYNCING -> Spinner(size = 14.dp)
            else -> Icon(
                imageVector = when (chip) {
                    SyncChip.SYNCED -> Lucide.CloudCheck
                    SyncChip.OFFLINE -> Lucide.CloudOff
                    SyncChip.NOT_SYNCED -> Lucide.CloudAlert
                    SyncChip.SIGN_IN -> Lucide.Lock
                    else -> Lucide.CircleAlert
                },
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(label, style = BudgetWiseTheme.type.size13.medium, color = content, maxLines = 1)
    }
}
