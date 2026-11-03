package app.budgetwise.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a refresh from writing an older server answer over a newer save. A refresh notes the owner's generation of a
 * table before it asks the server ([generation]) and writes its answer only while that generation is unchanged
 * ([writeIfUnchanged]); a save's cache write moves the generation on ([writeSaved]). Both writes run under one lock, so
 * they never interleave: whichever comes second sees the other.
 */
@Singleton
class CacheWrites @Inject constructor() {
    /** The cached tables a save and a refresh can both write. */
    enum class Table { MONTH_PLANS, BUDGETS }

    private val mutex = Mutex()
    private val generations = HashMap<Pair<String, Table>, Long>()

    suspend fun generation(owner: String, table: Table): Long = mutex.withLock { current(owner, table) }

    /** Writes a save's answer; any refresh of [tables] that started before it then drops its own. */
    suspend fun <T> writeSaved(owner: String, tables: Collection<Table>, write: suspend () -> T): T = mutex.withLock {
        val result = write()
        for (table in tables) generations[owner to table] = current(owner, table) + 1
        result
    }

    /** Writes a refresh's answer only if nothing was saved to [table] since [since]; false when it was dropped. */
    suspend fun writeIfUnchanged(owner: String, table: Table, since: Long, write: suspend () -> Unit): Boolean = mutex.withLock {
        if (current(owner, table) != since) return@withLock false
        write()
        true
    }

    private fun current(owner: String, table: Table): Long = generations[owner to table] ?: 0L
}
