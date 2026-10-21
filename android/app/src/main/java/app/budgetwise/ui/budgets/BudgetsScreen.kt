package app.budgetwise.ui.budgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.budgetwise.R
import app.budgetwise.ui.common.PlaceholderScreen

/** Placeholder until Task 11b. */
@Composable
fun BudgetsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.budgets_title),
        message = stringResource(R.string.budgets_placeholder),
        icon = R.drawable.ic_pie_chart,
        modifier = modifier,
    )
}
