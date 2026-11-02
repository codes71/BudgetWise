package app.budgetwise.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User settings and the pull cursors. Every flag defaults to off, except cloud parse. */
interface SettingsStore : PullCursorStore {
    /** Save a parsed transaction without asking when the parser is confident. Default false. */
    val autoSaveHighConfidence: Flow<Boolean>

    /** Allow sending text (never audio) to the cloud parser. Default true: voice has no offline mode. */
    val cloudParseEnabled: Flow<Boolean>

    /** Show amounts with Myanmar digits. Default false. */
    val myanmarDigits: Flow<Boolean>

    /** This phone has shown its "voice isn't available here" notice (it lacks the memory). Per phone. Default false. */
    val voiceNoticeShown: Flow<Boolean>

    suspend fun setAutoSaveHighConfidence(on: Boolean)
    suspend fun setCloudParseEnabled(on: Boolean)
    suspend fun setMyanmarDigits(on: Boolean)
    suspend fun setVoiceNoticeShown()
}

@Singleton
class DataStoreSettingsStore @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsStore {
    override val autoSaveHighConfidence: Flow<Boolean> = store.data.map { it[AUTO_SAVE] ?: false }
    override val cloudParseEnabled: Flow<Boolean> = store.data.map { it[CLOUD_PARSE] ?: true }
    override val myanmarDigits: Flow<Boolean> = store.data.map { it[MYANMAR_DIGITS] ?: false }
    override val voiceNoticeShown: Flow<Boolean> = store.data.map { it[VOICE_NOTICE_SHOWN] ?: false }

    override fun lastPulledAt(owner: String): Flow<Instant?> =
        store.data.map { p -> p[lastPulledAtKey(owner)]?.let(Instant::ofEpochMilli) }

    override suspend fun setAutoSaveHighConfidence(on: Boolean) {
        store.edit { it[AUTO_SAVE] = on }
    }

    override suspend fun setCloudParseEnabled(on: Boolean) {
        store.edit { it[CLOUD_PARSE] = on }
    }

    override suspend fun setMyanmarDigits(on: Boolean) {
        store.edit { it[MYANMAR_DIGITS] = on }
    }

    override suspend fun setVoiceNoticeShown() {
        store.edit { it[VOICE_NOTICE_SHOWN] = true }
    }

    override suspend fun setLastPulledAt(owner: String, at: Instant) {
        store.edit { it[lastPulledAtKey(owner)] = at.toEpochMilli() }
    }

    private companion object {
        val AUTO_SAVE = booleanPreferencesKey("auto_save_high_confidence")
        val CLOUD_PARSE = booleanPreferencesKey("cloud_parse_enabled")
        val MYANMAR_DIGITS = booleanPreferencesKey("myanmar_digits")
        val VOICE_NOTICE_SHOWN = booleanPreferencesKey("voice_unsupported_notice_shown")

        /**
         * One cursor per account. The single cursor of earlier versions (`last_pulled_at_ms`) is not read: the first
         * pull after the update starts from scratch, which only costs one full download.
         */
        fun lastPulledAtKey(owner: String) = longPreferencesKey("last_pulled_at_ms.$owner")
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
    /**
     * One Preferences DataStore per process, shared by [DataStoreSettingsStore] and the category cache (the same
     * file). A corrupt file is replaced by an empty one (defaults, full pull on the next sync) instead of failing
     * every read, which would crash the home screen on each launch.
     */
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        createAppPreferences { context.preferencesDataStoreFile("budgetwise_prefs") }
}

/** The app's Preferences DataStore over [produceFile]; a corrupt file is replaced by an empty one. Split out for tests. */
internal fun createAppPreferences(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = produceFile,
)
