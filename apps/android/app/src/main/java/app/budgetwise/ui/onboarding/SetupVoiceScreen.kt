package app.budgetwise.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.asr.VoicePack
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.voice.VoiceSetupUiState
import app.budgetwise.ui.voice.VoiceSetupViewModel
import app.budgetwise.ui.voice.VoiceUnsupportedDialog
import app.budgetwise.ui.voice.WifiNotice
import app.budgetwise.ui.voice.rememberAskNotifications
import app.budgetwise.ui.voice.voiceSize

/**
 * The last onboarding step: offer voice. Download starts the 1.1 GB download (it waits for Wi-Fi) and goes on to the
 * app at once; Not now goes on without it. A phone that already has voice, or is downloading it (another account set
 * it up), skips the step. A phone without the memory for voice gets a small notice saying so instead, and goes on.
 */
@Composable
fun SetupVoiceRoute(onDone: () -> Unit, viewModel: VoiceSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    if (!state.supported) {
        Box(Modifier.fillMaxSize().background(BudgetWiseTheme.colors.ground)) { VoiceUnsupportedDialog(onDismiss = onDone) }
        return
    }
    val alreadySetUp = remember { state.setUp }
    LaunchedEffect(alreadySetUp) { if (alreadySetUp) done() }
    val askNotifications = rememberAskNotifications()
    SetupVoiceScreen(
        state = state,
        onDownload = {
            askNotifications()
            viewModel.download(showProgress = false)
            onDone()
        },
        onUseMobileData = {
            askNotifications()
            viewModel.useMobileData()
            onDone()
        },
        onNotNow = onDone,
    )
}

/**
 * Step 3 of 3, with no way back: an example of what voice does, its size, and that the voice stays on the phone. Off
 * Wi-Fi it says the download will wait for Wi-Fi, and offers mobile data now instead.
 */
@Composable
fun SetupVoiceScreen(
    state: VoiceSetupUiState,
    onDownload: () -> Unit,
    onUseMobileData: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val size = voiceSize(VoicePack.downloadBytes, state.myanmarDigits)
    AuthPage(
        title = stringResource(R.string.setup_voice_title),
        subtitle = stringResource(R.string.setup_voice_subtitle),
        onBack = null,
        modifier = modifier,
        step = Step(at = 3, of = 3),
        button = {
            PrimaryButton(
                text = if (state.onWifi) stringResource(R.string.voice_download, size) else stringResource(R.string.setup_voice_download_on_wifi),
                onClick = onDownload,
                icon = Lucide.Download,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        footer = { NotNowLink(onNotNow) },
    ) {
        val accent = colors.accent
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(accent, size = Size(2.dp.toPx(), this.size.height)) }
                .padding(start = 14.dp, top = 12.dp, bottom = 14.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val said = stringResource(R.string.voice_example_1)
            Text(said, style = type.size20.forContent(said), color = colors.ink)
            val result = stringResource(R.string.setup_voice_example_result, categoryLabel("Food and Drink Item"), formatKyat(3000, state.myanmarDigits))
            val line = stringResource(R.string.setup_voice_example_becomes, result)
            Text(
                buildAnnotatedString {
                    append(line)
                    val at = line.indexOf(result)
                    if (at >= 0) addStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold), at, at + result.length)
                },
                style = type.size14,
                color = colors.muted,
            )
        }
        Column {
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            Fact(Lucide.Download, colors.accent, stringResource(R.string.setup_voice_size, size), stringResource(R.string.setup_voice_size_detail))
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            Fact(Lucide.Smartphone, colors.blue, stringResource(R.string.voice_stays_on_phone), stringResource(R.string.setup_voice_privacy_detail))
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
        if (!state.onWifi) {
            WifiNotice(
                title = stringResource(R.string.setup_voice_no_wifi_title),
                detail = stringResource(R.string.setup_voice_no_wifi_detail, size),
                onUseMobileData = onUseMobileData,
            )
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, tint: Color, title: String, detail: String) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).border(1.dp, colors.hairline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.size16.medium, color = colors.ink)
            Text(detail, style = type.size13, color = colors.muted)
        }
    }
}

/** Not now: muted, one 48 dp target, where the income step has Skip for now. */
@Composable
private fun NotNowLink(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.voice_not_now), style = BudgetWiseTheme.type.size15.semibold, color = BudgetWiseTheme.colors.muted)
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupVoiceScreenPreview() {
    BudgetWiseTheme {
        SetupVoiceScreen(state = VoiceSetupUiState(), onDownload = {}, onUseMobileData = {}, onNotNow = {})
    }
}
