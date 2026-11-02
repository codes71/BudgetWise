package app.budgetwise.ui.transactions

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.budgetwise.ui.common.groupDigits
import app.budgetwise.ui.common.localizedDate
import app.budgetwise.ui.common.monthName
import app.budgetwise.ui.common.signedDigits
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.forContent
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import kotlin.math.roundToInt

private enum class SpendingView { TOTAL, BY_CATEGORY }

/**
 * The month's spending under "Left in October", straight on the ground: what was spent so far and the Total / By
 * category switch, then the chosen view. Total: this month's running total from the 1st to today against last month's
 * for the same days (dashed). By category: the five largest categories so far, each against last month by the same
 * day (the tick). The block grows or shrinks with the view.
 */
@Composable
fun SpendingCard(
    pace: MonthPace,
    categories: CategoryBreakdown,
    myanmarDigits: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    var view by rememberSaveable { mutableStateOf(SpendingView.TOTAL) }
    var otherOpen by rememberSaveable { mutableStateOf(false) }
    val lastMonthName = monthName(pace.lastMonth)
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.spending_spent, groupDigits(pace.spent, myanmarDigits)),
                style = type.size16.semibold,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
        }
        if (view == SpendingView.TOTAL) {
            TotalPanel(pace, lastMonthName, myanmarDigits)
        } else {
            CategoryPanel(
                categories = categories,
                compare = pace.hasLastMonth,
                lastMonthName = lastMonthName,
                myanmarDigits = myanmarDigits,
                otherOpen = otherOpen,
                onOtherToggle = { otherOpen = !otherOpen },
            )
        }
    }
}

@Composable
private fun ViewTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.sageContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onSelect)
            .padding(horizontal = if (type.burmese) 10.dp else 12.dp, vertical = if (type.burmese) 2.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (selected) type.size12.semibold else type.size12.medium,
            color = if (selected) colors.onSageContainer else colors.muted,
            maxLines = 1,
        )
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
    val type = BudgetWiseTheme.type
    val (verdict, verdictColor) = verdictText(pace, lastMonthName, myanmarDigits)
    val spoken = buildString {
        append(stringResource(R.string.spending_chart_spoken, monthName(pace.month), groupDigits(pace.spent)))
        pace.lastBySameDay?.let { append(' ').append(stringResource(R.string.spending_chart_compare_spoken, lastMonthName, groupDigits(it))) }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = verdict,
            style = type.size14.medium,
            color = verdictColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        PaceChart(
            pace = pace,
            modifier = Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .height(112.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        )
        AxisLabels(
            start = localizedDate(pace.month.atDay(1), "MMMd", myanmarDigits),
            today = stringResource(R.string.transactions_today),
            end = localizedDate(pace.month.atEndOfMonth(), "MMMd", myanmarDigits),
            todayFraction = pace.today.dayOfMonth.toFloat() / pace.month.lengthOfMonth(),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clearAndSetSemantics { },
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

/** The two lines and today's dot. No labels on the chart: the amount spent heads the block, so nothing sits on a line. */
@Composable
private fun PaceChart(pace: MonthPace, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val days = pace.month.lengthOfMonth()
    Canvas(modifier) {
        val g = PaceGeometry(days, pace.chartMax, size.width, top = 6.dp.toPx(), bottom = size.height - 1.dp.toPx())
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
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
        drawPath(line(pace.running), colors.coral, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val dot = Offset(g.x(pace.running.size), g.y(pace.spent))
        // A 2 dp ring in the ground's color keeps the dot apart from the lines it sits on.
        drawCircle(colors.ground, radius = 6.dp.toPx(), center = dot)
        drawCircle(colors.coral, radius = 4.dp.toPx(), center = dot)
    }
}

/** The 1st, Today (in [todayColor]) and the month's end. A label Today would overlap is left out. */
@Composable
internal fun AxisLabels(
    start: String,
    today: String,
    end: String,
    todayFraction: Float,
    modifier: Modifier = Modifier,
    todayColor: Color = BudgetWiseTheme.colors.ink,
) {
    val type = BudgetWiseTheme.type
    val colors = BudgetWiseTheme.colors
    val density = LocalDensity.current
    Layout(
        modifier = modifier,
        content = {
            Text(start, style = type.size12, color = colors.muted, maxLines = 1, softWrap = false)
            Text(today, style = type.size12.semibold, color = todayColor, maxLines = 1, softWrap = false)
            Text(end, style = type.size12, color = colors.muted, maxLines = 1, softWrap = false)
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
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    if (categories.top.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.spending_nothing_yet), style = type.size14, color = colors.muted)
        }
        return
    }
    if (compare) {
        Text(
            text = stringResource(R.string.spending_tick_caption, lastMonthName),
            style = type.size13,
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
        )
    }
    val scale = categories.scale
    Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        categories.top.forEach { CategoryLine(it, scale, compare, lastMonthName, myanmarDigits) }
        val other = categories.other ?: return@Column
        val otherSpoken = stringResource(R.string.spending_other_spoken, categories.rest.size, groupDigits(other.spent))
        val expanded = stringResource(R.string.spending_other_expanded)
        val collapsed = stringResource(R.string.spending_other_collapsed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onOtherToggle)
                .clearAndSetSemantics {
                    contentDescription = otherSpoken
                    stateDescription = if (otherOpen) expanded else collapsed
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.otherCategories))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spending_other), style = type.size15.medium, color = colors.muted, maxLines = 1)
                val turn by animateFloatAsState(if (otherOpen) 180f else 0f, label = "chevron")
                Icon(Lucide.ChevronDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp).rotate(turn))
            }
            AmountCell(groupDigits(other.spent, myanmarDigits), colors.muted)
            if (compare) ChangeCell(other.change, myanmarDigits)
        }
        if (otherOpen) categories.rest.forEach { CategoryLine(it, scale, compare, lastMonthName, myanmarDigits) }
    }
}

/** One category: its dot, name, amount and change from last month, with its bar under them in its own color. */
@Composable
private fun CategoryLine(line: CategoryPace, scale: Long, compare: Boolean, lastMonthName: String, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    val name = categoryLabel(line.category)
    val tone = categoryColors(line.category).content
    val spoken = if (compare) {
        stringResource(R.string.spending_category_spoken, name, groupDigits(line.spent), groupDigits(line.lastBySameDay), lastMonthName)
    } else {
        stringResource(R.string.spending_category_alone_spoken, name, groupDigits(line.spent))
    }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Text(
                text = name,
                style = BudgetWiseTheme.type.size15.medium.forContent(name),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AmountCell(groupDigits(line.spent, myanmarDigits), colors.ink)
            if (compare) ChangeCell(line.change, myanmarDigits)
        }
        Bar(
            fill = line.spent.toFloat() / scale,
            color = tone,
            tick = if (compare) line.lastBySameDay.toFloat() / scale else null,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
    }
}

/** This month's share on the shared scale in the category's color, with last month's (by the same day) as a tick. */
@Composable
private fun Bar(fill: Float, color: Color, tick: Float?, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    BoxWithConstraints(modifier.height(14.dp), contentAlignment = Alignment.CenterStart) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.raised))
        Box(Modifier.width(width * fill.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        if (tick != null) {
            val at: Dp = width * tick.coerceIn(0f, 1f) - 1.dp
            // A 1.5 dp ring in the ground's color around the 2 x 14 tick, so it reads over the bar.
            Box(
                Modifier
                    .offset(x = at - 1.5.dp)
                    .size(width = 5.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(colors.ground),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 2.dp, height = 14.dp).clip(RoundedCornerShape(1.dp)).background(colors.chartTick))
            }
        }
    }
}

@Composable
private fun AmountCell(text: String, color: Color) {
    Text(
        text = text,
        style = BudgetWiseTheme.type.size16.semibold,
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 56.dp),
    )
}

/** "+2,300" in coral when more than last month, "−4,000" in sage when less. */
@Composable
private fun ChangeCell(change: Long, myanmarDigits: Boolean) {
    val colors = BudgetWiseTheme.colors
    Text(
        text = signedDigits(change, myanmarDigits),
        style = BudgetWiseTheme.type.size13,
        color = when {
            change > 0 -> colors.coralText
            change < 0 -> colors.accent
            else -> colors.muted
        },
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 60.dp),
    )
}
