package app.budgetwise.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.AccountActions
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.repo.LoginResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LoginError {
    /** 401 from sign-in. */
    data object WrongCredentials : LoginError

    /** 429. [minutes] when the server said how long to wait. */
    data class RateLimited(val minutes: Int?) : LoginError

    /** No connection, or the dev server is not reachable (adb reverse not set up). */
    data object Network : LoginError

    /** A 5xx or timeout: the server is there but failing. */
    data object Server : LoginError

    data object Generic : LoginError
}

/** Sign-in would delete [unsyncedCount] rows of another account ([previousEmail], when known) that never synced. */
data class DiscardPrompt(val previousEmail: String?, val unsyncedCount: Int)

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    /** The user signed in on this phone before (expired or expiring session): greet them back. */
    val returning: Boolean = false,
    val loading: Boolean = false,
    val error: LoginError? = null,
    val discardPrompt: DiscardPrompt? = null,
    val signedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotEmpty() && !loading && !signedIn

    override fun toString() =
        "LoginUiState(email=$email, password=<redacted>, returning=$returning, loading=$loading, error=$error, " +
            "discardPrompt=$discardPrompt, signedIn=$signedIn)"
}

sealed interface LoginEvent {
    /** Signed in: go to transactions and drop sign-in from the back stack. Fires once. */
    data object SignedIn : LoginEvent
}

/**
 * Sign-in form. The password lives only here, in memory (never in saved state), and is dropped after sign-in.
 * [AccountActions.login] stores the token and schedules the full and periodic syncs, so this class does not.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accounts: AccountActions,
    session: SessionManager,
) : ViewModel() {
    private val known = session.knownUser
    private val _state = MutableStateFlow(LoginUiState(email = known?.email.orEmpty(), returning = known != null))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    fun onEmailChange(email: String) = _state.update { it.copy(email = email, error = null) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, error = null) }

    fun submit() = submit(_state.value.email, _state.value.password)

    fun submit(email: String, password: String) {
        val s = _state.value
        if (email.isBlank() || password.isEmpty() || s.loading || s.signedIn) return
        attempt(email.trim(), password, discard = false)
    }

    /** The user agreed to delete the other account's unsynced rows. */
    fun confirmDiscard() {
        val s = _state.value
        if (s.discardPrompt == null || s.loading) return
        attempt(s.email.trim(), s.password, discard = true)
    }

    fun dismissDiscard() = _state.update { it.copy(discardPrompt = null) }

    private fun attempt(email: String, password: String, discard: Boolean) {
        _state.update { it.copy(loading = true, error = null, discardPrompt = null) }
        viewModelScope.launch {
            when (val r = accounts.login(email, password, discard)) {
                is LoginResult.Success -> {
                    _state.update { it.copy(password = "", loading = false, signedIn = true) }
                    _events.send(LoginEvent.SignedIn)
                }
                is LoginResult.ConfirmDiscard -> _state.update {
                    it.copy(loading = false, discardPrompt = DiscardPrompt(r.previousEmail, r.unsyncedCount))
                }
                LoginResult.WrongCredentials -> _state.update { it.copy(loading = false, error = LoginError.WrongCredentials) }
                is LoginResult.Failure -> _state.update { it.copy(loading = false, error = loginErrorFor(r)) }
            }
        }
    }
}

private val minutesInMessage = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)

/** `rate_limited` is the WS1 code for 429 (its message has no wait time today; Retry-After does not reach the app). */
internal fun loginErrorFor(failure: LoginResult.Failure): LoginError = when {
    failure.code == "network" -> LoginError.Network
    failure.code == "rate_limited" || failure.code == "http_429" ->
        LoginError.RateLimited(minutesInMessage.find(failure.message)?.groupValues?.get(1)?.toIntOrNull())
    failure.retryable -> LoginError.Server
    else -> LoginError.Generic
}
