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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import app.budgetwise.ui.common.BudgetMotion
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.MotionContent
import app.budgetwise.ui.common.PermissionMemory
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.Spinner
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.LocalDate
import java.util.Locale

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
    CloudAiConsentGate(CloudAiFeature.VOICE, onClose, onTypeInstead) {
        VoiceAcceptedRoute(onClose, onTypeInstead, onSaved, viewModel)
    }
}

/** NeedMic stays queued in the ViewModel until the disclosure's affirmative action mounts this route. */
@Composable
private fun VoiceAcceptedRoute(
    onClose: () -> Unit,
    onTypeInstead: () -> Unit,
    onSaved: () -> Unit,
    viewModel: VoiceViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun micGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val memory = remember(context) { PermissionMemory(context, Manifest.permission.RECORD_AUDIO) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        memory.answered(granted, rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true)
        if (granted) {
            viewModel.listen()
        } else {
            // After a second "Don't allow", Android stops asking: only Settings can allow it. A dismissed dialog was
            // no answer, so Allow asks again.
            viewModel.micDenied(canAsk = !memory.blocked)
        }
    }
    // Each ask follows a tap (Say it, or Allow), so a refusal faster than anyone could answer was Android's own.
    val ask = {
        memory.tapped()
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val saved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                VoiceEvent.NeedMic -> if (micGranted()) viewModel.listen() else ask()
                VoiceEvent.Saved -> saved()
            }
        }
    }
    // Back from the app's settings with the microphone allowed: start listening.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val problem = (viewModel.state.value.phase as? VoicePhase.Problem)?.problem
        if (micGranted()) memory.allowed()
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
            onAllowMic = ask,
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
    MotionContent(
        targetState = state,
        modifier = modifier.fillMaxSize(),
        contentKey = { voicePhaseKey(it.phase) },
        label = "voicePhase",
    ) { target ->
        // The outgoing tree keeps its own payload, but must not operate on the incoming recording/review.
        val active = voicePhaseKey(target.phase) == voicePhaseKey(state.phase)
        val currentActions = if (active) actions else VoiceActions()
        when (val phase = target.phase) {
            is VoicePhase.Review -> ReviewScreen(target, phase.review, currentActions, active = active)
            is VoicePhase.Answer -> AnswerScreen(target, phase, currentActions)
            else -> Box(Modifier.fillMaxSize()) {
                ListeningScreen(target, currentActions)
                when (phase) {
                    is VoicePhase.Working -> Overlay { WorkingCard(phase.step, currentActions.onClose) }
                    is VoicePhase.Problem -> Overlay { ProblemCard(phase.problem, currentActions) }
                    else -> Unit
                }
            }
        }
    }
}

/** Payload changes update an existing review form; a different answer remains a different content identity. */
internal fun voicePhaseKey(phase: VoicePhase): Any = when (phase) {
    is VoicePhase.Review -> "review"
    is VoicePhase.Answer -> phase.copy(showEntries = false)
    else -> phase
}

/**
 * The recording page: a level-driven waveform, one clearly labelled example and the three available actions.
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
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        BackHeader(
            title = stringResource(R.string.draft3_voice_entry),
            onBack = actions.onClose,
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val language = stringResource(R.string.draft3_voice_language)
            Text(language, style = type.size18.semibold.forContent(language), color = colors.ink)
            Box(Modifier.weight(1f))
            val seconds = state.elapsedSeconds
            Text(
                text = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
                    .let { if (state.myanmarDigits) toMyanmarDigits(it) else it },
                style = type.size14.medium,
                color = colors.muted,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
                MicRings(level = if (listening) state.level else 0f, listening = listening)
            }
            Text(
                stringResource(if (listening) R.string.voice_listening else R.string.voice_stopped),
                style = type.size32.extraBold.tracked(-0.025f),
                color = colors.ink,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.voice_prompt),
                style = type.size14,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            SectionLabel(stringResource(R.string.voice_for_example), Modifier.align(Alignment.Start).padding(top = 20.dp, bottom = 12.dp))
            Example(R.string.voice_example_1, R.string.voice_example_1_gloss)
            Text(
                stringResource(R.string.draft3_voice_finish_hint),
                style = type.size14,
                color = colors.muted,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingAction(Lucide.Trash, stringResource(R.string.draft3_discard), actions.onClose, Modifier.weight(1f))
            Column(
                Modifier.weight(1f).clickable(enabled = listening, role = Role.Button, onClick = actions.onDone).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(if (listening) colors.sageFill else colors.raised), contentAlignment = Alignment.Center) {
                    Icon(Lucide.CheckBold, contentDescription = null, tint = if (listening) colors.onSageFill else colors.disabled, modifier = Modifier.size(28.dp))
                }
                Text(stringResource(R.string.privacy_voice_finish), style = type.size14.semibold, color = if (listening) colors.ink else colors.disabled)
            }
            RecordingAction(Lucide.Pencil, stringResource(R.string.voice_type_instead), actions.onTypeInstead, Modifier.weight(1f))
        }
        StaysOnPhone(Modifier.align(Alignment.CenterHorizontally).padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
    }
}

@Composable
private fun Example(quote: Int, gloss: Int) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val said = stringResource(quote)
        Text(said, style = type.size24.forContent(said), color = colors.ink)
        Text(stringResource(gloss), style = type.size14, color = colors.muted)
    }
}

@Composable
private fun RecordingAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Column(
        modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
        Text(label, style = BudgetWiseTheme.type.size13.medium, color = colors.muted, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun StaysOnPhone(modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Lucide.Smartphone, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        val notice = stringResource(R.string.privacy_voice_notice)
        Text(notice, style = BudgetWiseTheme.type.size13.forContent(notice), color = colors.muted)
    }
}

/** Actual observed levels, bounded to twelve bars. Silence rests as dots instead of a decorative wave. */
@Composable
private fun MicRings(level: Float, listening: Boolean) {
    val colors = BudgetWiseTheme.colors
    var history by remember(listening) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(level, listening) {
        history = if (listening) observedWaveform(history, level) else emptyList()
    }
    // Keep animation State objects here; their frame-rate values are read only by the Canvas draw block.
    val bars = List(VOICE_WAVEFORM_BARS) { index ->
        animateFloatAsState(
            targetValue = history.getOrElse(index - (VOICE_WAVEFORM_BARS - history.size)) { 0f },
            animationSpec = BudgetMotion.response(),
            label = "voiceLevel$index",
        )
    }
    val atmosphere = remember(colors.sageContainer) {
        Brush.radialGradient(listOf(colors.sageContainer.copy(alpha = 0.5f), colors.sageContainer.copy(alpha = 0f)))
    }
    Canvas(Modifier.size(190.dp).clearAndSetSemantics { }) {
        val unit = size.minDimension / 190f
        drawCircle(brush = atmosphere, radius = size.minDimension / 2f)
        bars.forEachIndexed { index, sample ->
            val h = (2f + sample.value * 64f) * unit
            drawRoundRect(
                color = if (listening) colors.accent else colors.disabled,
                topLeft = Offset((37.5f + index * 10f) * unit, center.y - h / 2f),
                size = Size(5f * unit, h),
                cornerRadius = CornerRadius(2.5f * unit),
            )
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
