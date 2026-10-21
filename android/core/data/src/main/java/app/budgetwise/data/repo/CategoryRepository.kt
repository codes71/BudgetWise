package app.budgetwise.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.safeApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Category names for pickers. The cache lives in the shared Preferences DataStore (a JSON list under one
 * key, no Room table, so no schema bump). [categories] emits the cached list, or [BUILT_IN] until a
 * [refresh] has succeeded once.
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val api: ApiService,
    private val session: SessionManager,
) {
    val categories: Flow<List<String>> = store.data.map { p -> p[KEY]?.let(::decode)?.takeIf { it.isNotEmpty() } ?: BUILT_IN }

    /** Fetches `GET /categories` and caches a non-empty answer. Offline or failing, the cache is kept. */
    suspend fun refresh(): ApiResult<Unit> {
        val sent = session.bearer()
        return when (val r = safeApi { api.categories() }) {
            is ApiResult.Ok -> {
                val names = r.v.categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (names.isNotEmpty()) store.edit { it[KEY] = Json.encodeToString(LIST, names) }
                ApiResult.Ok(Unit)
            }
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(sent)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    /** Drops the cache (it can hold user-specific names); [categories] falls back to the built-ins. */
    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private fun decode(raw: String): List<String>? = try {
        Json.decodeFromString(LIST, raw)
    } catch (_: IllegalArgumentException) {
        null // SerializationException is an IllegalArgumentException; a corrupt cache falls back to the built-ins
    }

    companion object {
        val BUILT_IN: List<String> = listOf(
            "Groceries", "Utilities", "Entertainment", "Transport", "Housing", "Health", "Food and Drink Item", "Other",
        )
        private val KEY = stringPreferencesKey("categories_json")
        private val LIST = ListSerializer(String.serializer())
    }
}
