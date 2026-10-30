package app.budgetwise.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.tracked

/**
 * A tab's header: the big bold title on the left, the sync status, and the gear that opens Settings. It scrolls with
 * nothing and never collapses.
 */
@Composable
fun AppHeader(
    title: String,
    chip: SyncChip?,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(68.dp)
            .padding(start = 24.dp, end = 8.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderTitle(title, Modifier.weight(1f))
        if (chip != null) SyncChipView(chip, onClick = onChip)
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(
                Lucide.Settings,
                contentDescription = stringResource(R.string.nav_settings),
                tint = BudgetWiseTheme.colors.ink,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A screen's title: 26 bold with tight tracking, the redesign's one display moment above the content. */
@Composable
fun HeaderTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = BudgetWiseTheme.type.size26.bold.tracked(-0.02f),
        color = BudgetWiseTheme.colors.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A pushed screen's or the add form's header: back (or close) on the left, then the title. Without [title] it is the
 * back arrow alone, for screens that set their own big title below it (sign in, create account).
 */
@Composable
fun BackHeader(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Lucide.ArrowLeft,
    backLabel: String = stringResource(R.string.action_back),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(start = 8.dp, end = 24.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = backLabel, tint = BudgetWiseTheme.colors.ink, modifier = Modifier.size(24.dp))
        }
        if (title != null) HeaderTitle(title, Modifier.weight(1f))
    }
}
