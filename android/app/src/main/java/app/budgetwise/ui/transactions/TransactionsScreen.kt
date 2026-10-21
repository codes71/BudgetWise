package app.budgetwise.ui.transactions

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.budgetwise.R
import app.budgetwise.ui.common.PlaceholderScreen
import app.budgetwise.ui.common.SessionBanner
import app.budgetwise.ui.common.SessionNotice

/**
 * Placeholder until Task 11b replaces the body with the list. The parameters are what the shell gives the real
 * screen: the session banner (expired or expiring soon) with its sign-in action, and the Add FAB, which opens `manual`.
 */
@Composable
fun TransactionsScreen(
    sessionNotice: SessionNotice?,
    onSignIn: () -> Unit,
    onAddManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = stringResource(R.string.transactions_title),
        message = stringResource(R.string.transactions_placeholder),
        icon = R.drawable.ic_receipt_long,
        modifier = modifier,
        banner = sessionNotice?.let { notice -> { SessionBanner(notice, onSignIn) } },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddManual,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.action_add), style = MaterialTheme.typography.labelLarge) },
            )
        },
    )
}
