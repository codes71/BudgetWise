package app.budgetwise.ui.common

import androidx.lifecycle.SavedStateHandle

/** Restore private unfinished input only when its original account is provable and still active. Ledger data stays. */
internal fun bindDraftOwner(saved: SavedStateHandle, owner: String?, fields: List<String>): String? {
    val previous = saved.get<String>("draftOwner")
    if (owner == null || previous != owner) fields.forEach { saved.remove<Any>(it) }
    if (owner == null) saved.remove<String>("draftOwner") else saved["draftOwner"] = owner
    return owner
}
