package app.budgetwise.ui.scan

import app.budgetwise.data.repo.ScanItem
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/*
 * Review lines as a SavedStateHandle keeps them, so a check or a change of items outlives the process: the phone may
 * close BudgetWise while the user looks up a price in KPay. JSON in a String, as a Bundle takes it (there is no
 * serialization plugin in :app, and ReviewLine's items come from :core:data). Each line keeps its items as changed and
 * as read, so the counts and prices the user set come back with it. Check the receipt ([ScanViewModel]) and Change
 * items (ReceiptViewModel) both keep their lines this way.
 */

internal fun List<ReviewLine>.linesJson(): JsonArray = buildJsonArray {
    for (line in this@linesJson) {
        addJsonObject {
            put("type", line.type.name)
            put("originalKyat", line.originalKyat)
            put("category", line.category)
            put("description", line.description)
            put("date", line.date.toEpochDay())
            line.clientId?.let { put("clientId", it) }
            put("items", line.items.itemsJson())
            put("kept", line.kept)
            put("originalItems", line.originalItems.itemsJson())
        }
    }
}

/** The lines [linesJson] wrote. Throws a RuntimeException on JSON this build does not write. */
internal fun JsonArray.toLines(): List<ReviewLine> = map { it.jsonObject.toLine() }

/** The string at [key], or null when there is none. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Change items on a saved receipt: the lines, and the entries and total they were built from. */
internal data class SavedEditing(val lines: List<ReviewLine>, val fromIds: Set<String>, val baseKyat: Long) {
    fun toJson(): String = buildJsonObject {
        put("lines", lines.linesJson())
        put("fromIds", buildJsonArray { fromIds.forEach { add(it) } })
        put("base", baseKyat)
    }.toString()

    companion object {
        /** What [toJson] wrote; null when it cannot be read (an older build's, or damaged). */
        fun from(json: String): SavedEditing? = try {
            val o = Json.parseToJsonElement(json).jsonObject
            SavedEditing(
                lines = o["lines"]!!.jsonArray.toLines(),
                fromIds = o["fromIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                baseKyat = o["base"]!!.jsonPrimitive.long,
            )
        } catch (_: RuntimeException) {
            null
        }
    }
}

private fun List<ScanItem>.itemsJson() = buildJsonArray {
    for (item in this@itemsJson) {
        addJsonObject {
            put("name", item.name)
            put("quantity", item.quantity)
            put("unitPriceKyat", item.unitPriceKyat)
        }
    }
}

private fun JsonObject.toLine() = ReviewLine(
    type = TxType.valueOf(string("type")!!),
    originalKyat = this["originalKyat"]!!.jsonPrimitive.long,
    category = string("category")!!,
    description = string("description")!!,
    date = LocalDate.ofEpochDay(this["date"]!!.jsonPrimitive.long),
    clientId = string("clientId"),
    items = this["items"]!!.jsonArray.map { it.jsonObject.toItem() },
    kept = this["kept"]!!.jsonPrimitive.boolean,
    originalItems = this["originalItems"]!!.jsonArray.map { it.jsonObject.toItem() },
)

private fun JsonObject.toItem() =
    ScanItem(string("name")!!, this["quantity"]!!.jsonPrimitive.int, this["unitPriceKyat"]!!.jsonPrimitive.long)
