package app.budgetwise.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.CategorySource
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.model.Source
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.MAX_KYAT
import app.budgetwise.ui.common.asciiDigits
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
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

enum class AmountError { MISSING, ZERO }

enum class DescriptionError { BLANK, TOO_LONG }

enum class CategoryError { MISSING }

enum class DateError { FUTURE }

data class AddManualUiState(
    val today: LocalDate,
    val type: TxType = TxType.EXPENSE,
    /** Plain ASCII digits as typed, without grouping; the field shows them grouped. */
    val amountDigits: String = "",
    val category: String? = null,
    val categories: List<String> = CategoryRepository.BUILT_IN,
    val description: String = "",
    val date: LocalDate = today,
    val myanmarDigits: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** Which fields the user has changed; a changed field shows its error. */
    val amountTouched: Boolean = false,
    val descriptionTouched: Boolean = false,
    val categoryTouched: Boolean = false,
) {
    val amount: Long? get() = amountDigits.toLongOrNull()

    private val amountProblem: AmountError?
        get() = when (amount) {
            null -> AmountError.MISSING
            0L -> AmountError.ZERO
            else -> null
        }

    private val descriptionProblem: DescriptionError?
        get() {
            val length = description.trim().length
            return when {
                length == 0 -> DescriptionError.BLANK
                length > AddManualViewModel.MAX_DESCRIPTION -> DescriptionError.TOO_LONG
                else -> null
            }
        }

    private val categoryProblem: CategoryError? get() = if (category == null) CategoryError.MISSING else null

    val dateError: DateError? get() = if (date.isAfter(today)) DateError.FUTURE else null

    val canSave: Boolean
        get() = !saving && amountProblem == null && descriptionProblem == null && categoryProblem == null && dateError == null

    private val problems: List<Any> get() = listOfNotNull(amountProblem, descriptionProblem, categoryProblem, dateError)

    // A field's error shows once the user has changed it, or once it is the only thing left that keeps Save off, so
    // the form never opens in red and the user always sees why Save is disabled.
    val amountError: AmountError? get() = amountProblem?.takeIf { amountTouched || problems.size == 1 }

    val descriptionError: DescriptionError? get() = descriptionProblem?.takeIf { descriptionTouched || problems.size == 1 }

    val categoryError: CategoryError? get() = categoryProblem?.takeIf { categoryTouched || problems.size == 1 }
}

sealed interface AddManualEvent {
    /** The entry is in Room (and the outbox): go back. Fires once. */
    data object Saved : AddManualEvent
}

/**
 * The add form. [save] writes one [NewTransaction] with [Source.MANUAL] through [TransactionStore.addAll], which
 * stores it as a PENDING row and works offline; the sync runs when there is a connection.
 */
@HiltViewModel
class AddManualViewModel @Inject constructor(
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    session: SessionManager,
    @DeviceClock clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(AddManualUiState(today = LocalDate.now(clock)))
    val state: StateFlow<AddManualUiState> = _state.asStateFlow()

    private val _events = Channel<AddManualEvent>(Channel.BUFFERED)
    val events: Flow<AddManualEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { categories.categories.collect { names -> _state.update { it.copy(categories = names) } } }
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
        // Freshen the cached list for the next time; offline or signed out the cache (or the built-ins) is used.
        if (session.state.value is SessionState.SignedIn) viewModelScope.launch { categories.refresh() }
    }

    fun onTypeChange(type: TxType) = _state.update { it.copy(type = type) }

    /**
     * Keeps digits only (a Burmese keyboard's Myanmar digits become ASCII ones, grouping commas are dropped) and
     * leading zeros off. A decimal point or a value above [MAX_KYAT] is refused: the field keeps what it had.
     */
    fun onAmountChange(text: String) {
        if ('.' in text) return
        val raw = asciiDigits(text)
        val digits = if (raw.isEmpty()) "" else raw.trimStart('0').ifEmpty { "0" }
        if (digits.length > MAX_DIGITS || (digits.isNotEmpty() && digits.toLong() > MAX_KYAT)) return
        _state.update { it.copy(amountDigits = digits, amountTouched = true, saveFailed = false) }
    }

    fun onCategoryChange(category: String) =
        _state.update { it.copy(category = category, categoryTouched = true, saveFailed = false) }

    fun onDescriptionChange(description: String) =
        _state.update { it.copy(description = description, descriptionTouched = true, saveFailed = false) }

    fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date, saveFailed = false) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val input = NewTransaction(
            type = s.type,
            amountKyat = s.amount ?: return,
            category = s.category ?: return,
            description = s.description.trim(),
            date = s.date,
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
                // Room failed; nothing was saved (one transaction). The form stays for another try.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            _events.send(AddManualEvent.Saved)
        }
    }

    companion object {
        /** The server's limit for a description, in characters after trimming. */
        const val MAX_DESCRIPTION = 200
        private val MAX_DIGITS = MAX_KYAT.toString().length
    }
}
