package app.budgetwise.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TransactionEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BudgetWiseDb : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun outboxDao(): OutboxDao
}
