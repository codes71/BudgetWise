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
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.semibold

/**
 * A tab's header: a small fixed 56 dp bar with the title on the left, the sync chip, and the gear that opens Settings.
 * Never a tall collapsing title.
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
            .height(56.dp)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = BudgetWiseTheme.type.size20.semibold,
            color = BudgetWiseTheme.colors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
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

/** A pushed screen's or the add form's header: back (or close) on the left, then the title. */
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
            .height(56.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = backLabel, tint = BudgetWiseTheme.colors.ink, modifier = Modifier.size(22.dp))
        }
        if (title != null) {
            Text(
                text = title,
                style = BudgetWiseTheme.type.size20.semibold,
                color = BudgetWiseTheme.colors.ink,
                maxLines = 1,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}
