package app.budgetwise.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.R
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.Source
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.typedKyatDigits
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
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

/** Where this month's money comes from; its label becomes the entry's description. */
enum class IncomeSource(@StringRes val label: Int) {
    SALARY(R.string.income_source_salary),
    SHOP(R.string.income_source_shop),
    FAMILY(R.string.income_source_family),
    OTHER(R.string.income_source_other),
}

data class FirstIncomeUiState(
    val today: LocalDate,
    /** Plain ASCII digits as typed, without grouping; the field shows them grouped. */
    val amountDigits: String = "",
    val source: IncomeSource = IncomeSource.SALARY,
    val myanmarDigits: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** The amount to save; null until there is one above zero. */
    val amount: Long? get() = amountDigits.toLongOrNull()?.takeIf { it > 0 }

    val canSave: Boolean get() = amount != null && !saving
}

sealed interface FirstIncomeEvent {
    /** The income is in Room (and the outbox): go on to the app. Fires once. */
    data object Saved : FirstIncomeEvent
}

/**
 * Right after create account: what comes in this month, saved as one income entry for today, so the month's figure
 * ("Left in October") means something from the first screen. Offline-first like the add form. Skipping saves nothing.
 */
@HiltViewModel
class FirstIncomeViewModel @Inject constructor(
    private val transactions: TransactionStore,
    settings: SettingsStore,
    @DeviceClock clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(FirstIncomeUiState(today = LocalDate.now(clock)))
    val state: StateFlow<FirstIncomeUiState> = _state.asStateFlow()

    private val _events = Channel<FirstIncomeEvent>(Channel.BUFFERED)
    val events: Flow<FirstIncomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
    }

    /** The add form's rule: digits only, no leading zeros; a decimal point or too large a value is refused. */
    fun onAmountChange(text: String) {
        val digits = typedKyatDigits(text) ?: return
        _state.update { it.copy(amountDigits = digits, saveFailed = false) }
    }

    fun onSourceChange(source: IncomeSource) = _state.update { it.copy(source = source, saveFailed = false) }

    /** [description] is the chosen source's label in the app's language, which the entry keeps. */
    fun save(description: String) {
        val s = _state.value
        val amount = s.amount ?: return
        if (s.saving) return
        val input = NewTransaction(
            type = TxType.INCOME,
            amountKyat = amount,
            category = CategoryRepository.INCOME,
            description = description,
            date = s.today,
            source = Source.MANUAL,
            transcript = null,
        )
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                transactions.addAll(listOf(input))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; nothing was saved. The screen stays for another try, or Skip.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            _events.send(FirstIncomeEvent.Saved)
        }
    }
}
