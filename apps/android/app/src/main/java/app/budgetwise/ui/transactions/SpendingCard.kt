package app.budgetwise.ui.transactions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.budgetwise.R
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.SegmentedPill
import app.budgetwise.ui.common.categoryColors
import app.budgetwise.ui.common.categoryLabel
import app.budgetwise.ui.common.formatKyat
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.signedDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.theme.tabular
import kotlin.math.roundToInt

private enum class SpendingView { TOTAL, BY_CATEGORY }

/** The kit's height for both views. Burmese lines (or a long category list once opened) may need more. */
private val PanelHeight = 150.dp

/**
 * The month's spending card under "Left in October". Total: this month's running total from the 1st to today against
 * last month's for the same days (dashed). By category: the five largest categories so far, each against last month by
 * the same day.
 */
@Composable
fun SpendingCard(
    pace: MonthPace,
    categories: CategoryBreakdown,
    myanmarDigits: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    var view by rememberSaveable { mutableStateOf(SpendingView.TOTAL) }
    var otherOpen by rememberSaveable { mutableStateOf(false) }
    val lastMonthName = monthName(pace.lastMonth)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val viewLabel = stringResource(R.string.spending_view)
            SegmentedPill(Modifier.semantics { contentDescription = viewLabel }) {
                ViewTab(stringResource(R.string.spending_total), view == SpendingView.TOTAL) {
                    view = SpendingView.TOTAL
                    otherOpen = false
                }
                ViewTab(stringResource(R.string.spending_by_category), view == SpendingView.BY_CATEGORY) {
                    view = SpendingView.BY_CATEGORY
                    otherOpen = false
                }
            }
            Box(Modifier.weight(1f))
            if (pace.hasLastMonth) Legend(view, lastMonthName)
        }
        // Both views are laid out, the hidden one invisible and silent, so the card is always as tall as the taller of
        // them and switching never moves the list under it.
        val total = view == SpendingView.TOTAL
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = PanelHeight)) {
            Box(Modifier.shownIf(total)) { TotalPanel(pace, lastMonthName, myanmarDigits) }
            Box(Modifier.shownIf(!total)) {
                CategoryPanel(
                    categories = categories,
                    compare = pace.hasLastMonth,
                    lastMonthName = lastMonthName,
                    myanmarDigits = myanmarDigits,
                    otherOpen = otherOpen,
                    onOtherToggle = { otherOpen = !otherOpen },
                    interactive = !total,
                )
            }
        }
    }
}

private fun Modifier.shownIf(shown: Boolean): Modifier = if (shown) this else alpha(0f).clearAndSetSemantics { }

@Composable
private fun ViewTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val style = BudgetWiseTheme.type.size13
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (selected) colors.sageContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onSelect)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (selected) style.semibold else style.medium,
            color = if (selected) colors.onSageContainer else colors.muted,
            maxLines = 1,
        )
    }
}

/** What marks last month: the dashed line in Total, the tick in By category. Decorative; the views say it in words. */
@Composable
private fun Legend(view: SpendingView, lastMonthName: String) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = Modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (view == SpendingView.TOTAL) {
            Canvas(Modifier.size(width = 14.dp, height = 2.dp)) {
                val dash = 2.dp.toPx()
                drawLine(
                    color = colors.chartLastMonth,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash * 2, dash)),
                )
            }
        } else {
            Box(Modifier.size(width = 2.dp, height = 12.dp).clip(RoundedCornerShape(1.dp)).background(colors.chartTick))
        }
        Text(lastMonthName, style = BudgetWiseTheme.type.size12, color = colors.muted, maxLines = 1)
    }
}

@Composable
private fun verdictText(pace: MonthPace, lastMonthName: String, myanmarDigits: Boolean): Pair<String, Color> {
    val colors = BudgetWiseTheme.colors
    return when (val v = pace.verdict) {
        is PaceVerdict.Less -> stringResource(R.string.spending_less, groupDigits(v.amount, myanmarDigits), lastMonthName) to colors.accent
        is PaceVerdict.More -> stringResource(R.string.spending_more, groupDigits(v.amount, myanmarDigits), lastMonthName) to colors.coralText
        PaceVerdict.Same -> stringResource(R.string.spending_same, lastMonthName) to colors.muted
        is PaceVerdict.NoLastMonth -> stringResource(R.string.spending_so_far, groupDigits(v.spent, myanmarDigits)) to colors.muted
        PaceVerdict.NothingYet -> stringResource(R.string.spending_nothing_yet) to colors.muted
    }
}

@Composable
private fun TotalPanel(pace: MonthPace, lastMonthName: String, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    val (verdict, verdictColor) = verdictText(pace, lastMonthName, myanmarDigits)
    val spoken = buildString {
        append(stringResource(R.string.spending_chart_spoken, monthName(pace.month), groupDigits(pace.spent)))
        pace.lastBySameDay?.let { append(' ').append(stringResource(R.string.spending_chart_compare_spoken, lastMonthName, groupDigits(it))) }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(verdict, style = type.size14.medium, color = verdictColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PaceChart(
            pace = pace,
            label = formatKyat(pace.spent, myanmarDigits),
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(104.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        )
        AxisLabels(
            start = localizedDate(pace.month.atDay(1), "MMMd", myanmarDigits),
            today = stringResource(R.string.transactions_today),
            end = localizedDate(pace.month.atEndOfMonth(), "MMMd", myanmarDigits),
            todayFraction = pace.today.dayOfMonth.toFloat() / pace.month.lengthOfMonth(),
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clearAndSetSemantics { },
        )
    }
}

/**
 * Where the chart's points sit, in pixels: day d of the month (0 is the start of the 1st) at d / days of the width, an
 * amount at its share of [max] between [bottom] and [top].
 */
internal class PaceGeometry(private val days: Int, private val max: Long, private val width: Float, private val top: Float, private val bottom: Float) {
    fun x(day: Int): Float = day * width / days

    fun y(amount: Long): Float = if (max <= 0) bottom else bottom - (amount.toFloat() / max) * (bottom - top)
}

@Composable
private fun PaceChart(pace: MonthPace, label: String, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val density = LocalDensity.current
    val days = pace.month.lengthOfMonth()
    Layout(
        modifier = modifier,
        content = {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val g = PaceGeometry(days, pace.chartMax, size.width, top = 5.dp.toPx(), bottom = size.height - 1.dp.toPx())
                drawLine(colors.hairline, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
                fun line(values: List<Long>) = Path().apply {
                    moveTo(g.x(0), g.y(0))
                    values.forEachIndexed { i, v -> lineTo(g.x(i + 1), g.y(v)) }
                }
                if (pace.hasLastMonth) {
                    drawPath(
                        path = line(pace.lastRunning.take(days)),
                        color = colors.chartLastMonth,
                        style = Stroke(
                            width = 1.75.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        ),
                    )
                }
                drawPath(line(pace.running), colors.coral, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val dot = Offset(g.x(pace.running.size), g.y(pace.spent))
                // A 2 dp ring in the card's color keeps the dot apart from the lines it sits on.
                drawCircle(colors.surface, radius = 6.dp.toPx(), center = dot)
                drawCircle(colors.coral, radius = 4.dp.toPx(), center = dot)
            }
            Text(
                text = label,
                style = BudgetWiseTheme.type.size12.semibold.tabular,
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val canvas = measurables[0].measure(constraints)
        val text = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val g = with(density) { PaceGeometry(days, pace.chartMax, width.toFloat(), 5.dp.toPx(), height - 1.dp.toPx()) }
        val dotX = g.x(pace.running.size)
        val dotY = g.y(pace.spent)
        val gap = with(density) { 8.dp.toPx() }
        val drop = with(density) { 4.dp.toPx() }
        // To the dot's lower right, as on the board; flipped left near the end of the month, above it near the bottom.
        var x = dotX + gap
        if (x + text.width > width) x = dotX - gap - text.width
        var y = dotY + drop
        if (y + text.height > height) y = dotY - drop - text.height
        layout(width, height) {
            canvas.place(0, 0)
            text.place(x.roundToInt().coerceIn(0, (width - text.width).coerceAtLeast(0)), y.roundToInt().coerceAtLeast(0))
        }
    }
}

/** The 1st, Today and the month's end. A label Today would overlap is left out. */
@Composable
private fun AxisLabels(start: String, today: String, end: String, todayFraction: Float, modifier: Modifier = Modifier) {
    val style = BudgetWiseTheme.type.size12
    val muted = BudgetWiseTheme.colors.muted
    val density = LocalDensity.current
    Layout(
        modifier = modifier,
        content = {
            Text(start, style = style, color = muted, maxLines = 1, softWrap = false)
            Text(today, style = style, color = muted, maxLines = 1, softWrap = false)
            Text(end, style = style, color = muted, maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val (s, t, e) = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val gap = with(density) { 8.dp.toPx() }.roundToInt()
        val todayX = (todayFraction * width - t.width / 2f).roundToInt().coerceIn(0, (width - t.width).coerceAtLeast(0))
        val showStart = todayX >= s.width + gap
        val showEnd = todayX + t.width + gap <= width - e.width
        layout(width, maxOf(s.height, t.height, e.height)) {
            if (showStart) s.place(0, 0)
            t.place(todayX, 0)
            if (showEnd) e.place(width - e.width, 0)
        }
    }
}

@Composable
private fun CategoryPanel(
    categories: CategoryBreakdown,
    compare: Boolean,
    lastMonthName: String,
    myanmarDigits: Boolean,
    otherOpen: Boolean,
    onOtherToggle: () -> Unit,
    interactive: Boolean,
) {
    val colors = BudgetWiseTheme.colors
    if (categories.top.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(PanelHeight), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.spending_nothing_yet), style = BudgetWiseTheme.type.size14, color = colors.muted)
        }
        return
    }
    val scale = categories.scale
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        categories.top.forEach { CategoryLine(it, scale, compare, lastMonthName, myanmarDigits) }
        val other = categories.other ?: return@Column
        val otherSpoken = stringResource(R.string.spending_other_spoken, categories.rest.size, groupDigits(other.spent))
        val expanded = stringResource(R.string.spending_other_expanded)
        val collapsed = stringResource(R.string.spending_other_collapsed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .clickable(enabled = interactive, role = Role.Button, onClick = onOtherToggle)
                .clearAndSetSemantics {
                    contentDescription = otherSpoken
                    stateDescription = if (otherOpen) expanded else collapsed
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.otherCategories))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spending_other), style = BudgetWiseTheme.type.size13, color = colors.muted, maxLines = 1)
                val turn by animateFloatAsState(if (otherOpen) 180f else 0f, label = "chevron")
                Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp).rotate(turn))
            }
            AmountCell(groupDigits(other.spent, myanmarDigits))
            if (compare) ChangeCell(other.change, myanmarDigits)
        }
        if (otherOpen) categories.rest.forEach { CategoryLine(it, scale, compare, lastMonthName, myanmarDigits) }
    }
}

@Composable
private fun CategoryLine(line: CategoryPace, scale: Long, compare: Boolean, lastMonthName: String, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val name = categoryLabel(line.category)
    val spoken = if (compare) {
        stringResource(R.string.spending_category_spoken, name, groupDigits(line.spent), groupDigits(line.lastBySameDay), lastMonthName)
    } else {
        stringResource(R.string.spending_category_alone_spoken, name, groupDigits(line.spent))
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColors(line.category).content))
        Text(
            text = name,
            style = BudgetWiseTheme.type.size13.forContent(name),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp),
        )
        Bar(
            fill = line.spent.toFloat() / scale,
            tick = if (compare) line.lastBySameDay.toFloat() / scale else null,
            modifier = Modifier.weight(1f),
        )
        AmountCell(groupDigits(line.spent, myanmarDigits))
        if (compare) ChangeCell(line.change, myanmarDigits)
    }
}

/** This month's share on the shared scale in coral, with last month's (by the same day) as a tick. */
@Composable
private fun Bar(fill: Float, tick: Float?, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    BoxWithConstraints(modifier.height(12.dp), contentAlignment = Alignment.CenterStart) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.raised))
        Box(Modifier.width(width * fill.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.coral))
        if (tick != null) {
            val at: Dp = width * tick.coerceIn(0f, 1f) - 1.dp
            // A 1.5 dp ring in the card's color around the 2 x 12 tick, so it reads over the coral.
            Box(
                Modifier
                    .offset(x = at - 1.5.dp)
                    .size(width = 5.dp, height = 15.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 2.dp, height = 12.dp).clip(RoundedCornerShape(1.dp)).background(colors.chartTick))
            }
        }
    }
}

@Composable
private fun AmountCell(text: String) {
    Text(
        text = text,
        style = BudgetWiseTheme.type.size13.semibold.tabular,
        color = BudgetWiseTheme.colors.ink,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 46.dp),
    )
}

/** "+2,300" in coral when more than last month, "−4,000" in sage when less. */
@Composable
private fun ChangeCell(change: Long, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    Text(
        text = signedDigits(change, myanmarDigits),
        style = BudgetWiseTheme.type.size12.tabular,
        color = when {
            change > 0 -> colors.coralText
            change < 0 -> colors.accent
            else -> colors.muted
        },
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}
