package app.budgetwise.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.BuildConfig
import app.budgetwise.R
import app.budgetwise.asr.ModelState
import app.budgetwise.asr.VoicePack
import app.budgetwise.ui.voice.DownloadProgress
import app.budgetwise.ui.voice.WifiWaitNotice
import app.budgetwise.ui.voice.rememberAskNotifications
import app.budgetwise.ui.voice.voiceSize
import app.budgetwise.currentAppLanguage
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.KitSwitch
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.Spinner
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.relativeTime
import app.budgetwise.ui.common.rememberTickingNow
import app.budgetwise.ui.onboarding.ownName
import app.budgetwise.ui.onboarding.scriptStyle
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import java.text.BreakIterator
import java.time.Instant

@Composable
fun SettingsRoute(
    onSignIn: () -> Unit,
    onLanguage: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askNotifications = rememberAskNotifications()
    SettingsScreen(
        state = state,
        now = rememberTickingNow(),
        onBack = onBack,
        onSyncNow = viewModel::syncNow,
        onSignOut = viewModel::signOut,
        onMyanmarDigitsChange = viewModel::setMyanmarDigits,
        onDownloadVoice = {
            askNotifications()
            viewModel.downloadVoice()
        },
        onUseMobileData = {
            askNotifications()
            viewModel.useMobileDataForVoice()
        },
        onRemoveVoice = viewModel::removeVoice,
        onCloudParseChange = viewModel::setCloudParse,
        onSignIn = onSignIn,
        onLanguage = onLanguage,
        version = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    )
}

/** Opened from the gear in a tab's header, on top of it: a back arrow and no bottom bar. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    now: Instant,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onMyanmarDigitsChange: (Boolean) -> Unit,
    onDownloadVoice: () -> Unit,
    onUseMobileData: () -> Unit,
    onRemoveVoice: () -> Unit,
    onCloudParseChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onLanguage: () -> Unit,
    version: String,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    Column(modifier.fillMaxSize().background(colors.ground)) {
        BackHeader(stringResource(R.string.settings_title), onBack)
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            ) {
                state.account?.let { account -> item(key = "account") { AccountCard(account) } }
                item(key = "sync") {
                    Section(stringResource(R.string.settings_section_sync)) { SyncCard(state, now, onSyncNow, onSignIn) }
                }
                item(key = "voice") {
                    Section(stringResource(R.string.settings_section_voice)) {
                        VoiceCard(state, onDownloadVoice, onUseMobileData, onRemoveVoice, onCloudParseChange)
                    }
                }
                item(key = "display") {
                    Section(stringResource(R.string.settings_section_display)) { DisplayCard(state.myanmarDigits, onMyanmarDigitsChange, onLanguage) }
                }
                if (state.account != null) {
                    item(key = "sign-out") { SignOutCard(state.signingOut, onSignOut) }
                }
                item(key = "about") {
                    Column {
                        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                        SettingsRow(Lucide.Info, colors.muted) { Text(version, style = BudgetWiseTheme.type.size15, color = colors.muted) }
                    }
                }
            }
            if (state.signOutFailed) {
                Notice(
                    tone = NoticeTone.ERROR,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.settings_sign_out_failed),
                    detail = stringResource(R.string.settings_sign_out_failed_detail),
                    actionLabel = stringResource(R.string.action_try_again),
                    onAction = onSignOut,
                    floating = true,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
    }
}

/** A section's rows, straight on the ground. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

/** A section's small caps heading over a hairline, then its rows. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 10.dp).semantics { heading() })
        HorizontalDivider(thickness = 1.dp, color = BudgetWiseTheme.colors.hairline)
        content()
    }
}

/** A row in a settings card: a 24 dp icon slot, then the row's text. */
@Composable
private fun SettingsRow(
    icon: ImageVector?,
    tint: Color,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        content()
    }
}

@Composable
private fun AccountCard(account: AccountInfo) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val initials = initials(account)
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(colors.sageContainer).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, style = type.size18.semibold.forContent(initials), color = colors.onSageContainer)
        }
        Column(Modifier.weight(1f)) {
            if (account.name.isNotBlank()) {
                Text(account.name, style = type.size18.semibold.forContent(account.name), color = colors.ink)
                Text(account.email, style = type.size13, color = colors.muted)
            } else {
                Text(account.email, style = type.size18.semibold, color = colors.ink)
            }
        }
    }
}

/**
 * The first user-perceived character (a Burmese syllable keeps its marks) of the name's first two words, else of the
 * email: "Aye Chan" is "AC".
 */
internal fun initials(account: AccountInfo): String {
    fun first(word: String): String {
        val it = BreakIterator.getCharacterInstance().apply { setText(word) }
        return word.substring(0, it.next())
    }
    val words = account.name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return account.email.trim().takeIf { it.isNotEmpty() }?.let(::first)?.uppercase().orEmpty()
    return words.take(2).joinToString("") { first(it) }.uppercase()
}

@Composable
private fun SyncCard(state: SettingsUiState, now: Instant, onSyncNow: () -> Unit, onSignIn: () -> Unit) {
    val sync = state.sync
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
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
        SyncPhase.SYNCED, SyncPhase.SYNCING -> at?.let { stringResource(R.string.settings_sync_last_synced, it) }
        SyncPhase.WAITING ->
            at?.let { stringResource(R.string.settings_sync_last_synced, it) } ?: stringResource(R.string.settings_sync_waiting_detail)
        SyncPhase.OFFLINE -> at?.let { stringResource(R.string.settings_sync_last_tried, it) }
        SyncPhase.REJECTED -> stringResource(R.string.settings_sync_rejected_detail)
        SyncPhase.NEEDS_SIGN_IN -> stringResource(R.string.settings_sync_needs_sign_in_detail)
        SyncPhase.NEVER -> null
    }
    val (icon, tint) = when (sync.phase) {
        SyncPhase.SYNCING -> null to colors.accent
        SyncPhase.WAITING -> Lucide.CloudOff to colors.blue
        SyncPhase.SYNCED -> Lucide.CloudCheck to colors.accent
        SyncPhase.OFFLINE -> Lucide.CloudAlert to colors.coralText
        SyncPhase.NEEDS_SIGN_IN -> Lucide.Lock to colors.coralText
        SyncPhase.REJECTED -> Lucide.TriangleAlert to colors.error
        SyncPhase.NEVER -> Lucide.Refresh to colors.muted
    }

    Card {
        SettingsRow(
            icon = icon,
            tint = tint,
            modifier = Modifier.heightIn(min = 64.dp).semantics(mergeDescendants = true) {},
            leading = if (sync.phase == SyncPhase.SYNCING) ({ Spinner(size = 20.dp) }) else null,
        ) {
            Column(Modifier.weight(1f)) {
                Text(headline, style = type.size16.medium, color = colors.ink)
                if (supporting != null) Text(supporting, style = type.size13, color = colors.muted)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        // Rejected entries count as unsynced too; this line is about every other entry.
        val waiting = if (sync.phase == SyncPhase.REJECTED) state.unsyncedCount - sync.rejectedCount else state.unsyncedCount
        SettingsRow(
            icon = if (waiting > 0) Lucide.Clock else Lucide.Check,
            tint = if (waiting > 0) colors.blue else colors.accent,
        ) {
            Text(
                text = when {
                    waiting > 0 -> pluralStringResource(R.plurals.settings_unsynced, waiting, waiting)
                    sync.phase == SyncPhase.REJECTED -> stringResource(R.string.settings_all_other_synced)
                    else -> stringResource(R.string.settings_all_synced)
                },
                style = type.size15,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
            if (state.sessionExpired) {
                PrimaryButton(stringResource(R.string.session_sign_in), onSignIn, Modifier.fillMaxWidth(), height = 48.dp)
            } else {
                SecondaryButton(
                    text = stringResource(R.string.settings_sync_now),
                    onClick = onSyncNow,
                    enabled = state.canSyncNow,
                    icon = Lucide.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Voice: whether the models are on the phone (with Download, Cancel or Remove), and "Understand what I say", the switch
 * that lets voice send the words it hears.
 */
@Composable
private fun VoiceCard(
    state: SettingsUiState,
    onDownload: () -> Unit,
    onUseMobileData: () -> Unit,
    onRemove: () -> Unit,
    onCloudParseChange: (Boolean) -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val size = voiceSize(VoicePack.downloadBytes, state.myanmarDigits)
    val voice = state.voice
    Card {
        SettingsRow(icon = Lucide.Mic, tint = if (voice is ModelState.Ready) colors.accent else colors.muted, modifier = Modifier.heightIn(min = 64.dp)) {
            Column(Modifier.weight(1f)) {
                val title = when (voice) {
                    is ModelState.Ready -> R.string.settings_voice_ready
                    is ModelState.Downloading -> R.string.voice_downloading_title
                    is ModelState.Failed -> R.string.settings_voice_failed
                    ModelState.Missing -> R.string.settings_voice_missing
                }
                Text(stringResource(title), style = type.size16.medium, color = colors.ink)
                when (voice) {
                    is ModelState.Ready -> Text(stringResource(R.string.settings_voice_on_phone, size), style = type.size13, color = colors.muted)
                    // While it waits for Wi-Fi, the notice under the row says so and offers mobile data.
                    is ModelState.Downloading -> DownloadProgress(voice.progress, state.myanmarDigits, Modifier.padding(top = 6.dp))
                    is ModelState.Failed -> Text(
                        stringResource(if (voice.outOfStorage) R.string.voice_download_no_room else R.string.voice_download_failed, size),
                        style = type.size13,
                        color = colors.coralText,
                    )
                    ModelState.Missing -> Text(stringResource(R.string.settings_voice_missing_detail, size), style = type.size13, color = colors.muted)
                }
            }
            when (voice) {
                is ModelState.Ready -> TextAction(stringResource(R.string.settings_voice_remove), onRemove, color = colors.coralText)
                is ModelState.Downloading -> TextAction(stringResource(R.string.action_cancel), onRemove, color = colors.coralText)
                is ModelState.Failed -> TextAction(stringResource(R.string.action_try_again), onDownload)
                ModelState.Missing -> TextAction(stringResource(R.string.settings_voice_download), onDownload)
            }
        }
        if (voice is ModelState.Downloading && voice.waiting) {
            WifiWaitNotice(state.myanmarDigits, onUseMobileData, Modifier.padding(bottom = 12.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        SettingsRow(
            icon = Lucide.Cloud,
            tint = colors.muted,
            // The whole row is the switch (one 48 dp target), as for Myanmar digits.
            modifier = Modifier.heightIn(min = 64.dp).toggleable(value = state.cloudParse, role = Role.Switch, onValueChange = onCloudParseChange),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_cloud_parse), style = type.size16.medium, color = colors.ink)
                Text(stringResource(R.string.settings_cloud_parse_detail), style = type.size13, color = colors.muted)
            }
            KitSwitch(checked = state.cloudParse)
        }
    }
}

@Composable
private fun DisplayCard(myanmarDigits: Boolean, onChange: (Boolean) -> Unit, onLanguage: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Card {
        // The language's name is written in its own script, whatever the app's language is.
        val language = currentAppLanguage()
        SettingsRow(
            icon = Lucide.Globe,
            tint = colors.muted,
            modifier = Modifier.heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onLanguage),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_language), style = type.size16.medium, color = colors.ink)
                Text(language.ownName(), style = scriptStyle(language, 13), color = colors.muted)
            }
            Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        SettingsRow(
            icon = null,
            tint = colors.muted,
            // The whole row is the switch (one 48 dp target), read as "Myanmar digits, switch, on".
            modifier = Modifier.heightIn(min = 64.dp).toggleable(value = myanmarDigits, role = Role.Switch, onValueChange = onChange),
            leading = { Text(stringResource(R.string.settings_digits_glyph), style = type.size12.bold, color = colors.muted, maxLines = 1) },
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_myanmar_digits), style = type.size16.medium, color = colors.ink)
                val detail = stringResource(R.string.settings_myanmar_digits_detail)
                Text(detail, style = type.size13.forContent(detail), color = colors.muted)
            }
            KitSwitch(checked = myanmarDigits)
        }
    }
}

@Composable
private fun SignOutCard(signingOut: Boolean, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        SettingsRow(
            icon = Lucide.LogOut,
            tint = colors.coralText,
            modifier = Modifier.clickable(enabled = !signingOut, role = Role.Button, onClick = onSignOut),
        ) {
            Text(
                stringResource(R.string.settings_sign_out),
                style = BudgetWiseTheme.type.size16.medium,
                color = colors.coralText,
                modifier = Modifier.weight(1f),
            )
            if (signingOut) Spinner(size = 20.dp)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    val now = Instant.parse("2026-10-21T10:00:00Z")
    BudgetWiseTheme {
        SettingsScreen(
            state = SettingsUiState(
                account = AccountInfo("Aye Chan", "aye.chan@example.com"),
                sync = SyncSummary(SyncPhase.SYNCED, now.minusSeconds(120)),
                unsyncedCount = 0,
            ),
            now = now,
            onBack = {},
            onSyncNow = {},
            onSignOut = {},
            onMyanmarDigitsChange = {},
            onDownloadVoice = {},
            onUseMobileData = {},
            onRemoveVoice = {},
            onCloudParseChange = {},
            onSignIn = {},
            onLanguage = {},
            version = "Version 1.0 (1)",
        )
    }
}
