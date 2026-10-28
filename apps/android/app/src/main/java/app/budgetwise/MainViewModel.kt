package app.budgetwise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val startup: AppStartup,
    session: SessionManager,
) : ViewModel() {
    /** Null until the stored session is loaded; the activity holds its first frame until then. */
    val session: StateFlow<SessionState?> = flow {
        startup.awaitSession()
        emitAll(session.state)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Once per UI launch: the ViewModel outlives rotation, so a configuration change does not sync again.
        viewModelScope.launch { startup.onUiLaunch() }
    }
}
