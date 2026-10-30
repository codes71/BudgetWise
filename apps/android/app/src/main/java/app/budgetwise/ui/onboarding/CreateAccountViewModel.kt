package app.budgetwise.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.AccountActions
import app.budgetwise.data.repo.RegisterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CreateAccountError {
    /** Under the email field: 409 from the server. The form offers sign in instead. */
    data object EmailTaken : CreateAccountError

    /** Under the email field, before asking the server: no "@" between two parts. */
    data object InvalidEmail : CreateAccountError

    /** Under the password field, before asking the server: the web signup's rule. */
    data object ShortPassword : CreateAccountError

    /** No connection: a new account cannot start on the phone alone. */
    data object NeedsInternet : CreateAccountError

    /** 429. */
    data object RateLimited : CreateAccountError

    /** A 5xx: the server is there but failing. */
    data object Server : CreateAccountError

    data object Generic : CreateAccountError
}

data class CreateAccountUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: CreateAccountError? = null,
    val signedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotEmpty() && !loading && !signedIn

    override fun toString() =
        "CreateAccountUiState(name=$name, email=$email, password=<redacted>, loading=$loading, error=$error, signedIn=$signedIn)"
}

sealed interface CreateAccountEvent {
    /** The account exists and is signed in: go to transactions and drop onboarding from the back stack. Fires once. */
    data object SignedIn : CreateAccountEvent
}

/**
 * The create account form. Like sign-in, the password lives only here, in memory, and is dropped once the account is
 * made. [AccountActions.register] creates it on the server and signs it in, so this class only checks the two rules
 * it can check itself (an email shape, 6 characters) and maps the answer.
 */
@HiltViewModel
class CreateAccountViewModel @Inject constructor(private val accounts: AccountActions) : ViewModel() {
    private val _state = MutableStateFlow(CreateAccountUiState())
    val state: StateFlow<CreateAccountUiState> = _state.asStateFlow()

    private val _events = Channel<CreateAccountEvent>(Channel.BUFFERED)
    val events: Flow<CreateAccountEvent> = _events.receiveAsFlow()

    fun onNameChange(name: String) = _state.update { it.copy(name = name) }

    fun onEmailChange(email: String) = _state.update {
        it.copy(email = email, error = it.error.takeUnless { e -> e.isAboutEmail() || e.isNotice() })
    }

    fun onPasswordChange(password: String) = _state.update {
        it.copy(password = password, error = it.error.takeUnless { e -> e == CreateAccountError.ShortPassword || e.isNotice() })
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        val email = s.email.trim()
        val error = when {
            !EMAIL_SHAPE.matches(email) -> CreateAccountError.InvalidEmail
            s.password.length < MIN_PASSWORD -> CreateAccountError.ShortPassword
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = try {
                accounts.register(email, s.password, s.name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A Keystore or Room failure, not an answer from the server.
                _state.update { it.copy(loading = false, error = CreateAccountError.Generic) }
                return@launch
            }
            when (r) {
                is RegisterResult.Success -> {
                    _state.update { it.copy(password = "", loading = false, signedIn = true) }
                    _events.send(CreateAccountEvent.SignedIn)
                }
                RegisterResult.EmailTaken -> _state.update { it.copy(loading = false, error = CreateAccountError.EmailTaken) }
                RegisterResult.NeedsInternet -> _state.update { it.copy(loading = false, error = CreateAccountError.NeedsInternet) }
                is RegisterResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        error = when {
                            r.code == "rate_limited" || r.code == "http_429" -> CreateAccountError.RateLimited
                            r.retryable -> CreateAccountError.Server
                            else -> CreateAccountError.Generic
                        },
                    )
                }
            }
        }
    }

    private fun CreateAccountError?.isAboutEmail() = this == CreateAccountError.EmailTaken || this == CreateAccountError.InvalidEmail

    private fun CreateAccountError?.isNotice() = this != null && !isAboutEmail() && this != CreateAccountError.ShortPassword

    companion object {
        /** The web signup's minimum (it rejects shorter passwords before asking the server). */
        const val MIN_PASSWORD = 6

        /** What HTML's type="email" lets through, roughly: something, an "@", something, no spaces. */
        private val EMAIL_SHAPE = Regex("""[^\s@]+@[^\s@]+""")
    }
}
