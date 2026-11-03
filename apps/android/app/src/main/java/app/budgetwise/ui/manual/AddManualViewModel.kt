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
import app.budgetwise.data.repo.ReplaceResult
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.di.DeviceClock
import app.budgetwise.di.WorkDispatcher
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.MAX_KYAT
import app.budgetwise.ui.common.TypedKyat
import app.budgetwise.ui.common.readTypedKyat
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

enum class AmountError { MISSING, ZERO }

enum class DescriptionError { BLANK, TOO_LONG }

enum class CategoryError { MISSING, TOO_LONG }

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
    /** The user picked [date] (a chip or the date sheet); until then a new entry's date follows [today]. */
    val dateTouched: Boolean = false,
    /**
     * The date of the entry being changed. It is kept even when it is after [today] (the web, a scan or voice can save
     * one), so fixing the amount never forces a new date; only moving to another future day is refused.
     */
    val keptDate: LocalDate? = null,
    /** A fraction of a kyat was refused: the amount says it takes whole kyat, until the next change it keeps. */
    val wholeKyatHint: Boolean = false,
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
    /** The loaded entry belongs to a receipt; expense date changes apply to that whole receipt. */
    val editingReceipt: Boolean = false,
    /** How the entry being changed was added, for the header's tag; null until it is read, and for a new entry. */
    val source: Source? = null,
    /** False while the entry being changed is read; the form waits, so a blank is never saved in its place. */
    val loaded: Boolean = true,
    val deleting: Boolean = false,
    val deleteFailed: Boolean = false,
    /**
     * Save changes found the entry gone: deleted on the web or another phone, and pulled away while the form was open.
     * Nothing was saved; the form offers to save it as a new entry ([AddManualViewModel.saveAsNew]).
     */
    val gone: Boolean = false,
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

    private val categoryProblem: CategoryError?
        get() {
            val name = savedCategory
            return when {
                name == null -> CategoryError.MISSING
                // A web category the server would refuse from the phone: the user picks another chip.
                name.length > AddManualViewModel.MAX_CATEGORY -> CategoryError.TOO_LONG
                else -> null
            }
        }

    val dateError: DateError? get() = if (date.isAfter(today) && date != keptDate) DateError.FUTURE else null

    val canSave: Boolean
        get() = loaded && !busy && !gone && amountProblem == null && descriptionProblem == null && categoryProblem == null &&
            dateError == null

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

    /**
     * Delete entry: hand [clientId] to the list under the form, which deletes it with Undo as a swipe does, then go
     * back. With no list to hand it to, [AddManualViewModel.deleteNow]. Fires once.
     */
    data class Delete(val clientId: String) : AddManualEvent
}

/**
 * The add form, and the same form changing a saved entry. A new entry is one [NewTransaction] with [Source.MANUAL]
 * through [TransactionStore.addAll]; a change goes through [TransactionStore.replace] (entries never change on the
 * server, so the old one is deleted and the changed one saved in its place) and keeps how the entry was added (one from
 * the web becomes typed, as the server takes no web source from the phone) and its transcript. Receipt expenses keep
 * their receipt and share one date, so changing that date moves every entry atomically. Converting an expense to income
 * makes it standalone while keeping its provenance. Writes are PENDING rows that work offline; sync runs when connected.
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
    @DeviceClock private val clock: Clock,
    @WorkDispatcher work: CoroutineDispatcher,
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
                // Every entry is read and matched, again on each write a sync makes: off the main thread.
                .flowOn(work)
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
        // A new entry the user gave no day is for today, also when the process died before midnight and comes back
        // after it; a picked day, or the day of the entry being changed, is kept.
        date = saved.get<Long>(KEY_DATE)?.takeIf { saved.get<Boolean>(KEY_DATE_TOUCHED) == true || editId != null }
            ?.let(LocalDate::ofEpochDay) ?: today,
        dateTouched = saved[KEY_DATE_TOUCHED] ?: false,
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
        // Read on every load, so it is back after process death too.
        _state.update { it.copy(source = entry.source, loaded = true, keptDate = entry.date, editingReceipt = entry.receiptId != null) }
    }

    /** Every change the user makes goes through here, so the saved copy always matches the form. */
    private fun edit(change: (AddManualUiState) -> AddManualUiState) {
        val s = _state.updateAndGet { change(it).copy(saveFailed = false, deleteFailed = false) }
        saved[KEY_TYPE] = s.type.name
        saved[KEY_AMOUNT] = s.amountDigits
        saved[KEY_CATEGORY] = s.category
        saved[KEY_DESCRIPTION] = s.description
        saved[KEY_DATE] = s.date.toEpochDay()
        saved[KEY_DATE_TOUCHED] = s.dateTouched
        saved[KEY_AMOUNT_TOUCHED] = s.amountTouched
        saved[KEY_DESCRIPTION_TOUCHED] = s.descriptionTouched
        saved[KEY_CATEGORY_TOUCHED] = s.categoryTouched
        saved[KEY_CATEGORY_FROM] = s.categoryFrom
    }

    fun onTypeChange(type: TxType) = edit { it.copy(type = type) }

    /**
     * Keeps digits only (a Burmese keyboard's Myanmar digits become ASCII ones; grouping commas, "Ks" and ".00" of a
     * pasted bank amount are dropped) and leading zeros off ([readTypedKyat]). A fraction or a value above [MAX_KYAT]
     * is refused: the field keeps what it had, and a fraction says the field takes whole kyat.
     */
    fun onAmountChange(text: String) {
        when (val typed = readTypedKyat(text, _state.value.amountDigits)) {
            is TypedKyat.Digits -> edit { it.copy(amountDigits = typed.digits, amountTouched = true, wholeKyatHint = false) }
            TypedKyat.Fraction -> _state.update { it.copy(wholeKyatHint = true) }
            TypedKyat.TooLarge -> Unit
        }
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

    fun onDateChange(date: LocalDate) = edit { it.copy(date = date, dateTouched = true) }

    /**
     * Moves [AddManualUiState.today] to the clock's day: the form can stay open over midnight. A new entry still on the
     * old today moves with it, so the Today chip the user sees picked is the day it is saved under; a day the user
     * picked, and the day of an entry being changed, stay as they are. The screen calls it when it comes back.
     */
    fun refreshToday() {
        val now = LocalDate.now(clock)
        if (_state.value.today == now) return
        val moved = _state.updateAndGet { s ->
            val follows = !s.dateTouched && !s.editing && s.date == s.today
            s.copy(today = now, date = if (follows) now else s.date)
        }
        saved[KEY_DATE] = moved.date.toEpochDay()
    }

    fun save() {
        refreshToday()
        val s = _state.value
        if (!s.canSave) return
        val before = original
        if (editId != null && before == null) return
        val input = s.toInput(before) ?: return
        // Nothing changed: the entry stays as it is, rather than being deleted and saved again on the server.
        if (before != null && before.unchangedBy(input)) {
            _events.trySend(AddManualEvent.Saved)
            return
        }
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val result = try {
                if (editId != null) {
                    val receiptId = before?.receiptId
                    if (receiptId != null && input.receiptId != null && input.date != before.date) {
                        changeReceiptDate(receiptId, editId, input)
                    } else {
                        transactions.replace(listOf(editId), listOf(input))
                    }
                } else {
                    ReplaceResult.Saved(transactions.addAll(listOf(input)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; nothing was saved (one transaction). The form stays for another try.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            if (result == null) {
                // A sibling changed while the shared-date edit was being prepared. Nothing was written.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            if (result == ReplaceResult.Gone) {
                // Deleted elsewhere while the form was open: nothing was written. The user decides (Save as new, or close).
                _state.update { it.copy(saving = false, gone = true) }
                return@launch
            }
            _events.send(AddManualEvent.Saved)
        }
    }

    /** Save as new, once Save changes found the entry deleted elsewhere: the form saved as a new entry, then it closes. */
    fun saveAsNew() {
        val s = _state.value
        if (!s.gone || s.saving) return
        val before = original ?: return
        val input = s.copy(gone = false).toInput(before) ?: return
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val receipt = input.receiptId
                val siblings = receipt?.let { transactions.observeReceipt(it).first() }.orEmpty()
                if (receipt == null || siblings.isEmpty()) {
                    // A receipt with no entries left is gone too: keep this entry on its own.
                    transactions.addAll(listOf(input.copy(receiptId = null)))
                } else {
                    // Reattaching a saved-as-new expense must not introduce a second date to its receipt.
                    val inputs = siblings.map { it.forReceiptDate(input.date) } + input
                    if (transactions.replaceReceipt(receipt, siblings.mapTo(HashSet()) { it.clientId }, inputs) == null) {
                        _state.update { it.copy(saving = false, saveFailed = true) }
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            _events.send(AddManualEvent.Saved)
        }
    }

    /** The atomic receipt guard refuses a concurrent sibling edit instead of moving only part of the receipt. */
    private suspend fun changeReceiptDate(receiptId: String, clientId: String, input: NewTransaction): ReplaceResult? {
        val entries = transactions.observeReceipt(receiptId).first()
        if (entries.none { it.clientId == clientId }) {
            return if (transactions.get(clientId) == null) ReplaceResult.Gone else null
        }
        val inputs = entries.map { if (it.clientId == clientId) input else it.forReceiptDate(input.date) }
        val ids = transactions.replaceReceipt(receiptId, entries.mapTo(HashSet()) { it.clientId }, inputs)
        return if (ids != null) ReplaceResult.Saved(ids) else if (transactions.get(clientId) == null) ReplaceResult.Gone else null
    }

    /** Preserve the sibling's contents and provenance while moving it with its receipt. */
    private fun Transaction.forReceiptDate(day: LocalDate) = NewTransaction(
        type = type,
        amountKyat = amountKyat,
        category = category,
        description = description,
        date = day,
        source = source.takeUnless { it == Source.WEB } ?: Source.MANUAL,
        transcript = transcript,
        receiptId = receiptId,
        merchant = merchant,
        items = items,
    )

    /**
     * Delete entry. The list under the form deletes it, with the same notice and Undo as a swipe ([AddManualEvent.Delete]),
     * so a tap meant for Save changes just above it is one tap to undo.
     */
    fun delete() {
        val id = editId ?: return
        val s = _state.value
        if (!s.loaded || s.busy || s.gone) return
        _state.update { it.copy(deleting = true, saveFailed = false, deleteFailed = false) }
        _events.trySend(AddManualEvent.Delete(id))
    }

    /** Deletes the entry here and now (offline too), then the form closes: when there is no list to hand [delete] to. */
    fun deleteNow() {
        val id = editId ?: return
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

    /** What [save] writes: the form, with what it does not show kept from the entry being changed ([before]). */
    private fun AddManualUiState.toInput(before: Transaction?): NewTransaction? = NewTransaction(
        type = type,
        amountKyat = amount ?: return null,
        category = savedCategory ?: return null,
        description = description.trim(),
        date = date,
        // The server takes no "web" source from the phone: an entry from the website changed here is typed now.
        source = before?.source?.takeUnless { it == Source.WEB } ?: Source.MANUAL,
        transcript = before?.transcript,
        // Standalone scanned entries keep their id, including KPay payments that may be scanned again.
        clientId = before?.takeIf { it.keepsScanId }?.clientId,
        // Income stands alone. Receipt grouping and item editing are for the receipt's expenses only.
        receiptId = before?.receiptId?.takeIf { type == TxType.EXPENSE },
        merchant = before?.merchant,
        items = before?.items.orEmpty(),
    )

    /**
     * Compared as the repository stores them: the web keeps spaces round a description that the form trims, and a
     * save that changes nothing visible must write nothing.
     */
    private fun Transaction.unchangedBy(input: NewTransaction) = type == input.type && amountKyat == input.amountKyat &&
        category == input.category && date == input.date &&
        BurmeseTextNormalizer.prepare(description) == BurmeseTextNormalizer.prepare(input.description)

    /**
     * A standalone scan keeps its id across edits. KPay slips/history rely on this for repeat-scan deduplication;
     * a receipt entry converted to standalone income can keep its id on later edits as well.
     */
    private val Transaction.keepsScanId: Boolean get() = source == Source.SCAN && receiptId == null

    companion object {
        /** The server's limit for a description, in characters of the prepared text. */
        const val MAX_DESCRIPTION = TransactionRepository.MAX_DESCRIPTION

        /** The server's limit for a category name; the web takes longer ones. */
        const val MAX_CATEGORY = TransactionRepository.MAX_CATEGORY

        /** Navigation arguments: the clientId of the entry to change, and a new entry that starts as income. */
        const val ARG_CLIENT_ID = "clientId"
        const val ARG_INCOME = "income"

        // SavedStateHandle keys; values are Bundle types (String, Long, Boolean).
        private const val KEY_TYPE = "type"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_CATEGORY = "category"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_DATE = "dateEpochDay"
        private const val KEY_DATE_TOUCHED = "dateTouched"
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
