package app.budgetwise.ui.budgets

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.model.Transaction
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.BudgetMotion
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.NoticeHost
import app.budgetwise.ui.common.NoticeTone
import app.budgetwise.ui.common.NoticeVisuals
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import app.budgetwise.ui.transactions.DayHeader
import app.budgetwise.ui.transactions.DaySection
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import app.budgetwise.ui.transactions.DayLine
import app.budgetwise.ui.transactions.TransactionRow

/** The category drill-down keeps the existing entry editor, swipe Delete and Undo interactions. */
@Composable
fun BudgetTransactionsRoute(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    viewModel: BudgetTransactionsViewModel = hiltViewModel(),
    onEditBudget: (YearMonth, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }
    val notices = remember { SnackbarHostState() }
    val pending = state.pendingDelete
    val deleted = stringResource(R.string.transactions_deleted)
    val detail = pending?.row?.let { stringResource(R.string.transactions_deleted_detail, it.description, formatKyat(it.amountKyat, state.myanmarDigits)) }
    val undo = stringResource(R.string.transactions_undo)
    LaunchedEffect(pending?.clientId) {
        val row = pending ?: return@LaunchedEffect
        notices.currentSnackbarData?.dismiss()
        val result = notices.showSnackbar(NoticeVisuals(NoticeTone.NEUTRAL, null, deleted, detail, undo, SnackbarDuration.Short))
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(row.clientId) else viewModel.commitDelete(row.clientId)
    }
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.commitPendingDelete() }
    }
    BudgetTransactionsScreen(
        state, onBack, onOpenEntry, viewModel::delete, notices,
        onEditBudget = { onEditBudget(state.month, state.category) },
    )
}

@Composable
fun BudgetTransactionsScreen(
    state: BudgetTransactionsUiState,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onDelete: (String) -> Unit,
    notices: SnackbarHostState,
    modifier: Modifier = Modifier,
    onEditBudget: () -> Unit = {},
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = if (state.category == EVERYTHING_ELSE) stringResource(R.string.budgets_everything_else) else categoryLabel(state.category)
    Column(modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars)) {
        BackHeader(name, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                if (state.loading) {
                    item(key = "loading") {
                        val loading = stringResource(R.string.budgets_loading)
                        BudgetSurface(Modifier.padding(top = 12.dp).clearAndSetSemantics { contentDescription = loading }) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                SkeletonBar(180.dp, 12.dp, color = colors.raised)
                                SkeletonBar(230.dp, 44.dp, color = colors.raised)
                                SkeletonBar(180.dp, 14.dp, color = colors.raised)
                            }
                        }
                    }
                } else {
                    item(key = "summary") {
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
                            BudgetSurface(padding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                                val monthBudget = stringResource(R.string.budget_transactions_month, monthName(state.month))
                                val line = state.line
                                BudgetSummaryHeader(monthBudget) {
                                    if (line != null) {
                                        EditBudgetAction(
                                            text = stringResource(if (state.category == EVERYTHING_ELSE) R.string.budget_category_edit_split else R.string.budget_category_edit_budget),
                                            onClick = onEditBudget,
                                        )
                                    }
                                }
                                if (line != null) {
                                    val figure = groupDigits(kotlin.math.abs(line.remainingKyat), state.myanmarDigits)
                                    // Keep PR18's full-value autosizing and wrapping unit, including narrow phones and large text.
                                    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BasicText(
                                            figure,
                                            style = type.size44.extraBold.tracked(-0.03f).copy(
                                                color = if (line.over) colors.coralText else colors.ink,
                                                fontFeatureSettings = "tnum",
                                            ),
                                            autoSize = TextAutoSize.StepBased(minFontSize = type.size14.fontSize, maxFontSize = type.size44.fontSize),
                                            maxLines = 1,
                                            modifier = Modifier.alignByBaseline(),
                                        )
                                        Text(
                                            stringResource(if (line.over) R.string.budgets_ks_over else R.string.budgets_ks_left),
                                            style = type.size16.medium,
                                            color = if (line.over) colors.coralText else colors.muted,
                                            modifier = Modifier.alignByBaseline(),
                                        )
                                    }
                                    Bar(line.progress, if (line.over) colors.coral else colors.accent, 6.dp, Modifier.padding(top = 8.dp))
                                    Text(
                                        stringResource(R.string.budgets_spent_of_total, groupDigits(line.spentKyat, state.myanmarDigits), groupDigits(line.limitKyat, state.myanmarDigits)),
                                        style = type.size14,
                                        color = colors.muted,
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                } else {
                                    Text(stringResource(R.string.budget_transactions_plan_changed), style = type.size14, color = colors.muted, modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                            Text(
                                stringResource(R.string.transactions_title),
                                style = type.size20.semibold,
                                color = colors.ink,
                                modifier = Modifier.padding(top = 28.dp).semantics { heading() },
                            )
                            Text(
                                pluralStringResource(R.plurals.budget_transactions_count, state.count, groupDigits(state.count.toLong(), state.myanmarDigits)),
                                style = type.size13,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    if (state.days.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Lucide.Transactions,
                                title = stringResource(R.string.budget_transactions_empty_title),
                                message = stringResource(R.string.budget_transactions_empty_detail),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            )
                        }
                    }
                    state.days.forEachIndexed { dayIndex, day ->
                        item(key = "day:${day.date}", contentType = "day") {
                            DayHeader(day, state.today, state.myanmarDigits, Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null).padding(top = if (dayIndex == 0) 0.dp else 18.dp))
                        }
                        itemsIndexed(day.rows, key = { _, row -> "entry:${row.clientId}:${state.restored[row.clientId] ?: 0}" }, contentType = { _, _ -> "entry" }) { index, row ->
                            TransactionRow(
                                line = DayLine.Entry(row),
                                last = index == day.rows.lastIndex,
                                myanmarDigits = state.myanmarDigits,
                                onOpen = { onOpenEntry(row.clientId) },
                                onDelete = { onDelete(row.clientId) },
                                modifier = Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null),
                            )
                        }
                    }
                }
            }
            NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
        }
    }
}


/** Editing the category's allocation is distinct from opening any one contributing expense. */
@Composable
private fun EditBudgetAction(text: String, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .widthIn(max = 170.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, colors.hairline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BudgetWiseTheme.type.size14.semibold, color = colors.ink)
    }
}


@Preview(name = "Category expenses, light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "Category expenses, dark", showBackground = true, widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BudgetTransactionsPreview() {
    val date = LocalDate.of(2026, 11, 3)
    val manual = Transaction("rice", "server-rice", TxType.EXPENSE, 48_000, "Groceries", "Rice and cooking oil", date, Source.MANUAL, null, Instant.EPOCH, SyncState.SYNCED)
    val receipt = manual.copy(clientId = "receipt-groceries", amountKyat = 24_500, description = "City Mart, Groceries", source = Source.SCAN, receiptId = "city-mart", merchant = "City Mart")
    BudgetWiseTheme {
        BudgetTransactionsScreen(
            state = BudgetTransactionsUiState(YearMonth.from(date), "Groceries", date, loading = false, line = BudgetLine("Groceries", 350_000, 72_500), days = listOf(DaySection(date, listOf(manual, receipt)))),
            onBack = {}, onOpenEntry = {}, onDelete = {}, notices = remember { SnackbarHostState() },
        )
    }
}
