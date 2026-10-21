package app.budgetwise.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** One database per process. No destructive migration fallback: a missing migration must fail loudly, never wipe rows. */
@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): BudgetWiseDb =
        Room.databaseBuilder(context, BudgetWiseDb::class.java, "budgetwise.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun transactionDao(db: BudgetWiseDb): TransactionDao = db.transactionDao()

    @Provides
    fun outboxDao(db: BudgetWiseDb): OutboxDao = db.outboxDao()

    @Provides
    fun budgetDao(db: BudgetWiseDb): BudgetDao = db.budgetDao()
}
