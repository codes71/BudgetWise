package app.budgetwise.ui.manual

import androidx.lifecycle.SavedStateHandle
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
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.text.BurmeseTextNormalizer
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
import kotlinx.coroutines.flow.updateAndGet
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

    /** Length of the description as the repository will store it (Zawgyi converted, normalized), which can exceed what was typed. */
    val descriptionLength: Int by lazy(LazyThreadSafetyMode.NONE) { BurmeseTextNormalizer.prepare(description).length }

    private val amountProblem: AmountError?
        get() = when (amount) {
            null -> AmountError.MISSING
            0L -> AmountError.ZERO
            else -> null
        }

    private val descriptionProblem: DescriptionError?
        get() {
            val length = descriptionLength
            return when {
                length == 0 -> DescriptionError.BLANK
                length > AddManualViewModel.MAX_DESCRIPTION -> DescriptionError.TOO_LONG
                else -> null
            }
        }

    /**
     * What the entry is saved under. Income always goes under [CategoryRepository.INCOME], as on the web, so the
     * form only asks for a category for an expense; the expense pick is kept in case the user switches back.
     */
    val savedCategory: String? get() = if (type == TxType.INCOME) CategoryRepository.INCOME else category

    private val categoryProblem: CategoryError? get() = if (savedCategory == null) CategoryError.MISSING else null

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
 *
 * The fields live in [SavedStateHandle] too, so a form left half filled (the user checks an amount in a banking app
 * and the phone kills BudgetWise meanwhile) comes back as it was.
 */
@HiltViewModel
class AddManualViewModel @Inject constructor(
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    session: SessionManager,
    @DeviceClock clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(restore(LocalDate.now(clock)))
    val state: StateFlow<AddManualUiState> = _state.asStateFlow()

    private val _events = Channel<AddManualEvent>(Channel.BUFFERED)
    val events: Flow<AddManualEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { categories.categories.collect { names -> _state.update { it.copy(categories = names) } } }
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
        // Freshen the cached list for the next time; offline or signed out the cache (or the built-ins) is used.
        if (session.state.value is SessionState.SignedIn) {
            viewModelScope.launch {
                try {
                    categories.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Writing the list to DataStore failed (an IOException); the cached list stays.
                }
            }
        }
    }

    private fun restore(today: LocalDate) = AddManualUiState(
        today = today,
        type = saved.get<String>(KEY_TYPE)?.let { name -> TxType.entries.firstOrNull { it.name == name } } ?: TxType.EXPENSE,
        amountDigits = saved[KEY_AMOUNT] ?: "",
        category = saved[KEY_CATEGORY],
        description = saved[KEY_DESCRIPTION] ?: "",
        date = saved.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: today,
        amountTouched = saved[KEY_AMOUNT_TOUCHED] ?: false,
        descriptionTouched = saved[KEY_DESCRIPTION_TOUCHED] ?: false,
        categoryTouched = saved[KEY_CATEGORY_TOUCHED] ?: false,
    )

    /** Every change the user makes goes through here, so the saved copy always matches the form. */
    private fun edit(change: (AddManualUiState) -> AddManualUiState) {
        val s = _state.updateAndGet { change(it).copy(saveFailed = false) }
        saved[KEY_TYPE] = s.type.name
        saved[KEY_AMOUNT] = s.amountDigits
        saved[KEY_CATEGORY] = s.category
        saved[KEY_DESCRIPTION] = s.description
        saved[KEY_DATE] = s.date.toEpochDay()
        saved[KEY_AMOUNT_TOUCHED] = s.amountTouched
        saved[KEY_DESCRIPTION_TOUCHED] = s.descriptionTouched
        saved[KEY_CATEGORY_TOUCHED] = s.categoryTouched
    }

    fun onTypeChange(type: TxType) = edit { it.copy(type = type) }

    /**
     * Keeps digits only (a Burmese keyboard's Myanmar digits become ASCII ones, grouping commas are dropped) and
     * leading zeros off. A decimal point or a value above [MAX_KYAT] is refused: the field keeps what it had.
     */
    fun onAmountChange(text: String) {
        if ('.' in text) return
        val raw = asciiDigits(text)
        val digits = if (raw.isEmpty()) "" else raw.trimStart('0').ifEmpty { "0" }
        if (digits.length > MAX_DIGITS || (digits.isNotEmpty() && digits.toLong() > MAX_KYAT)) return
        edit { it.copy(amountDigits = digits, amountTouched = true) }
    }

    fun onCategoryChange(category: String) = edit { it.copy(category = category, categoryTouched = true) }

    fun onDescriptionChange(description: String) = edit { it.copy(description = description, descriptionTouched = true) }

    fun onDateChange(date: LocalDate) = edit { it.copy(date = date) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val input = NewTransaction(
            type = s.type,
            amountKyat = s.amount ?: return,
            category = s.savedCategory ?: return,
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
        /** The server's limit for a description, in characters of the prepared text. */
        const val MAX_DESCRIPTION = TransactionRepository.MAX_DESCRIPTION
        private val MAX_DIGITS = MAX_KYAT.toString().length

        // SavedStateHandle keys; values are Bundle types (String, Long, Boolean).
        private const val KEY_TYPE = "type"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_CATEGORY = "category"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_DATE = "dateEpochDay"
        private const val KEY_AMOUNT_TOUCHED = "amountTouched"
        private const val KEY_DESCRIPTION_TOUCHED = "descriptionTouched"
        private const val KEY_CATEGORY_TOUCHED = "categoryTouched"
    }
}
