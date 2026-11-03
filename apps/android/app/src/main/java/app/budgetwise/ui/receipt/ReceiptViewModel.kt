package app.budgetwise.ui.receipt

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.CategorySource
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.Transaction
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.ui.scan.ReviewLine
import app.budgetwise.ui.scan.SavedEditing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReceiptUiState(
    /** True until Room's first answer, so the screen never closes before it knows what is left. */
    val loading: Boolean = true,
    /** The receipt's entries, largest first. */
    val entries: List<Transaction> = emptyList(),
    val categories: List<String> = CategoryRepository.BUILT_IN,
    val myanmarDigits: Boolean = false,
    /** Change items: the entries as lines to change, one per category; null while the receipt itself shows. */
    val editing: List<ReviewLine>? = null,
    /** What the receipt came to when Change items opened, for "Saved as". */
    val editingBase: Long = 0,
    /**
     * Change items is open, and the receipt's entries are no longer the ones it opened on: a sync brought a change made
     * on another phone or the web. Saving would write over it, so the lines are checked again first.
     */
    val receiptChanged: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** No entries left: all deleted (here, from the list or on the web), or another account is active now. */
    val gone: Boolean get() = !loading && entries.isEmpty()

    val merchant: String? get() = entries.firstNotNullOfOrNull { it.merchant }

    /**
     * The receipt's date. Its entries share one; a receipt saved across two days before that rule shows on the newer,
     * as in the list.
     */
    val date: LocalDate? get() = entries.maxOfOrNull { it.date }

    val totalKyat: Long get() = entries.sumOf { it.amountKyat }

    /** Change items has something to change: an entry with items (an Add as Other line, or a read without, has none). */
    val canChangeItems: Boolean get() = entries.any { it.items.isNotEmpty() }

    /** Items whose count or price was changed in Change items. */
    val changedItems: Int get() = editing.orEmpty().sumOf { it.changedItems }

    /** What the changed lines come to. */
    val editedTotal: Long get() = editing.orEmpty().sumOf { it.amountKyat }

    /** Every item was set to 0: nothing would be left to save, so Change items offers Delete this receipt instead. */
    val allLeftOut: Boolean get() = changedItems > 0 && editing.orEmpty().none { it.amountKyat > 0 }

    /** Something changed, something is left, and the receipt is still the one the changes were made on. */
    val canSave: Boolean get() = !saving && !receiptChanged && changedItems > 0 && !allLeftOut
}

sealed interface ReceiptEvent {
    /**
     * Delete this receipt: [clientIds], its entries as Room has them now (a move or Change items gives entries new
     * ids). The list deletes them with its Undo notice, as after a swipe. Fires once.
     */
    data class Delete(val receiptId: String, val clientIds: List<String>) : ReceiptEvent
}

/**
 * One scanned receipt: the entries it saved, read from Room. Entries never change on the server, so every change here
 * replaces entries ([TransactionStore.replace]) with new ones that keep the receipt's id, shop and date. The screen
 * closes once the receipt has no entries left. Change items is kept in [saved] while it is open, so the user's changes
 * outlive the process.
 */
@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val receiptId: String = checkNotNull(saved[RECEIPT_ID]) { "the receipt route carries its id" }

    private data class Local(
        val editing: List<ReviewLine>? = null,
        /** The entries Change items was built from, to tell when the receipt changes under it. */
        val editingIds: Set<String> = emptySet(),
        val editingBase: Long = 0,
        /** A save found the receipt changed (before Room's next answer says so). */
        val changed: Boolean = false,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    private val _events = Channel<ReceiptEvent>(Channel.BUFFERED)
    val events: Flow<ReceiptEvent> = _events.receiveAsFlow()

    val state: StateFlow<ReceiptUiState> =
        combine(transactions.observeReceipt(receiptId), local, categories.categories, settings.myanmarDigits) { entries, l, names, digits ->
            ReceiptUiState(
                loading = false,
                entries = entries,
                categories = names.filter { it != CategoryRepository.INCOME },
                myanmarDigits = digits,
                editing = l.editing,
                editingBase = l.editingBase,
                // While saving, the receipt's ids change because of this save.
                receiptChanged = l.editing != null && (l.changed || !l.saving && entries.map { it.clientId }.toSet() != l.editingIds),
                saving = l.saving,
                saveFailed = l.saveFailed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptUiState())

    init {
        // Change items from before the process ended comes back only onto the same entries: in the meantime a sync
        // may have changed the receipt, or deleted it.
        saved.get<String>(EDITING)?.let { SavedEditing.from(it) }?.let { kept ->
            viewModelScope.launch {
                if (entries().map { it.clientId }.toSet() == kept.fromIds) {
                    local.update { it.copy(editing = kept.lines, editingIds = kept.fromIds, editingBase = kept.baseKyat) }
                }
            }
        }
        viewModelScope.launch {
            local.collect { l ->
                val editing = l.editing
                if (editing != null) saved[EDITING] = SavedEditing(editing, l.editingIds, l.editingBase).toJson() else saved.remove<String>(EDITING)
            }
        }
    }

    /**
     * One entry under another category; the rest of it stays. When the receipt has an entry in that category already,
     * the moved one joins it (its amount and items), as on Check the receipt, unless together they would have more
     * items than an entry keeps. A description the app wrote for the old category (the shop and the category, in any
     * of its languages, see [EntryDescriptions]) is written again for the new one; one the user wrote stays as it is.
     */
    fun move(clientId: String, category: String, descriptions: EntryDescriptions) = write {
        val entry = transactions.get(clientId) ?: return@write
        if (entry.category == category) return@write
        val into = entries().firstOrNull {
            it.clientId != clientId && it.type == entry.type && it.category == category &&
                it.items.size + entry.items.size <= TransactionRepository.MAX_ITEMS
        }
        if (into != null) {
            val joined = into.toNew(category, into.description).copy(amountKyat = into.amountKyat + entry.amountKyat, items = into.items + entry.items)
            transactions.replace(listOf(into.clientId, clientId), listOf(joined))
            return@write
        }
        val description = entry.merchant
            ?.takeIf { descriptions.wrote(entry.description, it, entry.category) }
            ?.let { descriptions.describe(it, category) }
            ?: entry.description
        transactions.replace(listOf(clientId), listOf(entry.toNew(category, description)))
    }

    /**
     * Delete this receipt: its entries go to the list to delete, with Undo ([ReceiptEvent.Delete]). Read from Room now,
     * as a move or Change items gives entries new ids.
     */
    fun delete() = write {
        val ids = entries().map { it.clientId }
        if (ids.isNotEmpty()) _events.send(ReceiptEvent.Delete(receiptId, ids))
    }

    /** Deletes [clientIds] at once, with no Undo: for when there is no list under the receipt to offer one. */
    fun deleteNow(clientIds: List<String>) = write {
        transactions.replace(clientIds, emptyList())
    }

    /**
     * Change items: the entries as lines to change, one per category, as the scan review shows a receipt. Entries of one
     * category whose items together are more than an entry keeps stay one line each, so saving cuts none.
     */
    fun startEditing() = local.update { l ->
        val entries = state.value.entries
        val lines = entries.groupBy { it.category }.flatMap { (category, rows) ->
            val parts = if (rows.sumOf { it.items.size } > TransactionRepository.MAX_ITEMS) rows.map { listOf(it) } else listOf(rows)
            parts.map { part ->
                val first = part.first()
                ReviewLine(first.type, part.sumOf { it.amountKyat }, category, first.description, first.date, null, part.flatMap { it.items })
            }
        }
        l.copy(
            editing = lines,
            editingIds = entries.map { it.clientId }.toSet(),
            editingBase = entries.sumOf { it.amountKyat },
            changed = false,
            saveFailed = false,
        )
    }

    fun stopEditing() = local.update { it.copy(editing = null, editingIds = emptySet(), changed = false, saveFailed = false) }

    fun setCount(line: Int, item: Int, count: Int) = edit(line) { it.withCount(item, count) }

    fun setPrice(line: Int, item: Int, price: Long) = edit(line) { it.withPrice(item, price) }

    private fun edit(index: Int, change: (ReviewLine) -> ReviewLine) = local.update { l ->
        val lines = l.editing ?: return@update l
        val line = lines.getOrNull(index) ?: return@update l
        l.copy(editing = lines.toMutableList().also { it[index] = change(line) }, saveFailed = false)
    }

    /**
     * Saves Change items: the receipt's entries are replaced by one per category, at what its items now come to, without
     * the items left at 0; a category with nothing left is dropped. They all take the receipt's one date. Nothing is
     * written when the receipt changed since Change items opened ([ReceiptUiState.receiptChanged]).
     */
    fun saveItems() {
        val l = local.value
        val lines = l.editing ?: return
        if (!state.value.canSave) return
        local.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val old = entries()
                val merchant = old.firstNotNullOfOrNull { it.merchant }
                val day = old.maxOfOrNull { it.date }
                val inputs = lines.filter { it.amountKyat > 0 }.map { it.toNew(merchant, day ?: it.date) }
                if (transactions.replaceReceipt(receiptId, l.editingIds, inputs) == null) {
                    local.update { it.copy(saving = false, changed = true) }
                } else {
                    local.update { it.copy(editing = null, editingIds = emptySet(), saving = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; nothing changed (one transaction). The changes stay for another try.
                local.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** The receipt's entries as Room has them now (the screen may be a moment behind). */
    private suspend fun entries(): List<Transaction> = transactions.observeReceipt(receiptId).first()

    private fun write(block: suspend () -> Unit) {
        local.update { it.copy(saveFailed = false) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(saveFailed = true) }
            }
        }
    }

    private fun Transaction.toNew(category: String, description: String) = NewTransaction(
        type = type,
        amountKyat = amountKyat,
        category = category,
        description = description,
        date = date,
        source = source,
        transcript = transcript,
        receiptId = receiptId,
        merchant = merchant,
        items = items,
    )

    private fun ReviewLine.toNew(merchant: String?, date: LocalDate) = NewTransaction(
        type = type,
        amountKyat = amountKyat,
        category = category,
        description = description,
        date = date,
        source = Source.SCAN,
        transcript = null,
        receiptId = receiptId,
        merchant = merchant,
        items = savedItems,
    )

    companion object {
        /** The route's argument: the receipt's id. */
        const val RECEIPT_ID = "receiptId"

        /** Where [saved] keeps Change items while it is open. */
        private const val EDITING = "receipt_editing"
    }
}
