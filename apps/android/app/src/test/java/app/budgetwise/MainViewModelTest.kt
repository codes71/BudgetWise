package app.budgetwise

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.auth.StoredSession
import app.budgetwise.data.sync.SyncScheduler
import app.budgetwise.reminders.MonthEndLink
import app.budgetwise.reminders.MonthEndScheduler
import app.budgetwise.testing.FakeTokenStore
import java.time.Clock
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The month-end link waiting for AppNav, kept in saved state. */
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob())
    private val october = MonthEndLink("u1", YearMonth.of(2026, 10))

    @After
    fun tearDown() = scope.cancel()

    /** The session never finishes loading here, as when the process is killed during the load. */
    private fun vm(handle: SavedStateHandle): MainViewModel {
        val session = SessionManager(FakeTokenStore(null as StoredSession?), Clock.systemUTC())
        val startup = AppStartup(session, {}, SyncScheduler(context), MonthEndScheduler(context, Clock.systemDefaultZone()), scope)
        return MainViewModel(startup, session, handle)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** What Android hands a new process: the saved values, as a Bundle would carry them. */
    private fun SavedStateHandle.afterProcessDeath() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    @Test
    fun aWaitingLinkComesBackAfterTheProcessDiedAndIsFollowedOnce() {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.openMonthEnd(october)
        idle()
        assertEquals(october, first.monthEndLink.value)
        // Kept as a plain string, which a saved-state Bundle can hold.
        assertTrue(handle.keys().single().let { handle.get<Any>(it) } is String)

        val restored = handle.afterProcessDeath()
        val second = vm(restored)
        assertEquals(october, second.monthEndLink.value)

        // AppNav followed it: gone, also for the next process.
        second.monthEndLinkHandled()
        idle()
        assertNull(second.monthEndLink.value)
        assertNull(vm(restored.afterProcessDeath()).monthEndLink.value)
    }

    @Test
    fun aNewerLinkReplacesOneNotFollowedYet() {
        val vm = vm(SavedStateHandle())
        val november = MonthEndLink("u2", YearMonth.of(2026, 11))

        vm.openMonthEnd(october)
        vm.openMonthEnd(november)
        idle()

        assertEquals(november, vm.monthEndLink.value)
    }

    @Test
    fun theSavedStringReadsBack() {
        assertEquals("2026-10/u1", october.encode())
        assertEquals(october, MonthEndLink.decode(october.encode()))
        // An account id with a slash in it still reads: the month never has one.
        assertEquals(MonthEndLink("a/b", YearMonth.of(2026, 10)), MonthEndLink.decode("2026-10/a/b"))
        for (bad in listOf("", "2026-10", "2026-10/", "October/u1")) assertNull(bad, MonthEndLink.decode(bad))
    }
}
