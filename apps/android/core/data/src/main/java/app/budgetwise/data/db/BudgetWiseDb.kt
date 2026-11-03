package app.budgetwise.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TransactionEntity::class, OutboxEntity::class, BudgetEntity::class, MonthPlanEntity::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BudgetWiseDb : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun budgetDao(): BudgetDao
    abstract fun monthPlanDao(): MonthPlanDao
}
