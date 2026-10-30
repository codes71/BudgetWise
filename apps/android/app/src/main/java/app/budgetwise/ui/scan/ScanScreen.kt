package app.budgetwise.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.Spinner
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.tracked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receipt scanning from the camera to the saved entries. [onEnterYourself] opens the add form instead; [onSaved]
 * closes scanning once the kept entries are saved.
 */
@Composable
fun ScanRoute(
    onClose: () -> Unit,
    onEnterYourself: () -> Unit,
    onSignIn: () -> Unit,
    onSaved: (ScanEvent.Saved) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is ScanEvent.Saved) saved(it) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shrinking a photo takes a moment; the reading card shows from the shutter on.
    var preparing by remember { mutableStateOf(false) }
    fun prepareAndRead(load: () -> Bitmap?) {
        preparing = true
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { load()?.let(PhotoPrep::prepare) }
            preparing = false
            if (prepared != null) viewModel.read(prepared)
        }
    }

    // Back from a card or the review returns to the camera; from the camera it leaves scanning.
    BackHandler(enabled = state.stage != ScanStage.CAMERA) { viewModel.retake() }

    when (state.stage) {
        ScanStage.CAMERA -> CameraStage(
            busy = preparing,
            onClose = onClose,
            onCaptured = { image -> prepareAndRead { PhotoPrep.fromCapture(image) } },
            onPicked = { uri -> prepareAndRead { PhotoPrep.fromUri(context, uri) } },
        )
        ScanStage.READING, ScanStage.PROBLEM -> PhotoBackdrop(viewModel.photo?.bitmap, onClose) {
            val problem = state.problem
            if (state.stage == ScanStage.READING || problem == null) {
                ReadingCard(onCancel = viewModel::retake)
            } else {
                ProblemCard(problem, onTryAgain = viewModel::tryAgain, onRetake = viewModel::retake, onEnterYourself, onSignIn)
            }
        }
        ScanStage.REVIEW -> ScanReview(
            state = state,
            onClose = onClose,
            onMove = viewModel::move,
            onAddShort = viewModel::addShortAsOther,
            onToggle = viewModel::toggle,
            onAddEntries = viewModel::addEntries,
            onAddAsOne = viewModel::addAsOne,
        )
    }
}

/** The camera is always dark, whatever the theme: the photo is the light thing on screen. */
private val Viewfinder = Color(0xFF1F1C18)
private val CameraBar = Color(0xFF17191C)
private val OnCamera = Color(0xFFF9FAFB)
private val OnCameraMuted = Color(0xFFB8BEC6)

/**
 * The viewfinder with the sage frame and the hint, then Choose a photo and the shutter. Without the camera
 * permission, a card asks for it; choosing a photo works either way (the system picker needs no permission).
 */
@Composable
private fun CameraStage(busy: Boolean, onClose: () -> Unit, onCaptured: (ImageProxy) -> Unit, onPicked: (Uri) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val type = BudgetWiseTheme.type
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted && !asked) permission.launch(Manifest.permission.CAMERA) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPicked) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            // About 2560 x 1920: sharp enough for receipt text before PhotoPrep shrinks it to 2000 px, and never the
            // full 50 MP a phone sensor can give.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
        }
    }
    DisposableEffect(granted, lifecycleOwner) {
        if (granted) controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    val shoot = {
        controller.takePicture(
            Dispatchers.Default.asExecutor(),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) = onCaptured(image)

                // A failed capture leaves the viewfinder as it was; the user takes it again.
                override fun onError(exception: ImageCaptureException) = Unit
            },
        )
    }

    Column(Modifier.fillMaxSize().background(CameraBar)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Viewfinder)) {
            if (granted) {
                AndroidView(
                    factory = { PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
                    modifier = Modifier.fillMaxSize(),
                )
                // Below the close button and the hint, so the corners never sit under them.
                ReceiptFrame(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(start = 44.dp, end = 44.dp, top = 120.dp, bottom = 32.dp),
                )
            } else {
                CameraOff(
                    modifier = Modifier.align(Alignment.Center),
                    onAllow = {
                        val again = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
                        if (!asked || again) {
                            permission.launch(Manifest.permission.CAMERA)
                        } else {
                            // Refused twice: only the app's settings can allow it now.
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }
                    },
                )
            }
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CloseButton(onClose, Modifier.align(Alignment.Start).padding(start = 16.dp))
                if (granted) {
                    Text(
                        stringResource(R.string.scan_hint),
                        style = type.size14.medium,
                        color = OnCamera,
                        modifier = Modifier
                            .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (busy) Box(Modifier.align(Alignment.Center)) { ReadingCard(onCancel = null) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(168.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !busy, role = Role.Button, onClick = pick).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(Lucide.Image, contentDescription = null, tint = OnCamera, modifier = Modifier.size(22.dp))
                    }
                    Text(stringResource(R.string.scan_choose_photo), style = type.size14, color = OnCamera)
                }
            }
            Shutter(enabled = granted && !busy, onClick = shoot)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClose, modifier = modifier.size(48.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Icon(Lucide.X, contentDescription = stringResource(R.string.action_close), tint = OnCamera, modifier = Modifier.size(22.dp))
        }
    }
}

/** A 76 dp white disc in a ring. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.scan_take_photo)
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, OnCamera.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(OnCamera.copy(alpha = if (enabled) 1f else 0.4f)))
    }
}

/** The four sage corners the receipt goes between. Decorative. */
@Composable
private fun ReceiptFrame(modifier: Modifier = Modifier) {
    val sage = BudgetWiseTheme.colors.accent
    Canvas(modifier) {
        val arm = 32.dp.toPx()
        val r = 8.dp.toPx()
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val path = Path().apply {
                moveTo(x, y + dy * arm)
                lineTo(x, y + dy * r)
                quadraticTo(x, y, x + dx * r, y)
                lineTo(x + dx * arm, y)
            }
            drawPath(path, sage, style = stroke)
        }
        corner(0f, 0f, 1f, 1f)
        corner(size.width, 0f, -1f, 1f)
        corner(0f, size.height, 1f, -1f)
        corner(size.width, size.height, -1f, -1f)
    }
}

@Composable
private fun CameraOff(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val type = BudgetWiseTheme.type
    Column(modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Lucide.Camera, contentDescription = null, tint = OnCameraMuted, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.scan_camera_off_title),
            style = type.size20.bold,
            color = OnCamera,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.scan_camera_off_detail),
            style = type.size15,
            color = OnCameraMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        PrimaryButton(stringResource(R.string.scan_allow_camera), onAllow, height = 48.dp)
    }
}

/** The photo, dimmed, behind the reading and problem cards, with close on top. */
@Composable
private fun PhotoBackdrop(photo: Bitmap?, onClose: () -> Unit, card: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(CameraBar)) {
        if (photo != null) {
            val image = remember(photo) { photo.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 96.dp),
            )
        }
        CloseButton(onClose, Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 16.dp))
        Box(Modifier.align(Alignment.Center).padding(horizontal = 24.dp)) { card() }
    }
}

/** "Reading the receipt" with a spinner; [onCancel] null while the photo is still being prepared. */
@Composable
private fun ReadingCard(onCancel: (() -> Unit)?) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.raised)
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spinner(size = 28.dp)
        Text(
            stringResource(R.string.scan_reading_title),
            style = type.size20.bold.tracked(-0.01f),
            color = colors.ink,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(stringResource(R.string.scan_reading_detail), style = type.size15, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
        if (onCancel != null) TextAction(stringResource(R.string.action_cancel), onCancel, Modifier.padding(top = 12.dp))
    }
}

/**
 * Why nothing could be read, with the way on. A photo problem offers Try again (a new photo) before typing it in;
 * no internet keeps the photo and puts typing it in first, since it may be a while.
 */
@Composable
private fun ProblemCard(
    problem: ScanProblem,
    onTryAgain: () -> Unit,
    onRetake: () -> Unit,
    onEnterYourself: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val tryAgain = stringResource(R.string.action_try_again)
    val enterYourself = stringResource(R.string.scan_enter_yourself)
    data class Card(
        val icon: ImageVector,
        val tint: Color,
        val title: String,
        val detail: String,
        val primary: Pair<String, () -> Unit>,
        val secondary: Pair<String, () -> Unit>,
    )
    val card = when (problem) {
        ScanProblem.UNREADABLE -> Card(
            Lucide.ScanLine, colors.coralText,
            stringResource(R.string.scan_unreadable_title), stringResource(R.string.scan_unreadable_detail),
            tryAgain to onRetake, enterYourself to onEnterYourself,
        )
        ScanProblem.NO_INTERNET -> Card(
            Lucide.CloudOff, colors.blue,
            stringResource(R.string.login_error_needs_internet_title), stringResource(R.string.scan_offline_detail),
            enterYourself to onEnterYourself, tryAgain to onTryAgain,
        )
        ScanProblem.TOO_MANY -> Card(
            Lucide.Clock, colors.coralText,
            stringResource(R.string.scan_too_many_title), stringResource(R.string.scan_too_many_detail),
            enterYourself to onEnterYourself, tryAgain to onTryAgain,
        )
        ScanProblem.SERVER -> Card(
            Lucide.CloudAlert, colors.coralText,
            stringResource(R.string.login_error_server_title), stringResource(R.string.login_error_server),
            tryAgain to onTryAgain, enterYourself to onEnterYourself,
        )
        ScanProblem.SIGN_IN -> Card(
            Lucide.CircleAlert, colors.coralText,
            stringResource(R.string.scan_sign_in_title), stringResource(R.string.session_ending_detail),
            stringResource(R.string.session_sign_in) to onSignIn, enterYourself to onEnterYourself,
        )
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.raised)
            // The tone's color down the left edge, as on the kit's problem cards.
            .drawBehind { drawLine(card.tint, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx()) }
            .padding(start = 27.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
    ) {
        Icon(card.icon, contentDescription = null, tint = card.tint, modifier = Modifier.size(26.dp))
        Text(
            card.title,
            style = type.size24.bold.tracked(-0.02f),
            color = colors.ink,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Text(card.detail, style = type.size15, color = colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        PrimaryButton(card.primary.first, card.primary.second, Modifier.fillMaxWidth())
        TextAction(card.secondary.first, card.secondary.second, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}
