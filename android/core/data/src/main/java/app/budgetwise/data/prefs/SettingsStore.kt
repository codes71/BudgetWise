package app.budgetwise.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.budgetwise.data.sync.PullCursorStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User settings and the pull cursor. Every flag defaults to off. */
interface SettingsStore : PullCursorStore {
    /** Save a parsed transaction without asking when the parser is confident. Default false. */
    val autoSaveHighConfidence: Flow<Boolean>

    /** Allow sending text (never audio) to the cloud parser. Default false. */
    val cloudParseEnabled: Flow<Boolean>

    /** Show amounts with Myanmar digits. Default false. */
    val myanmarDigits: Flow<Boolean>

    suspend fun setAutoSaveHighConfidence(on: Boolean)
    suspend fun setCloudParseEnabled(on: Boolean)
    suspend fun setMyanmarDigits(on: Boolean)

    /** Forgets the incremental pull cursor so the next user starts from a full pull. */
    suspend fun clearLastPulledAt()
}

@Singleton
class DataStoreSettingsStore @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsStore {
    override val autoSaveHighConfidence: Flow<Boolean> = store.data.map { it[AUTO_SAVE] ?: false }
    override val cloudParseEnabled: Flow<Boolean> = store.data.map { it[CLOUD_PARSE] ?: false }
    override val myanmarDigits: Flow<Boolean> = store.data.map { it[MYANMAR_DIGITS] ?: false }
    override val lastPulledAt: Flow<Instant?> = store.data.map { p -> p[LAST_PULLED_AT]?.let(Instant::ofEpochMilli) }

    override suspend fun setAutoSaveHighConfidence(on: Boolean) {
        store.edit { it[AUTO_SAVE] = on }
    }

    override suspend fun setCloudParseEnabled(on: Boolean) {
        store.edit { it[CLOUD_PARSE] = on }
    }

    override suspend fun setMyanmarDigits(on: Boolean) {
        store.edit { it[MYANMAR_DIGITS] = on }
    }

    override suspend fun setLastPulledAt(at: Instant) {
        store.edit { it[LAST_PULLED_AT] = at.toEpochMilli() }
    }

    override suspend fun clearLastPulledAt() {
        store.edit { it.remove(LAST_PULLED_AT) }
    }

    private companion object {
        val AUTO_SAVE = booleanPreferencesKey("auto_save_high_confidence")
        val CLOUD_PARSE = booleanPreferencesKey("cloud_parse_enabled")
        val MYANMAR_DIGITS = booleanPreferencesKey("myanmar_digits")
        val LAST_PULLED_AT = longPreferencesKey("last_pulled_at_ms")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindingsModule {
    @Binds
    abstract fun settingsStore(impl: DataStoreSettingsStore): SettingsStore

    /** The sync engine's cursor is the same singleton store. */
    @Binds
    abstract fun pullCursorStore(impl: DataStoreSettingsStore): PullCursorStore
}

@Module
@InstallIn(SingletonComponent::class)
object PrefsModule {
    /** One Preferences DataStore per process, shared by [DataStoreSettingsStore] and the category cache. */
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("budgetwise_prefs") }
}
