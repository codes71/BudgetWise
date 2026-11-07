package app.budgetwise.ui.voice

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeMicrophone
import app.budgetwise.testing.FakeNetworkStatus
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSpeech
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.FakeVoiceParser
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.voice.RecordingEnd
import java.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class CloudAiDisclosureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences(CLOUD_AI_CONSENT_PREFERENCES, 0)

    @Before fun clearChoice() { preferences.edit().clear().commit() }

    @Test fun cameraAndPickerContentAreNotMountedBeforeAgreementOrAfterManualDecline() {
        var captureOrPermissionEffects = 0
        var manual = 0
        compose.setContent {
            BudgetWiseTheme {
                CloudAiConsentGate(CloudAiFeature.SCAN, {}, { manual++ }) {
                    DisposableEffect(Unit) { captureOrPermissionEffects++; onDispose {} }
                    Text("Camera and picker")
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.privacy_scan_body)).assertIsDisplayed()
        compose.onNodeWithText("Camera and picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, captureOrPermissionEffects) }
        compose.onNodeWithText(context.getString(R.string.privacy_enter_manually)).performClick()
        compose.runOnIdle {
            assertEquals(1, manual)
            assertEquals(0, captureOrPermissionEffects)
            assertFalse(preferences.getBoolean(CloudAiFeature.SCAN.preference, false))
        }
    }

    @Test fun oldChoiceDoesNotSkipUpdatedRetentionDisclosureAndOtherPreferencesArePreserved() {
        preferences.edit().putBoolean("scan_v1", true).putBoolean("voice_v1", true)
            .putString("unrelated", "keep").commit()
        var mounted = 0
        compose.setContent {
            BudgetWiseTheme {
                CloudAiConsentGate(CloudAiFeature.SCAN, {}, {}) {
                    DisposableEffect(Unit) { mounted++; onDispose {} }
                    Text("Updated consent accepted")
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.privacy_scan_body)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, mounted)
            assertEquals("scan_v2", CloudAiFeature.SCAN.preference)
            assertEquals("voice_v2", CloudAiFeature.VOICE.preference)
            assertEquals("keep", preferences.getString("unrelated", null))
            assertTrue(preferences.getBoolean("scan_v1", false))
        }
        compose.onNodeWithText(context.getString(R.string.privacy_scan_agree)).performClick()
        compose.onNodeWithText("Updated consent accepted").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounted)
            assertTrue(preferences.getBoolean(CloudAiFeature.SCAN.preference, false))
            assertFalse(preferences.getBoolean(CloudAiFeature.VOICE.preference, false))
        }
    }

    @Test fun closeIsNotConsentAndExplicitAgreementPersistsOnlyThatFeature() {
        var closed = 0
        var mounted = 0
        val epoch = mutableIntStateOf(0)
        compose.setContent {
            BudgetWiseTheme {
                key(epoch.intValue) {
                    CloudAiConsentGate(CloudAiFeature.SCAN, { closed++ }, {}) {
                        DisposableEffect(Unit) { mounted++; onDispose {} }
                        Text("Accepted camera")
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(0, mounted)
            assertFalse(preferences.getBoolean(CloudAiFeature.SCAN.preference, false))
        }
        compose.onNodeWithText(context.getString(R.string.privacy_scan_agree)).performClick()
        compose.onNodeWithText("Accepted camera").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(preferences.getBoolean(CloudAiFeature.SCAN.preference, false))
            assertFalse(preferences.getBoolean(CloudAiFeature.VOICE.preference, false))
            epoch.intValue++
        }
        compose.onNodeWithText("Accepted camera").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.privacy_scan_agree)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, mounted) }
    }

    @Test fun realVoiceRouteKeepsQueuedMicEventUntilConsentAndOnlyThenCanTimeLimitSendWords() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val microphone = FakeMicrophone()
        val parser = FakeVoiceParser()
        val voice = VoiceViewModel(microphone, FakeSpeech(), parser, FakeSettingsStore(), FakeTransactionStore(),
            FakeBudgetSource(), FakeCategorySource(), FakeNetworkStatus(online = true), Clock.systemUTC())
        compose.setContent { BudgetWiseTheme { VoiceRoute({}, {}, {}, voice) } }
        compose.onNodeWithText(context.getString(R.string.privacy_voice_body)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(microphone.recordings.isEmpty())
            assertTrue(parser.calls.isEmpty())
        }
        compose.onNodeWithText(context.getString(R.string.privacy_voice_agree)).performClick()
        compose.waitUntil { microphone.recordings.size == 1 }
        compose.runOnIdle {
            assertTrue(parser.calls.isEmpty())
            microphone.recordings.single().ended.value = RecordingEnd.LIMIT
        }
        compose.waitUntil { parser.calls.size == 1 }
        compose.runOnIdle {
            assertTrue(preferences.getBoolean(CloudAiFeature.VOICE.preference, false))
            assertEquals(1, microphone.recordings.size)
        }
    }
}
