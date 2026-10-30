package app.budgetwise.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.common.categoryIcon
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.formatSignedKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.spokenKyat
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked

/**
 * What was read, to check before anything is saved. A receipt: the shop and date, the total, and one line per category
 * (tap to move its items); a KPay slip or history: one line per payment (tap a history row to leave it out).
 */
@Composable
internal fun ScanReview(
    state: ScanUiState,
    onClose: () -> Unit,
    onMove: (Int, String) -> Unit,
    onAddShort: () -> Unit,
    onToggle: (Int) -> Unit,
    onAddEntries: ((ReviewLine) -> String) -> Unit,
    onAddAsOne: (String) -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val receipt = state.kind == ScanKind.RECEIPT
    var moving by rememberSaveable { mutableStateOf<Int?>(null) }
    // Labels in the app's language, for the descriptions of a receipt's entries ("City Mart, Groceries").
    val labels = state.lines.associate { it.category to categoryLabel(it.category) }
    val pattern = stringResource(R.string.scan_entry_description)
    val describe = { line: ReviewLine ->
        state.merchant?.let { pattern.format(it, labels[line.category] ?: line.category) } ?: line.description
    }

    Column(Modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars)) {
        BackHeader(
            title = stringResource(
                when (state.kind) {
                    ScanKind.HISTORY -> R.string.scan_title_history
                    ScanKind.PAYMENT -> R.string.scan_title_payment
                    else -> R.string.scan_title_receipt
                },
            ),
            onBack = onClose,
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            SourceRow(state, Modifier.padding(top = 12.dp))
            Total(state, Modifier.padding(top = 28.dp))
            state.shortBy?.let { short ->
                Notice(
                    tone = NoticeTone.ATTENTION,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.scan_short_title, groupDigits(short, state.myanmarDigits)),
                    detail = stringResource(R.string.scan_short_detail, groupDigits(state.totalKyat ?: 0, state.myanmarDigits)),
                    actionLabel = stringResource(R.string.scan_short_add),
                    onAction = onAddShort,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            if (receipt) SectionLabel(stringResource(R.string.scan_split), Modifier.padding(top = 28.dp, bottom = 10.dp).semantics { heading() })
            HorizontalDivider(thickness = 1.dp, color = colors.hairline, modifier = Modifier.padding(top = if (receipt) 0.dp else 28.dp))
            state.lines.forEachIndexed { index, line ->
                if (receipt) {
                    CategoryLine(line, state.myanmarDigits, onClick = { moving = index })
                } else {
                    PaymentLine(line, state.myanmarDigits, togglable = state.kind == ScanKind.HISTORY, onToggle = { onToggle(index) })
                }
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }
            val note = when (state.kind) {
                ScanKind.RECEIPT -> stringResource(R.string.scan_split_note)
                ScanKind.HISTORY -> stringResource(R.string.scan_history_note)
                else -> null
            }
            if (note != null) Text(note, style = type.size13.forContent(note), color = colors.muted, modifier = Modifier.padding(top = 14.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.saveFailed) {
                Notice(
                    tone = NoticeTone.ERROR,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.manual_save_failed),
                    detail = stringResource(R.string.login_error_generic),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (receipt && state.keptLines.size > 1) {
                val shop = state.merchant ?: labels[state.keptLines.maxBy { it.amountKyat }.category].orEmpty()
                TextAction(stringResource(R.string.scan_as_one), onClick = { onAddAsOne(shop) }, enabled = !state.saving)
            }
            val count = state.keptLines.size
            PrimaryButton(
                text = pluralStringResource(R.plurals.scan_add, count, count),
                onClick = { onAddEntries(describe) },
                enabled = state.canSave,
                busy = state.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    moving?.let { index ->
        val line = state.lines.getOrNull(index)
        if (line == null) {
            moving = null
        } else {
            MoveSheet(
                line = line,
                categories = state.categories,
                onPick = { category ->
                    onMove(index, category)
                    moving = null
                },
                onDismiss = { moving = null },
            )
        }
    }
}

/** The shop and the date on the receipt (or today, said so), with the Scanned tag. */
@Composable
private fun SourceRow(state: ScanUiState, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val shop = state.merchant ?: stringResource(R.string.scan_added_from_photo)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface).border(1.dp, colors.hairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Receipt, contentDescription = null, tint = colors.muted, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(shop, style = type.size17.semibold.forContent(shop), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val date = state.date?.let { localizedDate(it, "EEEEMMMMd") } ?: stringResource(R.string.scan_no_date)
            Text(date, style = type.size13, color = if (state.date == null) colors.coralText else colors.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.ScanLine, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.scan_scanned), style = type.size13.medium, color = colors.accent)
        }
    }
}

@Composable
private fun Total(state: ScanUiState, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val label = stringResource(R.string.scan_total)
    val spoken = "$label, ${spokenKyat(state.shownTotal)}"
    Column(modifier) {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            SectionLabel(label)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val figure = groupDigits(state.shownTotal, state.myanmarDigits)
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
        }
        SageRule(Modifier.padding(top = 16.dp))
    }
}

/** A receipt's category share: badge, name with a chevron (tap to move), its item count, the amount. */
@Composable
private fun CategoryLine(line: ReviewLine, myanmarDigits: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = categoryLabel(line.category)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CategoryBadge(line.category, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = type.size16.medium.forContent(name), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
            }
            if (line.items.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.scan_items, line.items.size, line.items.size), style = type.size13, color = colors.muted)
            }
        }
        Text(formatKyat(line.amountKyat, myanmarDigits), style = type.size16.semibold, color = colors.ink, softWrap = false)
    }
}

/** A KPay payment: what it was and when, the signed amount, and (history) whether it is kept. */
@Composable
private fun PaymentLine(line: ReviewLine, myanmarDigits: Boolean, togglable: Boolean, onToggle: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val base = Modifier.fillMaxWidth().heightIn(min = 68.dp)
    Row(
        (if (togglable) base.toggleable(value = line.kept, role = Role.Checkbox, onValueChange = { onToggle() }) else base)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CategoryBadge(line.category, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(
                line.description,
                style = type.size16.medium.forContent(line.description),
                color = if (line.kept) colors.ink else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(localizedDate(line.date, "MMMd"), style = type.size13, color = colors.muted)
        }
        Text(
            formatSignedKyat(line.amountKyat, line.type, myanmarDigits),
            style = type.size16.semibold,
            color = when {
                !line.kept -> colors.disabled
                line.type == TxType.INCOME -> colors.accent
                else -> colors.ink
            },
            softWrap = false,
        )
        if (togglable) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (line.kept) Modifier.background(colors.sageFill) else Modifier.border(1.5.dp, colors.outline, RoundedCornerShape(6.dp))),
                contentAlignment = Alignment.Center,
            ) {
                if (line.kept) Icon(Lucide.CheckBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** "Move 2 items to": the items' names, then every expense category with the current one checked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveSheet(line: ReviewLine, categories: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.raised,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp).size(width = 32.dp, height = 4.dp).clip(CircleShape).background(colors.disabled))
        },
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            val count = line.items.size
            Text(
                if (count > 0) pluralStringResource(R.plurals.scan_move_title, count, count) else stringResource(R.string.scan_move_title_none),
                style = type.size24.semibold.tracked(-0.02f),
                color = colors.ink,
                modifier = Modifier.padding(top = 20.dp).semantics { heading() },
            )
            if (count > 0) {
                val names = line.items.joinToString(", ") { it.name }
                Text(
                    names,
                    style = type.size14.forContent(names),
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline, modifier = Modifier.padding(top = 16.dp))
            categories.forEach { category ->
                val selected = category == line.category
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.RadioButton, onClick = { onPick(category) })
                        .semantics { this.selected = selected },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(categoryIcon(category), contentDescription = null, tint = categoryColors(category).content, modifier = Modifier.size(20.dp))
                    val label = categoryLabel(category)
                    Text(label, style = type.size16.forContent(label), color = colors.ink, modifier = Modifier.weight(1f))
                    if (selected) Icon(Lucide.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }
        }
    }
}
