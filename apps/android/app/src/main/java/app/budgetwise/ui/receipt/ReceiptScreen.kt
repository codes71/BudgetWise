package app.budgetwise.ui.receipt

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.AppLanguage
import app.budgetwise.R
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Transaction
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.ConfirmDialog
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.BudgetMotion
import app.budgetwise.ui.common.MotionContent
import app.budgetwise.ui.common.MotionReveal
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.builtInCategoryStyle
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.scan.ChangedNote
import app.budgetwise.ui.scan.ItemLines
import app.budgetwise.ui.scan.MoveSheet
import app.budgetwise.ui.scan.SourceRow
import app.budgetwise.ui.scan.Total
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.semibold
import java.util.Locale

/**
 * A scanned receipt, opened from its line in the list: the shop, the total, and each entry it saved with its items. An
 * entry's category can be changed, the items' counts and prices fixed (Change items), or the whole receipt deleted.
 * [onClose] goes back; it is also called once the receipt has no entries left. [onDelete] closes the receipt and hands
 * its entries (by the receipt's id) to the list, which deletes them with Undo as after a swipe; false when there is no
 * list to do that, and the entries are deleted here at once.
 */
@Composable
fun ReceiptRoute(
    onClose: () -> Unit,
    onDelete: (receiptId: String, clientIds: List<String>) -> Boolean = { _, _ -> false },
    viewModel: ReceiptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    // Deleted here, from the list or on the web, or another account signed in: nothing is left to show.
    LaunchedEffect(state.gone) {
        if (state.gone) close()
    }
    val delete by rememberUpdatedState(onDelete)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ReceiptEvent.Delete && !delete(event.receiptId, event.clientIds)) viewModel.deleteNow(event.clientIds)
        }
    }
    // Back or the X from Change items returns to the receipt; with an item changed it asks first, as the changes go.
    // While saving neither does anything.
    var discarding by rememberSaveable { mutableStateOf(false) }
    fun leaveEditing() {
        when {
            state.saving -> Unit
            state.changedItems > 0 -> discarding = true
            else -> viewModel.stopEditing()
        }
    }
    BackHandler(enabled = state.editing != null) { leaveEditing() }

    val descriptions = rememberEntryDescriptions()
    MotionContent(
        targetState = state,
        modifier = Modifier.fillMaxSize(),
        label = "receiptEditing",
        // A price/count edit updates the mounted fields rather than starting a new screen transition.
        contentKey = { it.editing != null },
    ) { shown ->
        if (shown.editing == null) {
            ReceiptScreen(
                state = shown,
                onBack = onClose,
                onMove = { clientId, category ->
                    if (viewModel.state.value.editing == null) viewModel.move(clientId, category, descriptions)
                },
                onDelete = { if (viewModel.state.value.editing == null) viewModel.delete() },
                onChangeItems = {
                    val current = viewModel.state.value
                    if (current.editing == null && current.canChangeItems && !current.saving) viewModel.startEditing()
                },
            )
        } else {
            ChangeItems(
                state = shown,
                onClose = ::leaveEditing,
                onCount = viewModel::setCount,
                onPrice = viewModel::setPrice,
                onSave = viewModel::saveItems,
                onCheckAgain = {
                    val current = viewModel.state.value
                    if (current.editing != null && current.receiptChanged && !current.saving) viewModel.startEditing()
                },
                onDelete = {
                    val current = viewModel.state.value
                    if (current.editing != null && current.allLeftOut && !current.saving) viewModel.delete()
                },
                canDelete = state.editing != null && state.allLeftOut && !state.saving,
                canCheckAgain = state.editing != null && state.receiptChanged && !state.saving,
            )
        }
    }
    // Native confirmation stays outside the transition so an outgoing editor cannot keep a dialog alive.
    if (state.editing != null && discarding) {
        ConfirmDialog(
            title = stringResource(R.string.receipt_discard_title),
            detail = stringResource(R.string.receipt_discard_detail),
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.receipt_discard_keep),
            onConfirm = {
                discarding = false
                viewModel.stopEditing()
            },
            onDismiss = { discarding = false },
        )
    }
}

/**
 * The descriptions written for a receipt's entries ("City Mart, Groceries"): in the app's language now, and known in
 * each language the app is written in ([AppLanguage]), so a move after a language switch still rewrites one.
 */
@Composable
private fun rememberEntryDescriptions(): EntryDescriptions {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        fun language(texts: Context) = DescriptionLanguage(texts.getString(R.string.scan_entry_description)) { category ->
            builtInCategoryStyle(category)?.let { texts.getString(it.label) } ?: category
        }
        val others = AppLanguage.entries.map { app ->
            context.createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.forLanguageTag(app.tag)) })
        }
        EntryDescriptions(language(context), others.map(::language))
    }
}

@Composable
private fun ReceiptScreen(
    state: ReceiptUiState,
    onBack: () -> Unit,
    onMove: (String, String) -> Unit,
    onDelete: () -> Unit,
    onChangeItems: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    ReceiptFrame(
        state = state,
        title = stringResource(R.string.receipt_title),
        onBack = onBack,
        total = state.totalKyat,
        content = {
            val count = state.entries.size
            SectionLabel(
                pluralStringResource(R.plurals.receipt_entries, count, groupDigits(count.toLong(), state.myanmarDigits)),
                Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
            )
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            Column(Modifier.fillMaxWidth().animateContentSize(BudgetMotion.change())) {
                state.entries.forEach { entry ->
                    key(entry.clientId) {
                        EntryBlock(entry, state.myanmarDigits, onClick = { moving = entry.clientId })
                        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                    }
                }
            }
            val note = stringResource(R.string.receipt_note)
            Text(note, style = type.size13.forContent(note), color = colors.muted, modifier = Modifier.padding(top = 14.dp))
        },
        actions = {
            // A receipt without readable items has nothing for Change items to change.
            MotionReveal(visible = state.canChangeItems) {
                PrimaryButton(
                    text = stringResource(R.string.receipt_change_items),
                    onClick = onChangeItems,
                    enabled = state.canChangeItems && !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextAction(
                stringResource(R.string.receipt_delete),
                onClick = onDelete,
                color = colors.coralText,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
    )

    moving?.let { clientId ->
        val entry = state.entries.firstOrNull { it.clientId == clientId }
        if (entry == null) {
            moving = null
        } else {
            MoveSheet(
                category = entry.category,
                items = entry.items,
                categories = state.categories,
                myanmarDigits = state.myanmarDigits,
                onPick = { category ->
                    onMove(clientId, category)
                    moving = null
                },
                onDismiss = { moving = null },
            )
        }
    }
}

/**
 * Change items: each category's items with their counts and prices, as when the receipt was checked, then Save. When
 * the receipt changed elsewhere meanwhile, a notice offers [onCheckAgain] (the lines afresh) instead; with every item
 * at 0, Delete this receipt ([onDelete]).
 */
@Composable
private fun ChangeItems(
    state: ReceiptUiState,
    onClose: () -> Unit,
    onCount: (Int, Int, Int) -> Unit,
    onPrice: (Int, Int, Long) -> Unit,
    onSave: () -> Unit,
    onCheckAgain: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    canCheckAgain: Boolean,
) {
    val changed = state.changedItems
    ReceiptFrame(
        state = state,
        title = stringResource(R.string.receipt_change_items),
        onBack = onClose,
        closes = true,
        total = state.editedTotal,
        content = {
            MotionReveal(visible = state.receiptChanged) {
                Notice(
                    tone = NoticeTone.ATTENTION,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.receipt_changed_elsewhere_title),
                    detail = stringResource(R.string.receipt_changed_elsewhere_detail),
                    actionLabel = stringResource(R.string.receipt_changed_elsewhere_action),
                    onAction = onCheckAgain.takeIf { canCheckAgain },
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            MotionContent(targetState = state, label = "receiptChangedNote", contentKey = { it.changedItems > 0 }) { shown ->
                if (shown.changedItems > 0) {
                    // What the receipt was when Change items opened, as the lines below were built from it.
                    ChangedNote(R.plurals.receipt_changed, shown.editingBase, shown.changedItems, shown.myanmarDigits, Modifier.padding(top = 16.dp))
                }
            }
            ItemLines(
                lines = state.editing.orEmpty(),
                myanmarDigits = state.myanmarDigits,
                onCount = onCount,
                onPrice = onPrice,
                onMove = null,
                modifier = Modifier.padding(top = if (changed > 0) 12.dp else 18.dp),
            )
        },
        actions = {
            MotionReveal(visible = state.allLeftOut) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Notice(
                        tone = NoticeTone.INFO,
                        icon = Lucide.CircleAlert,
                        title = stringResource(R.string.receipt_all_left_out),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    TextAction(
                        stringResource(R.string.receipt_delete),
                        onClick = onDelete,
                        enabled = canDelete,
                        color = BudgetWiseTheme.colors.coralText,
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.receipt_save),
                onClick = onSave,
                enabled = state.canSave,
                busy = state.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        },
    )
}

/**
 * What the receipt and Change items share: the header, the shop and date, the big total, [content] scrolling under it,
 * and [actions] at the bottom under the save error, above the keyboard while a price is typed. [closes]: an X (leaving
 * the changes, which [onBack] asks about) instead of the back arrow.
 */
@Composable
private fun ReceiptFrame(
    state: ReceiptUiState,
    title: String,
    onBack: () -> Unit,
    total: Long,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    closes: Boolean = false,
) {
    val colors = BudgetWiseTheme.colors
    Column(Modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        if (closes) {
            BackHeader(title, onBack = onBack, icon = Lucide.X, backLabel = stringResource(R.string.action_close))
        } else {
            BackHeader(title, onBack = onBack)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            SourceRow(
                shop = state.merchant ?: stringResource(R.string.receipt_title),
                date = state.date,
                myanmarDigits = state.myanmarDigits,
                noDate = false,
                modifier = Modifier.padding(top = 12.dp),
            )
            Total(total, state.myanmarDigits, Modifier.padding(top = 12.dp))
            content()
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
            actions()
        }
    }
}

/** One entry of the receipt: its category, amount and a chevron (tap to change the category), then its items. */
@Composable
private fun EntryBlock(entry: Transaction, myanmarDigits: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = categoryLabel(entry.category)
    // One entry remains one TalkBack/action target, including the category, its total and the item breakdown.
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(top = 8.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                name,
                style = type.size16.semibold.forContent(name),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatKyat(entry.amountKyat, myanmarDigits), style = type.size16.semibold, color = colors.ink, softWrap = false)
            Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        }
        entry.items.forEachIndexed { index, item ->
            ItemLine(item, myanmarDigits)
            if (index < entry.items.lastIndex) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
    }
}

/**
 * An item under its category: the name and total in the same page lanes, then the count and price each.
 */
@Composable
private fun ItemLine(item: EntryItem, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = type.size16.forContent(item.name), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val count = stringResource(
                R.string.receipt_item_count,
                groupDigits(item.quantity.toLong(), myanmarDigits),
                groupDigits(item.unitPriceKyat, myanmarDigits),
            )
            Text(count, style = type.size14, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
        Text(formatKyat(item.amountKyat, myanmarDigits), style = type.size16.semibold, color = colors.ink, softWrap = false)
    }
}
