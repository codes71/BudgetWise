package app.budgetwise.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.budgetwise.R
import app.budgetwise.asr.ModelManager
import app.budgetwise.asr.ModelState
import app.budgetwise.asr.VoicePack
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.di.DeviceClock
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SecondaryButton
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The card over the screen that offered Say it: set up voice (the download), or its progress. */
enum class VoiceCard { SETUP, DOWNLOADING }

data class VoiceSetupUiState(
    val model: ModelState = ModelState.Missing,
    val card: VoiceCard? = null,
    /** At the speed so far; null until a few seconds of a running download tell. */
    val minutesLeft: Int? = null,
    val myanmarDigits: Boolean = false,
) {
    /** Downloading or done: nothing to offer. */
    val setUp: Boolean get() = model is ModelState.Ready || model is ModelState.Downloading
}

sealed interface VoiceSetupEvent {
    /** Voice is ready: open the listening screen. */
    data object OpenVoice : VoiceSetupEvent
}

/**
 * Say it before voice is on the phone. [onSayIt] opens voice when the models are ready, else the setup card; Download
 * starts the 1.1 GB download, which waits for Wi-Fi unless the user chooses mobile data, and shows its progress. When
 * the download finishes while its card is open, voice opens.
 */
@HiltViewModel
class VoiceSetupViewModel @Inject constructor(
    private val models: ModelManager,
    settings: SettingsStore,
    @DeviceClock private val clock: Clock,
) : ViewModel() {
    private val card = MutableStateFlow<VoiceCard?>(null)
    private val minutesLeft = MutableStateFlow<Int?>(null)

    /** The first sample of the running download: when, and how far it was. */
    private var since: Pair<Long, Float>? = null

    private val _events = Channel<VoiceSetupEvent>(Channel.BUFFERED)
    val events: Flow<VoiceSetupEvent> = _events.receiveAsFlow()

    val state: StateFlow<VoiceSetupUiState> =
        combine(models.state, card, minutesLeft, settings.myanmarDigits) { model, card, minutes, digits ->
            VoiceSetupUiState(model, card, minutes, digits)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSetupUiState(models.state.value))

    init {
        viewModelScope.launch {
            models.state.collect { model ->
                track(model)
                when {
                    model is ModelState.Ready && card.value == VoiceCard.DOWNLOADING -> {
                        card.value = null
                        _events.send(VoiceSetupEvent.OpenVoice)
                    }
                    // Back to the offer, which says why the download stopped.
                    model is ModelState.Failed && card.value == VoiceCard.DOWNLOADING -> card.value = VoiceCard.SETUP
                }
            }
        }
    }

    fun onSayIt() {
        when (models.state.value) {
            is ModelState.Ready -> _events.trySend(VoiceSetupEvent.OpenVoice)
            is ModelState.Downloading -> card.value = VoiceCard.DOWNLOADING
            ModelState.Missing, is ModelState.Failed -> card.value = VoiceCard.SETUP
        }
    }

    /** Starts (or resumes) the download on Wi-Fi; [showProgress] opens its card. */
    fun download(showProgress: Boolean = true) {
        if (showProgress) card.value = VoiceCard.DOWNLOADING
        start(allowMetered = false)
    }

    /** The waiting download goes ahead on mobile data. */
    fun useMobileData() = start(allowMetered = true)

    fun dismiss() {
        card.value = null
    }

    /** Stops the download and deletes what it fetched. */
    fun cancelDownload() {
        card.value = null
        viewModelScope.launch {
            try {
                models.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // WorkManager or the file system failed; the state still shows what is on the phone.
            }
        }
    }

    private fun start(allowMetered: Boolean) {
        viewModelScope.launch {
            try {
                models.ensureDownloaded(allowMetered)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Enqueueing failed (a WorkManager database error); the card stays as it is for another try.
            }
        }
    }

    /** Minutes left from the average speed since the running download was first seen. */
    private fun track(model: ModelState) {
        if (model !is ModelState.Downloading || model.waiting) {
            since = null
            minutesLeft.value = null
            return
        }
        val now = clock.millis()
        val first = since ?: (now to model.progress).also { since = it }
        val elapsed = now - first.first
        val done = model.progress - first.second
        if (elapsed < MIN_SAMPLE_MS || done <= 0f) return
        val left = (1f - model.progress) / done * elapsed
        minutesLeft.value = ceil(left / 60_000.0).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val MIN_SAMPLE_MS = 5_000L
    }
}

/**
 * Hosts the setup and download cards for a screen that offers Say it, and returns what Say it does. [onOpenVoice]
 * opens the listening screen once voice is on the phone.
 */
@Composable
fun rememberSayIt(onOpenVoice: () -> Unit, viewModel: VoiceSetupViewModel = hiltViewModel()): () -> Unit {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpenVoice)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == VoiceSetupEvent.OpenVoice) open() }
    }
    val askNotifications = rememberAskNotifications()
    VoiceSetupCards(
        state = state,
        onDownload = {
            askNotifications()
            viewModel.download()
        },
        onUseMobileData = viewModel::useMobileData,
        onDismiss = viewModel::dismiss,
        onCancelDownload = viewModel::cancelDownload,
    )
    return viewModel::onSayIt
}

/**
 * Asks once for notifications (Android 13 and later), so the download's progress and "voice is ready" can show. The
 * answer changes nothing else: the download runs either way.
 */
@Composable
fun rememberAskNotifications(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun VoiceSetupCards(
    state: VoiceSetupUiState,
    onDownload: () -> Unit,
    onUseMobileData: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val card = state.card ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            when (card) {
                VoiceCard.SETUP -> SetupCard(state, onDownload, onDismiss)
                VoiceCard.DOWNLOADING -> DownloadingCard(state, onUseMobileData, onDismiss, onCancelDownload)
            }
        }
    }
}

/** "Set up voice": what it does, the size, and that the voice itself never leaves the phone. */
@Composable
private fun SetupCard(state: VoiceSetupUiState, onDownload: () -> Unit, onNotNow: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val size = voiceSize(VoicePack.downloadBytes, state.myanmarDigits)
    VoiceDialogCard {
        MicChip()
        Text(
            stringResource(R.string.voice_setup_title),
            style = type.size22.extraBold.tracked(-0.02f),
            color = colors.ink,
            modifier = Modifier.padding(top = 10.dp).semantics { heading() },
        )
        val body = stringResource(R.string.voice_setup_body, size)
        Text(
            text = buildAnnotatedString {
                append(body)
                val at = body.indexOf(size)
                if (at >= 0) addStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold), at, at + size.length)
            },
            style = type.size14,
            color = colors.muted,
        )
        val failed = state.model as? ModelState.Failed
        if (failed != null) {
            Text(
                stringResource(if (failed.outOfStorage) R.string.voice_download_no_room else R.string.voice_download_failed, size),
                style = type.size13,
                color = colors.coralText,
            )
        }
        InfoBox(Lucide.Smartphone, colors.blue, stringResource(R.string.voice_privacy))
        PrimaryButton(
            text = stringResource(R.string.voice_download, size),
            onClick = onDownload,
            icon = Lucide.Download,
            height = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        CardLink(stringResource(R.string.voice_not_now), colors.accent, onNotNow)
    }
}

/** "Downloading voice": how far, how long, and that the app stays usable. */
@Composable
private fun DownloadingCard(
    state: VoiceSetupUiState,
    onUseMobileData: () -> Unit,
    onKeepUsing: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val downloading = state.model as? ModelState.Downloading
    val progress = downloading?.progress ?: 0f
    VoiceDialogCard {
        MicChip()
        Text(
            stringResource(R.string.voice_downloading_title),
            style = type.size22.extraBold.tracked(-0.02f),
            color = colors.ink,
            modifier = Modifier.padding(top = 10.dp).semantics { heading() },
        )
        DownloadProgress(progress, state.myanmarDigits, Modifier.padding(top = 4.dp))
        when {
            downloading?.waiting == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.voice_waiting_wifi), style = type.size13, color = colors.muted, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.voice_use_mobile_data),
                    style = type.size13.semibold,
                    color = colors.accent,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onUseMobileData)
                        .padding(horizontal = 6.dp)
                        .padding(vertical = 10.dp),
                )
            }
            state.minutesLeft != null -> Text(
                pluralStringResource(R.plurals.voice_minutes_left, state.minutesLeft, state.minutesLeft),
                style = type.size13,
                color = colors.muted,
            )
        }
        InfoBox(Lucide.Bell, colors.muted, stringResource(R.string.voice_download_note))
        SecondaryButton(
            text = stringResource(R.string.voice_keep_using),
            onClick = onKeepUsing,
            height = 50.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        CardLink(stringResource(R.string.voice_cancel_download), colors.coralText, onCancel)
    }
}

/** "430 MB of 1.1 GB" and the percent over a sage bar. */
@Composable
fun DownloadProgress(progress: Float, myanmarDigits: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val total = VoicePack.downloadBytes
    val done = voiceSize((total * progress.toDouble()).toLong(), myanmarDigits)
    val line = stringResource(R.string.voice_downloaded_of, done, voiceSize(total, myanmarDigits))
    val percent = "${(progress * 100).toInt()}%".let { if (myanmarDigits) toMyanmarDigits(it) else it }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildAnnotatedString {
                    append(line)
                    val at = line.indexOf(done)
                    if (at >= 0) addStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold), at, at + done.length)
                },
                style = type.size14,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            Text(percent, style = type.size14.semibold, color = colors.accent)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.raised)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.accent))
        }
    }
}

/** "430 MB", "1.1 GB": decimal units, as Android's storage settings count. */
fun voiceSize(bytes: Long, myanmarDigits: Boolean = false): String {
    val text = if (bytes >= 1_000_000_000L) {
        String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.US, "%d MB", bytes / 1_000_000L)
    }
    return if (myanmarDigits) toMyanmarDigits(text) else text
}

/** The rounded card voice's pop-ups share: on the surface, 24 dp in. [edge] colors its left side (a problem's tone). */
@Composable
internal fun VoiceDialogCard(modifier: Modifier = Modifier, edge: Color? = null, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(BudgetWiseTheme.colors.surface)
            .then(if (edge != null) Modifier.drawBehind { drawRect(edge, size = Size(3.dp.toPx(), size.height)) } else Modifier)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun MicChip() {
    Box(Modifier.size(52.dp).clip(CircleShape).background(BudgetWiseTheme.colors.sageContainer), contentAlignment = Alignment.Center) {
        Icon(Lucide.Mic, contentDescription = null, tint = BudgetWiseTheme.colors.accent, modifier = Modifier.size(24.dp))
    }
}

/** A note on the ground color inside a card: an icon and a few lines. */
@Composable
internal fun InfoBox(icon: ImageVector, tint: Color, text: String, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.ground)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = BudgetWiseTheme.type.size13, color = colors.ink.copy(alpha = 0.82f))
    }
}

/** A centered text action under a card's button: Not now, Cancel download. */
@Composable
internal fun CardLink(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = BudgetWiseTheme.type.size15.semibold,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
