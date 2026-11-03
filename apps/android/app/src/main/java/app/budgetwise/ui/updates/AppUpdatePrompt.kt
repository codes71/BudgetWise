package app.budgetwise.ui.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.updates.UpdateState

/** One optional offer per version in this app session. Dismissing never prevents using BudgetWise. */
@Composable
fun AppUpdatePrompt(viewModel: UpdateViewModel = hiltViewModel()) {
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
    UpdatePrompt(
        state = state,
        onCheck = viewModel::check,
        onDownload = viewModel::download,
        onCancel = viewModel::cancelDownload,
        onInstall = { activity?.let(viewModel::install) },
        canInstall = activity != null,
    )
}

@Composable
fun UpdatePrompt(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    canInstall: Boolean = true,
) {
    var offeredVersion by rememberSaveable { mutableLongStateOf(-1L) }
    var open by rememberSaveable { mutableStateOf(false) }
    val offeredRelease = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Ready -> state.release
        else -> null
    }
    LaunchedEffect(offeredRelease?.versionCode) {
        val version = offeredRelease?.versionCode
        if (version != null && version != offeredVersion) {
            offeredVersion = version
            open = true
        }
    }
    if (!open || state == UpdateState.Disabled) return
    Dialog(onDismissRequest = { open = false }) {
        Surface(shape = RoundedCornerShape(24.dp), color = BudgetWiseTheme.colors.ground) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val title = stringResource(R.string.updates_prompt_title)
                Text(
                    title,
                    style = BudgetWiseTheme.type.size24.bold.forContent(title),
                    color = BudgetWiseTheme.colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
                UpdateSection(
                    state = state,
                    myanmarDigits = false,
                    onCheck = onCheck,
                    onDownload = onDownload,
                    onCancel = {
                        onCancel()
                        open = false
                    },
                    onInstall = onInstall,
                    canInstall = canInstall,
                    showHeading = false,
                )
                TextAction(
                    text = stringResource(if (state is UpdateState.Available) R.string.updates_not_now else R.string.updates_later),
                    onClick = { open = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
