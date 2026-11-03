package app.budgetwise.ui.manual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import app.budgetwise.ui.voice.VoiceSetupViewModel
import app.budgetwise.ui.voice.VoiceUnsupportedDialog
import app.budgetwise.ui.voice.rememberSayIt

/**
 * The bottom bar's Add, shown while [visible]. It hosts what Say it needs (the voice setup and download cards) for as
 * long as the shell is on screen, so a card Say it opens stays when the sheet goes. Each choice closes the sheet
 * ([onClose]), then opens what was picked; Say it opens voice ([onOpenVoice]) once voice is on the phone.
 */
@Composable
fun AddSheetRoute(
    visible: Boolean,
    onClose: () -> Unit,
    onScan: () -> Unit,
    onOpenVoice: () -> Unit,
    onTypeIt: () -> Unit,
    onAddIncome: () -> Unit,
    voiceSetup: VoiceSetupViewModel = hiltViewModel(),
) {
    val voice by voiceSetup.state.collectAsStateWithLifecycle()
    val sayIt = rememberSayIt(onOpenVoice, voiceSetup)
    if (!visible) return
    fun picked(action: () -> Unit): () -> Unit = {
        onClose()
        action()
    }
    AddSheet(
        onDismiss = onClose,
        onScan = picked(onScan),
        onSayIt = sayIt?.let { picked(it) },
        onTypeIt = picked(onTypeIt),
        onAddIncome = picked(onAddIncome),
        explainNoVoice = voice.showUnsupportedNotice,
        onNoVoiceExplained = voiceSetup::noticeSeen,
        myanmarDigits = voice.myanmarDigits,
    )
}

/**
 * How to add an entry, the camera first (board D5): the big sage Scan a receipt card, Say it under it, and Type it and
 * Add income as two outline pills. On a phone without the memory for voice ([onSayIt] null) there is no Say it, and
 * [explainNoVoice] says once why, where Say it would be: people who signed in rather than signing up never saw
 * onboarding's notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onSayIt: (() -> Unit)?,
    onTypeIt: () -> Unit,
    onAddIncome: () -> Unit,
    explainNoVoice: Boolean = false,
    onNoVoiceExplained: () -> Unit = {},
    myanmarDigits: Boolean = false,
) {
    val colors = BudgetWiseTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 18.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(colors.outline))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.draft3_entry_sheet_title),
                    style = BudgetWiseTheme.type.size24.bold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.action_close), tint = colors.ink, modifier = Modifier.size(22.dp))
                }
            }
            AddChoices(onScan, onSayIt, onTypeIt, onAddIncome, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp))
        }
        // Drawn from inside the sheet, so it opens over it.
        if (explainNoVoice) VoiceUnsupportedDialog(onDismiss = onNoVoiceExplained, myanmarDigits = myanmarDigits)
    }
}

/** The sheet's content: Scan a receipt, Say it when [onSayIt] is set, then Type it and Add income side by side. */
@Composable
internal fun AddChoices(
    onScan: () -> Unit,
    onSayIt: (() -> Unit)?,
    onTypeIt: () -> Unit,
    onAddIncome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanCard(onScan)
        if (onSayIt != null) SayItCard(onSayIt)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPill(Lucide.Keyboard, BudgetWiseTheme.colors.muted, stringResource(R.string.add_type_it), onTypeIt, Modifier.weight(1f))
            SheetPill(
                Lucide.ArrowDownLeftBold,
                categoryColors(CategoryRepository.INCOME).content,
                stringResource(R.string.add_income),
                onAddIncome,
                Modifier.weight(1f),
            )
        }
    }
}

/** The big sage card: the camera badge, the receipt in a viewfinder at the top right, and what scanning does. */
@Composable
private fun ScanCard(onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(colors.sageFill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(20.dp),
    ) {
        ReceiptInViewfinder(Modifier.align(Alignment.TopEnd).padding(top = 2.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.onSageFill), contentAlignment = Alignment.Center) {
            Icon(Lucide.Camera, contentDescription = null, tint = colors.sageFill, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(top = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.scan_entry), style = type.size26.extraBold.tracked(-0.025f), color = colors.onSageFill)
            Text(stringResource(R.string.add_scan_detail), style = type.size14, color = colors.onSageFill)
        }
    }
}

// The board's slip of paper and its grey-green lines. The card is sage in both themes, so these are too.
private val PaperWhite = Color(0xFFE9F5EF)
private val PaperLine = Color(0xFF8FB8A6)

/** The board's four corner brackets, in its 96 x 118 box. */
private val ViewfinderCorners by lazy {
    PathParser().parsePathString("M2 20V8a6 6 0 0 1 6-6h12M76 2h12a6 6 0 0 1 6 6v12M94 98v12a6 6 0 0 1-6 6H76M20 116H8a6 6 0 0 1-6-6V98").toPath()
}

/** A receipt tilted in a viewfinder, in forest on the sage card. Decorative. */
@Composable
private fun ReceiptInViewfinder(modifier: Modifier = Modifier) {
    val ink = BudgetWiseTheme.colors.onSageFill
    Canvas(modifier.size(width = 96.dp, height = 118.dp)) {
        // Drawn in the board's units, one per dp.
        scale(density, pivot = Offset.Zero) {
            drawPath(ViewfinderCorners, ink, style = Stroke(width = 3f, cap = StrokeCap.Round))
            // The slip, 52 x 86, turned 6 degrees about its top left corner: six lines 3 high (the total, last, 4)
            // with 6 between.
            rotate(-6f, pivot = Offset(22f, 16f)) {
                drawRoundRect(PaperWhite, topLeft = Offset(22f, 16f), size = Size(52f, 86f), cornerRadius = CornerRadius(4f))
                var top = 26f
                listOf(24f to ink, 34f to PaperLine, 28f to PaperLine, 32f to PaperLine, 20f to PaperLine, 36f to ink).forEachIndexed { i, (width, color) ->
                    val height = if (i == 5) 4f else 3f
                    drawRoundRect(color, topLeft = Offset(30f, top), size = Size(width, height), cornerRadius = CornerRadius(2f))
                    top += height + 6f
                }
            }
        }
    }
}

/** Say it: the mic in a sage circle, an example of what to say, and a small sound wave. */
@Composable
private fun SayItCard(onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val shape = RoundedCornerShape(22.dp)
    val example = stringResource(R.string.add_say_it_example)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(shape)
            .background(colors.raised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Lucide.Mic, contentDescription = null, tint = colors.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.voice_say_it), style = type.size20.bold.tracked(-0.015f), color = colors.ink)
            Text(example, style = type.size14.forContent(example), color = colors.muted)
        }
        Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
    }
}

/** Type it or Add income: an outline pill with a small icon in [tint]. */
@Composable
private fun SheetPill(icon: ImageVector, tint: Color, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Text(text, style = BudgetWiseTheme.type.size14.semibold, color = colors.ink, maxLines = 1)
    }
}

@Preview(showBackground = true)
@Composable
private fun AddChoicesPreview() {
    BudgetWiseTheme {
        AddChoices(onScan = {}, onSayIt = {}, onTypeIt = {}, onAddIncome = {}, modifier = Modifier.padding(20.dp))
    }
}
