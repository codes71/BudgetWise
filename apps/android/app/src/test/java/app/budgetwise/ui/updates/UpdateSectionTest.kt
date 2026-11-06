package app.budgetwise.ui.updates

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.updates.UpdateProblem
import app.budgetwise.updates.UpdateRelease
import app.budgetwise.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal val testRelease = UpdateRelease(
    versionCode = 2,
    versionName = "0.2.0",
    minSdk = 26,
    apkUrl = "https://github.com/codes71/BudgetWise/releases/download/pocket-peek-android-friends-v0.3.0-4/PocketPeek-0.3.0-4.apk",
    sha256 = "a".repeat(64),
    sizeBytes = 40_000_000,
    notes = "New features and fixes.",
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp")
class UpdateSectionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var checks = 0
    private var downloads = 0
    private var cancellations = 0
    private var installations = 0

    private fun text(id: Int) = context.getString(id)

    private fun show(state: UpdateState, canInstall: Boolean = true, largeDark: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (largeDark) 2f else 1f)) {
                BudgetWiseTheme(darkTheme = largeDark) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        UpdateSection(
                            state = state,
                            myanmarDigits = false,
                            onCheck = { checks++ },
                            onDownload = { downloads++ },
                            onCancel = { cancellations++ },
                            onInstall = { installations++ },
                            canInstall = canInstall,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun anAvailableUpdateOnlyDownloadsAfterItsButtonIsPressed() {
        show(UpdateState.Available(testRelease))
        compose.onNodeWithText(context.getString(R.string.updates_available, "0.2.0")).assertIsDisplayed()
        compose.onNodeWithText("New features and fixes.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.updates_install)).assertDoesNotExist()
        assertEquals(0, downloads)
        compose.onNodeWithText(text(R.string.updates_download)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, downloads)
        assertEquals(0, installations)
    }

    @Test
    fun theDownloadShowsSemanticProgressAndCanBeCancelled() {
        show(UpdateState.Downloading(testRelease, 20_000_000, 40_000_000))
        compose.onNodeWithText("20 MB of 40 MB").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        compose.onNodeWithText(text(R.string.updates_cancel_download)).performClick()
        assertEquals(1, cancellations)
        compose.onNodeWithText(text(R.string.updates_install)).assertDoesNotExist()
    }

    @Test
    fun theReadyUpdateRequiresAnExplicitInstallAction() {
        show(UpdateState.Ready(testRelease))
        assertEquals(0, installations)
        compose.onNodeWithText(text(R.string.updates_install)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, installations)
    }

    @Test
    fun checkingCannotBePressedAgainAndCurrentCanBeCheckedAgain() {
        val state = mutableStateOf<UpdateState>(UpdateState.Checking)
        compose.setContent {
            BudgetWiseTheme {
                UpdateSection(state.value, false, { checks++ }, {}, {}, {})
            }
        }
        compose.onNode(hasText(text(R.string.updates_checking)) and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { state.value = UpdateState.Current }
        compose.onNodeWithText(text(R.string.updates_check_again)).performClick()
        assertEquals(1, checks)
    }

    @Test
    fun permissionAndDownloadProblemsOfferTheRightNextAction() {
        val state = mutableStateOf<UpdateState>(UpdateState.Problem(UpdateProblem.PERMISSION_NEEDED, testRelease))
        compose.setContent {
            BudgetWiseTheme {
                UpdateSection(state.value, false, { checks++ }, { downloads++ }, {}, { installations++ })
            }
        }
        assertEquals(0, installations)
        compose.onNodeWithText(text(R.string.updates_allow_install)).performClick()
        assertEquals(1, installations)
        compose.runOnIdle { state.value = UpdateState.Problem(UpdateProblem.DOWNLOAD_FAILED, testRelease) }
        compose.onNodeWithText(text(R.string.updates_download_again)).performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun aHostWithoutAnActivityCannotOfferInstallation() {
        show(UpdateState.Ready(testRelease), canInstall = false)
        compose.onNodeWithText(text(R.string.updates_install)).assertIsNotEnabled()
    }

    @Test
    fun disabledUpdatesAddNoControls() {
        show(UpdateState.Disabled)
        compose.onNodeWithText(text(R.string.updates_section)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.updates_check)).assertDoesNotExist()
    }

    @Test
    fun theInstallActionRemainsReachableWithLargeTextInDarkMode() {
        show(UpdateState.Ready(testRelease), largeDark = true)
        compose.onNodeWithText(text(R.string.updates_install)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, installations)
    }
}
