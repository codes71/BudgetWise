package app.budgetwise.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.ui.common.BudgetMotion
import app.budgetwise.ui.common.AppHeader
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.shortKyat
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tracked
import java.time.YearMonth
import kotlin.math.abs

/** [onEditPlan] opens the plan editor for a month; [onCheckMonth] the end check of a month that is over. */
@Composable
fun BudgetsRoute(
    chip: SyncChip?,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    onEditPlan: (YearMonth) -> Unit,
    onCheckMonth: (YearMonth) -> Unit,
    onOpenCategory: (YearMonth, String) -> Unit = { _, _ -> },
    viewModel: BudgetsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Every time the tab opens (and when the app comes back to it), not only when the ViewModel is new: the bottom
    // bar keeps this tab's ViewModel while another tab is open.
    LifecycleStartEffect(viewModel) {
        viewModel.load()
        onStopOrDispose { }
    }
    BudgetsScreen(
        state = state,
        chip = chip,
        onChip = onChip,
        onSettings = onSettings,
        onRefresh = viewModel::refresh,
        onEditPlan = { onEditPlan(state.month) },
        onCheckMonth = { state.toCheck?.let { onCheckMonth(it.month) } },
        onOpenCategory = { onOpenCategory(state.month, it) },
    )
}

/** Over budget first (it needs attention), then the most spent. */
internal fun orderedForScreen(lines: List<BudgetLine>): List<BudgetLine> =
    lines.sortedWith(compareByDescending<BudgetLine> { it.over }.thenByDescending { it.spentKyat })

/**
 * This month's plan: what is left of its total, then one row per share and Everything else last. Before the month has
 * a plan, the first-plan screen asks for one. A month that ended without its check gets a card on top of either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(
    state: BudgetsUiState,
    chip: SyncChip?,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onEditPlan: () -> Unit,
    onCheckMonth: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCategory: (String) -> Unit = {},
) {
    val colors = BudgetWiseTheme.colors
    val lines = remember(state.lines) { orderedForScreen(state.lines) }
    Column(modifier.fillMaxSize().background(colors.ground)) {
        AppHeader(
            title = stringResource(R.string.budgets_title),
            chip = chip,
            onChip = onChip,
            onSettings = onSettings,
        )
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            state = pull,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = colors.raised,
                    color = colors.accent,
                )
            },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                state.toCheck?.let { check ->
                    item(key = "check") { CheckCard(check, state.myanmarDigits, onCheckMonth, Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null).padding(top = 16.dp)) }
                }
                val plan = state.plan
                when {
                    plan != null -> {
                        item(key = "summary") { MonthSummary(state, plan, onEditPlan) }
                        // Keys apart from the fixed items' keys, whatever a category is called.
                        itemsIndexed(lines, key = { _, line -> "share:${line.category}" }) { index, line ->
                            BudgetRow(line, state.myanmarDigits, first = index == 0, modifier = Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null), onClick = { onOpenCategory(line.category) })
                        }
                        state.everythingElse?.let { rest ->
                            item(key = "everything_else") { BudgetRow(rest, state.myanmarDigits, first = lines.isEmpty(), everythingElse = true, modifier = Modifier.animateItem(fadeInSpec = BudgetMotion.effects(), placementSpec = BudgetMotion.change(), fadeOutSpec = null), onClick = { onOpenCategory(rest.category) }) }
                        }
                        item(key = "footer") {
                            Text(
                                text = stringResource(R.string.budgets_footer),
                                style = BudgetWiseTheme.type.size13,
                                color = colors.muted,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                    }
                    state.showFirstPlan -> item(key = "first") { FirstPlan(state, onEditPlan) }
                    else -> item(key = "loading") { LoadingSkeleton() }
                }
            }
        }
    }
}

/** Edit plan sits in the summary so the app header has room for its title and sync status. */
@Composable
private fun EditPlanPill(onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.Pencil, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
        Text(stringResource(R.string.budgets_edit_plan), style = BudgetWiseTheme.type.size14.semibold, color = colors.ink, maxLines = 1)
    }
}

/** "October ended with 343,500 Ks left. Check it": opens that month's end check. One button for TalkBack. */
@Composable
private fun CheckCard(check: MonthToCheck, myanmarDigits: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = monthName(check.month)
    val text = if (check.leftKyat >= 0) {
        stringResource(R.string.budgets_check_left, name, formatKyat(check.leftKyat, myanmarDigits))
    } else {
        stringResource(R.string.budgets_check_over, name, formatKyat(-check.leftKyat, myanmarDigits))
    }
    val action = stringResource(R.string.budgets_check_it)
    val spoken = if (check.leftKyat >= 0) {
        stringResource(R.string.budgets_check_left, name, formatKyat(check.leftKyat))
    } else {
        stringResource(R.string.budgets_check_over, name, formatKyat(-check.leftKyat))
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$spoken $action"
                role = Role.Button
            }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = type.size14, color = colors.ink, modifier = Modifier.weight(1f))
        Text(action, style = type.size14.semibold, color = colors.accent, maxLines = 1)
        Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
    }
}

/** S0: no plan for this month yet. One amount for the month, then split it. */
@Composable
private fun FirstPlan(state: BudgetsUiState, onSetBudget: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = monthName(state.month)
    Column(Modifier.fillMaxWidth().padding(top = 48.dp)) {
        SectionLabel(name)
        Text(
            stringResource(R.string.budgets_first_headline),
            style = type.size40.extraBold.tracked(-0.03f),
            color = colors.ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            stringResource(R.string.budgets_first_body),
            style = type.size16,
            color = colors.muted,
            modifier = Modifier.padding(top = 16.dp),
        )
        PrimaryButton(
            text = stringResource(R.string.budgets_first_set, name),
            onClick = onSetBudget,
            modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
        )
        if (state.lastMonthSpentKyat > 0) {
            Text(
                stringResource(R.string.budgets_first_spent, formatKyat(state.lastMonthSpentKyat, state.myanmarDigits), monthName(state.month.minusMonths(1))),
                style = type.size13,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

/** "October budget · 20 lakh"; a total under 1 lakh in Ks ("October budget · 50,000 Ks"), never "0.5 lakh". */
@Composable
internal fun monthBudgetLabel(month: YearMonth, totalKyat: Long, myanmarDigits: Boolean): String =
    "${stringResource(R.string.plan_month_budget, monthName(month))} · ${shortKyat(totalKyat, myanmarDigits)}"

/** The month's total: what is left of it, big, then the bar and "spent of total". */
@Composable
private fun MonthSummary(state: BudgetsUiState, plan: MonthPlan, onEditPlan: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val total = plan.totalKyat
    val spent = state.spentKyat
    val left = state.leftKyat
    val over = left < 0
    val monthBudget = stringResource(R.string.plan_month_budget, monthName(state.month))
    val spoken = "$monthBudget, ${stringResource(R.string.kyat_amount, groupDigits(total))}. " +
        spentOfLimitSpoken(spent, total) + ", " + leftSpoken(left)
    val figure = groupDigits(abs(left), state.myanmarDigits)
    BudgetSurface(
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        padding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
    ) {
        BudgetSummaryHeader(monthBudget) { EditPlanPill(onEditPlan) }
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            BudgetFigure(
                figure = figure,
                suffix = stringResource(if (over) R.string.budgets_ks_over else R.string.budgets_ks_left),
                color = if (over) colors.coralText else colors.ink,
            )
            Bar(
                progress = if (total > 0) spent.toFloat() / total else if (spent > 0) 1f else 0f,
                color = if (over) colors.coral else colors.accent,
                height = 6.dp,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                text = stringResource(R.string.budgets_spent_of_total, groupDigits(spent, state.myanmarDigits), groupDigits(total, state.myanmarDigits)),
                style = type.size14,
                color = colors.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * One share between hairlines: badge, name, "spent of share", a thin bar, then what is left (or over) and the percent.
 * Everything else has the dashed wallet badge and a muted bar, for money no category has.
 */
@Composable
private fun BudgetRow(line: BudgetLine, myanmarDigits: Boolean, first: Boolean, modifier: Modifier = Modifier, everythingElse: Boolean = false, onClick: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = if (everythingElse) stringResource(R.string.budgets_everything_else) else categoryLabel(line.category)
    val spent = groupDigits(line.spentKyat, myanmarDigits)
    val ofLimit = stringResource(R.string.budgets_spent_of_limit, spent, groupDigits(line.limitKyat, myanmarDigits))
    // "162,400 of 150,000" with the amount spent larger and bold, in coral when over.
    val figures = buildAnnotatedString {
        append(ofLimit)
        val at = ofLimit.indexOf(spent)
        if (at >= 0) {
            addStyle(
                SpanStyle(color = if (line.over) colors.coralText else colors.ink, fontWeight = FontWeight.Bold, fontSize = type.size16.fontSize),
                at,
                at + spent.length,
            )
        }
    }
    val footColor = if (line.over) colors.coralText else colors.muted
    val footStyle = if (line.over) type.size13.semibold else type.size13
    val spoken = "$name, ${spentOfLimitSpoken(line.spentKyat, line.limitKyat)}, " + leftSpoken(line.remainingKyat)
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics { contentDescription = spoken; role = Role.Button }) {
        if (first) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The name is written next to it, so the badge needs no description.
            if (everythingElse) EverythingElseBadge() else CategoryBadge(line.category, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        name,
                        style = type.size16.medium.forContent(name),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                    )
                    Text(figures, style = type.size14, color = colors.muted, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
                Bar(
                    progress = line.progress,
                    color = when {
                        line.over -> colors.coral
                        everythingElse -> colors.muted
                        else -> colors.accent
                    },
                    height = 4.dp,
                )
                Row {
                    Text(
                        text = when {
                            line.over -> stringResource(R.string.budgets_over, formatKyat(-line.remainingKyat, myanmarDigits))
                            line.remainingKyat == 0L && line.limitKyat > 0 -> stringResource(R.string.budgets_all_used)
                            else -> stringResource(R.string.budgets_left, formatKyat(line.remainingKyat, myanmarDigits))
                        },
                        style = footStyle,
                        color = footColor,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.budgets_percent, groupDigits(line.percentUsed, myanmarDigits)), style = footStyle, color = footColor)
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

/** The month summary and four rows, as grey blocks of the same size, while the first answer comes. */
@Composable
private fun LoadingSkeleton() {
    val colors = BudgetWiseTheme.colors
    val loading = stringResource(R.string.budgets_loading)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        BudgetSurface(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            SkeletonBar(190.dp, 12.dp, color = colors.raised)
            SkeletonBar(220.dp, 52.dp, Modifier.padding(top = 12.dp), color = colors.raised)
            Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.raised))
            SkeletonBar(170.dp, 12.dp, Modifier.padding(top = 14.dp), color = colors.raised)
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        listOf(96.dp, 120.dp, 84.dp, 108.dp).forEach { width ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(colors.surface))
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row {
                        SkeletonBar(width, 14.dp, color = colors.surface)
                        Box(Modifier.weight(1f))
                        SkeletonBar(88.dp, 14.dp, color = colors.surface)
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.surface))
                    SkeletonBar(80.dp, 12.dp, color = colors.surface)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }
    }
}

@Composable
private fun spentOfLimitSpoken(spent: Long, limit: Long) =
    stringResource(R.string.budgets_spent_of_limit_spoken, groupDigits(spent), groupDigits(limit))

@Composable
private fun leftSpoken(remaining: Long) =
    if (remaining < 0) overSpoken(abs(remaining)) else stringResource(R.string.budgets_left_spoken, groupDigits(remaining))

@Composable
private fun overSpoken(over: Long) = stringResource(R.string.budgets_over_spoken, groupDigits(over))

@Preview(showBackground = true)
@Composable
private fun BudgetsScreenPreview() {
    val month = YearMonth.of(2026, 10)
    val plan = MonthPlan(month, 2_000_000, 0, listOf(Budget("Transport", 150_000), Budget("Housing", 500_000), Budget("Groceries", 350_000)))
    BudgetWiseTheme {
        BudgetsScreen(
            state = BudgetsUiState(
                month = month,
                loading = false,
                plan = plan,
                spentKyat = 1_456_500,
                lines = listOf(BudgetLine("Transport", 150_000, 162_400), BudgetLine("Housing", 500_000, 500_000), BudgetLine("Groceries", 350_000, 298_200)),
                everythingElse = BudgetLine(EVERYTHING_ELSE, 1_000_000, 58_900),
                toCheck = MonthToCheck(month.minusMonths(1), 343_500),
            ),
            chip = SyncChip.SYNCED,
            onChip = {},
            onSettings = {},
            onRefresh = {},
            onEditPlan = {},
            onCheckMonth = {},
        )
    }
}
