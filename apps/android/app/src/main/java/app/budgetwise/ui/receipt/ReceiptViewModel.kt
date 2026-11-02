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
import app.budgetwise.ui.scan.ReviewLine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** No entries left: all deleted (here, from the list or on the web), or another account is active now. */
    val gone: Boolean get() = !loading && entries.isEmpty()

    val merchant: String? get() = entries.firstNotNullOfOrNull { it.merchant }

    val date: LocalDate? get() = entries.firstOrNull()?.date

    val totalKyat: Long get() = entries.sumOf { it.amountKyat }

    /** Items whose count or price was changed in Change items. */
    val changedItems: Int get() = editing.orEmpty().sumOf { it.changedItems }

    /** What the changed lines come to. */
    val editedTotal: Long get() = editing.orEmpty().sumOf { it.amountKyat }

    /** Something changed and something is left; leaving every item at 0 is Delete this receipt instead. */
    val canSave: Boolean get() = !saving && changedItems > 0 && editing.orEmpty().any { it.amountKyat > 0 }
}

/**
 * One scanned receipt: the entries it saved, read from Room. Entries never change on the server, so every change here
 * replaces entries ([TransactionStore.replace]) with new ones that keep the receipt's id, shop and date. The screen
 * closes once the receipt has no entries left.
 */
@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    saved: SavedStateHandle,
) : ViewModel() {
    private val receiptId: String = checkNotNull(saved[RECEIPT_ID]) { "the receipt route carries its id" }

    private data class Local(
        val editing: List<ReviewLine>? = null,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ReceiptUiState> =
        combine(transactions.observeReceipt(receiptId), local, categories.categories, settings.myanmarDigits) { entries, l, names, digits ->
            ReceiptUiState(
                loading = false,
                entries = entries,
                categories = names.filter { it != CategoryRepository.INCOME },
                myanmarDigits = digits,
                editing = l.editing,
                saving = l.saving,
                saveFailed = l.saveFailed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptUiState())

    /**
     * One entry under another category; the rest of it stays. A description written for the old category (the shop
     * and the category, see [describe]) is written again for the new one; one the user wrote stays as it is.
     */
    fun move(clientId: String, category: String, describe: (String) -> String?) = write {
        val entry = transactions.get(clientId) ?: return@write
        if (entry.category == category) return@write
        val description = describe(category)?.takeIf { entry.description == describe(entry.category) } ?: entry.description
        transactions.replace(listOf(clientId), listOf(entry.toNew(category, description)))
    }

    /** Delete this receipt: all its entries, in one go. The screen closes once Room has none left. */
    fun delete() = write {
        transactions.replace(entries().map { it.clientId }, emptyList())
    }

    /** Change items: the entries as lines to change, one per category, as the scan review shows a receipt. */
    fun startEditing() = local.update { l ->
        val lines = state.value.entries.groupBy { it.category }.map { (category, rows) ->
            val first = rows.first()
            ReviewLine(first.type, rows.sumOf { it.amountKyat }, category, first.description, first.date, null, rows.flatMap { it.items })
        }
        l.copy(editing = lines, saveFailed = false)
    }

    fun stopEditing() = local.update { it.copy(editing = null, saveFailed = false) }

    fun setCount(line: Int, item: Int, count: Int) = edit(line) { it.withCount(item, count) }

    fun setPrice(line: Int, item: Int, price: Long) = edit(line) { it.withPrice(item, price) }

    private fun edit(index: Int, change: (ReviewLine) -> ReviewLine) = local.update { l ->
        val lines = l.editing ?: return@update l
        val line = lines.getOrNull(index) ?: return@update l
        l.copy(editing = lines.toMutableList().also { it[index] = change(line) }, saveFailed = false)
    }

    /**
     * Saves Change items: the receipt's entries are replaced by one per category, at what its items now come to, without
     * the items left at 0; a category with nothing left is dropped.
     */
    fun saveItems() {
        val lines = local.value.editing ?: return
        if (!state.value.canSave) return
        local.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val old = entries()
                val merchant = old.firstNotNullOfOrNull { it.merchant }
                transactions.replace(old.map { it.clientId }, lines.filter { it.amountKyat > 0 }.map { it.toNew(merchant) })
                local.update { it.copy(editing = null, saving = false) }
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

    private fun ReviewLine.toNew(merchant: String?) = NewTransaction(
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
    }
}
