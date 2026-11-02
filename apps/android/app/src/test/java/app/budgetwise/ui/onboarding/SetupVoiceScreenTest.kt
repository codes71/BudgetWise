package app.budgetwise.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.voice.VoiceSetupUiState
import app.budgetwise.ui.voice.VoiceUnsupportedDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class SetupVoiceScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var downloads = 0
    private var mobileData = 0

    private fun show(onWifi: Boolean) = compose.setContent {
        BudgetWiseTheme {
            SetupVoiceScreen(
                state = VoiceSetupUiState(onWifi = onWifi),
                onDownload = { downloads++ },
                onUseMobileData = { mobileData++ },
                onNotNow = {},
            )
        }
    }

    @Test
    fun onWifiItSimplyOffersTheDownload() {
        show(onWifi = true)
        compose.onNodeWithText("Download 1.1 GB").performClick()
        compose.onNodeWithText(context.getString(R.string.setup_voice_no_wifi_title)).assertDoesNotExist()
        assertEquals(1, downloads)
    }

    @Test
    fun offWifiItWarnsThatTheDownloadWaitsAndOffersMobileData() {
        show(onWifi = false)
        compose.onNodeWithText(context.getString(R.string.setup_voice_no_wifi_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.setup_voice_download_on_wifi)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.voice_use_mobile_data)).performClick()
        assertEquals(1, mobileData)
        assertEquals(0, downloads)
    }

    @Test
    fun aPhoneWithoutTheMemorySeesOnlyWhyAndGoesOn() {
        var dismissed = 0
        compose.setContent { BudgetWiseTheme { VoiceUnsupportedDialog(onDismiss = { dismissed++ }) } }

        compose.onNodeWithText(context.getString(R.string.voice_unsupported_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_ok)).performClick()

        assertEquals(1, dismissed)
    }
}
