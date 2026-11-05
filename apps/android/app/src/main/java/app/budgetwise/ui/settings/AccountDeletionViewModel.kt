package app.budgetwise.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.AccountDeletionCleanup
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.repo.AccountDeletionRepository
import app.budgetwise.data.repo.AccountDeletionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class AccountDeletionUiState(
    val accountId: String? = null,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val failure: AccountDeletionResult? = null,
) {
    override fun toString() = "AccountDeletionUiState(accountId=$accountId, busy=$busy, failure=$failure, password=<redacted>)"
}

@HiltViewModel
class AccountDeletionViewModel @Inject constructor(
    private val deletion: AccountDeletionRepository,
    private val session: SessionManager,
    private val cleanup: AccountDeletionCleanup,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountDeletionUiState())
    val state = mutableState.asStateFlow()

    fun open() {
        if (mutableState.value.busy) return
        val user = session.knownUser ?: return
        mutableState.value = AccountDeletionUiState(accountId = user.id, email = user.email)
    }

    fun password(value: String) {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(password = value, failure = null)
    }

    fun dismiss() {
        if (!mutableState.value.busy) mutableState.value = AccountDeletionUiState()
    }

    fun confirm() {
        val before = mutableState.value
        val id = before.accountId ?: return
        if (before.busy || before.password.isEmpty()) return
        mutableState.value = before.copy(busy = true, failure = null, password = "")
        viewModelScope.launch {
            // Successful deletion changes the shell's session and pops Settings. Finish success feedback even then.
            withContext(NonCancellable) {
                val result = try {
                    deletion.delete(id, before.password) { cleanup.finish(id) }
                } catch (_: Exception) { AccountDeletionResult.TryAgain }
                if (result == AccountDeletionResult.Success) {
                    mutableState.value = AccountDeletionUiState()
                    cleanup.completed()
                } else {
                    mutableState.value = before.copy(password = "", busy = false, failure = result)
                }
            }
        }
    }
}
