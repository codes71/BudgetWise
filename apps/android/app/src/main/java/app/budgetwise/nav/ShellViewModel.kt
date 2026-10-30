package app.budgetwise.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.NetworkStatus
import app.budgetwise.SyncControls
import app.budgetwise.VoiceAvailability
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
 * The shell's own state: the sync signals both tabs' headers show, and whether this phone runs voice (onboarding's
 * length depends on it). It lives as long as the activity.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(sync: SyncControls, network: NetworkStatus, voice: VoiceAvailability) : ViewModel() {
    val voiceSupported: Boolean = voice.supported

    val signals: StateFlow<SyncSignals> =
        combine(network.online, sync.status, sync.queued, sync.unsyncedCount) { online, status, queued, unsynced ->
            SyncSignals(online, status, queued, unsynced)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSignals(status = sync.status.value))
}
