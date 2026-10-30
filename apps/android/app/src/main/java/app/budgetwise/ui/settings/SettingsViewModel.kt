package app.budgetwise.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.AccountActions
import app.budgetwise.SyncControls
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.auth.UserInfo
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountInfo(val name: String, val email: String)

enum class SyncPhase { SYNCING, WAITING, SYNCED, OFFLINE, NEEDS_SIGN_IN, REJECTED, NEVER }

/** What Settings says about sync. [at] is when the last run finished (null before the first run in this process). */
data class SyncSummary(val phase: SyncPhase, val at: Instant? = null, val rejectedCount: Int = 0)

data class SettingsUiState(
    val account: AccountInfo? = null,
    val sessionExpired: Boolean = false,
    val sync: SyncSummary = SyncSummary(SyncPhase.NEVER),
    val unsyncedCount: Int = 0,
    val signingOut: Boolean = false,
    /** The last sign-out threw (a Room or Keystore failure); nothing was signed out. */
    val signOutFailed: Boolean = false,
    /** Amounts in Myanmar digits (SettingsStore, default off). */
    val myanmarDigits: Boolean = false,
) {
    val canSyncNow: Boolean get() = account != null && !sessionExpired && sync.phase != SyncPhase.SYNCING
}

/**
 * A failed last run outranks queued work: after a failure WorkManager retries with backoff (queued), but "couldn't
 * reach BudgetWise" is what the user needs to read. [SyncStatus] lives in memory, so after a restart the first
 * line is "waiting" or "not synced yet" until the launch sync runs.
 */
internal fun syncSummary(status: SyncStatus, queued: Boolean, expired: Boolean): SyncSummary {
    val last = status as? SyncStatus.LastResult
    if (expired) return SyncSummary(SyncPhase.NEEDS_SIGN_IN, last?.at)
    if (status == SyncStatus.Running) return SyncSummary(SyncPhase.SYNCING)
    if (last == null) return SyncSummary(if (queued) SyncPhase.WAITING else SyncPhase.NEVER)
    return when (val outcome = last.outcome) {
        SyncOutcome.RetryLater -> SyncSummary(SyncPhase.OFFLINE, last.at)
        SyncOutcome.NeedsLogin -> SyncSummary(SyncPhase.NEEDS_SIGN_IN, last.at)
        is SyncOutcome.PermanentFailure -> SyncSummary(SyncPhase.REJECTED, last.at, rejectedCount = outcome.clientIds.size)
        is SyncOutcome.Success -> SyncSummary(if (queued) SyncPhase.WAITING else SyncPhase.SYNCED, last.at)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accounts: AccountActions,
    private val session: SessionManager,
    private val sync: SyncControls,
    private val settings: SettingsStore,
) : ViewModel() {
    private data class SignOut(val running: Boolean = false, val failed: Boolean = false)

    private val signOut = MutableStateFlow(SignOut())

    /** The sign-out flow and the display settings, combined so the main combine stays at five sources. */
    private data class Local(val out: SignOut, val myanmarDigits: Boolean)

    private val local = combine(signOut, settings.myanmarDigits) { out, digits -> Local(out, digits) }

    val state: StateFlow<SettingsUiState> =
        combine(session.state, sync.status, sync.queued, sync.unsyncedCount, local) { s, status, queued, unsynced, l ->
            uiState(s, status, queued, unsynced, l)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            uiState(session.state.value, sync.status.value, queued = false, unsynced = 0, l = Local(SignOut(), false)),
        )

    private fun uiState(s: SessionState, status: SyncStatus, queued: Boolean, unsynced: Int, l: Local): SettingsUiState {
        val expired = s == SessionState.Expired
        val user: UserInfo? = when (s) {
            is SessionState.SignedIn -> s.user
            is SessionState.SignedInOffline -> s.user
            SessionState.Expired -> session.knownUser
            SessionState.SignedOut -> null
        }
        return SettingsUiState(
            account = user?.let { AccountInfo(it.fullName, it.email) },
            sessionExpired = expired,
            sync = syncSummary(status, queued, expired),
            unsyncedCount = unsynced,
            signingOut = l.out.running,
            signOutFailed = l.out.failed,
            myanmarDigits = l.myanmarDigits,
        )
    }

    fun syncNow() {
        if (state.value.canSyncNow) sync.syncNow()
    }

    /**
     * Signs out at once, without asking: nothing on the phone is deleted, and entries that have not synced yet keep
     * uploading in the background. The shell sees the session go to SignedOut and returns to sign-in.
     */
    fun signOut() {
        if (signOut.value.running) return
        signOut.value = SignOut(running = true)
        viewModelScope.launch {
            signOut.value = try {
                accounts.logout()
                SignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A Room or Keystore failure: say so, and let the user try again.
                SignOut(failed = true)
            }
        }
    }

    fun setMyanmarDigits(on: Boolean) {
        viewModelScope.launch {
            try {
                settings.setMyanmarDigits(on)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // DataStore could not write (an IOException); the switch stays where it was.
            }
        }
    }
}
