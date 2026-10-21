package app.budgetwise.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.BuildConfig
import app.budgetwise.R
import app.budgetwise.ui.common.SessionBanner
import app.budgetwise.ui.common.SessionNotice
import app.budgetwise.ui.common.relativeTime
import app.budgetwise.ui.common.rememberTickingNow
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.text.BreakIterator
import java.time.Instant

@Composable
fun SettingsRoute(onSignIn: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        now = rememberTickingNow(),
        onSyncNow = viewModel::syncNow,
        onSignOut = { viewModel.signOut(force = false) },
        onConfirmSignOut = { viewModel.signOut(force = true) },
        onDismissSignOut = viewModel::dismissSignOut,
        onMyanmarDigitsChange = viewModel::setMyanmarDigits,
        onSignIn = onSignIn,
        serverUrl = if (BuildConfig.DEBUG) BuildConfig.API_BASE_URL else null,
        version = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    now: Instant,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onConfirmSignOut: () -> Unit,
    onDismissSignOut: () -> Unit,
    onMyanmarDigitsChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    serverUrl: String?,
    version: String,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.account?.let { account ->
                item(key = "account") { AccountCard(account, state.sessionExpired, onSignIn) }
            }
            item(key = "sync-header") { SectionHeader(stringResource(R.string.settings_section_sync)) }
            item(key = "sync") { SyncCard(state, now, onSyncNow) }
            item(key = "display-header") { SectionHeader(stringResource(R.string.settings_section_display)) }
            item(key = "display") { DisplayCard(state.myanmarDigits, onMyanmarDigitsChange) }
            if (state.account != null) {
                item(key = "sign-out") { SignOutCard(state.signingOut, onSignOut) }
            }
            if (serverUrl != null) {
                item(key = "dev-header") { SectionHeader(stringResource(R.string.settings_section_developer)) }
                item(key = "server") {
                    SettingsCard {
                        ListItem(
                            leadingContent = { ListIcon(R.drawable.ic_dns) },
                            headlineContent = { Text(stringResource(R.string.settings_server)) },
                            supportingContent = { Text(serverUrl) },
                            colors = transparentListItemColors(),
                        )
                    }
                }
            }
            item(key = "version") {
                Text(
                    text = version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }

    state.signOutPrompt?.let { count ->
        AlertDialog(
            onDismissRequest = onDismissSignOut,
            iconContentColor = MaterialTheme.colorScheme.error,
            icon = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
            title = { Text(stringResource(R.string.settings_sign_out_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    pluralStringResource(R.plurals.settings_sign_out_message, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmSignOut,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_sign_out_anyway), style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSignOut) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    // Large corners, like the cards and grouped rows on the Transactions and Budgets tabs.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { content() }
}

@Composable
private fun transparentListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun ListIcon(@DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(icon), contentDescription = null, tint = tint)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun AccountCard(account: AccountInfo, expired: Boolean, onSignIn: () -> Unit) {
    SettingsCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial(account),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (account.name.isNotBlank()) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        account.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(account.email, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (expired) {
            SessionBanner(
                notice = SessionNotice.Expired,
                onSignIn = onSignIn,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

/** First user-perceived character of the name (a Burmese syllable keeps its marks), else of the email. */
private fun initial(account: AccountInfo): String {
    val source = account.name.trim().ifEmpty { account.email.trim() }
    if (source.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance().apply { setText(source) }
    return source.substring(0, it.next()).uppercase()
}

@Composable
private fun SyncCard(state: SettingsUiState, now: Instant, onSyncNow: () -> Unit) {
    val sync = state.sync
    val colors = MaterialTheme.colorScheme
    val headline = when (sync.phase) {
        SyncPhase.SYNCING -> stringResource(R.string.settings_sync_syncing)
        SyncPhase.WAITING -> stringResource(R.string.settings_sync_waiting)
        SyncPhase.SYNCED -> stringResource(R.string.settings_sync_synced)
        SyncPhase.OFFLINE -> stringResource(R.string.settings_sync_offline)
        SyncPhase.NEEDS_SIGN_IN -> stringResource(R.string.settings_sync_needs_sign_in)
        SyncPhase.REJECTED -> pluralStringResource(R.plurals.settings_sync_rejected, sync.rejectedCount, sync.rejectedCount)
        SyncPhase.NEVER -> stringResource(R.string.settings_sync_never)
    }
    val at = sync.at?.let { relativeTime(it, now) }
    val supporting = when (sync.phase) {
        SyncPhase.SYNCED -> at?.let { stringResource(R.string.settings_sync_last_synced, it) }
        SyncPhase.WAITING ->
            at?.let { stringResource(R.string.settings_sync_last_synced, it) } ?: stringResource(R.string.settings_sync_waiting_detail)
        SyncPhase.OFFLINE -> at?.let { stringResource(R.string.settings_sync_last_tried, it) }
        SyncPhase.REJECTED -> stringResource(R.string.settings_sync_rejected_detail)
        SyncPhase.SYNCING, SyncPhase.NEEDS_SIGN_IN, SyncPhase.NEVER -> null
    }

    SettingsCard {
        ListItem(
            leadingContent = {
                when (sync.phase) {
                    SyncPhase.SYNCING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    SyncPhase.WAITING -> ListIcon(R.drawable.ic_schedule)
                    SyncPhase.SYNCED -> ListIcon(R.drawable.ic_cloud_done, colors.primary)
                    SyncPhase.OFFLINE -> ListIcon(R.drawable.ic_cloud_off, colors.tertiary)
                    SyncPhase.NEEDS_SIGN_IN -> ListIcon(R.drawable.ic_lock_clock, colors.tertiary)
                    SyncPhase.REJECTED -> ListIcon(R.drawable.ic_sync_problem, colors.error)
                    SyncPhase.NEVER -> ListIcon(R.drawable.ic_sync)
                }
            },
            headlineContent = { Text(headline, style = MaterialTheme.typography.titleMedium) },
            supportingContent = supporting?.let { { Text(it) } },
            colors = transparentListItemColors(),
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
        ListItem(
            leadingContent = { ListIcon(R.drawable.ic_receipt_long) },
            headlineContent = {
                Text(
                    if (state.unsyncedCount > 0) {
                        pluralStringResource(R.plurals.settings_unsynced, state.unsyncedCount, state.unsyncedCount)
                    } else {
                        stringResource(R.string.settings_all_synced)
                    },
                )
            },
            colors = transparentListItemColors(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            FilledTonalButton(onClick = onSyncNow, enabled = state.canSyncNow) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_sync_now), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DisplayCard(myanmarDigits: Boolean, onChange: (Boolean) -> Unit) {
    SettingsCard {
        ListItem(
            // The whole row is the switch (one 48 dp target), read as "Myanmar digits, switch, on".
            modifier = Modifier.toggleable(value = myanmarDigits, role = Role.Switch, onValueChange = onChange),
            leadingContent = { ListIcon(R.drawable.ic_digits) },
            headlineContent = { Text(stringResource(R.string.settings_myanmar_digits)) },
            supportingContent = { Text(stringResource(R.string.settings_myanmar_digits_detail)) },
            trailingContent = { Switch(checked = myanmarDigits, onCheckedChange = null) },
            colors = transparentListItemColors(),
        )
    }
}

@Composable
private fun SignOutCard(signingOut: Boolean, onSignOut: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    SettingsCard {
        ListItem(
            modifier = Modifier.clickable(enabled = !signingOut, onClick = onSignOut),
            leadingContent = { ListIcon(R.drawable.ic_logout, error) },
            headlineContent = { Text(stringResource(R.string.settings_sign_out), color = error) },
            trailingContent = if (signingOut) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
            colors = transparentListItemColors(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    val now = Instant.parse("2026-10-21T10:00:00Z")
    BudgetWiseTheme {
        SettingsScreen(
            state = SettingsUiState(
                account = AccountInfo("Aye Aye", "aye@example.com"),
                sync = SyncSummary(SyncPhase.SYNCED, now.minusSeconds(300)),
                unsyncedCount = 2,
            ),
            now = now,
            onSyncNow = {},
            onSignOut = {},
            onConfirmSignOut = {},
            onDismissSignOut = {},
            onMyanmarDigitsChange = {},
            onSignIn = {},
            serverUrl = "http://127.0.0.1:9002/api/v1/",
            version = "Version 0.1.0 (1)",
        )
    }
}
