package app.budgetwise.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.AccountActions
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.repo.LoginResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LoginError {
    /** 401 from sign-in, or offline a password that does not match this phone's check. */
    data object WrongCredentials : LoginError

    /** 429. [minutes] when the server said how long to wait. */
    data class RateLimited(val minutes: Int?) : LoginError

    /**
     * No connection (or the dev server is not reachable, adb reverse not set up) and this account never signed in on
     * this phone, so there is nothing to check the password against.
     */
    data object NeedsInternet : LoginError

    /** A 5xx: the server is there but failing. */
    data object Server : LoginError

    data object Generic : LoginError
}

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    /** The user signed in on this phone before (expired or expiring session): greet them back. */
    val returning: Boolean = false,
    val loading: Boolean = false,
    val error: LoginError? = null,
    val signedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotEmpty() && !loading && !signedIn

    override fun toString() =
        "LoginUiState(email=$email, password=<redacted>, returning=$returning, loading=$loading, error=$error, " +
            "signedIn=$signedIn)"
}

sealed interface LoginEvent {
    /**
     * Signed in: go to transactions and drop sign-in from the back stack. Fires once. [offline] when the phone signed
     * in without the server: transactions then says the entries sync once it is back online.
     */
    data class SignedIn(val offline: Boolean) : LoginEvent
}

/**
 * Sign-in form. The password lives only here, in memory (never in saved state), and is dropped after sign-in.
 * [AccountActions.login] stores the session and schedules the full and periodic syncs, so this class does not. Signing
 * in as another account never asks anything: each account keeps its own entries.
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
        attempt(email.trim(), password)
    }

    private fun attempt(email: String, password: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = try {
                accounts.login(email, password)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A Keystore or Room failure, not an answer from the server.
                _state.update { it.copy(loading = false, error = LoginError.Generic) }
                return@launch
            }
            when (r) {
                is LoginResult.Success -> {
                    _state.update { it.copy(password = "", loading = false, signedIn = true) }
                    _events.send(LoginEvent.SignedIn(offline = r.offline))
                }
                LoginResult.WrongCredentials -> _state.update { it.copy(loading = false, error = LoginError.WrongCredentials) }
                LoginResult.NeedsInternet -> _state.update { it.copy(loading = false, error = LoginError.NeedsInternet) }
                is LoginResult.Failure -> _state.update { it.copy(loading = false, error = loginErrorFor(r)) }
            }
        }
    }
}

private val minutesInMessage = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)

/**
 * `rate_limited` is the WS1 code for 429 (its message has no wait time today; Retry-After does not reach the app). No
 * connection never ends up here: [AccountActions.login] then signs in offline or answers [LoginResult.NeedsInternet].
 */
internal fun loginErrorFor(failure: LoginResult.Failure): LoginError = when {
    failure.code == "rate_limited" || failure.code == "http_429" ->
        LoginError.RateLimited(minutesInMessage.find(failure.message)?.groupValues?.get(1)?.toIntOrNull())
    failure.retryable -> LoginError.Server
    else -> LoginError.Generic
}
