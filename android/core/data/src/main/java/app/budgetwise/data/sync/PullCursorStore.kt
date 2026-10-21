package app.budgetwise.data.sync

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Home of the incremental pull cursor. Task 7's SettingsStore implements this on DataStore and binds it
 * in Hilt; tests use an in-memory fake.
 */
interface PullCursorStore {
    /** Null until the first pull that saw a row. */
    val lastPulledAt: Flow<Instant?>

    suspend fun setLastPulledAt(at: Instant)

    /** Forgets the cursor so the next pull starts from scratch. */
    suspend fun clearLastPulledAt()
}
