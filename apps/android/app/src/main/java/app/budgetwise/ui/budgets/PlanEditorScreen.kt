package app.budgetwise.ui.budgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.BudgetMotion
import app.budgetwise.ui.common.MotionContent
import app.budgetwise.ui.common.MotionReveal
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.FieldError
import app.budgetwise.ui.common.KyatGroupingTransformation
import app.budgetwise.ui.common.LAKH
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.common.Spinner
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.TextAction
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.lakhText
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.shortKyat
import app.budgetwise.ui.common.toMyanmarDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked

/** [onClose] leaves without saving; [onSaved] after the plan is saved. */
@Composable
fun PlanEditorRoute(onClose: () -> Unit, onSaved: () -> Unit, viewModel: PlanEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { saved() }
    }
    // System back from the split goes to the total; from the total it leaves the editor.
    BackHandler(enabled = state.step == PlanStep.SPLIT) { viewModel.back() }
    PlanEditorScreen(
        state = state,
        actions = PlanEditorActions(
            onClose = onClose,
            onBack = { viewModel.back() },
            onNewMoneyChange = viewModel::onNewMoneyChange,
            onPickLakh = viewModel::onPickLakh,
            onCarryTicked = viewModel::onCarryTicked,
            onNext = viewModel::next,
            onOpenShare = viewModel::openShare,
            onCloseShare = viewModel::closeShare,
            onShareChange = viewModel::onShareChange,
            onShareAmount = viewModel::onShareAmount,
            onRemoveShare = viewModel::removeShare,
            onOpenAddCategory = viewModel::openAddCategory,
            onCloseAddCategory = viewModel::closeAddCategory,
            onAddCategory = viewModel::addCategory,
            onRaiseMonth = viewModel::raiseMonth,
            onSave = viewModel::save,
        ),
    )
}

/** Everything the editor's screen can ask of its ViewModel. */
class PlanEditorActions(
    val onClose: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onNewMoneyChange: (String) -> Unit = {},
    val onPickLakh: (Long) -> Unit = {},
    val onCarryTicked: (Boolean) -> Unit = {},
    val onNext: () -> Unit = {},
    val onOpenShare: (String) -> Unit = {},
    val onCloseShare: () -> Unit = {},
    val onShareChange: (String, String) -> Unit = { _, _ -> },
    val onShareAmount: (String, Long) -> Unit = { _, _ -> },
    val onRemoveShare: (String) -> Unit = {},
    val onOpenAddCategory: () -> Unit = {},
    val onCloseAddCategory: () -> Unit = {},
    val onAddCategory: (String) -> Unit = {},
    val onRaiseMonth: () -> Unit = {},
    val onSave: () -> Unit = {},
)

/** The new-money presets on step 1, in lakh. */
private val LakhChips = listOf(10L, 15L, 20L, 25L)

/**
 * The plan editor: step 1 sets the month's total (new money, plus what last month left), step 2 splits it into
 * category shares. The step's main button stays pinned at the bottom, above the keyboard when it is open.
 */
@Composable
fun PlanEditorScreen(state: PlanEditorUiState, actions: PlanEditorActions, modifier: Modifier = Modifier) {
    val currentStep = rememberUpdatedState(state.step)
    val currentActions = rememberUpdatedState(actions)
    Column(
        modifier
            .fillMaxSize()
            .background(BudgetWiseTheme.colors.ground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        MotionContent(state, Modifier.fillMaxSize(), label = "planStep", contentKey = { it.step }) { stepState ->
            // Render each picture from its snapshot, but only the current step can change the live form.
            val stepActions = PlanEditorActions(
                onClose = { if (currentStep.value == PlanStep.TOTAL) currentActions.value.onClose() },
                onBack = { if (currentStep.value == PlanStep.SPLIT) currentActions.value.onBack() },
                onNewMoneyChange = { if (currentStep.value == PlanStep.TOTAL) currentActions.value.onNewMoneyChange(it) },
                onPickLakh = { if (currentStep.value == PlanStep.TOTAL) currentActions.value.onPickLakh(it) },
                onCarryTicked = { if (currentStep.value == PlanStep.TOTAL) currentActions.value.onCarryTicked(it) },
                onNext = { if (currentStep.value == PlanStep.TOTAL) currentActions.value.onNext() },
                onOpenShare = { if (currentStep.value == PlanStep.SPLIT) currentActions.value.onOpenShare(it) },
                onOpenAddCategory = { if (currentStep.value == PlanStep.SPLIT) currentActions.value.onOpenAddCategory() },
                onRaiseMonth = { if (currentStep.value == PlanStep.SPLIT) currentActions.value.onRaiseMonth() },
                onSave = { if (currentStep.value == PlanStep.SPLIT) currentActions.value.onSave() },
            )
            Column(Modifier.fillMaxSize()) {
                when (stepState.step) {
                    PlanStep.TOTAL -> TotalStep(stepState, stepActions)
                    PlanStep.SPLIT -> SplitStep(stepState, stepActions)
                }
            }
        }
    }
    state.editing?.let { category ->
        state.shares.firstOrNull { it.category == category }?.let { ShareSheet(state, it, actions) }
    }
    if (state.addingCategory) AddCategorySheet(state, actions)
}

@Composable
private fun ColumnScope.TotalStep(state: PlanEditorUiState, actions: PlanEditorActions) {
    val colors = BudgetWiseTheme.colors
    val name = monthName(state.month)
    BackHeader(
        title = stringResource(R.string.plan_month_budget, name),
        onBack = actions.onClose,
        icon = Lucide.X,
        backLabel = stringResource(R.string.action_close),
        trailing = { StepText(stringResource(R.string.onboarding_step, 1, 2)) },
    )
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        if (!state.loaded) {
            // The plans are being fetched; the form fills in once they are here (or the phone's copy, if not).
            val loading = stringResource(R.string.budgets_loading)
            Box(Modifier.fillMaxWidth().padding(top = 48.dp).semantics { contentDescription = loading }, contentAlignment = Alignment.Center) {
                Spinner(size = 28.dp)
            }
            return@Column
        }
        MotionReveal(state.stale) { StaleNote(Modifier.padding(bottom = 20.dp)) }
        val label = when {
            state.carry != null -> stringResource(R.string.plan_new_money, name)
            state.isThisMonth -> stringResource(R.string.plan_how_much_this_month)
            else -> stringResource(R.string.plan_how_much, name)
        }
        BudgetLabel(label)
        NewMoneyField(state, label, actions.onNewMoneyChange, Modifier.padding(top = 14.dp))
        LakhChipRow(state, actions.onPickLakh, Modifier.padding(top = 24.dp))
        val carry = state.carry
        if (carry != null) {
            CarryCard(state, carry, actions.onCarryTicked, Modifier.padding(top = 32.dp))
            MonthTotal(state)
        } else if (state.spentBefore.isNotEmpty()) {
            SpentBefore(state, Modifier.padding(top = 36.dp))
        }
    }
    Box(Modifier.fillMaxWidth().background(colors.ground).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)) {
        PrimaryButton(
            text = stringResource(R.string.plan_next, shortKyat(state.totalKyat, state.myanmarDigits)),
            onClick = actions.onNext,
            enabled = state.canGoOn,
            trailingIcon = Lucide.ArrowRightBold,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The plans could not be fetched: the editor shows the phone's copy, which another phone or the web may have changed. */
@Composable
private fun StaleNote(modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.CloudOff, contentDescription = null, tint = colors.blue, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.plan_stale), style = BudgetWiseTheme.type.size13, color = colors.muted)
    }
}

/**
 * The new money, big and without a box, on the phone's own number keyboard, then the sage rule with the amount in
 * lakh. The keyboard comes up by itself only when there is nothing in it yet.
 */
@Composable
private fun NewMoneyField(state: PlanEditorUiState, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.newMoneyDigits.isEmpty()) focus.requestFocus()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigAmountField(
            digits = state.newMoneyDigits,
            onChange = onChange,
            label = label,
            myanmarDigits = state.myanmarDigits,
            enabled = state.loaded && !state.saving,
            focus = focus,
            style = bigStyle(state.newMoneyDigits.length),
            unitStyle = type.size24.medium,
            onDone = { keyboard?.hide() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SageRule()
            if (state.newMoneyKyat >= LAKH) {
                Text(lakhText(state.newMoneyKyat, state.myanmarDigits), style = type.size15.semibold, color = colors.accent, maxLines = 1)
            }
        }
    }
}

/** A big figure steps down a size as it grows, so it and its "Ks" stay on one line. */
@Composable
private fun bigStyle(length: Int): TextStyle {
    val type = BudgetWiseTheme.type
    val size = when (length) {
        in 0..9 -> type.size44
        else -> type.size32
    }
    return size.extraBold.tracked(-0.03f).copy(fontFeatureSettings = "tnum")
}

/** An amount field without a box: plain digits shown grouped, "Ks" after it, "0" in muted while it is empty. */
@Composable
private fun BigAmountField(
    digits: String,
    onChange: (String) -> Unit,
    label: String,
    myanmarDigits: Boolean,
    enabled: Boolean,
    focus: FocusRequester,
    style: TextStyle,
    unitStyle: TextStyle,
    onDone: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val transformation = remember(myanmarDigits) { KyatGroupingTransformation(myanmarDigits) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).drawBehind {
            drawLine(colors.hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = digits,
            onValueChange = onChange,
            // No label on screen: TalkBack says what the figure is. As wide as the figure, so "Ks" follows it.
            modifier = Modifier
                .weight(1f, fill = false)
                .width(IntrinsicSize.Max)
                .alignByBaseline()
                .focusRequester(focus)
                .semantics { contentDescription = label },
            enabled = enabled,
            textStyle = style.copy(color = colors.ink, fontFeatureSettings = "tnum"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            singleLine = true,
            visualTransformation = transformation,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box {
                    if (digits.isEmpty()) Text(if (myanmarDigits) toMyanmarDigits("0") else "0", style = style, color = colors.muted)
                    inner()
                }
            },
        )
        Text("Ks", style = unitStyle, color = colors.muted, modifier = Modifier.alignByBaseline())
    }
}

/** 10, 15, 20 and 25 lakh: tapping one sets the new money; the one it equals is selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LakhChipRow(state: PlanEditorUiState, onPick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LakhChips.forEach { lakhs ->
            val selected = state.newMoneyKyat == lakhs * LAKH
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) colors.accent.copy(alpha = 0.16f) else colors.ground)
                    .border(1.dp, if (selected) colors.accent else colors.chipBorder, RoundedCornerShape(16.dp))
                    .selectable(selected = selected, enabled = state.loaded && !state.saving, role = Role.RadioButton, onClick = { onPick(lakhs) })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lakhText(lakhs * LAKH, state.myanmarDigits),
                    style = if (selected) type.size14.semibold else type.size14.medium,
                    color = colors.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "Add what's left from October": a checkbox card with the amount it adds, in sage while ticked. */
@Composable
private fun CarryCard(state: PlanEditorUiState, carry: Carry, onTicked: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val ticked = state.carryTicked
    val shape = RoundedCornerShape(20.dp)
    val detail = when {
        carry.checkedOn != null -> stringResource(R.string.plan_carry_checked, localizedDate(carry.checkedOn, "dMMMM", state.myanmarDigits))
        carry.saved -> stringResource(R.string.plan_carry_saved)
        else -> stringResource(R.string.plan_carry_not_checked)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (ticked) colors.accent.copy(alpha = 0.12f) else colors.ground)
            .border(1.dp, if (ticked) colors.accent else colors.hairline, shape)
            .toggleable(value = ticked, enabled = state.loaded && !state.saving, role = Role.Checkbox, onValueChange = onTicked)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KitCheckbox(ticked)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.plan_carry_title, monthName(carry.from)), style = type.size15.semibold, color = colors.ink)
            Text(detail, style = type.size13, color = colors.muted)
        }
        Text(
            "+" + groupDigits(carry.amountKyat, state.myanmarDigits),
            style = type.size16.bold.copy(fontFeatureSettings = "tnum"),
            color = if (ticked) colors.accent else colors.muted,
            maxLines = 1,
        )
    }
}

/** "November budget 2,280,000 Ks" over a hairline, then what it is in lakh and what the box does. */
@Composable
private fun MonthTotal(state: PlanEditorUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = monthName(state.month)
    val label = stringResource(R.string.plan_month_budget, name)
    val total = formatKyat(state.totalKyat, state.myanmarDigits)
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(label, Modifier.weight(1f).alignByBaseline())
            Text(total, style = type.size22.extraBold.tracked(-0.02f), color = colors.ink, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        val lakh = shortKyat(state.totalKyat, state.myanmarDigits)
        Text(
            if (state.carryTicked) {
                stringResource(R.string.plan_total_note_ticked, lakh, name)
            } else {
                stringResource(R.string.plan_total_note_unticked, lakh, monthName(state.previousMonth))
            },
            style = type.size13,
            color = colors.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** The last months' spending, newest first, and their average in lakh: a guide for the new money. */
@Composable
private fun SpentBefore(state: PlanEditorUiState, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.plan_spent_before), Modifier.padding(bottom = 10.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        state.spentBefore.forEach { spent ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(monthName(spent.month), style = type.size15, color = colors.ink, modifier = Modifier.weight(1f).alignByBaseline())
                Text(formatKyat(spent.amountKyat, state.myanmarDigits), style = type.size15.semibold, color = colors.ink, modifier = Modifier.alignByBaseline())
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
        val average = state.spentBefore.sumOf { it.amountKyat } / state.spentBefore.size
        Text(
            stringResource(R.string.plan_spent_before_note, shortKyat(average, state.myanmarDigits)),
            style = type.size13,
            color = colors.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ColumnScope.SplitStep(state: PlanEditorUiState, actions: PlanEditorActions) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = monthName(state.month)
    BackHeader(
        title = stringResource(R.string.plan_split_title, shortKyat(state.totalKyat, state.myanmarDigits)),
        onBack = actions.onBack,
        trailing = { StepText(stringResource(R.string.onboarding_step, 2, 2)) },
    )
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)) {
        item(key = "left") { LeftToSplit(state) }
        itemsIndexed(state.shares, key = { _, share -> "share:${share.category}" }) { index, share ->
            Box(Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null)) {
                ShareRow(state, share, first = index == 0, onClick = { actions.onOpenShare(share.category) })
            }
        }
        if (state.unshared.isNotEmpty()) {
            item(key = "add") {
                Box(Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null)) {
                    AddCategoryRow(first = state.shares.isEmpty(), onClick = actions.onOpenAddCategory)
                }
            }
        }
        item(key = "everything_else") {
            Box(Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null)) {
                EverythingElseRow(state, first = state.shares.isEmpty() && state.unshared.isEmpty())
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().background(colors.ground).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MotionContent(
            state,
            label = "planValidation",
            contentKey = { if (it.over) "over" else it.missingShares.firstOrNull() },
        ) { noteState ->
            val missing = noteState.missingShares.firstOrNull()
            if (noteState.over || missing != null) {
                Box(Modifier.padding(bottom = 12.dp)) {
                    if (noteState.over) RaiseMonth(noteState, actions.onRaiseMonth)
                    else if (missing != null) Text(
                        stringResource(R.string.plan_share_missing, categoryLabel(missing)),
                        style = type.size14,
                        color = colors.coralText,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        PrimaryButton(
            text = if (state.saving) stringResource(R.string.manual_saving) else stringResource(R.string.plan_save, name),
            onClick = actions.onSave,
            enabled = state.canSave,
            busy = state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        MotionContent(state.saveError to state.unknownCategory, Modifier.fillMaxWidth(), label = "planSaveError") { (error, category) ->
            if (error != null) FieldError(saveErrorText(error, category), Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

@Composable
private fun saveErrorText(error: SaveError, unknownCategory: String?): String = when (error) {
    SaveError.NETWORK -> stringResource(R.string.plan_save_failed_network)
    SaveError.SPLIT_OVER_TOTAL -> stringResource(R.string.plan_save_failed_over)
    SaveError.UNKNOWN_CATEGORY -> unknownCategory?.let { stringResource(R.string.plan_save_failed_category_named, categoryLabel(it)) }
        ?: stringResource(R.string.plan_save_failed_category)
    SaveError.SIGN_IN -> stringResource(R.string.plan_save_failed_sign_in)
    SaveError.OTHER -> stringResource(R.string.plan_save_failed)
}

/** Left to split, big in sage (coral "too much" once the split is over), the split as a bar, and a line about it. */
@Composable
private fun LeftToSplit(state: PlanEditorUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val over = state.over
    val figure = groupDigits(if (over) -state.unsplitKyat else state.unsplitKyat, state.myanmarDigits)
    val suffix = if (over) stringResource(R.string.plan_too_much) else stringResource(R.string.budgets_of_limit, formatKyat(state.totalKyat, state.myanmarDigits))
    val label = stringResource(if (over) R.string.plan_over_by else R.string.plan_left_to_split)
    val spoken = if (over) {
        "$label ${stringResource(R.string.kyat_amount, groupDigits(-state.unsplitKyat))}"
    } else {
        "$label ${stringResource(R.string.kyat_amount, groupDigits(state.unsplitKyat))}, " +
            stringResource(R.string.budgets_of_limit, stringResource(R.string.kyat_amount, groupDigits(state.totalKyat)))
    }
    BudgetSurface(Modifier.padding(bottom = 20.dp)) {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            BudgetLabel(label)
            BudgetFigure(
                figure = figure,
                color = if (over) colors.coralText else colors.accent,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(suffix, style = type.size18.medium, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
        SplitBar(state, Modifier.padding(top = 16.dp))
        Text(
            when {
                over -> stringResource(R.string.plan_split_note_over, shortKyat(state.splitKyat, state.myanmarDigits))
                state.startedFrom == StartedFrom.PREVIOUS -> stringResource(R.string.plan_split_note_previous, monthName(state.previousMonth))
                else -> stringResource(R.string.plan_split_note)
            },
            style = type.size14,
            color = colors.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * The shares side by side in their categories' colors, then what is left on the raised track. Over the month, the
 * shares fill the month's part and a coral end shows how much too much. Decorative: the figures above say it.
 */
@Composable
private fun SplitBar(state: PlanEditorUiState, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val total = state.totalKyat.toFloat()
    val split = state.splitKyat.toFloat()
    // Match the existing segment proportions, including the coral part beyond the total.
    val denominator = if (state.over) split else total
    val scale = if (state.over && split > 0f) total / split else 1f
    val segments = state.shares.map { share ->
        key(share.category) {
            val target = if (denominator > 0f) share.amountKyat * scale / denominator else 0f
            categoryColors(share.category).content to animateFloatAsState(target, BudgetMotion.change(), label = "share:${share.category}")
        }
    }
    val tail = animateFloatAsState(
        if (denominator > 0f) {
            (if (state.over) split - total else state.unsplitKyat.toFloat()).coerceAtLeast(0f) / denominator
        } else 1f,
        BudgetMotion.change(),
        label = "unsplitProgress",
    )
    val tailColor = if (state.over) colors.coral else colors.raised
    Box(
        modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(colors.raised).clearAndSetSemantics { }
            .drawBehind {
                // Fractions are read only in draw, including during interrupted amount edits.
                val count = segments.count { it.second.value > 0f } + if (tail.value > 0f) 1 else 0
                val gap = 2.dp.toPx()
                val width = (size.width - gap * (count - 1).coerceAtLeast(0)).coerceAtLeast(0f)
                var x = 0f
                segments.forEach { (color, fraction) ->
                    if (fraction.value > 0f) {
                        val segmentWidth = (width * fraction.value).coerceAtMost((size.width - x).coerceAtLeast(0f))
                        drawRect(color, topLeft = Offset(x, 0f), size = Size(segmentWidth, size.height))
                        x += segmentWidth + gap
                    }
                }
                if (tail.value > 0f) {
                    drawRect(tailColor, topLeft = Offset(x, 0f), size = Size((width * tail.value).coerceAtMost((size.width - x).coerceAtLeast(0f)), size.height))
                }
            },
    )
}

/** A share: badge, name, last month's spending in it, and its amount in a box that opens the share sheet. */
@Composable
private fun ShareRow(state: PlanEditorUiState, share: ShareDraft, first: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = categoryLabel(share.category)
    val lastMonth = state.lastMonthSpent[share.category] ?: 0L
    val previous = monthName(state.previousMonth)
    val detail = if (lastMonth > 0) {
        stringResource(R.string.plan_spent_in, groupDigits(lastMonth, state.myanmarDigits), previous)
    } else {
        stringResource(R.string.plan_nothing_in, previous)
    }
    // Marked: no amount yet (Save waits for it), the share that took the split over the month, or a category the
    // server no longer has.
    val marked = share.missing || (state.over && state.lastChanged == share.category) || state.unknownCategory == share.category
    val spoken = buildString {
        append(name).append(", ")
        append(
            if (share.missing) {
                stringResource(R.string.plan_share_needs_amount)
            } else {
                stringResource(R.string.plan_share_spoken, groupDigits(share.amountKyat))
            },
        )
        append(", ").append(if (lastMonth > 0) stringResource(R.string.plan_spent_in, stringResource(R.string.kyat_amount, groupDigits(lastMonth)), previous) else detail)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !state.saving, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
    ) {
        if (first) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CategoryBadge(share.category, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = type.size16.medium.forContent(name), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = type.size13, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier
                    .width(124.dp)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(colors.surface)
                    .border(if (marked) 2.dp else 1.dp, if (marked) colors.coral else colors.hairline, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    if (share.missing) groupDigits(0, state.myanmarDigits) else groupDigits(share.amountKyat, state.myanmarDigits),
                    style = type.size17.semibold.copy(fontFeatureSettings = "tnum"),
                    color = if (share.missing) colors.muted else colors.ink,
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

@Composable
private fun AddCategoryRow(first: Boolean, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        if (first) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DashedBadge(Lucide.Plus, tint = colors.accent, outline = colors.accent)
            Text(stringResource(R.string.plan_add_category), style = BudgetWiseTheme.type.size16.semibold, color = colors.accent, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

/** What no share has: the categories it covers, and its amount (0 once the split takes the whole month). */
@Composable
private fun EverythingElseRow(state: PlanEditorUiState, first: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val rest = state.unsplitKyat.coerceAtLeast(0)
    val names = state.categories.filter { c -> state.shares.none { it.category == c } }.take(2).map { categoryLabel(it) }
    val detail = when {
        rest == 0L -> stringResource(R.string.plan_nothing_left)
        names.size >= 2 -> stringResource(R.string.plan_rest_two, names[0], names[1])
        names.size == 1 -> stringResource(R.string.plan_rest_one, names[0])
        else -> stringResource(R.string.plan_rest_none)
    }
    val title = stringResource(R.string.budgets_everything_else)
    val spoken = "$title, ${stringResource(R.string.kyat_amount, groupDigits(rest))}, $detail"
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        if (first) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EverythingElseBadge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = type.size16.medium, color = colors.ink, maxLines = 1)
                Text(detail, style = type.size13.forContent(detail), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                groupDigits(rest, state.myanmarDigits),
                style = type.size17.semibold,
                color = colors.muted,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(124.dp).padding(horizontal = 15.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

/** "↑ Raise the month to 21.5 lakh": sets the new money so the total equals the split. */
@Composable
private fun RaiseMonth(state: PlanEditorUiState, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.ArrowUpBold, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.plan_raise, shortKyat(state.splitKyat, state.myanmarDigits)),
            style = BudgetWiseTheme.type.size15.semibold,
            color = colors.accent,
        )
    }
}

/** The sheets' look on this board: the surface, 24 dp corners, a short handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = BudgetWiseTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(colors.outline))
        },
    ) {
        content()
    }
}

/**
 * One category's share: its amount on the number keyboard, what that leaves to split, chips with last month's spending,
 * the average and all that is left, and Take it out of the split. Changes apply as they are typed; Done closes it.
 */
@Composable
private fun ShareSheet(state: PlanEditorUiState, share: ShareDraft, actions: PlanEditorActions) {
    PlanSheet(onDismiss = actions.onCloseShare) { ShareSheetContent(state, share, actions) }
}

/** The share sheet's body, apart from the sheet so it can be drawn on its own. */
@Composable
internal fun ShareSheetContent(state: PlanEditorUiState, share: ShareDraft, actions: PlanEditorActions) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = categoryLabel(share.category)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(share.category) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        Row(
            Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryBadge(share.category, contentDescription = null)
            Text(
                name,
                style = type.size24.bold.tracked(-0.02f).forContent(name),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAmountField(
                digits = share.digits,
                onChange = { actions.onShareChange(share.category, it) },
                label = stringResource(R.string.plan_share_label, name),
                myanmarDigits = state.myanmarDigits,
                enabled = !state.saving,
                focus = focus,
                style = bigStyle(share.digits.length),
                unitStyle = type.size22.medium,
                onDone = { keyboard?.hide() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SageRule()
                LeavesText(state)
            }
        }
        ShareChips(state, share, actions, Modifier.padding(top = 22.dp))
        Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextAction(
                stringResource(R.string.plan_take_out, name),
                onClick = { actions.onRemoveShare(share.category) },
                color = colors.coralText,
            )
            PrimaryButton(stringResource(R.string.plan_done), onClick = actions.onCloseShare, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "Leaves 370,000 to split" with the amount in sage, or "Over the month by 20,000" in coral. */
@Composable
private fun LeavesText(state: PlanEditorUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val over = state.over
    val amount = groupDigits(if (over) -state.unsplitKyat else state.unsplitKyat, state.myanmarDigits)
    val text = stringResource(if (over) R.string.plan_share_over else R.string.plan_leaves, amount)
    val styled = buildAnnotatedString {
        append(text)
        val at = text.indexOf(amount)
        if (at >= 0) addStyle(SpanStyle(color = if (over) colors.coralText else colors.accent, fontWeight = FontWeight.SemiBold), at, at + amount.length)
    }
    Text(styled, style = type.size14, color = if (over) colors.coralText else colors.muted, modifier = Modifier.semantics { contentDescription = text })
}

/** Amounts to tap for this share: last month's spending, the average, all that is left. Only the ones there are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareChips(state: PlanEditorUiState, share: ShareDraft, actions: PlanEditorActions, modifier: Modifier = Modifier) {
    val lastMonth = state.lastMonthSpent[share.category] ?: 0L
    val average = state.averageSpent[share.category] ?: 0L
    val rest = state.unsplitKyat
    if (lastMonth <= 0 && average <= 0 && rest <= 0) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lastMonth > 0) {
            AmountChip(monthName(state.previousMonth), groupDigits(lastMonth, state.myanmarDigits), lastMonth) {
                actions.onShareAmount(share.category, lastMonth)
            }
        }
        if (average > 0) {
            AmountChip(stringResource(R.string.plan_average), groupDigits(average, state.myanmarDigits), average) {
                actions.onShareAmount(share.category, average)
            }
        }
        if (rest > 0) {
            AmountChip(stringResource(R.string.plan_all_left), "+" + groupDigits(rest, state.myanmarDigits), share.amountKyat + rest) {
                actions.onShareAmount(share.category, share.amountKyat + rest)
            }
        }
    }
}

/** A chip with a label and an amount; TalkBack hears the label and the share it sets. */
@Composable
private fun AmountChip(label: String, amount: String, setsKyat: Long, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val spoken = "$label, ${stringResource(R.string.kyat_amount, groupDigits(setsKyat))}"
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = type.size13.medium, color = colors.ink, maxLines = 1)
        Text(amount, style = type.size13.medium, color = colors.muted, maxLines = 1)
    }
}

/** The categories without a share, each with last month's spending; tapping one adds it and opens its share sheet. */
@Composable
private fun AddCategorySheet(state: PlanEditorUiState, actions: PlanEditorActions) {
    PlanSheet(onDismiss = actions.onCloseAddCategory) { AddCategoryContent(state, actions) }
}

/** The Add a category sheet's body. */
@Composable
internal fun AddCategoryContent(state: PlanEditorUiState, actions: PlanEditorActions) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val previous = monthName(state.previousMonth)
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        Text(
            stringResource(R.string.plan_add_category),
            style = type.size24.bold.tracked(-0.02f),
            color = colors.ink,
            modifier = Modifier.padding(top = 20.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.plan_add_note, groupDigits(state.unsplitKyat.coerceAtLeast(0), state.myanmarDigits)),
            style = type.size14,
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState())) {
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            state.unshared.forEach { category ->
                val name = categoryLabel(category)
                val spent = state.lastMonthSpent[category] ?: 0L
                val detail = if (spent > 0) {
                    stringResource(R.string.plan_spent_in, groupDigits(spent, state.myanmarDigits), previous)
                } else {
                    stringResource(R.string.plan_nothing_in, previous)
                }
                val spoken = "${stringResource(R.string.plan_add_one, name)}, $detail"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { actions.onAddCategory(category) })
                        .clearAndSetSemantics {
                            contentDescription = spoken
                            role = Role.Button
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CategoryBadge(category, contentDescription = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = type.size16.medium.forContent(name), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(detail, style = type.size13, color = colors.muted, maxLines = 1)
                    }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.PlusBold, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
