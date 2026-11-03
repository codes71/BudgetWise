package app.budgetwise.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.model.Source
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.FieldError
import app.budgetwise.ui.common.FieldHint
import app.budgetwise.ui.common.FieldLabel
import app.budgetwise.ui.common.KitTextField
import app.budgetwise.ui.common.KitFieldStyle
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.Notice
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.spokenKyat
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.WeekFields

/**
 * [onDone] closes the form: after a save or a delete, when the entry to change is gone, or when the user closes it.
 * [onDelete] hands Delete entry's clientId to the list under the form, which deletes it with Undo; false when there is
 * no list to hand it to, and the entry is deleted here instead.
 */
@Composable
fun AddManualRoute(onDone: () -> Unit, onDelete: (String) -> Boolean = { false }, viewModel: AddManualViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    val handOver by rememberUpdatedState(onDelete)
    LaunchedEffect(viewModel) {
        // Saved, deleted, or nothing left to change: every event ends the form. Delete entry goes to the list, or,
        // with no list under the form, is made here and ends it when done.
        viewModel.events.collect { event ->
            if (event is AddManualEvent.Delete && !handOver(event.clientId)) viewModel.deleteNow() else done()
        }
    }
    // The form can be left open over midnight: back on it, Today is the clock's day.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }
    AddManualScreen(
        state = state,
        onClose = onDone,
        onTypeChange = viewModel::onTypeChange,
        onAmountChange = viewModel::onAmountChange,
        onCategoryChange = viewModel::onCategoryChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onRecentPick = viewModel::onRecentPick,
        onDateChange = viewModel::onDateChange,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onSaveAsNew = viewModel::saveAsNew,
        // A saved entry opens as it is, without the keyboard over it.
        focusAmount = !state.editing,
    )
}

/**
 * The add form on one screen: the amount on the phone's own number keyboard, what it was for (with the user's recent
 * entries to tap), the category and the day as chips, and Save with the amount on it. Changing a saved entry is the
 * same form titled "Edit entry", with how it was added under the title and Delete entry over Save changes.
 */
@Composable
fun AddManualScreen(
    state: AddManualUiState,
    onClose: () -> Unit,
    onTypeChange: (TxType) -> Unit,
    onAmountChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onRecentPick: (RecentEntry) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveAsNew: () -> Unit = {},
    focusAmount: Boolean = true,
) {
    val colors = BudgetWiseTheme.colors
    val amountFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    // The amount comes first: the keyboard is up for it as soon as the form opens (once, not again on rotation).
    var focused by rememberSaveable { mutableStateOf(!focusAmount) }
    LaunchedEffect(Unit) {
        if (!focused) {
            amountFocus.requestFocus()
            focused = true
        }
    }

    // It all fits on a phone screen at the default font size. Save stays pinned at the bottom, above the keyboard when
    // it is open; the fields scroll under the keyboard (or a large font) instead of pushing Save off screen.
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        // While saving or deleting the form is dimmed and locked; until a saved entry is read it waits.
        val editable = !state.busy && state.loaded
        FormHeader(state, editable, onClose, onTypeChange)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .alpha(if (state.busy) 0.5f else 1f)
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AmountField(state, editable, onAmountChange, amountFocus, onNext = { descriptionFocus.requestFocus() })
                DescriptionField(state, editable, onDescriptionChange, onRecentPick, descriptionFocus, onDone = { keyboard?.hide() })
                // Income always goes under "Income" (AddManualUiState.savedCategory), so only an expense asks.
                if (state.type != TxType.INCOME) CategoryPicker(state, editable, onCategoryChange)
                DayPicker(state, editable, onDateChange, onOtherDay = {
                    keyboard?.hide()
                    pickingDate = true
                })
            }
            val failure = when {
                state.saveFailed -> R.string.manual_save_failed to R.string.manual_save_failed_detail
                state.deleteFailed -> R.string.manual_delete_failed to R.string.manual_delete_failed_detail
                else -> null
            }
            if (state.gone) {
                // Deleted on the web or another phone while the form was open: saved as a new entry, or closed. It
                // stays over a failed Save as new too, as Save changes is off and its action is the way to try again.
                Notice(
                    tone = NoticeTone.ATTENTION,
                    icon = Lucide.CircleAlert,
                    title = stringResource(R.string.manual_gone),
                    detail = stringResource(R.string.manual_gone_detail),
                    actionLabel = stringResource(R.string.manual_save_as_new),
                    onAction = { if (!state.busy) onSaveAsNew() },
                    floating = true,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            } else if (failure != null) {
                Notice(
                    tone = NoticeTone.ERROR,
                    icon = Lucide.CircleAlert,
                    title = stringResource(failure.first),
                    detail = stringResource(failure.second),
                    floating = true,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(colors.ground).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.editing) {
                TextAction(stringResource(R.string.manual_delete), onClick = onDelete, enabled = editable && !state.gone, color = colors.coralText)
            }
            val amount = state.amount?.takeIf { it > 0 }
            PrimaryButton(
                text = when {
                    state.saving -> stringResource(R.string.manual_saving)
                    state.editing -> stringResource(R.string.manual_save_changes)
                    amount != null -> stringResource(R.string.manual_save_amount, groupDigits(amount, state.myanmarDigits))
                    else -> stringResource(R.string.manual_save)
                },
                onClick = onSave,
                enabled = state.canSave,
                busy = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (pickingDate) {
        DateSheet(
            date = state.date,
            today = state.today,
            keep = state.keptDate,
            myanmarDigits = state.myanmarDigits,
            onPick = {
                onDateChange(it)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Close, the title, and the expense or income switch; a saved entry also says how it was added, under its title. */
@Composable
private fun FormHeader(state: AddManualUiState, enabled: Boolean, onClose: () -> Unit, onTypeChange: (TxType) -> Unit) {
    val colors = BudgetWiseTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp).padding(start = 24.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (state.editing) R.string.manual_edit_title else R.string.manual_title),
                style = BudgetWiseTheme.type.size24.bold,
                color = colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Lucide.X, contentDescription = stringResource(R.string.action_close), tint = colors.ink, modifier = Modifier.size(22.dp))
            }
        }
        TypeSwitch(state.type, enabled, onTypeChange)
        state.source?.let { SourceTag(it, Modifier.padding(start = 24.dp, top = 12.dp)) }
    }
}

/** Two equal, thumb-sized choices below the title; selecting changes the existing form type. */
@Composable
internal fun TypeSwitch(type: TxType, enabled: Boolean, onTypeChange: (TxType) -> Unit) {
    val colors = BudgetWiseTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).selectableGroup()) {
        listOf(TxType.EXPENSE to R.string.manual_expense, TxType.INCOME to R.string.manual_income).forEach { (option, label) ->
            val selected = option == type
            val container = if (selected) colors.raised else Color.Transparent
            val content = if (selected) colors.accent else colors.muted
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(container)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onTypeChange(option) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    style = if (selected) BudgetWiseTheme.type.size14.semibold else BudgetWiseTheme.type.size14.medium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/** How a saved entry was added: said, scanned, typed, or on the web. Muted, with its icon. */
@Composable
private fun SourceTag(source: Source, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val (icon, label) = when (source) {
        Source.VOICE -> Lucide.Mic to R.string.manual_source_voice
        Source.SCAN -> Lucide.ScanLine to R.string.manual_source_scan
        Source.MANUAL -> Lucide.Keyboard to R.string.manual_source_manual
        Source.WEB -> Lucide.Globe to R.string.manual_source_web
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        Text(stringResource(label), style = BudgetWiseTheme.type.size13.medium, color = colors.muted, maxLines = 1)
    }
}

/**
 * The amount, labelled and without a box, on the phone's own number keyboard. Long amounts
 * step down a size so the figure and its "Ks" stay on one line.
 */
@Composable
private fun AmountField(
    state: AddManualUiState,
    enabled: Boolean,
    onAmountChange: (String) -> Unit,
    focus: FocusRequester,
    onNext: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val size = when (state.amountDigits.length) {
        in 0..7 -> type.size60.copy(fontSize = type.size60.fontSize * (56f / 60f), lineHeight = type.size60.lineHeight * (56f / 60f))
        in 8..10 -> type.size44
        else -> type.size32
    }
    val big = size.bold.tracked(-0.04f)
    val transformation = remember(state.myanmarDigits) { KyatGroupingTransformation(state.myanmarDigits) }
    val label = stringResource(R.string.manual_amount)
    val error = state.amountError?.let { error ->
        stringResource(
            when (error) {
                AmountError.MISSING -> R.string.manual_amount_error_missing
                AmountError.ZERO -> R.string.manual_amount_error_zero
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = state.amountDigits,
                onValueChange = onAmountChange,
                // As wide as the figure, so "Ks" follows it; a figure wider than the row scrolls inside it.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .width(IntrinsicSize.Max)
                    .alignByBaseline()
                    .focusRequester(focus)
                    .semantics { contentDescription = label },
                enabled = enabled,
                textStyle = big.copy(color = colors.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onNext() }),
                singleLine = true,
                visualTransformation = transformation,
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box {
                        if (state.amountDigits.isEmpty()) {
                            Text(if (state.myanmarDigits) toMyanmarDigits("0") else "0", style = big, color = colors.muted)
                        }
                        inner()
                    }
                },
            )
            Text("Ks", style = type.size24.medium, color = colors.muted, modifier = Modifier.alignByBaseline())
        }
        when {
            error != null -> FieldError(error)
            state.wholeKyatHint -> FieldHint(stringResource(R.string.manual_amount_whole_kyat))
            state.type == TxType.INCOME -> FieldHint(stringResource(R.string.manual_income_note))
        }
    }
}

/** What it was for, with up to three of the user's recent entries under it to tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DescriptionField(
    state: AddManualUiState,
    enabled: Boolean,
    onDescriptionChange: (String) -> Unit,
    onRecentPick: (RecentEntry) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val max = AddManualViewModel.MAX_DESCRIPTION
    val error = when (state.descriptionError) {
        DescriptionError.BLANK -> stringResource(R.string.manual_description_error_blank)
        DescriptionError.TOO_LONG -> pluralStringResource(R.plurals.manual_description_error_too_long, max, max)
        null -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = stringResource(R.string.manual_description_hint),
            fieldModifier = Modifier.focusRequester(focus),
            enabled = enabled,
            // Burmese descriptions wrap onto a second line instead of scrolling sideways.
            singleLine = false,
            maxLines = 2,
            textStyle = type.size17.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            error = error,
            fieldStyle = KitFieldStyle.Underlined,
            trailing = if (state.description.isEmpty()) {
                null
            } else {
                {
                    // The count shows near the limit, formatted like amounts: the Myanmar-digits setting decides.
                    if (state.descriptionLength > max * 3 / 4) {
                        Text(
                            stringResource(
                                R.string.manual_description_count,
                                groupDigits(state.descriptionLength.toLong(), state.myanmarDigits),
                                groupDigits(max.toLong(), state.myanmarDigits),
                            ),
                            style = if (error != null) type.size12.semibold else type.size12,
                            color = if (state.descriptionError == DescriptionError.TOO_LONG) colors.error else colors.muted,
                        )
                    }
                    IconButton(onClick = { onDescriptionChange("") }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(colors.outline), contentAlignment = Alignment.Center) {
                            Icon(
                                Lucide.X,
                                contentDescription = stringResource(R.string.manual_description_clear),
                                tint = colors.surface,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            },
        )
        val recent = state.recent
        if (recent.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { entry -> RecentChip(entry, state.myanmarDigits, enabled, onClick = { onRecentPick(entry) }) }
            }
        }
    }
}

/** A recent entry: its description, then its amount in muted digits. TalkBack hears "Tea shop, 1,200 kyat". */
@Composable
private fun RecentChip(entry: RecentEntry, myanmarDigits: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val spoken = "${entry.description}, ${spokenKyat(entry.amountKyat)}"
    Row(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, colors.chipBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            entry.description,
            style = type.size13.medium.forContent(entry.description),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(groupDigits(entry.amountKyat, myanmarDigits), style = type.size13.medium, color = colors.muted, maxLines = 1)
    }
}

/**
 * The categories as compact chips that wrap; the chosen one in its own color. A category picked from a past entry
 * says so beside the label, until the user taps one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(state: AddManualUiState, enabled: Boolean, onCategoryChange: (String) -> Unit) {
    val colors = BudgetWiseTheme.colors
    val error = state.categoryError != null
    var showingAll by rememberSaveable { mutableStateOf(false) }
    // Keep a selected account-specific name visible; More exposes the complete cached list.
    val quickChoices = (listOfNotNull(state.category, "Food and Drink Item", "Groceries", "Other") + state.categoryChoices)
        .filter { it in state.categoryChoices }.distinct().take(3)
    val message = when (state.categoryError) {
        CategoryError.MISSING -> stringResource(R.string.manual_category_error)
        CategoryError.TOO_LONG -> stringResource(R.string.manual_category_error_too_long)
        null -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel(stringResource(R.string.manual_category))
            state.categoryFrom?.let { from ->
                Text(
                    stringResource(R.string.manual_picked_from, from),
                    style = BudgetWiseTheme.type.size12.medium.forContent(from),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickChoices.forEach { name ->
                CategoryChip(name, selected = name == state.category, error = error, enabled = enabled, onClick = { onCategoryChange(name) })
            }
            Row(
                Modifier.heightIn(min = 48.dp).clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClick = { showingAll = true })
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.draft3_entry_more), style = BudgetWiseTheme.type.size14.medium, color = colors.muted)
                Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (message != null) FieldError(message)
    }
    if (showingAll) {
        CategorySheet(state, enabled, onPick = {
            onCategoryChange(it)
            showingAll = false
        }, onDismiss = { showingAll = false })
    }
}

/** Quiet quick choices keep the amount as the form's focal point. */
@Composable
internal fun CategoryChip(name: String, selected: Boolean, error: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val label = if (name == "Food and Drink Item") stringResource(R.string.draft3_entry_food_short) else categoryLabel(name)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.raised else colors.surface)
            .then(if (error) Modifier.border(1.dp, colors.error, CircleShape) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            label,
            style = (if (selected) type.size14.semibold else type.size14.medium).forContent(label),
            color = if (selected) colors.accent else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The complete account list, including custom names, remains accessible from More. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(state: AddManualUiState, enabled: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                .verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.manual_category), style = type.size24.bold, color = colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.action_close), tint = colors.ink, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.categoryChoices.forEach { name ->
                    val label = categoryLabel(name)
                    val selected = name == state.category
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (selected) colors.raised else Color.Transparent)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(name) })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(label, style = type.size16.medium.forContent(label), color = if (selected) colors.accent else colors.ink, modifier = Modifier.weight(1f))
                        if (selected) Icon(Lucide.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Today, Yesterday, or another day from the date sheet; that chip shows the day once one is picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(state: AddManualUiState, enabled: Boolean, onDateChange: (LocalDate) -> Unit, onOtherDay: () -> Unit) {
    val today = state.today
    val yesterday = today.minusDays(1)
    val otherDay = state.date != today && state.date != yesterday
    val receiptDate = state.editingReceipt && state.type == TxType.EXPENSE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(if (receiptDate) R.string.manual_receipt_date else R.string.manual_date))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DayChip(stringResource(R.string.transactions_today), selected = state.date == today, enabled = enabled, onClick = { onDateChange(today) })
            DayChip(stringResource(R.string.transactions_yesterday), selected = state.date == yesterday, enabled = enabled, onClick = { onDateChange(yesterday) })
            DayChip(
                text = if (otherDay) {
                    localizedDate(state.date, if (state.date.year == today.year) "EEEdMMM" else "dMMMy", state.myanmarDigits)
                } else {
                    stringResource(R.string.manual_day_other)
                },
                selected = otherDay,
                enabled = enabled,
                icon = Lucide.Calendar,
                onClick = onOtherDay,
            )
        }
        if (state.editingReceipt) {
            FieldHint(stringResource(if (receiptDate) R.string.manual_receipt_date_note else R.string.manual_receipt_income_note))
        }
        if (state.dateError != null) FieldError(stringResource(R.string.manual_date_error_future))
    }
}

@Composable
internal fun DayChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.raised else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (icon != null) 13.dp else 14.dp, end = if (icon != null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(15.dp))
        Text(text, style = if (selected) type.size14.semibold else type.size14.medium, color = colors.ink, maxLines = 1)
    }
}

/**
 * The board's date sheet: a month at a time, today and earlier only (the form refuses future dates), plus [keep], the
 * day of an entry being changed, even when that is later (the web, a scan or voice can save one). The day tapped is
 * kept until OK; Cancel or a swipe down leaves the date as it was. Days follow the Myanmar-digits setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateSheet(
    date: LocalDate,
    today: LocalDate,
    myanmarDigits: Boolean,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    keep: LocalDate? = null,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val locale = LocalConfiguration.current.locales[0]
    val lastMonth = YearMonth.from(if (keep != null && keep.isAfter(today)) keep else today)
    val start = if (date == keep) date else date.coerceAtMost(today)
    var shown by rememberSaveable { mutableStateOf(YearMonth.from(start)) }
    var picked by rememberSaveable { mutableStateOf(start) }
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.raised,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp).size(width = 32.dp, height = 4.dp).clip(CircleShape).background(colors.disabled))
        },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(
                stringResource(R.string.manual_date),
                style = type.size20.semibold,
                color = colors.ink,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
            )
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shown = shown.minusMonths(1) }, modifier = Modifier.size(48.dp)) {
                    Icon(Lucide.ChevronLeft, stringResource(R.string.manual_previous_month), tint = colors.ink, modifier = Modifier.size(22.dp))
                }
                Text(
                    localizedDate(shown, "MMMMy", myanmarDigits),
                    style = type.size16.semibold,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                val canGoOn = shown < lastMonth
                IconButton(onClick = { shown = shown.plusMonths(1) }, enabled = canGoOn, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Lucide.ChevronRight,
                        stringResource(R.string.manual_next_month),
                        tint = if (canGoOn) colors.ink else colors.unavailable,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            val weekdays = remember(locale, firstDay) { (0 until 7).map { firstDay.plus(it.toLong()).getDisplayName(DateTextStyle.NARROW, locale) } }
            Row(Modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                weekdays.forEach { Text(it, style = type.size13, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            val lead = (shown.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
            val cells = List(lead) { null } + (1..shown.lengthOfMonth()).map { shown.atDay(it) }
            val todayLabel = stringResource(R.string.manual_day_today)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        (0 until 7).forEach { i ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                val day = week.getOrNull(i) ?: return@Box
                                val future = day.isAfter(today) && day != keep
                                val selected = day == picked
                                val name = localizedDate(day, "MMMMd")
                                val spoken = if (day == today) "$name, $todayLabel" else name
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) colors.sageFill else Color.Transparent)
                                        .selectable(selected = selected, enabled = !future, role = Role.RadioButton, onClick = { picked = day })
                                        .semantics { contentDescription = spoken },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        groupDigits(day.dayOfMonth.toLong(), myanmarDigits),
                                        style = if (selected) type.size15.semibold else type.size15,
                                        color = when {
                                            selected -> colors.onSageFill
                                            future -> colors.unavailable
                                            else -> colors.ink
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                TextAction(stringResource(R.string.action_ok), onClick = { onPick(picked) })
            }
        }
    }
}

private fun LocalDate.coerceAtMost(max: LocalDate): LocalDate = if (isAfter(max)) max else this

@Preview(showBackground = true)
@Composable
private fun AddManualScreenPreview() {
    val today = LocalDate.parse("2026-10-23")
    BudgetWiseTheme {
        AddManualScreen(
            state = AddManualUiState(
                today = today,
                amountDigits = "4500",
                category = "Food and Drink Item",
                categoryFrom = "Mohinga",
                description = "Mohinga with Ko Aung",
                past = listOf(
                    RecentEntry(TxType.EXPENSE, "Bus to Sule", 600, "Transport"),
                    RecentEntry(TxType.EXPENSE, "Tea shop", 1_200, "Food and Drink Item"),
                ),
            ),
            onClose = {},
            onTypeChange = {},
            onAmountChange = {},
            onCategoryChange = {},
            onDescriptionChange = {},
            onRecentPick = {},
            onDateChange = {},
            onSave = {},
            onDelete = {},
            focusAmount = false,
        )
    }
}
