package app.budgetwise.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the month-end reminder remembers: its posts per account and month, and whether it has asked to post at all. */
interface ReminderStore {
    suspend fun posted(account: String, month: YearMonth): Posted

    suspend fun markPosted(account: String, month: YearMonth, run: ReminderRun)

    /** The reason for notifications has been shown after a plan save and answered, either way. Per phone. */
    val notificationsAsked: Flow<Boolean>

    suspend fun setNotificationsAsked()
}

/**
 * In the app's Preferences DataStore (the file [app.budgetwise.data.prefs.SettingsStore] uses; one instance per
 * process). One small key per account and month that had a post; a few a year, so they are never cleaned up.
 */
@Singleton
class DataStoreReminderStore @Inject constructor(private val store: DataStore<Preferences>) : ReminderStore {
    override suspend fun posted(account: String, month: YearMonth): Posted {
        val bits = store.data.first()[postedKey(account, month)] ?: 0
        return Posted(daily = bits and DAILY != 0, tonight = bits and TONIGHT != 0)
    }

    override suspend fun markPosted(account: String, month: YearMonth, run: ReminderRun) {
        val bit = when (run) {
            ReminderRun.Daily -> DAILY
            is ReminderRun.Tonight -> TONIGHT
        }
        store.edit { it[postedKey(account, month)] = (it[postedKey(account, month)] ?: 0) or bit }
    }

    override val notificationsAsked: Flow<Boolean> = store.data.map { it[ASKED] ?: false }

    override suspend fun setNotificationsAsked() {
        store.edit { it[ASKED] = true }
    }

    private companion object {
        const val DAILY = 1
        const val TONIGHT = 2
        val ASKED = booleanPreferencesKey("budget_reminders_asked")

        fun postedKey(account: String, month: YearMonth) = intPreferencesKey("month_end_posted.$account.$month")
    }
}
