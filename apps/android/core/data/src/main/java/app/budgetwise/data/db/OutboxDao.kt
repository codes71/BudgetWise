package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(o: OutboxEntity)

    /** The first [n] ops of [owner], in the order they were queued. */
    @Query("SELECT * FROM outbox WHERE ownerId = :owner ORDER BY seq LIMIT :n")
    suspend fun head(owner: String, n: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE seq IN (:seqs)")
    suspend fun ack(seqs: List<Long>)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE seq IN (:seqs)")
    suspend fun bump(seqs: List<Long>)

    /** True when the row has any queued op; a pull must not overwrite or remove such a row. */
    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE clientId = :clientId)")
    suspend fun hasPendingFor(clientId: String): Boolean

    /** True when a queued op for the row was already sent at least once, so the server may hold it. */
    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE clientId = :clientId AND attempts > 0)")
    suspend fun wasSent(clientId: String): Boolean

    /**
     * Drops the row's queued CREATEs: the server never stored this version of it. A DELETE of an earlier version (an
     * entry rewritten in place under its own id) stays queued and still goes out.
     */
    @Query("DELETE FROM outbox WHERE clientId = :clientId AND op = 'CREATE'")
    suspend fun dropCreatesFor(clientId: String)

    /** The edits [clientId]'s queued CREATE belongs to ([OutboxEntity.replaceGroup]): one at most. */
    @Query("SELECT DISTINCT replaceGroup FROM outbox WHERE clientId = :clientId AND op = 'CREATE' AND replaceGroup IS NOT NULL")
    suspend fun groupsOf(clientId: String): List<String>

    /** Makes [groups] one edit, [into]. */
    @Query("UPDATE outbox SET replaceGroup = :into WHERE replaceGroup IN (:groups)")
    suspend fun mergeGroups(groups: List<String>, into: String)

    /** The DELETEs of one edit, in the order they were queued. */
    @Query("SELECT * FROM outbox WHERE replaceGroup = :group AND op = 'DELETE' ORDER BY seq")
    suspend fun deletesIn(group: String): List<OutboxEntity>

    /** Every account with at least one queued op. */
    @Query("SELECT DISTINCT ownerId FROM outbox WHERE ownerId IS NOT NULL")
    suspend fun owners(): List<String>

    @Query("SELECT COUNT(*) FROM outbox WHERE ownerId = :owner")
    suspend fun countFor(owner: String): Int

    @Query("UPDATE outbox SET ownerId = :owner WHERE ownerId IS NULL")
    suspend fun claimUnowned(owner: String)
}
