package app.budgetwise.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import java.time.Instant
import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Cached month plan of the account [ownerId], one row per account and [month]. Plans only ever come from the server, so
 * the owner is never null. The end check is both columns or neither.
 */
@Entity(tableName = "budget_month", primaryKeys = ["ownerId", "month"])
data class MonthPlanEntity(
    val ownerId: String,
    val month: YearMonth,
    val newMoneyKyat: Long,
    val carriedOverKyat: Long,
    /** The shares as a JSON array ([SharesJson]), in the plan's order. */
    @ColumnInfo(name = "shares") val sharesJson: String,
    val endCheckOnHandKyat: Long?,
    val endCheckAt: Instant?,
)

fun MonthPlanEntity.toDomain() = MonthPlan(
    month = month,
    newMoneyKyat = newMoneyKyat,
    carriedOverKyat = carriedOverKyat,
    shares = SharesJson.decode(sharesJson),
    endCheck = if (endCheckOnHandKyat != null && endCheckAt != null) EndCheck(endCheckOnHandKyat, endCheckAt) else null,
)

fun MonthPlan.toEntity(ownerId: String) = MonthPlanEntity(
    ownerId = ownerId,
    month = month,
    newMoneyKyat = newMoneyKyat,
    carriedOverKyat = carriedOverKyat,
    sharesJson = SharesJson.encode(shares),
    endCheckOnHandKyat = endCheck?.onHandKyat,
    endCheckAt = endCheck?.checkedAt,
)

/** A plan's shares as stored in the `shares` column: always a JSON array, `[]` when nothing is split. */
internal object SharesJson {
    @Serializable
    private data class StoredShare(val category: String, val limit: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(StoredShare.serializer())

    fun encode(shares: List<Budget>): String = json.encodeToString(list, shares.map { StoredShare(it.category, it.limitKyat) })

    fun decode(text: String): List<Budget> = json.decodeFromString(list, text).map { Budget(it.category, it.limit) }
}
