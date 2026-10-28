package app.budgetwise.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.budgetwise.data.model.Budget

/** Cached server budget. `limit` is a SQL keyword, so the column is [limitKyat]. */
@Entity(tableName = "budget")
data class BudgetEntity(
    @PrimaryKey val category: String,
    val limitKyat: Long,
)

fun BudgetEntity.toDomain() = Budget(category, limitKyat)
