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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.R
import app.budgetwise.ui.common.AppHeader
import app.budgetwise.ui.common.CategoryBadge
import app.budgetwise.ui.common.EmptyState
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.SkeletonBar
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tabular
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
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
                            BudgetRow(line, state.myanmarDigits, groupedShape(index == 0, index == lines.lastIndex), first = index == 0)
                        }
                        item(key = "footer") {
                            Text(
                                text = stringResource(R.string.budgets_footer),
                                style = BudgetWiseTheme.type.size13,
                                color = colors.muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun groupedShape(first: Boolean, last: Boolean, outer: Dp = 18.dp): Shape {
    val top = if (first) outer else 0.dp
    val bottom = if (last) outer else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** The whole month across every budget. */
@Composable
private fun MonthSummary(state: BudgetsUiState) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val spent = state.totalSpentKyat
    val limit = state.totalLimitKyat
    val count = state.lines.size
    val over = spent > limit
    val spoken = spentOfLimitSpoken(spent, limit) + ", " + leftSpoken(limit - spent)
    Column(
        Modifier
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 20.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(stringResource(R.string.budgets_spent_in, monthName(state.month)), style = type.size15, color = colors.muted)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = groupDigits(spent, state.myanmarDigits),
                style = type.size32.bold.tabular.copy(letterSpacing = if (type.burmese) (-0.01).em else (-0.02).em),
                color = colors.ink,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = stringResource(R.string.budgets_of_limit, formatKyat(limit, state.myanmarDigits)),
                style = type.size16.tabular,
                color = colors.muted,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Bar(
            progress = if (limit > 0) spent.toFloat() / limit else if (spent > 0) 1f else 0f,
            color = if (over) colors.coral else colors.accent,
            height = 10.dp,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = if (over) {
                pluralStringResource(R.plurals.budgets_over_across, count, formatKyat(spent - limit, state.myanmarDigits), count)
            } else {
                pluralStringResource(R.plurals.budgets_left_across, count, formatKyat(limit - spent, state.myanmarDigits), count)
            },
            style = type.size13.tabular,
            color = if (over) colors.coralText else colors.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun BudgetRow(line: BudgetLine, myanmarDigits: Boolean, shape: Shape, first: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val name = categoryLabel(line.category)
    val spent = groupDigits(line.spentKyat, myanmarDigits)
    val ofLimit = stringResource(R.string.budgets_spent_of_limit, spent, groupDigits(line.limitKyat, myanmarDigits))
    // "21,251 of 20,000" with the amount spent in ink and semibold.
    val figures = buildAnnotatedString {
        append(ofLimit)
        val at = ofLimit.indexOf(spent)
        if (at >= 0) addStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold), at, at + spent.length)
    }
    val spoken = "$name, ${spentOfLimitSpoken(line.spentKyat, line.limitKyat)}, " +
        if (line.over) overSpoken(-line.remainingKyat) else leftSpoken(line.remainingKyat)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface).clearAndSetSemantics { contentDescription = spoken }) {
        if (!first) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The name is written next to it, so the badge needs no description.
            CategoryBadge(line.category, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        name,
                        style = type.size16.medium.forContent(name),
                        color = colors.ink,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                    )
                    Text(figures, style = type.size14.tabular, color = colors.muted, modifier = Modifier.alignByBaseline())
                }
                Bar(progress = line.progress, color = if (line.over) colors.coral else colors.accent, height = 6.dp)
                if (line.over) {
                    Text(
                        stringResource(R.string.budgets_over, formatKyat(-line.remainingKyat, myanmarDigits)),
                        style = type.size13.semibold.tabular,
                        color = colors.coralText,
                    )
                } else {
                    Text(
                        stringResource(R.string.budgets_left, formatKyat(line.remainingKyat, myanmarDigits)),
                        style = type.size13.tabular,
                        color = colors.muted,
                    )
                }
            }
        }
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
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 20.dp)) {
            SkeletonBar(190.dp, 14.dp, color = colors.surface)
            SkeletonBar(220.dp, 30.dp, Modifier.padding(top = 10.dp), color = colors.surface)
            Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(colors.surface))
            SkeletonBar(170.dp, 12.dp, Modifier.padding(top = 12.dp), color = colors.surface)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface)) {
            listOf(96.dp, 120.dp, 84.dp, 108.dp).forEachIndexed { i, width ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = colors.hairline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.raised))
                    Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row {
                            SkeletonBar(width, 14.dp)
                            Box(Modifier.weight(1f))
                            SkeletonBar(88.dp, 14.dp)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.raised))
                        SkeletonBar(80.dp, 12.dp)
                    }
                }
            }
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
