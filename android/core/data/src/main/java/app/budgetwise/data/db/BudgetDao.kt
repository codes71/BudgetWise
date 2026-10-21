package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget ORDER BY category")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<BudgetEntity>)

    @Query("DELETE FROM budget")
    suspend fun deleteAll()

    /** The server list is the truth: budgets it no longer returns are removed from the cache. */
    @Transaction
    suspend fun replaceAll(rows: List<BudgetEntity>) {
        deleteAll()
        insertAll(rows)
    }
}
