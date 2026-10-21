package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(o: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :n")
    suspend fun head(n: Int): List<OutboxEntity>

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

    /** Drops every queued op for the row (delete of a row the server never stored). */
    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    suspend fun dropFor(clientId: String)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}
