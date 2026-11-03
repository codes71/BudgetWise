package app.budgetwise.reminders

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the reminder remembers, in a real Preferences DataStore file. */
class ReminderStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy {
        DataStoreReminderStore(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("prefs.preferences_pb") })
    }
    private val october = YearMonth.of(2026, 10)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun postsAreKeptPerAccountAndMonth() = runBlocking {
        assertEquals(Posted(), store.posted("u1", october))

        store.markPosted("u1", october, ReminderRun.Daily)
        assertEquals(Posted(daily = true), store.posted("u1", october))
        store.markPosted("u1", october, ReminderRun.Tonight("u1", october))
        assertEquals(Posted(daily = true, tonight = true), store.posted("u1", october))

        assertEquals(Posted(), store.posted("u2", october))
        assertEquals(Posted(), store.posted("u1", october.plusMonths(1)))
    }

    @Test
    fun askingIsRememberedOnce() = runBlocking {
        assertFalse(store.notificationsAsked.first())
        store.setNotificationsAsked()
        assertTrue(store.notificationsAsked.first())
    }
}
