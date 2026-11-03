package app.budgetwise.ui.budgets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.R
import app.budgetwise.di.ApplicationScope
import app.budgetwise.reminders.ReminderStore
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.tracked
import app.budgetwise.ui.voice.CardLink
import app.budgetwise.ui.voice.VoiceDialogCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the reason for budget reminders has been shown and answered on this phone ([ReminderStore]). */
@HiltViewModel
class ReminderAskViewModel @Inject constructor(
    private val store: ReminderStore,
    @ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {
    /** True until the store says otherwise (or cannot be read), so nothing is asked before it is known. */
    val asked: StateFlow<Boolean> = store.notificationsAsked.catch { emit(true) }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Either answer, Not now included, is final: the reason is never shown again. In the process scope, so it is saved
     * even when the answer is the last thing before the app goes away.
     */
    fun answered() {
        scope.launch {
            try {
                store.setNotificationsAsked()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // DataStore could not write: the reason may show once more after the next plan save.
            }
        }
    }
}

/** Android 13 and later ask before an app may post; earlier versions allow it from the install. */
internal fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * Hosts the reason for budget reminders and returns what to call after a plan is saved: the first time, where
 * notifications are not allowed yet, a small card says in one line why, and Allow opens Android's own question. Not
 * now, Allow and whatever Android answers are all final ([ReminderAskViewModel.answered]); without the permission the
 * reminder simply posts nothing.
 */
@Composable
fun rememberAskReminders(viewModel: ReminderAskViewModel = hiltViewModel()): () -> Unit {
    val context = LocalContext.current
    var showing by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (showing) {
        ReminderAskDialog(
            onAllow = {
                showing = false
                viewModel.answered()
                // Only shown from Android 13 ([needsNotificationPermission]); the check is for lint.
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onNotNow = {
                showing = false
                viewModel.answered()
            },
        )
    }
    return { if (!viewModel.asked.value && needsNotificationPermission(context)) showing = true }
}

/** The one-line reason, Allow, and Not now; dismissing it counts as Not now. */
@Composable
fun ReminderAskDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Dialog(onDismissRequest = onNotNow, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            VoiceDialogCard {
                Icon(Lucide.Bell, contentDescription = null, tint = colors.accent, modifier = Modifier.size(26.dp))
                Text(
                    stringResource(R.string.reminders_ask_title),
                    style = type.size22.extraBold.tracked(-0.02f),
                    color = colors.ink,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                Text(stringResource(R.string.reminders_ask_body), style = type.size14, color = colors.muted)
                PrimaryButton(
                    text = stringResource(R.string.reminders_ask_allow),
                    onClick = onAllow,
                    height = 50.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                CardLink(stringResource(R.string.reminders_ask_not_now), colors.accent, onNotNow)
            }
        }
    }
}
