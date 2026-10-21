package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM tx WHERE deleted = 0 ORDER BY date DESC, updatedAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Upsert
    suspend fun upsert(e: TransactionEntity)

    @Query("SELECT * FROM tx WHERE clientId = :id")
    suspend fun byClientId(id: String): TransactionEntity?

    /** For the full-pull reconcile: candidates for removal when missing from the server list. */
    @Query("SELECT clientId FROM tx WHERE syncState = 'SYNCED'")
    suspend fun syncedClientIds(): List<String>

    @Query("DELETE FROM tx WHERE clientId = :id")
    suspend fun deleteByClientId(id: String)

    /** Rows the server does not have in their current form: queued, rejected, or awaiting a delete. */
    @Query("SELECT COUNT(*) FROM tx WHERE syncState <> 'SYNCED'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tx WHERE syncState <> 'SYNCED'")
    suspend fun unsyncedCount(): Int

    @Query("DELETE FROM tx")
    suspend fun deleteAll()
}
