package app.budgetwise.ui.manual

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.CategorySource
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.SessionState
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.MAX_KYAT
import app.budgetwise.ui.common.typedKyatDigits
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

enum class AmountError { MISSING, ZERO }

enum class DescriptionError { BLANK, TOO_LONG }

enum class CategoryError { MISSING }

enum class DateError { FUTURE }

/** One of the user's past entries, offered under "What was it for?": tapping it fills in all three. */
data class RecentEntry(val type: TxType, val description: String, val amountKyat: Long, val category: String) {
    /** [description] as it is matched: stored text is already prepared, so only case and spacing are evened out. */
    val key: String = BurmeseTextNormalizer.normalize(description).lowercase(Locale.ROOT)
}

/** What was typed, matched against [RecentEntry.key]: prepared as the repository would store it, then lowercased. */
internal fun typedKey(text: String): String = BurmeseTextNormalizer.prepare(text).lowercase(Locale.ROOT)

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
    /** The past entry's description the category was picked from ("Picked from …"); null once the user taps one. */
    val categoryFrom: String? = null,
    /** The user's past entries, newest first, one per description: the chips under the description and the pick. */
    val past: List<RecentEntry> = emptyList(),
    /** Changing a saved entry rather than adding one. */
    val editing: Boolean = false,
    /** How the entry being changed was added, for the header's tag; null until it is read, and for a new entry. */
    val source: Source? = null,
    /** False while the entry being changed is read; the form waits, so a blank is never saved in its place. */
    val loaded: Boolean = true,
    val deleting: Boolean = false,
    val deleteFailed: Boolean = false,
) {
    val amount: Long? get() = amountDigits.toLongOrNull()

    /** Length of the description as the repository will store it (Zawgyi converted, normalized), which can exceed what was typed. */
    val descriptionLength: Int by lazy(LazyThreadSafetyMode.NONE) { BurmeseTextNormalizer.prepare(description).length }

    /**
     * Up to three past entries of this type with what is typed in their description, newest first. One already
     * filled in (same description and amount) is left out, and a changed entry offers none: it is filled in already.
     */
    val recent: List<RecentEntry> by lazy(LazyThreadSafetyMode.NONE) {
        if (editing) return@lazy emptyList()
        val typed = typedKey(description)
        past.asSequence()
            .filter { it.type == type && typed in it.key }
            .filterNot { it.key == typed && it.amountKyat == amount }
            .take(MAX_RECENT)
            .toList()
    }

    /** The chips to pick from: a category of the entry being changed that the list no longer has is kept. */
    val categoryChoices: List<String> get() = if (category == null || category in categories) categories else categories + category

    /** Busy saving or deleting: the form is locked. */
    val busy: Boolean get() = saving || deleting

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
        get() = loaded && !busy && amountProblem == null && descriptionProblem == null && categoryProblem == null && dateError == null

    private val problems: List<Any> get() = listOfNotNull(amountProblem, descriptionProblem, categoryProblem, dateError)

    // A field's error shows once the user has changed it, or once it is the only thing left that keeps Save off, so
    // the form never opens in red and the user always sees why Save is disabled.
    val amountError: AmountError? get() = amountProblem?.takeIf { amountTouched || problems.size == 1 }

    val descriptionError: DescriptionError? get() = descriptionProblem?.takeIf { descriptionTouched || problems.size == 1 }

    val categoryError: CategoryError? get() = categoryProblem?.takeIf { categoryTouched || problems.size == 1 }

    companion object {
        /** How many recent entries the form offers at once: one row on a phone, two at most. */
        const val MAX_RECENT = 3
    }
}

sealed interface AddManualEvent {
    /** The entry is in Room (and the outbox): go back. Fires once. */
    data object Saved : AddManualEvent

    /** The entry being changed is gone (deleted here, or before the form could read it): go back. Fires once. */
    data object Gone : AddManualEvent
}

/**
 * The add form, and the same form changing a saved entry. A new entry is one [NewTransaction] with [Source.MANUAL]
 * through [TransactionStore.addAll]; a change goes through [TransactionStore.replace] (entries never change on the
 * server, so the old one is deleted and the changed one saved in its place) and keeps how the entry was added, its
 * transcript and its receipt. Both are PENDING rows that work offline; the sync runs when there is a connection.
 *
 * The navigation arguments pick the mode: [ARG_CLIENT_ID] changes that entry, [ARG_INCOME] starts a new one as income.
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
    /** The entry being changed, or null for a new one. */
    private val editId: String? = saved[ARG_CLIENT_ID]

    /** That entry as it was read, for what the form does not show (how it was added, its receipt). */
    private var original: Transaction? = null

    private val _state = MutableStateFlow(restore(LocalDate.now(clock)))
    val state: StateFlow<AddManualUiState> = _state.asStateFlow()

    private val _events = Channel<AddManualEvent>(Channel.BUFFERED)
    val events: Flow<AddManualEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { categories.categories.collect { names -> _state.update { it.copy(categories = names) } } }
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
        viewModelScope.launch {
            transactions.observe()
                .map(::pastEntries)
                // Room failed: no chips and no picked category, and the form still works.
                .catch { }
                .collect { past -> _state.update { it.copy(past = past) } }
        }
        editId?.let { id -> viewModelScope.launch { load(id) } }
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
        type = saved.get<String>(KEY_TYPE)?.let { name -> TxType.entries.firstOrNull { it.name == name } }
            ?: if (saved.get<Boolean>(ARG_INCOME) == true) TxType.INCOME else TxType.EXPENSE,
        amountDigits = saved[KEY_AMOUNT] ?: "",
        category = saved[KEY_CATEGORY],
        description = saved[KEY_DESCRIPTION] ?: "",
        date = saved.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: today,
        amountTouched = saved[KEY_AMOUNT_TOUCHED] ?: false,
        descriptionTouched = saved[KEY_DESCRIPTION_TOUCHED] ?: false,
        categoryTouched = saved[KEY_CATEGORY_TOUCHED] ?: false,
        categoryFrom = saved[KEY_CATEGORY_FROM],
        editing = editId != null,
        loaded = editId == null,
    )

    /**
     * Reads the entry being changed. The first time it fills the form in; after process death the form already holds
     * the user's changes, so only what it does not show is read again. A missing entry closes the form.
     */
    private suspend fun load(clientId: String) {
        val entry = try {
            transactions.get(clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // Room failed: there is nothing to change.
        }
        if (entry == null) {
            _events.send(AddManualEvent.Gone)
            return
        }
        original = entry
        if (saved.get<Boolean>(KEY_FILLED) != true) {
            edit {
                it.copy(
                    type = entry.type,
                    amountDigits = entry.amountKyat.toString(),
                    // The expense pick; income is always saved under "Income".
                    category = entry.category.takeIf { entry.type == TxType.EXPENSE },
                    description = entry.description,
                    date = entry.date,
                    // Chosen when the entry was made: a description typed now does not pick another.
                    categoryTouched = true,
                )
            }
            saved[KEY_FILLED] = true
        }
        _state.update { it.copy(source = entry.source, loaded = true) }
    }

    /** Every change the user makes goes through here, so the saved copy always matches the form. */
    private fun edit(change: (AddManualUiState) -> AddManualUiState) {
        val s = _state.updateAndGet { change(it).copy(saveFailed = false, deleteFailed = false) }
        saved[KEY_TYPE] = s.type.name
        saved[KEY_AMOUNT] = s.amountDigits
        saved[KEY_CATEGORY] = s.category
        saved[KEY_DESCRIPTION] = s.description
        saved[KEY_DATE] = s.date.toEpochDay()
        saved[KEY_AMOUNT_TOUCHED] = s.amountTouched
        saved[KEY_DESCRIPTION_TOUCHED] = s.descriptionTouched
        saved[KEY_CATEGORY_TOUCHED] = s.categoryTouched
        saved[KEY_CATEGORY_FROM] = s.categoryFrom
    }

    fun onTypeChange(type: TxType) = edit { it.copy(type = type) }

    /**
     * Keeps digits only (a Burmese keyboard's Myanmar digits become ASCII ones, grouping commas are dropped) and
     * leading zeros off. A decimal point or a value above [MAX_KYAT] is refused: the field keeps what it had.
     */
    fun onAmountChange(text: String) {
        val digits = typedKyatDigits(text) ?: return
        edit { it.copy(amountDigits = digits, amountTouched = true) }
    }

    fun onCategoryChange(category: String) = edit { it.copy(category = category, categoryTouched = true, categoryFrom = null) }

    /** An expense typed as one saved before gets that entry's category, until the user taps one: their tap wins. */
    fun onDescriptionChange(description: String) = edit { s ->
        val typed = s.copy(description = description, descriptionTouched = true)
        if (typed.categoryTouched) return@edit typed
        // A pick stays while the user goes on typing ("Mohinga" then "Mohinga with Ko Aung"); a new match replaces it.
        val key = typedKey(description)
        val match = s.past.firstOrNull { it.type == TxType.EXPENSE && it.key == key }
        match?.let { typed.copy(category = it.category, categoryFrom = it.description) } ?: typed
    }

    /** A recent chip: its description and amount, and for an expense its category, shown as picked from it. */
    fun onRecentPick(entry: RecentEntry) = edit { s ->
        val filled = s.copy(description = entry.description, amountDigits = entry.amountKyat.toString(), descriptionTouched = true, amountTouched = true)
        if (entry.type == TxType.EXPENSE) filled.copy(category = entry.category, categoryFrom = entry.description) else filled
    }

    fun onDateChange(date: LocalDate) = edit { it.copy(date = date) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val before = original
        if (editId != null && before == null) return
        val input = NewTransaction(
            type = s.type,
            amountKyat = s.amount ?: return,
            category = s.savedCategory ?: return,
            description = s.description.trim(),
            date = s.date,
            source = before?.source ?: Source.MANUAL,
            transcript = before?.transcript,
            receiptId = before?.receiptId,
            merchant = before?.merchant,
            items = before?.items.orEmpty(),
        )
        // Nothing changed: the entry stays as it is, rather than being deleted and saved again on the server.
        if (before != null && before.unchangedBy(input)) {
            _events.trySend(AddManualEvent.Saved)
            return
        }
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                if (editId != null) transactions.replace(listOf(editId), listOf(input)) else transactions.addAll(listOf(input))
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

    /** Deletes the entry being changed (offline too, like a swipe on the list), then the form closes. */
    fun delete() {
        val id = editId ?: return
        if (!_state.value.loaded || _state.value.busy) return
        _state.update { it.copy(deleting = true, saveFailed = false, deleteFailed = false) }
        viewModelScope.launch {
            try {
                transactions.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; the entry is still there. The form stays for another try.
                _state.update { it.copy(deleting = false, deleteFailed = true) }
                return@launch
            }
            _events.send(AddManualEvent.Gone)
        }
    }

    private fun Transaction.unchangedBy(input: NewTransaction) = type == input.type && amountKyat == input.amountKyat &&
        category == input.category && description == input.description && date == input.date

    companion object {
        /** The server's limit for a description, in characters of the prepared text. */
        const val MAX_DESCRIPTION = TransactionRepository.MAX_DESCRIPTION

        /** Navigation arguments: the clientId of the entry to change, and a new entry that starts as income. */
        const val ARG_CLIENT_ID = "clientId"
        const val ARG_INCOME = "income"

        // SavedStateHandle keys; values are Bundle types (String, Long, Boolean).
        private const val KEY_TYPE = "type"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_CATEGORY = "category"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_DATE = "dateEpochDay"
        private const val KEY_AMOUNT_TOUCHED = "amountTouched"
        private const val KEY_DESCRIPTION_TOUCHED = "descriptionTouched"
        private const val KEY_CATEGORY_TOUCHED = "categoryTouched"
        private const val KEY_CATEGORY_FROM = "categoryFrom"
        private const val KEY_FILLED = "filled"

        /** Newest first (as [TransactionStore.observe] lists them), one per description. */
        private fun pastEntries(rows: List<Transaction>): List<RecentEntry> =
            rows.asSequence()
                .map { RecentEntry(it.type, it.description, it.amountKyat, it.category) }
                .filter { it.key.isNotEmpty() }
                .distinctBy { it.type to it.key }
                .toList()
    }
}
