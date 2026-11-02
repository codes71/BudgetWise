package app.budgetwise.ui.scan

import app.budgetwise.data.repo.ScanItem
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.nlu.TxType
import java.io.Serializable
import java.time.LocalDate

/*
 * Review lines as a SavedStateHandle keeps them, so a check or a change of items outlives the process: the phone may
 * close BudgetWise while the user looks up a price in KPay. Plain values only, as a Bundle takes them; ReviewLine's
 * items come from :core:data and are not Serializable.
 */

internal data class SavedItem(val name: String, val quantity: Int, val unitPriceKyat: Long) : Serializable

internal data class SavedLine(
    val type: TxType,
    val originalKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val clientId: String?,
    val kept: Boolean,
    val items: List<SavedItem>,
    val originalItems: List<SavedItem>,
) : Serializable

/** A receipt or KPay screen being checked: what was read, with the user's changes in [lines]. */
internal data class SavedReview(
    val kind: ScanKind,
    val merchant: String?,
    val date: LocalDate?,
    val totalKyat: Long?,
    val lines: List<SavedLine>,
) : Serializable

/** Change items on a saved receipt: the lines, and the entries and total they were built from. */
internal data class SavedEditing(val lines: List<SavedLine>, val fromIds: Set<String>, val baseKyat: Long) : Serializable

private fun ScanItem.toSaved() = SavedItem(name, quantity, unitPriceKyat)

private fun SavedItem.toItem() = ScanItem(name, quantity, unitPriceKyat)

internal fun ReviewLine.toSaved() = SavedLine(
    type, originalKyat, category, description, date, clientId, kept, items.map { it.toSaved() }, originalItems.map { it.toSaved() },
)

internal fun SavedLine.toLine() = ReviewLine(
    type, originalKyat, category, description, date, clientId, items.map { it.toItem() }, kept, originalItems.map { it.toItem() },
)
