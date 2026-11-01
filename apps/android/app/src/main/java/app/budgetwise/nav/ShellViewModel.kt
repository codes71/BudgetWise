package app.budgetwise.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.NetworkStatus
import app.budgetwise.SyncControls
import app.budgetwise.VoiceAvailability
import app.budgetwise.asr.ModelManager
import app.budgetwise.asr.ModelState
import app.budgetwise.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the header's sync chip is made from, besides the session ([app.budgetwise.ui.common.syncChipFor]). */
data class SyncSignals(
    val online: Boolean = true,
    val status: SyncStatus = SyncStatus.Idle,
    val queued: Boolean = false,
    val unsynced: Int = 0,
)

/**
 * The shell's own state: the sync signals both tabs' headers show, and whether onboarding has its voice step. It lives
 * as long as the activity.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    sync: SyncControls,
    network: NetworkStatus,
    voice: VoiceAvailability,
    models: ModelManager,
) : ViewModel() {
    /**
     * Onboarding offers voice (three steps, not two): the phone has the memory for it, and voice is not already on it
     * or coming (another account set it up), in which case the step skips itself ([app.budgetwise.ui.onboarding.SetupVoiceRoute]).
     */
    val voiceStep: Boolean = voice.supported && models.state.value.let { it !is ModelState.Ready && it !is ModelState.Downloading }

    val signals: StateFlow<SyncSignals> =
        combine(network.online, sync.status, sync.queued, sync.unsyncedCount) { online, status, queued, unsynced ->
            SyncSignals(online, status, queued, unsynced)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSignals(status = sync.status.value))
}
