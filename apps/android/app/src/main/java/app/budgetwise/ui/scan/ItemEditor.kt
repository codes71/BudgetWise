package app.budgetwise.ui.scan

import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.data.repo.ScanItem
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.typedKyatDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold

/**
 * A receipt's lines item by item, to fix what was misread: each category's live total over its items, then per item its
 * line total, a count stepper, the price each and what changed. [onMove] makes a category's header open the sheet that
 * moves its items; null where categories are changed elsewhere (a saved receipt's screen).
 */
@Composable
internal fun ItemLines(
    lines: List<ReviewLine>,
    myanmarDigits: Boolean,
    onCount: (line: Int, item: Int, count: Int) -> Unit,
    onPrice: (line: Int, item: Int, price: Long) -> Unit,
    onMove: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    Column(modifier.fillMaxWidth()) {
        lines.forEachIndexed { lineIndex, line ->
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            CategoryHeader(line = line, myanmarDigits = myanmarDigits, onClick = onMove?.let { move -> { move(lineIndex) } })
            // Indented under their category, dividers too, so each category reads as one block.
            Column(Modifier.fillMaxWidth().padding(start = ITEM_INDENT)) {
                line.items.forEachIndexed { itemIndex, item ->
                    if (itemIndex > 0) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                    ItemRow(
                        item = item,
                        original = line.originalItems.getOrNull(itemIndex) ?: item,
                        myanmarDigits = myanmarDigits,
                        onCount = { onCount(lineIndex, itemIndex, it) },
                        onPrice = { onPrice(lineIndex, itemIndex, it) },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

/** "Scanned 46,307 Ks. You changed 3 items.", under the total once an item was changed. [text] says what [before] was. */
@Composable
internal fun ChangedNote(
    @PluralsRes text: Int,
    before: Long,
    changed: Int,
    myanmarDigits: Boolean,
    modifier: Modifier = Modifier,
) {
    val note = pluralStringResource(text, changed, groupDigits(before, myanmarDigits), groupDigits(changed.toLong(), myanmarDigits))
    Text(note, style = BudgetWiseTheme.type.size14, color = BudgetWiseTheme.colors.muted, modifier = modifier)
}

/** The category's name and live total; with [onClick], a chevron and a tap to move its items. */
@Composable
private fun CategoryHeader(line: ReviewLine, myanmarDigits: Boolean, onClick: (() -> Unit)?) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val category = categoryLabel(line.category)
        Text(
            category,
            style = type.size16.semibold.forContent(category),
            color = colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(formatKyat(line.amountKyat, myanmarDigits), style = type.size16.semibold, color = colors.ink, softWrap = false)
        if (onClick != null) Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * One item: its name and what it comes to, then the count, the price each and what changed. At 0 it is struck through
 * and muted: it stays on the list to be put back, but is not saved.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ItemRow(item: ScanItem, original: ScanItem, myanmarDigits: Boolean, onCount: (Int) -> Unit, onPrice: (Long) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val left = item.quantity == 0
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val name = type.size16.medium.forContent(item.name)
            Text(
                item.name,
                style = if (left) name.copy(textDecoration = TextDecoration.LineThrough) else name,
                color = if (left) colors.disabled else colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatKyat(item.amountKyat, myanmarDigits),
                style = type.size16.semibold,
                color = if (left) colors.disabled else colors.ink,
                softWrap = false,
            )
        }
        // These controls can wrap independently at larger font scales instead of squeezing the previous-value note.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CountStepper(item.quantity, myanmarDigits, onCount)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("×", style = type.size14, color = colors.muted)
                PriceField(item.unitPriceKyat, item.name, myanmarDigits, onPrice)
            }
        }
        changeNote(item, original, myanmarDigits)?.let { note ->
            Text(note, style = type.size13, color = if (left) colors.muted else colors.amber)
        }
    }
}

/** What changed since the item was read: "was 2", "was 802 each", or "not added"; null when nothing did. */
@Composable
private fun changeNote(item: ScanItem, was: ScanItem, myanmarDigits: Boolean): String? {
    val count = groupDigits(was.quantity.toLong(), myanmarDigits)
    val each = groupDigits(was.unitPriceKyat, myanmarDigits)
    val countChanged = item.quantity != was.quantity
    val priceChanged = item.unitPriceKyat != was.unitPriceKyat
    return when {
        item.quantity == 0 -> stringResource(R.string.item_not_added)
        countChanged && priceChanged -> stringResource(R.string.item_was_both, count, each)
        countChanged -> stringResource(R.string.item_was_count, count)
        priceChanged -> stringResource(R.string.item_was_price, each)
        else -> null
    }
}

/** The 48 dp count controls keep zero-count items available to restore. */
@Composable
private fun CountStepper(count: Int, myanmarDigits: Boolean, onCount: (Int) -> Unit) {
    val colors = BudgetWiseTheme.colors
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(colors.raised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Lucide.MinusBold, stringResource(R.string.item_fewer), enabled = count > 0) { onCount(count - 1) }
        Text(
            groupDigits(count.toLong(), myanmarDigits),
            style = BudgetWiseTheme.type.size16.semibold,
            color = colors.ink,
            textAlign = TextAlign.Center,
            softWrap = false,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        StepButton(Lucide.PlusBold, stringResource(R.string.item_more), enabled = count < ReviewLine.MAX_COUNT) { onCount(count + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) colors.ink else colors.disabled, modifier = Modifier.size(18.dp))
    }
}

/**
 * The price each, typed on the phone's number keyboard: plain digits in the field, shown grouped. While it has focus it
 * keeps what was typed (it may be empty); the line follows each change, and it shows the line's price again after. The
 * box is as wide as its digits, and a tap anywhere on it (on "Ks" too) puts the cursor in.
 */
@Composable
private fun PriceField(price: Long, name: String, myanmarDigits: Boolean, onPrice: (Long) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var typed by remember { mutableStateOf<String?>(null) }
    val label = stringResource(R.string.item_price, name)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (focused) colors.accent else colors.hairline, shape)
            .pointerInput(Unit) { detectTapGestures { focus.requestFocus() } }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicTextField(
            value = typed ?: price.toString(),
            onValueChange = { text ->
                // A fraction or an amount too large is refused: the field keeps what it had.
                val digits = typedKyatDigits(text, typed ?: price.toString()) ?: return@BasicTextField
                typed = digits
                onPrice(digits.toLongOrNull() ?: 0)
            },
            modifier = Modifier
                .widthIn(min = 12.dp, max = 160.dp)
                .width(IntrinsicSize.Max)
                .focusRequester(focus)
                .onFocusChanged { if (!it.isFocused) typed = null }
                .semantics { contentDescription = label },
            textStyle = type.size14.medium.copy(color = colors.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
            visualTransformation = KyatGroupingTransformation(myanmarDigits),
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.accent),
        )
        Text("Ks", style = type.size14.medium, color = colors.muted, softWrap = false)
    }
}

/** How far a category's items sit in from its heading. */
private val ITEM_INDENT = 20.dp
