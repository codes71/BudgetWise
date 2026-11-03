package app.budgetwise.ui.scan

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
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
import app.budgetwise.data.repo.ScanItem
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.ConfirmDialog
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.MotionContent
import app.budgetwise.ui.common.MotionReveal
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.categoryLabel
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
import java.time.LocalDate

/** How the user asked to leave the review: back (to the camera) or the X (out of scanning). */
private enum class ReviewExit { BACK, CLOSE }

/**
 * What was read, to check before anything is saved. A receipt: the shop and date, the total, and each category's items
 * with their counts and prices to fix (tap a category to move its items); a KPay slip or history: one line per payment
 * (tap a history row to leave it out). Back ([onRetake], to the camera) and the X ([onClose]) ask first, as both drop
 * what a slow, rate-limited read found and every change made to it; while saving they do nothing.
 */
@Composable
internal fun ScanReview(
    state: ScanUiState,
    onClose: () -> Unit,
    onRetake: () -> Unit,
    onMove: (Int, String) -> Unit,
    onAddShort: () -> Unit,
    onToggle: (Int) -> Unit,
    onCount: (line: Int, item: Int, count: Int) -> Unit,
    onPrice: (line: Int, item: Int, price: Long) -> Unit,
    onAddEntries: ((ReviewLine) -> String) -> Unit,
    onAddAsOne: (String) -> Unit,
    active: Boolean = true,
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
    var leaving by rememberSaveable { mutableStateOf<ReviewExit?>(null) }
    val saving = state.saving
    fun leave(exit: ReviewExit) {
        if (active && !saving) leaving = exit
    }
    BackHandler(enabled = active) { leave(ReviewExit.BACK) }

    // Save stays above the keyboard while a price is typed, and the items scroll above it too.
    Column(Modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        BackHeader(
            title = stringResource(
                when (state.kind) {
                    ScanKind.HISTORY -> R.string.scan_title_history
                    ScanKind.PAYMENT -> R.string.scan_title_payment
                    else -> R.string.scan_title_receipt
                },
            ),
            onBack = { leave(ReviewExit.CLOSE) },
            icon = Lucide.X,
            backLabel = stringResource(R.string.action_close),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            SourceRow(
                shop = state.merchant ?: stringResource(R.string.scan_added_from_photo),
                date = state.date,
                myanmarDigits = state.myanmarDigits,
                // Only a receipt is one dated thing; KPay rows carry their own dates.
                noDate = receipt && state.date == null,
                modifier = Modifier.padding(top = 12.dp),
            )
            // A KPay history mixes money in and out and has no printed total: each row shows its own amount.
            if (state.kind != ScanKind.HISTORY) Total(state.shownTotal, state.myanmarDigits, Modifier.padding(top = 12.dp))
            MotionContent(targetState = state, label = "scanChangedNote", contentKey = { it.changedItems > 0 }) { shown ->
                if (shown.changedItems > 0) {
                    ChangedNote(R.plurals.scan_changed, shown.scannedTotal, shown.changedItems, shown.myanmarDigits, Modifier.padding(top = 16.dp))
                }
            }
            MotionContent(
                targetState = state,
                label = "scanTotalMismatch",
                contentKey = { when { it.shortBy != null -> "short"; it.overBy != null -> "over"; else -> "matched" } },
            ) { shown ->
                shown.shortBy?.let { short ->
                    Notice(
                        tone = NoticeTone.ATTENTION,
                        icon = Lucide.CircleAlert,
                        title = stringResource(R.string.scan_short_title, groupDigits(short, shown.myanmarDigits)),
                        detail = stringResource(R.string.scan_short_detail, groupDigits(shown.totalKyat ?: 0, shown.myanmarDigits)),
                        actionLabel = stringResource(R.string.scan_short_add),
                        onAction = onAddShort.takeIf { active && state.shortBy != null && !state.saving },
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                // More than the printed total: a discount the read missed, or a price misread. The total above is what is
                // saved, so the user can fix the item or save it as it is.
                shown.overBy?.let {
                    Notice(
                        tone = NoticeTone.ATTENTION,
                        icon = Lucide.CircleAlert,
                        title = stringResource(R.string.scan_over_title, groupDigits(shown.shownTotal, shown.myanmarDigits)),
                        detail = stringResource(R.string.scan_over_detail, groupDigits(shown.totalKyat ?: 0, shown.myanmarDigits)),
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            if (receipt) {
                ItemLines(
                    lines = state.lines,
                    myanmarDigits = state.myanmarDigits,
                    onCount = onCount,
                    onPrice = onPrice,
                    onMove = { moving = it },
                    modifier = Modifier.padding(top = if (state.changedItems > 0) 12.dp else 18.dp),
                )
            } else {
                HorizontalDivider(thickness = 1.dp, color = colors.hairline, modifier = Modifier.padding(top = 28.dp))
                state.lines.forEachIndexed { index, line ->
                    PaymentLine(line, state.myanmarDigits, togglable = state.kind == ScanKind.HISTORY, onToggle = { onToggle(index) })
                    HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                }
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
            MotionReveal(visible = state.saveFailed) {
                Notice(
                    tone = NoticeTone.ERROR,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.manual_save_failed),
                    detail = stringResource(R.string.login_error_generic),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            val count = state.keptLines.size
            PrimaryButton(
                text = pluralStringResource(R.plurals.scan_add, count, groupDigits(count.toLong(), state.myanmarDigits)),
                onClick = { onAddEntries(describe) },
                enabled = state.canSave,
                busy = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.canAddAsOne) {
                val shop = state.merchant ?: labels[state.keptLines.maxBy { it.amountKyat }.category].orEmpty()
                TextAction(
                    stringResource(R.string.scan_as_one),
                    onClick = { onAddAsOne(shop) },
                    enabled = !state.saving,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    moving?.let { index ->
        val line = state.lines.getOrNull(index)
        if (line == null) {
            moving = null
        } else {
            MoveSheet(
                category = line.category,
                items = line.items,
                categories = state.categories,
                myanmarDigits = state.myanmarDigits,
                onPick = { category ->
                    onMove(index, category)
                    moving = null
                },
                onDismiss = { moving = null },
            )
        }
    }

    leaving?.let { exit ->
        ConfirmDialog(
            title = stringResource(R.string.scan_discard_title),
            detail = stringResource(R.string.scan_discard_detail),
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.scan_discard_keep),
            onConfirm = {
                leaving = null
                if (exit == ReviewExit.BACK) onRetake() else onClose()
            },
            onDismiss = { leaving = null },
        )
    }
}

/**
 * The merchant and date lead the review, without a decorative badge competing with its amount. [noDate] retains
 * the warning for a receipt that will use today because its date could not be read.
 */
@Composable
internal fun SourceRow(shop: String, date: LocalDate?, myanmarDigits: Boolean, noDate: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(shop, style = type.size26.semibold.forContent(shop), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val day = date?.let { localizedDate(it, "yMMMMd", myanmarDigits) } ?: stringResource(R.string.scan_no_date).takeIf { noDate }
        if (day != null) Text(day, style = type.size14, color = if (date == null) colors.coralText else colors.muted)
    }
}

/** A compact receipt total. The spoken total remains complete even when an unusually long figure is ellipsized. */
@Composable
internal fun Total(amountKyat: Long, myanmarDigits: Boolean, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val label = stringResource(R.string.scan_total)
    val spoken = "$label, ${spokenKyat(amountKyat)}"
    Column(modifier) {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            SectionLabel(label)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val figure = groupDigits(amountKyat, myanmarDigits)
                Text(
                    figure,
                    style = when {
                        figure.length > 16 -> type.size24
                        figure.length > 10 -> type.size32
                        else -> type.size40
                    }.extraBold.tracked(-0.035f),
                    color = colors.ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                Text("Ks", style = type.size20, color = colors.muted, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/** A KPay payment: what it was and when, the signed amount, and (history) whether it is kept. */
@Composable
private fun PaymentLine(line: ReviewLine, myanmarDigits: Boolean, togglable: Boolean, onToggle: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val base = Modifier.fillMaxWidth().heightIn(min = 88.dp)
    Row(
        (if (togglable) base.toggleable(value = line.kept, role = Role.Checkbox, onValueChange = { onToggle() }) else base)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (togglable) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (line.kept) Modifier.background(colors.sageFill) else Modifier.border(1.5.dp, colors.outline, RoundedCornerShape(6.dp))),
                contentAlignment = Alignment.Center,
            ) {
                if (line.kept) Icon(Lucide.CheckBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(14.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                line.description,
                style = type.size16.medium.forContent(line.description),
                color = if (line.kept) colors.ink else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(localizedDate(line.date, "yMMMd", myanmarDigits), style = type.size13, color = colors.muted)
            val category = categoryLabel(line.category)
            Text(category, style = type.size14.forContent(category), color = colors.muted)
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
    }
}

/** "Move 2 items to": the items' names, then every expense category with [category] checked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveSheet(
    category: String,
    items: List<ScanItem>,
    categories: List<String>,
    myanmarDigits: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(colors.disabled))
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            val count = items.size
            Text(
                if (count > 0) {
                    pluralStringResource(R.plurals.scan_move_title, count, groupDigits(count.toLong(), myanmarDigits))
                } else {
                    stringResource(R.string.scan_move_title_none)
                },
                style = type.size24.semibold.tracked(-0.02f),
                color = colors.ink,
                modifier = Modifier.padding(top = 20.dp).semantics { heading() },
            )
            if (count > 0) {
                val names = items.joinToString(", ") { it.name }
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
            categories.forEach { option ->
                val selected = option == category
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.RadioButton, onClick = { onPick(option) })
                        .semantics { this.selected = selected },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val label = categoryLabel(option)
                    Text(label, style = type.size18.medium.forContent(label), color = colors.ink, modifier = Modifier.weight(1f))
                    if (selected) Icon(Lucide.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }
        }
    }
}
