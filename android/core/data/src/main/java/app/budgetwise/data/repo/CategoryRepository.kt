package app.budgetwise.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Category names for pickers. The cache lives in the shared Preferences DataStore (a JSON list under one key per
 * account, since the list can hold an account's own names; no Room table). [categories] emits the active account's
 * cached list, or [BUILT_IN] until a [refresh] has succeeded once for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CategoryRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val api: ApiService,
    private val session: SessionManager,
) {
    val categories: Flow<List<String>> = session.activeUserId.flatMapLatest { owner ->
        store.data.map { p -> owner?.let { p[keyFor(it)] }?.let(::decode)?.takeIf { it.isNotEmpty() } ?: BUILT_IN }
    }

    /**
     * Fetches `GET /categories` with the active account's token and caches a non-empty answer for that account.
     * Offline or failing, the cache is kept; without a valid token nothing is sent.
     */
    suspend fun refresh(): ApiResult<Unit> {
        val owner = session.activeUserId.value ?: return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        return when (val r = safeApi { api.categories(authorization(token)) }) {
            is ApiResult.Ok -> {
                val names = r.v.categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (names.isNotEmpty()) store.edit { it[keyFor(owner)] = Json.encodeToString(LIST, names) }
                ApiResult.Ok(Unit)
            }
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    /** The single list of earlier versions (`categories_json`) is not read: it may be another account's. */
    private fun keyFor(owner: String) = stringPreferencesKey("categories_json.$owner")

    private fun decode(raw: String): List<String>? = try {
        Json.decodeFromString(LIST, raw)
    } catch (_: IllegalArgumentException) {
        null // SerializationException is an IllegalArgumentException; a corrupt cache falls back to the built-ins
    }

    companion object {
        val BUILT_IN: List<String> = listOf(
            "Groceries", "Utilities", "Entertainment", "Transport", "Housing", "Health", "Food and Drink Item", "Other",
        )

        /** Every income entry is filed under this name, as the web does; it is not one of the expense categories. */
        const val INCOME = "Income"
        private val NOT_SIGNED_IN = ApiResult.Unauthorized("No valid session")
        private val LIST = ListSerializer(String.serializer())
    }
}
