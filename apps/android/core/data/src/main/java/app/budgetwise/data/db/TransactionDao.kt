package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.budgetwise.data.model.DailySpend
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM tx WHERE ownerId = :owner AND deleted = 0 ORDER BY date DESC, updatedAt DESC")
    fun observeAll(owner: String): Flow<List<TransactionEntity>>

    /**
     * [owner]'s expenses dated [from] to [to] (both included) summed per day and category, for the spending chart.
     * Tombstones are left out, as in [observeAll]; ISO dates compare as text.
     */
    @Query(
        "SELECT date, category, SUM(amountKyat) AS amountKyat FROM tx " +
            "WHERE ownerId = :owner AND deleted = 0 AND type = 'EXPENSE' AND date BETWEEN :from AND :to " +
            "GROUP BY date, category",
    )
    fun observeDailySpend(owner: String, from: LocalDate, to: LocalDate): Flow<List<DailySpend>>

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
