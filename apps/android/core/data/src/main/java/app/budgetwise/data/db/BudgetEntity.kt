package app.budgetwise.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.budgetwise.data.model.Budget

/**
 * Cached server budget of the account [ownerId] (null only before an account claims it). One row per account and
 * category; `limit` is a SQL keyword, so the column is [limitKyat].
 */
@Entity(tableName = "budget", indices = [Index(value = ["ownerId", "category"], unique = true)])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerId: String?,
    val category: String,
    val limitKyat: Long,
)

fun BudgetEntity.toDomain() = Budget(category, limitKyat)
