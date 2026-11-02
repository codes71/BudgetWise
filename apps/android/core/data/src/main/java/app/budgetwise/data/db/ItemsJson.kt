package app.budgetwise.data.db

import app.budgetwise.data.model.EntryItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** An entry's receipt lines as stored in the `items` column: a JSON array, or null when there are none. */
internal object ItemsJson {
    @Serializable
    private data class StoredItem(val name: String, val quantity: Int, val unitPrice: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(StoredItem.serializer())

    fun encode(items: List<EntryItem>): String? =
        if (items.isEmpty()) null else json.encodeToString(list, items.map { StoredItem(it.name, it.quantity, it.unitPriceKyat) })

    fun decode(text: String?): List<EntryItem> =
        text?.let { json.decodeFromString(list, it) }.orEmpty().map { EntryItem(it.name, it.quantity, it.unitPrice) }
}
