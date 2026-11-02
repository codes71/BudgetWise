package app.budgetwise.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.AppHeader
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.SectionLabel
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.tracked
import app.budgetwise.ui.theme.semibold
import java.time.YearMonth
import kotlin.math.abs

@Composable
fun BudgetsRoute(
    chip: SyncChip?,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    viewModel: BudgetsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Every time the tab opens (and when the app comes back to it), not only when the ViewModel is new: the bottom
    // bar keeps this tab's ViewModel while another tab is open.
    LifecycleStartEffect(viewModel) {
        viewModel.load()
        onStopOrDispose { }
    }
    BudgetsScreen(state = state, chip = chip, onChip = onChip, onSettings = onSettings, onRefresh = viewModel::refresh)
}

/** Over budget first (it needs attention), then the most spent. */
internal fun orderedForScreen(lines: List<BudgetLine>): List<BudgetLine> =
    lines.sortedWith(compareByDescending<BudgetLine> { it.over }.thenByDescending { it.spentKyat })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(
    state: BudgetsUiState,
    chip: SyncChip?,
    onChip: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val lines = remember(state.lines) { orderedForScreen(state.lines) }
    Column(modifier.fillMaxSize().background(colors.ground)) {
        AppHeader(stringResource(R.string.budgets_title), chip, onChip = onChip, onSettings = onSettings)
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
                when {
                    state.showEmpty -> item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(
                                icon = Lucide.PiggyBank,
                                title = stringResource(R.string.budgets_empty_title),
                                message = stringResource(R.string.budgets_empty),
                                modifier = Modifier.padding(bottom = 64.dp),
                            )
                        }
                    }
                    lines.isEmpty() -> item(key = "loading") { LoadingSkeleton() }
                    else -> {
                        item(key = "summary") { MonthSummary(state) }
                        itemsIndexed(lines, key = { _, line -> line.category }) { index, line ->
                            BudgetRow(line, state.myanmarDigits, first = index == 0)
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
                }
            }
        }
    }
}

/** The whole month across every budget: the big amount spent, its limit, the bar and what is left. */
@Composable
private fun MonthSummary(state: BudgetsUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val spent = state.totalSpentKyat
    val limit = state.totalLimitKyat
    val count = state.lines.size
    val over = spent > limit
    val spoken = spentOfLimitSpoken(spent, limit) + ", " + leftSpoken(limit - spent)
    val figure = groupDigits(spent, state.myanmarDigits)
    Column(
        Modifier
            .padding(top = 26.dp, bottom = 22.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        SectionLabel(stringResource(R.string.budgets_spent_in, monthName(state.month)))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = figure,
                style = (if (figure.length > 8) type.size44 else type.size60).extraBold.tracked(-0.035f),
                color = if (over) colors.coralText else colors.ink,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = stringResource(R.string.budgets_of_limit, formatKyat(limit, state.myanmarDigits)),
                style = type.size18.medium,
                color = colors.muted,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Bar(
            progress = if (limit > 0) spent.toFloat() / limit else if (spent > 0) 1f else 0f,
            color = if (over) colors.coral else colors.accent,
            height = 6.dp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = if (over) {
                pluralStringResource(R.plurals.budgets_over_across, count, formatKyat(spent - limit, state.myanmarDigits), count)
            } else {
                pluralStringResource(R.plurals.budgets_left_across, count, formatKyat(limit - spent, state.myanmarDigits), count)
            },
            style = type.size14,
            color = if (over) colors.coralText else colors.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** One budget between hairlines: badge, name, "spent of limit", a thin bar, then what is left (or over) and the share. */
@Composable
private fun BudgetRow(line: BudgetLine, myanmarDigits: Boolean, first: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = categoryLabel(line.category)
    val spent = groupDigits(line.spentKyat, myanmarDigits)
    val ofLimit = stringResource(R.string.budgets_spent_of_limit, spent, groupDigits(line.limitKyat, myanmarDigits))
    // "21,251 of 20,000" with the amount spent larger and bold, in coral when over.
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
    val spoken = "$name, ${spentOfLimitSpoken(line.spentKyat, line.limitKyat)}, " +
        if (line.over) overSpoken(-line.remainingKyat) else leftSpoken(line.remainingKyat)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        if (first) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The name is written next to it, so the badge needs no description.
            CategoryBadge(line.category, contentDescription = null)
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
                Bar(progress = line.progress, color = if (line.over) colors.coral else colors.accent, height = 4.dp)
                Row {
                    Text(
                        text = if (line.over) {
                            stringResource(R.string.budgets_over, formatKyat(-line.remainingKyat, myanmarDigits))
                        } else {
                            stringResource(R.string.budgets_left, formatKyat(line.remainingKyat, myanmarDigits))
                        },
                        style = footStyle,
                        color = footColor,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.budgets_percent, groupDigits(line.percentUsed, myanmarDigits)), style = footStyle, color = footColor)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
    }
}

@Composable
private fun Bar(progress: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxWidth().then(modifier).height(height).clip(CircleShape).background(BudgetWiseTheme.colors.raised)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

/** The month summary and four rows, as grey blocks of the same size, while the first answer comes. */
@Composable
private fun LoadingSkeleton() {
    val colors = BudgetWiseTheme.colors
    val loading = stringResource(R.string.budgets_loading)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Column(Modifier.padding(top = 26.dp, bottom = 22.dp)) {
            SkeletonBar(190.dp, 12.dp, color = colors.surface)
            SkeletonBar(240.dp, 52.dp, Modifier.padding(top = 12.dp), color = colors.surface)
            Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.surface))
            SkeletonBar(170.dp, 12.dp, Modifier.padding(top = 14.dp), color = colors.surface)
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
    BudgetWiseTheme {
        BudgetsScreen(
            state = BudgetsUiState(
                month = YearMonth.of(2026, 10),
                loading = false,
                lines = listOf(
                    BudgetLine("Food and Drink Item", 60_000, 48_500),
                    BudgetLine("Groceries", 80_000, 62_200),
                    BudgetLine("Transport", 20_000, 21_251),
                ),
            ),
            chip = SyncChip.SYNCED,
            onChip = {},
            onSettings = {},
            onRefresh = {},
        )
    }
}
