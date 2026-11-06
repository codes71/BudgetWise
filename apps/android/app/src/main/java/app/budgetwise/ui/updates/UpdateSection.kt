package app.budgetwise.ui.updates

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.voice.voiceSize
import app.budgetwise.updates.AppUpdates
import app.budgetwise.updates.UpdateProblem
import app.budgetwise.updates.UpdateRelease
import app.budgetwise.updates.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updates: AppUpdates) : ViewModel() {
    val state = updates.state
    val enabled = updates.enabled
    fun check() = updates.check(force = true)
    fun download() = updates.download()
    fun cancelDownload() = updates.cancelDownload()
    fun install(activity: Activity) = updates.install(activity)
    fun onResume(activity: Activity) = updates.onResume(activity)
}

/** Permission settings are resumed only by the updater's explicit pending-install flow. */
@Composable
fun AppUpdateSection(myanmarDigits: Boolean, viewModel: UpdateViewModel = hiltViewModel()) {
    if (!viewModel.enabled) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, activity, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) viewModel.onResume(activity)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    UpdateSection(
        state = state,
        myanmarDigits = myanmarDigits,
        onCheck = viewModel::check,
        onDownload = viewModel::download,
        onCancel = viewModel::cancelDownload,
        onInstall = { activity?.let(viewModel::install) },
        canInstall = activity != null,
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

/** Optional update controls keep the existing Settings typography, spacing and button styles. */
@Composable
fun UpdateSection(
    state: UpdateState,
    myanmarDigits: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    canInstall: Boolean = true,
    showHeading: Boolean = true,
) {
    if (state == UpdateState.Disabled) return
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) SectionLabel(stringResource(R.string.updates_section), Modifier.semantics { heading() })
        val title = when (state) {
            UpdateState.Disabled -> ""
            UpdateState.Idle -> stringResource(R.string.updates_idle)
            UpdateState.Checking -> stringResource(R.string.updates_checking)
            UpdateState.Current -> stringResource(R.string.updates_current)
            is UpdateState.Available -> stringResource(R.string.updates_available, state.release.versionName)
            is UpdateState.Downloading -> stringResource(R.string.updates_downloading)
            is UpdateState.Ready -> stringResource(R.string.updates_ready, state.release.versionName)
            is UpdateState.Problem -> stringResource(problemTitle(state.problem))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = type.size16.semibold.forContent(title),
                color = if (state is UpdateState.Problem) colors.coralText else colors.ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val detail = when (state) {
                UpdateState.Disabled, UpdateState.Checking -> null
                UpdateState.Idle -> stringResource(R.string.updates_idle_detail)
                UpdateState.Current -> stringResource(R.string.updates_current_detail)
                is UpdateState.Available -> stringResource(R.string.updates_size, voiceSize(state.release.sizeBytes, myanmarDigits))
                is UpdateState.Downloading -> stringResource(
                    R.string.updates_download_progress,
                    voiceSize(state.downloadedBytes.coerceIn(0, state.totalBytes.coerceAtLeast(0)), myanmarDigits),
                    voiceSize(state.totalBytes.coerceAtLeast(0), myanmarDigits),
                )
                is UpdateState.Ready -> stringResource(R.string.updates_install_detail)
                is UpdateState.Problem -> stringResource(problemDetail(state.problem))
            }
            detail?.let { Text(it, style = type.size13.forContent(it), color = colors.muted) }
        }
        when (state) {
            UpdateState.Disabled -> Unit
            UpdateState.Idle -> SecondaryButton(stringResource(R.string.updates_check), onCheck, Modifier.fillMaxWidth())
            UpdateState.Checking -> PrimaryButton(
                stringResource(R.string.updates_checking), {}, Modifier.fillMaxWidth(), busy = true, height = 48.dp,
            )
            UpdateState.Current -> SecondaryButton(stringResource(R.string.updates_check_again), onCheck, Modifier.fillMaxWidth())
            is UpdateState.Available -> {
                ReleaseNotes(state.release)
                PrimaryButton(stringResource(R.string.updates_download), onDownload, Modifier.fillMaxWidth(), height = 48.dp)
            }
            is UpdateState.Downloading -> {
                val progress = if (state.totalBytes > 0) (state.downloadedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = colors.accent,
                    trackColor = colors.raised,
                )
                SecondaryButton(stringResource(R.string.updates_cancel_download), onCancel, Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> PrimaryButton(
                stringResource(R.string.updates_install), onInstall, Modifier.fillMaxWidth(), enabled = canInstall, height = 48.dp,
            )
            is UpdateState.Problem -> when {
                state.problem == UpdateProblem.PERMISSION_NEEDED -> PrimaryButton(
                    stringResource(R.string.updates_allow_install), onInstall, Modifier.fillMaxWidth(), enabled = canInstall, height = 48.dp,
                )
                state.problem == UpdateProblem.INSTALL_FAILED && state.release != null -> PrimaryButton(
                    stringResource(R.string.updates_install_again), onInstall, Modifier.fillMaxWidth(), enabled = canInstall, height = 48.dp,
                )
                state.problem == UpdateProblem.DOWNLOAD_FAILED && state.release != null -> PrimaryButton(
                    stringResource(R.string.updates_download_again), onDownload, Modifier.fillMaxWidth(), height = 48.dp,
                )
                else -> SecondaryButton(stringResource(R.string.updates_check_again), onCheck, Modifier.fillMaxWidth())
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

@Composable
private fun ReleaseNotes(release: UpdateRelease) {
    if (release.notes.isNotBlank()) {
        Text(release.notes, style = BudgetWiseTheme.type.size14.forContent(release.notes), color = BudgetWiseTheme.colors.ink)
    }
}

private fun problemTitle(problem: UpdateProblem): Int = when (problem) {
    UpdateProblem.OFFLINE -> R.string.updates_offline
    UpdateProblem.INVALID_RELEASE -> R.string.updates_invalid_release
    UpdateProblem.DOWNLOAD_FAILED -> R.string.updates_download_failed
    UpdateProblem.INVALID_APK -> R.string.updates_invalid_apk
    UpdateProblem.INSTALL_FAILED -> R.string.updates_install_failed
    UpdateProblem.PERMISSION_NEEDED -> R.string.updates_permission
    UpdateProblem.NOT_CONFIGURED -> R.string.updates_not_configured
}

private fun problemDetail(problem: UpdateProblem): Int = when (problem) {
    UpdateProblem.OFFLINE -> R.string.updates_offline_detail
    UpdateProblem.INVALID_RELEASE, UpdateProblem.INVALID_APK -> R.string.updates_verification_detail
    UpdateProblem.DOWNLOAD_FAILED -> R.string.updates_download_failed_detail
    UpdateProblem.INSTALL_FAILED -> R.string.updates_install_failed_detail
    UpdateProblem.PERMISSION_NEEDED -> R.string.updates_permission_detail
    UpdateProblem.NOT_CONFIGURED -> R.string.updates_not_configured_detail
}

@Preview(showBackground = true)
@Composable
private fun UpdateSectionPreview() {
    BudgetWiseTheme {
        Column(Modifier.padding(24.dp)) {
            UpdateSection(
                state = UpdateState.Available(
                    UpdateRelease(2, "0.2.0", 26, "", "", 40_000_000, "Update from inside Pocket Peek."),
                ),
                myanmarDigits = false,
                onCheck = {}, onDownload = {}, onCancel = {}, onInstall = {},
            )
        }
    }
}
