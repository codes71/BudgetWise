package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM tx WHERE ownerId = :owner AND deleted = 0 ORDER BY date DESC, updatedAt DESC")
    fun observeAll(owner: String): Flow<List<TransactionEntity>>

    @Upsert
    suspend fun upsert(e: TransactionEntity)

    @Query("SELECT * FROM tx WHERE clientId = :id")
    suspend fun byClientId(id: String): TransactionEntity?

    /** For the full-pull reconcile: [owner]'s candidates for removal when missing from the server list. */
    @Query("SELECT clientId FROM tx WHERE ownerId = :owner AND syncState = 'SYNCED'")
    suspend fun syncedClientIds(owner: String): List<String>

    @Query("DELETE FROM tx WHERE clientId = :id")
    suspend fun deleteByClientId(id: String)

    /** [owner]'s rows the server does not have in their current form: queued, rejected, or awaiting a delete. */
    @Query("SELECT COUNT(*) FROM tx WHERE ownerId = :owner AND syncState <> 'SYNCED'")
    fun observeUnsyncedCount(owner: String): Flow<Int>

    @Query("UPDATE tx SET ownerId = :owner WHERE ownerId IS NULL")
    suspend fun claimUnowned(owner: String)
}
