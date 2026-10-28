package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget WHERE ownerId = :owner ORDER BY category")
    fun observeAll(owner: String): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<BudgetEntity>)

    @Query("DELETE FROM budget WHERE ownerId = :owner")
    suspend fun deleteFor(owner: String)

    /** The server list is the truth: budgets it no longer returns are removed from [owner]'s cache. */
    @Transaction
    suspend fun replaceAll(owner: String, rows: List<BudgetEntity>) {
        deleteFor(owner)
        insertAll(rows)
    }

    /** Budgets cached before several accounts go to [owner]; a category it already has keeps its own limit. */
    @Query("UPDATE OR IGNORE budget SET ownerId = :owner WHERE ownerId IS NULL")
    suspend fun claimUnowned(owner: String)

    @Query("DELETE FROM budget WHERE ownerId IS NULL")
    suspend fun deleteUnowned()
}
