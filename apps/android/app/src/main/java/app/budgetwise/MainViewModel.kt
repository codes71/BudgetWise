package app.budgetwise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.reminders.MonthEndLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val startup: AppStartup,
    session: SessionManager,
    private val handle: SavedStateHandle,
) : ViewModel() {
    /** Null until the stored session is loaded; the activity holds its first frame until then. */
    val session: StateFlow<SessionState?> = flow {
        startup.awaitSession()
        emitAll(session.state)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * A month-end notification's link waiting for AppNav to follow it. In the saved state, not the activity: one that
     * arrives just before a rotation, or before the process is killed while the session still loads, is followed once
     * the activity is back; [monthEndLinkHandled] removes it, so it is followed once.
     */
    val monthEndLink: StateFlow<MonthEndLink?> = handle.getStateFlow<String?>(KEY_MONTH_END_LINK, null)
        .map { it?.let(MonthEndLink::decode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, handle.get<String>(KEY_MONTH_END_LINK)?.let(MonthEndLink::decode))

    fun openMonthEnd(link: MonthEndLink) {
        handle[KEY_MONTH_END_LINK] = link.encode()
    }

    /** Set to null, not removed: removing the key would leave [monthEndLink]'s flow holding the old link. */
    fun monthEndLinkHandled() {
        handle[KEY_MONTH_END_LINK] = null
    }

    init {
        // Once per UI launch: the ViewModel outlives rotation, so a configuration change does not sync again.
        viewModelScope.launch { startup.onUiLaunch() }
    }

    private companion object {
        const val KEY_MONTH_END_LINK = "month_end_link"
    }
}
