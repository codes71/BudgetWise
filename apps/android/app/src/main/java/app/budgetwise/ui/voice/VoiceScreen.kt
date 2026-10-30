package app.budgetwise.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.repo.VoiceEntry
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.Spinner
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.LocalDate

/**
 * Voice entry, full screen. [onClose] leaves it; [onTypeInstead] swaps it for the add form; [onSaved] leaves after the
 * entries are saved.
 */
@Composable
fun VoiceRoute(
    onClose: () -> Unit,
    onTypeInstead: () -> Unit,
    onSaved: () -> Unit,
    viewModel: VoiceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun micGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.listen()
        } else {
            // After a second "Don't allow", Android stops asking: only Settings can allow it.
            viewModel.micDenied(canAsk = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true)
        }
    }
    val saved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                VoiceEvent.NeedMic -> if (micGranted()) viewModel.listen() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                VoiceEvent.Saved -> saved()
            }
        }
    }
    // Back from the app's settings with the microphone allowed: start listening.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val problem = (viewModel.state.value.phase as? VoicePhase.Problem)?.problem
        if (problem is VoiceProblem.MicDenied && micGranted()) viewModel.listen()
    }
    VoiceScreen(
        state = state,
        actions = VoiceActions(
            onClose = onClose,
            onDone = viewModel::done,
            onSayAgain = viewModel::sayAgain,
            onTypeInstead = onTypeInstead,
            onRetry = viewModel::retry,
            onTurnOnVoice = viewModel::turnOnVoice,
            onAllowMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onEditEntry = viewModel::updateEntry,
            onRemoveEntry = viewModel::removeEntry,
            onSave = viewModel::save,
            onToggleAnswerEntries = viewModel::toggleAnswerEntries,
        ),
    )
}

/** Everything the voice screens can do; one holder keeps [VoiceScreen]'s signature short. */
class VoiceActions(
    val onClose: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onSayAgain: () -> Unit = {},
    val onTypeInstead: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTurnOnVoice: () -> Unit = {},
    val onAllowMic: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onEditEntry: (Int, VoiceEntry) -> Unit = { _, _ -> },
    val onRemoveEntry: (Int) -> Unit = {},
    val onSave: () -> Unit = {},
    val onToggleAnswerEntries: () -> Unit = {},
)

@Composable
fun VoiceScreen(state: VoiceUiState, actions: VoiceActions, modifier: Modifier = Modifier) {
    when (val phase = state.phase) {
        is VoicePhase.Review -> ReviewScreen(state, phase.review, actions, modifier)
        is VoicePhase.Answer -> AnswerScreen(state, phase, actions, modifier)
        else -> Box(modifier.fillMaxSize()) {
            ListeningScreen(state, actions)
            when (phase) {
                is VoicePhase.Working -> Overlay { WorkingCard(phase.step, actions.onClose) }
                is VoicePhase.Problem -> Overlay { ProblemCard(phase.problem, actions) }
                else -> Unit
            }
        }
    }
}

/**
 * "Say what you spent": the timer, the microphone in rings that follow the voice's loudness, three examples and Done.
 * Stopped (under a card, or before the microphone starts) the label says so and Done is off.
 */
@Composable
private fun ListeningScreen(state: VoiceUiState, actions: VoiceActions) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val listening = state.phase == VoicePhase.Listening
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onClose, modifier = Modifier.size(48.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.action_close), tint = colors.ink, modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.weight(1f))
            val seconds = state.elapsedSeconds
            Text(
                text = "%d:%02d".format(seconds / 60, seconds % 60),
                style = type.size15.medium.tracked(0.04f),
                color = colors.muted,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (listening) colors.accent else colors.disabled))
                SectionLabel(
                    stringResource(if (listening) R.string.voice_listening else R.string.voice_stopped),
                    color = if (listening) colors.accent else colors.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                stringResource(R.string.voice_prompt),
                style = type.size26.extraBold.tracked(-0.02f),
                color = colors.ink,
                modifier = Modifier.padding(top = 10.dp).semantics { heading() },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            MicRings(level = if (listening) state.level else 0f, listening = listening)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            SectionLabel(stringResource(R.string.voice_for_example), Modifier.padding(bottom = 10.dp))
            Example(R.string.voice_example_1, R.string.voice_example_1_gloss)
            Example(R.string.voice_example_2, R.string.voice_example_2_gloss)
            Example(R.string.voice_example_3, R.string.voice_example_3_gloss)
        }
        PrimaryButton(
            text = stringResource(R.string.voice_done),
            onClick = actions.onDone,
            enabled = listening,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp),
        )
        StaysOnPhone(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 12.dp))
    }
}

@Composable
private fun Example(quote: Int, gloss: Int) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val said = stringResource(quote)
        Text(said, style = type.size16.forContent(said), color = colors.ink, modifier = Modifier.weight(1f))
        Text(stringResource(gloss), style = type.size13, color = colors.muted, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun StaysOnPhone(modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Lucide.Smartphone, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.voice_stays_on_phone), style = BudgetWiseTheme.type.size13, color = colors.muted)
    }
}

/** The sage microphone in two rings that grow with the voice, 260 dp across or less on a short screen. Decorative. */
@Composable
private fun MicRings(level: Float, listening: Boolean) {
    val colors = BudgetWiseTheme.colors
    val loud by animateFloatAsState(level, tween(120), label = "level")
    Box(
        Modifier.heightIn(max = 260.dp).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.minDimension / 260f
            val ring = colors.sageContainer
            drawCircle(ring.copy(alpha = if (listening) 0.35f else 0.15f), radius = unit * (100f + 30f * loud))
            drawCircle(ring.copy(alpha = if (listening) 0.7f else 0.3f), radius = unit * (80f + 12f * loud))
        }
        Box(
            Modifier.fillMaxSize(120f / 260f).clip(CircleShape).background(if (listening) colors.sageFill else colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.MicBold, contentDescription = null, tint = if (listening) colors.onSageFill else colors.muted, modifier = Modifier.size(36.dp))
        }
    }
}

/** The scrim over the stopped listening screen, with a card in the middle; it takes every touch under it. */
@Composable
private fun Overlay(card: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BudgetWiseTheme.colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { card() }
}

/** "Working it out": on the phone first, then on the server. Cancel leaves voice. */
@Composable
private fun WorkingCard(step: WorkStep, onCancel: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    VoiceDialogCard(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner(size = 30.dp)
            Text(
                stringResource(R.string.voice_working_title),
                style = type.size22.extraBold.tracked(-0.02f),
                color = colors.ink,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                stringResource(if (step == WorkStep.LISTENING_BACK) R.string.voice_working_listening_back else R.string.voice_working_understanding),
                style = type.size14,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CardLink(stringResource(R.string.action_cancel), colors.accent, onCancel)
    }
}

private data class ProblemCopy(
    val icon: ImageVector,
    val tone: Color,
    val title: String,
    val body: String,
    val primary: Pair<String, () -> Unit>,
    val primaryIcon: ImageVector? = null,
    val secondary: Pair<String, () -> Unit>,
)

/** One card per [VoiceProblem]: what happened, and the two ways on. */
@Composable
private fun ProblemCard(problem: VoiceProblem, actions: VoiceActions) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val sayAgain = stringResource(R.string.voice_say_again) to actions.onSayAgain
    val typeInstead = stringResource(R.string.voice_type_instead) to actions.onTypeInstead
    val copy = when (problem) {
        is VoiceProblem.NoAmount -> ProblemCopy(
            Lucide.CircleHelp, colors.amber, stringResource(R.string.voice_no_amount_title),
            stringResource(R.string.voice_no_amount_body, problem.heard), sayAgain, Lucide.Mic, typeInstead,
        )
        VoiceProblem.NothingHeard -> ProblemCopy(
            Lucide.CircleHelp, colors.amber, stringResource(R.string.voice_nothing_title),
            stringResource(R.string.voice_nothing_body), sayAgain, Lucide.Mic, typeInstead,
        )
        VoiceProblem.Offline -> ProblemCopy(
            Lucide.CloudOff, colors.blue, stringResource(R.string.voice_offline_title),
            stringResource(R.string.voice_offline_body), stringResource(R.string.voice_type_yourself) to actions.onTypeInstead,
            secondary = stringResource(R.string.action_try_again) to actions.onRetry,
        )
        VoiceProblem.Failed -> ProblemCopy(
            Lucide.CloudAlert, colors.coralText, stringResource(R.string.voice_failed_title),
            stringResource(R.string.voice_failed_body), stringResource(R.string.action_try_again) to actions.onRetry,
            secondary = typeInstead,
        )
        VoiceProblem.SignedOut -> ProblemCopy(
            Lucide.Lock, colors.coralText, stringResource(R.string.voice_signed_out_title),
            stringResource(R.string.voice_signed_out_body), typeInstead, secondary = stringResource(R.string.action_close) to actions.onClose,
        )
        VoiceProblem.ListenFailed -> ProblemCopy(
            Lucide.TriangleAlert, colors.coralText, stringResource(R.string.voice_listen_failed_title),
            stringResource(R.string.voice_listen_failed_body), sayAgain, Lucide.Mic, typeInstead,
        )
        is VoiceProblem.MicDenied -> ProblemCopy(
            Lucide.MicOff, colors.blue, stringResource(R.string.voice_mic_title), stringResource(R.string.voice_mic_body),
            if (problem.canAsk) {
                stringResource(R.string.voice_allow) to actions.onAllowMic
            } else {
                stringResource(R.string.voice_open_settings) to actions.onOpenAppSettings
            },
            secondary = typeInstead,
        )
        VoiceProblem.MicBusy -> ProblemCopy(
            Lucide.MicOff, colors.blue, stringResource(R.string.voice_mic_busy_title),
            stringResource(R.string.voice_mic_busy_body), sayAgain, Lucide.Mic, typeInstead,
        )
        VoiceProblem.VoiceOff -> ProblemCopy(
            Lucide.ToggleLeft, colors.blue, stringResource(R.string.voice_off_title), stringResource(R.string.voice_off_body),
            stringResource(R.string.voice_turn_on) to actions.onTurnOnVoice, secondary = typeInstead,
        )
    }
    VoiceDialogCard(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, edge = copy.tone) {
        Icon(copy.icon, contentDescription = null, tint = copy.tone, modifier = Modifier.size(26.dp))
        Text(
            copy.title,
            style = type.size22.extraBold.tracked(-0.02f),
            color = colors.ink,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Text(copy.body, style = type.size14.forContent(copy.body), color = colors.muted)
        PrimaryButton(
            text = copy.primary.first,
            onClick = copy.primary.second,
            icon = copy.primaryIcon,
            height = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        CardLink(copy.secondary.first, colors.accent, copy.secondary.second)
    }
}

/** What was heard, set off by a sage bar: the words as the server understood them. */
@Composable
internal fun HeardQuote(text: String, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val accent = colors.accent
    Text(
        text = text,
        style = BudgetWiseTheme.type.size17.forContent(text),
        color = colors.ink,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(accent, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** The big figure with "Ks" and the sage rule under it, as on the home screen. */
@Composable
internal fun BigKyat(figure: String, modifier: Modifier = Modifier, spoken: String) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier) {
        Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                figure,
                style = (if (figure.length > 8) type.size44 else type.size60).extraBold.tracked(-0.035f),
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Text("Ks", style = type.size24.medium, color = colors.muted, modifier = Modifier.alignByBaseline())
        }
        SageRule(Modifier.padding(top = 16.dp))
    }
}

/** The page for Review and Answer: a close header, scrolling [content], and [footer] pinned at the bottom. */
@Composable
internal fun VoicePage(
    title: String,
    onClose: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    Column(modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars)) {
        BackHeader(
            title = title,
            onBack = onClose,
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = footer,
        )
    }
}

/** Say it again / Ask something else: sage words after a microphone, one 48 dp target. */
@Composable
internal fun MicLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Mic, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Text(text, style = BudgetWiseTheme.type.size15.semibold, color = colors.accent)
    }
}

@Preview(showBackground = true, heightDp = 844)
@Composable
private fun ListeningPreview() {
    BudgetWiseTheme {
        VoiceScreen(VoiceUiState(LocalDate.parse("2026-10-30"), VoicePhase.Listening, elapsedSeconds = 4, level = 0.5f), VoiceActions())
    }
}

@Preview(showBackground = true, heightDp = 844)
@Composable
private fun NoAmountPreview() {
    BudgetWiseTheme {
        VoiceScreen(
            VoiceUiState(LocalDate.parse("2026-10-30"), VoicePhase.Problem(VoiceProblem.NoAmount("ကော်ဖီ သောက်ခဲ့တယ်")), elapsedSeconds = 4),
            VoiceActions(),
        )
    }
}
