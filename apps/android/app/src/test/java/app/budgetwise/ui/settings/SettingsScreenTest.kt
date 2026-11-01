package app.budgetwise.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private var signOuts = 0
    private var languageOpened = 0
    private var voiceDownloads = 0
    private var mobileData = 0
    private var voiceRemovals = 0
    private val cloudParse = mutableListOf<Boolean>()

    private fun show(state: SettingsUiState) = compose.setContent {
        BudgetWiseTheme {
            SettingsScreen(
                state = state,
                now = now,
                onBack = {},
                onSyncNow = {},
                onSignOut = { signOuts++ },
                onMyanmarDigitsChange = {},
                onDownloadVoice = { voiceDownloads++ },
                onUseMobileData = { mobileData++ },
                onRemoveVoice = { voiceRemovals++ },
                onCloudParseChange = { cloudParse += it },
                onSignIn = {},
                onLanguage = { languageOpened++ },
                version = "Version 0.1.0 (1)",
            )
        }
    }

    private val signedIn = SettingsUiState(
        account = AccountInfo("Aye Aye", "aye@example.com"),
        sync = SyncSummary(SyncPhase.SYNCED, now.minusSeconds(120)),
        unsyncedCount = 2,
    )

    @Test
    fun theVersionIsTheLastRowAndThereIsNoServerRow() {
        show(signedIn)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Version 0.1.0 (1)"))
        compose.onNodeWithText("Version 0.1.0 (1)").assertIsDisplayed()
        compose.onNodeWithText("Server", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Developer", substring = true).assertDoesNotExist()
        // The sync button and its state stay.
        compose.onNodeWithText(context.getString(R.string.settings_sync_now)).assertExists()
    }

    @Test
    fun theVoiceSectionShowsWhatIsOnThePhoneAndTheSwitch() {
        show(signedIn.copy(voice = app.budgetwise.asr.ModelState.Ready("/voice"), cloudParse = true))

        compose.onNodeWithText(context.getString(R.string.settings_voice_ready)).assertIsDisplayed()
        compose.onNodeWithText("1.1 GB on this phone").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_voice_remove)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_cloud_parse)).performClick()

        assertEquals(1, voiceRemovals)
        assertEquals("the switch turns it off", listOf(false), cloudParse)
    }

    @Test
    fun aDownloadWaitingForWifiSaysSoAndOffersMobileData() {
        show(signedIn.copy(voice = app.budgetwise.asr.ModelState.Downloading(0f, waiting = true, wifiOnly = true), onWifi = false))

        compose.onNodeWithText(context.getString(R.string.voice_waiting_wifi)).assertIsDisplayed()
        compose.onNodeWithText("Voice downloads on Wi-Fi to save your data. On mobile data it uses about 1.1 GB.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.voice_use_mobile_data)).performClick()

        assertEquals(1, mobileData)
    }

    @Test
    fun aPhoneWithoutTheMemoryForVoiceOnlySaysWhy() {
        show(signedIn.copy(voiceSupported = false))

        compose.onNodeWithText(context.getString(R.string.voice_unsupported_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_voice_download)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_cloud_parse)).assertDoesNotExist()
    }

    @Test
    fun aRetryOnWifiOrADownloadAllowedOnMobileDataGetsNoWifiNotice() {
        show(signedIn.copy(voice = app.budgetwise.asr.ModelState.Downloading(0.3f, waiting = true, wifiOnly = true), onWifi = true))
        compose.onNodeWithText(context.getString(R.string.voice_use_mobile_data)).assertDoesNotExist()
    }

    @Test
    fun voiceNotOnThePhoneOffersTheDownload() {
        show(signedIn)

        compose.onNodeWithText(context.getString(R.string.settings_voice_missing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_voice_download)).performClick()

        assertEquals(1, voiceDownloads)
    }

    @Test
    fun signOutWithUnsyncedEntriesHappensWithoutADialog() {
        show(signedIn)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.settings_sign_out)))
        compose.onNodeWithText(context.getString(R.string.settings_sign_out)).performClick()

        assertEquals(1, signOuts)
        compose.onNodeWithText(context.getString(R.string.action_cancel)).assertDoesNotExist()
    }

    @Test
    fun theLanguageRowNamesTheLanguageAndOpensThePicker() {
        show(signedIn)

        val row = context.getString(R.string.settings_language)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(row))
        // The language is named in its own script.
        compose.onNodeWithText(context.getString(R.string.language_english)).assertIsDisplayed()
        compose.onNodeWithText(row).performClick()

        assertEquals(1, languageOpened)
    }
}
