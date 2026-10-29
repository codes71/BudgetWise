package app.budgetwise.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold

/** What a notice is about; it picks the icon's color. Error and attention are read out at once. */
enum class NoticeTone { INFO, ATTENTION, ERROR, SUCCESS, NEUTRAL }

@Composable
private fun NoticeTone.color(): Color {
    val colors = BudgetWiseTheme.colors
    return when (this) {
        NoticeTone.INFO -> colors.blue
        NoticeTone.ATTENTION -> colors.coralText
        NoticeTone.ERROR -> colors.error
        NoticeTone.SUCCESS -> colors.accent
        NoticeTone.NEUTRAL -> colors.muted
    }
}

private val NoticeShape = RoundedCornerShape(16.dp)

/**
 * The one card for every pop-up, snackbar, banner and form alert: [title] says what happened, [detail] (muted) what to
 * do or what is safe, and [actionLabel] is an optional text action at the end. [floating] adds the shadow it has over
 * a list. Shown only when the user's own action could not finish, or to confirm one with Undo; never for what the
 * header's sync chip already says.
 */
@Composable
fun Notice(
    tone: NoticeTone,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    floating: Boolean = false,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val hasAction = actionLabel != null && onAction != null
    val urgent = tone == NoticeTone.ERROR || tone == NoticeTone.ATTENTION
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (floating) Modifier.shadow(12.dp, NoticeShape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(NoticeShape)
            .background(colors.notice)
            .border(1.dp, colors.noticeBorder, NoticeShape)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = if (hasAction) 8.dp else 16.dp)
            .semantics(mergeDescendants = !hasAction) {
                liveRegion = if (urgent) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.size15.semibold, color = colors.ink)
            if (detail != null) {
                Text(detail, style = type.size13.forContent(detail), color = colors.muted)
            }
        }
        if (hasAction) TextAction(actionLabel!!, onClick = onAction!!, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/** A [Notice] shown through a [SnackbarHostState], which keeps its timing, queueing and accessibility durations. */
class NoticeVisuals(
    val tone: NoticeTone,
    val icon: ImageVector,
    val title: String,
    val detail: String? = null,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
) : SnackbarVisuals {
    override val message: String get() = title
    override val withDismissAction: Boolean get() = false
}

/**
 * Shows the host's current notice floating at the bottom of the screen (12 dp from the sides and from what is under
 * it: the bottom bar, the pinned footer or the screen edge).
 */
@Composable
fun NoticeHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier.padding(12.dp)) { data: SnackbarData ->
        val v = data.visuals as? NoticeVisuals
        Notice(
            tone = v?.tone ?: NoticeTone.NEUTRAL,
            icon = v?.icon ?: Lucide.Info,
            title = data.visuals.message,
            detail = v?.detail,
            actionLabel = data.visuals.actionLabel,
            onAction = data::performAction,
            floating = true,
        )
    }
}
