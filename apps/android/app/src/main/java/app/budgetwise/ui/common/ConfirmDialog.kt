package app.budgetwise.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked

/**
 * Asks before something that loses the user's work, such as leaving with changes: [title] and [detail], then
 * [dismissLabel] (stay, the safe choice, also a tap outside or back) and [confirmLabel] in coral.
 */
@Composable
fun ConfirmDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextAction(confirmLabel, onClick = onConfirm, color = colors.coralText) },
        dismissButton = { TextAction(dismissLabel, onClick = onDismiss) },
        title = { Text(title, style = type.size20.semibold.tracked(-0.01f).forContent(title), color = colors.ink) },
        text = { Text(detail, style = type.size15.forContent(detail), color = colors.muted) },
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.raised,
        titleContentColor = colors.ink,
        textContentColor = colors.muted,
    )
}
