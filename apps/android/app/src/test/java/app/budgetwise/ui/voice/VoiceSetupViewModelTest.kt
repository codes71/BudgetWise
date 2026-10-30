package app.budgetwise.ui.voice

import app.budgetwise.asr.ModelState
import app.budgetwise.testing.FakeModelManager
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeVoiceAvailability
import app.budgetwise.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** A clock the test moves. */
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = TestClock(Instant.parse("2026-10-30T03:00:00Z"))

    private val network = FakeNetworkStatus()
    private val settings = FakeSettingsStore()

    private fun TestScope.start(
        models: FakeModelManager,
        supported: Boolean = true,
    ): Pair<VoiceSetupViewModel, MutableList<VoiceSetupEvent>> {
        val vm = VoiceSetupViewModel(models, settings, network, FakeVoiceAvailability(supported), clock)
        val events = mutableListOf<VoiceSetupEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to events
    }

    @Test
    fun sayItOpensVoiceWhenItIsOnThePhone() = runTest {
        val (vm, events) = start(FakeModelManager(ModelState.Ready("/voice")))
        vm.onSayIt()
        assertEquals(listOf<VoiceSetupEvent>(VoiceSetupEvent.OpenVoice), events)
        assertNull(vm.state.value.card)
    }

    @Test
    fun theOfferThenTheDownloadOnWifiThenMobileData() = runTest {
        val models = FakeModelManager()
        val (vm, events) = start(models)
        vm.onSayIt()
        assertEquals(VoiceCard.SETUP, vm.state.value.card)

        vm.download()
        assertEquals(VoiceCard.DOWNLOADING, vm.state.value.card)
        vm.useMobileData()
        assertEquals(listOf(false, true), models.downloads)

        models.state.value = ModelState.Ready("/voice")
        assertNull(vm.state.value.card)
        assertEquals(listOf<VoiceSetupEvent>(VoiceSetupEvent.OpenVoice), events)
    }

    @Test
    fun offWifiTheStateSaysSoAndMobileDataStartsAtOnce() = runTest {
        network.unmetered.value = false
        val models = FakeModelManager()
        val (vm, _) = start(models)
        assertEquals(false, vm.state.value.onWifi)

        vm.useMobileData()

        assertEquals(listOf(true), models.downloads)
        assertEquals(ModelState.Downloading(0f, waiting = false, wifiOnly = false), models.state.value)
        network.unmetered.value = true
        assertEquals(true, vm.state.value.onWifi)
    }

    @Test
    fun withoutTheMemoryVoiceIsOffAndNothingStartsTheDownload() = runTest {
        val models = FakeModelManager()
        val (vm, events) = start(models, supported = false)
        assertEquals(false, vm.state.value.supported)

        vm.onSayIt()
        vm.download(showProgress = false)
        vm.useMobileData()

        assertNull(vm.state.value.card)
        assertEquals(emptyList<Boolean>(), models.downloads)
        assertEquals(emptyList<VoiceSetupEvent>(), events)
    }

    @Test
    fun theNoticeShowsOnceOnAPhoneWithoutTheMemory() = runTest {
        val (vm, _) = start(FakeModelManager(), supported = false)
        assertEquals(true, vm.state.value.showUnsupportedNotice)

        vm.noticeSeen()

        assertEquals(true, settings.voiceNoticeShown.value)
        assertEquals(false, vm.state.value.showUnsupportedNotice)
        val (again, _) = start(FakeModelManager(), supported = false)
        assertEquals("not again on this phone", false, again.state.value.showUnsupportedNotice)
        val (big, _) = start(FakeModelManager(), supported = true)
        assertEquals(false, big.state.value.showUnsupportedNotice)
    }

    @Test
    fun aFailedDownloadGoesBackToTheOffer() = runTest {
        val models = FakeModelManager(ModelState.Downloading(0.2f))
        val (vm, _) = start(models)
        vm.onSayIt()
        assertEquals(VoiceCard.DOWNLOADING, vm.state.value.card)
        models.state.value = ModelState.Failed(ModelState.NOT_ENOUGH_STORAGE)
        assertEquals(VoiceCard.SETUP, vm.state.value.card)
    }

    @Test
    fun cancellingDeletesTheDownload() = runTest {
        val models = FakeModelManager(ModelState.Downloading(0.2f))
        val (vm, _) = start(models)
        vm.onSayIt()
        vm.cancelDownload()
        assertEquals(1, models.deletes)
        assertNull(vm.state.value.card)
    }

    @Test
    fun minutesLeftFollowTheSpeedOnceThereIsEnoughToTell() = runTest {
        val models = FakeModelManager(ModelState.Downloading(0.1f))
        val (vm, _) = start(models)
        clock.now = clock.now.plusSeconds(2)
        models.state.value = ModelState.Downloading(0.12f)
        assertNull("two seconds are too few", vm.state.value.minutesLeft)
        // 10% more in 60 s: the remaining 80% takes 8 minutes.
        clock.now = clock.now.plusSeconds(58)
        models.state.value = ModelState.Downloading(0.2f)
        assertEquals(8, vm.state.value.minutesLeft)
        models.state.value = ModelState.Downloading(0.2f, waiting = true)
        assertNull("waiting for Wi-Fi has no estimate", vm.state.value.minutesLeft)
    }
}
