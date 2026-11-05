package app.budgetwise.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent

internal enum class CloudAiFeature(val preference: String) { SCAN("scan_v1"), VOICE("voice_v1") }
internal const val CLOUD_AI_CONSENT_PREFERENCES = "cloud_ai_disclosure"

/** Mounts permissions, capture and recording effects only after an explicit, versioned first-use choice. */
@Composable
internal fun CloudAiConsentGate(
    feature: CloudAiFeature,
    onClose: () -> Unit,
    onManual: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(CLOUD_AI_CONSENT_PREFERENCES, 0) }
    var accepted by remember(preferences, feature) { mutableStateOf(preferences.getBoolean(feature.preference, false)) }
    if (accepted) {
        content()
    } else {
        CloudAiDisclosurePanel(
            feature = feature,
            onClose = onClose,
            onManual = onManual,
            onAgree = {
                preferences.edit().putBoolean(feature.preference, true).apply()
                accepted = true
            },
        )
    }
}

/** A normal page in the native flow, with the existing manual path and no preselected agreement. */
@Composable
internal fun CloudAiDisclosurePanel(feature: CloudAiFeature, onClose: () -> Unit, onManual: () -> Unit, onAgree: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val body = stringResource(if (feature == CloudAiFeature.SCAN) R.string.privacy_scan_body else R.string.privacy_voice_body)
    val manual = stringResource(R.string.privacy_manual_alternative)
    Column(Modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars)) {
        BackHeader(
            stringResource(if (feature == CloudAiFeature.SCAN) R.string.privacy_scan_title else R.string.privacy_voice_title),
            onClose,
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(body, style = BudgetWiseTheme.type.size16.forContent(body), color = colors.ink)
            Text(manual, style = BudgetWiseTheme.type.size14.forContent(manual), color = colors.muted)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                stringResource(if (feature == CloudAiFeature.SCAN) R.string.privacy_scan_agree else R.string.privacy_voice_agree),
                onAgree, Modifier.fillMaxWidth(),
            )
            SecondaryButton(stringResource(R.string.privacy_enter_manually), onManual, Modifier.fillMaxWidth())
        }
    }
}
