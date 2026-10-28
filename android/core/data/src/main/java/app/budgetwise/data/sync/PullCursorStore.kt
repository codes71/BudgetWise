package app.budgetwise.data.sync

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Home of the incremental pull cursor, one per account. SettingsStore implements this on DataStore and binds it in
 * Hilt; tests use an in-memory fake.
 */
interface PullCursorStore {
    /** Null until the first pull for [owner] that saw a row. */
    fun lastPulledAt(owner: String): Flow<Instant?>

    suspend fun setLastPulledAt(owner: String, at: Instant)
}
