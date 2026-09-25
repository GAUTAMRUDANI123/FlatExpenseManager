package com.flatexpense.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Chart palette and marks.
 *
 * The colours below are pinned rather than taken from MaterialTheme because
 * this app runs with dynamic colour: on Android 12+ the scheme is derived from
 * the user's wallpaper, so a chart drawn in theme colours would land on an
 * unknown surface and the contrast and colour-blind separation could not be
 * guaranteed for anybody. Pinning both the series colours *and* the surface
 * they sit on is what makes the validated figures below mean something.
 *
 * Validated with the data-viz palette validator, all checks passing in both
 * modes against these surfaces:
 *
 *   light  #2a78d6 / #eb6834 on #fcfcfb — CVD ΔE 24.7, normal ΔE 33.6, ≥3:1
 *   dark   #3987e5 / #d95926 on #1a1a19 — CVD ΔE 26.8, normal ΔE 31.8, ≥3:1
 *
 * Dark is a selected set stepped for the dark surface, not an automatic flip
 * of the light one.
 */
object ChartColors {

    // Surfaces the palette was validated against.
    private val surfaceLight = Color(0xFFFCFCFB)
    private val surfaceDark = Color(0xFF1A1A19)

    // Categorical slots 1 and 2. Used for identity (spent vs received).
    private val series1Light = Color(0xFF2A78D6)
    private val series1Dark = Color(0xFF3987E5)
    private val series2Light = Color(0xFFEB6834)
    private val series2Dark = Color(0xFFD95926)

    private val inkLight = Color(0xFF0B0B0B)
    private val inkDark = Color(0xFFFFFFFF)
    private val secondaryLight = Color(0xFF52514E)
    private val secondaryDark = Color(0xFFC3C2B7)
    private val muted = Color(0xFF898781)
    private val gridLight = Color(0xFFE1E0D9)
    private val gridDark = Color(0xFF2C2C2A)
    private val baselineLight = Color(0xFFC3C2B7)
    private val baselineDark = Color(0xFF383835)

    @Composable fun surface(): Color = if (isSystemInDarkTheme()) surfaceDark else surfaceLight
    @Composable fun series1(): Color = if (isSystemInDarkTheme()) series1Dark else series1Light
    @Composable fun series2(): Color = if (isSystemInDarkTheme()) series2Dark else series2Light
    @Composable fun ink(): Color = if (isSystemInDarkTheme()) inkDark else inkLight
    @Composable fun secondary(): Color = if (isSystemInDarkTheme()) secondaryDark else secondaryLight
    @Composable fun muted(): Color = muted
    @Composable fun grid(): Color = if (isSystemInDarkTheme()) gridDark else gridLight
    @Composable fun baseline(): Color = if (isSystemInDarkTheme()) baselineDark else baselineLight
}

/**
 * A card that pins its own background to the surface the palette was validated
 * against, so a wallpaper-derived theme cannot silently change the contrast the
 * chart was checked for.
 */
@Composable
fun ChartCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChartColors.surface())
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = ChartColors.ink()
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(fontSize = 12.sp),
                    color = ChartColors.secondary()
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** A coloured chip plus a text label — identity is never carried by colour alone. */
@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp),
            color = ChartColors.secondary()
        )
    }
}

/**
 * Spending by category, as horizontal bars sorted high to low.
 *
 * Deliberately not a pie. The job here is comparing magnitudes, which length
 * does far better than angle, and this flat has twelve categories — well past
 * the point where a slice palette stops being distinguishable at all, let
 * alone for a colour-blind reader. One hue encodes magnitude; the share is
 * stated as a number beside each row rather than left to be judged by eye.
 */
@Composable
fun CategoryBars(
    rows: List<Triple<String, String, Double>>,
    modifier: Modifier = Modifier
) {
    val total = rows.sumOf { it.third }
    if (rows.isEmpty() || total <= 0.0) {
        Text(
            text = "Nothing approved this month yet.",
            style = TextStyle(fontSize = 13.sp),
            color = ChartColors.secondary()
        )
        return
    }

    val max = rows.maxOf { it.third }
    val fill = ChartColors.series1()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (name, amount, value) ->
            val share = value / total * 100.0
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = name,
                        style = TextStyle(fontSize = 13.sp),
                        color = ChartColors.ink()
                    )
                    Text(
                        // Direct label: the value, plus the share the bar length encodes.
                        text = "${formatMoney(amount)}  ·  ${share.toInt()}%",
                        style = TextStyle(fontSize = 12.sp),
                        color = ChartColors.secondary()
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ChartColors.grid())
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((value / max).toFloat().coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(fill)
                    )
                }
            }
        }
    }
}

/** Shared y-axis scale: a rounded ceiling so gridlines land on readable numbers. */
private fun niceCeiling(max: Double): Double {
    if (max <= 0.0) return 100.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(max)))
    val normalised = max / magnitude
    val step = when {
        normalised <= 1.0 -> 1.0
        normalised <= 2.0 -> 2.0
        normalised <= 5.0 -> 5.0
        else -> 10.0
    }
    return step * magnitude
}

private fun shortMoney(value: Double): String = when {
    value >= 10_000_000 -> "${(value / 10_000_000).format1()}Cr"
    value >= 100_000 -> "${(value / 100_000).format1()}L"
    value >= 1_000 -> "${(value / 1_000).format1()}k"
    else -> value.toInt().toString()
}

private fun Double.format1(): String {
    val rounded = Math.round(this * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
    else String.format(java.util.Locale.US, "%.1f", rounded)
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    centreX: Float,
    topY: Float,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit = 10.sp
): TextLayoutResult {
    val laid = measurer.measure(text, TextStyle(fontSize = size, color = color))
    drawText(laid, topLeft = Offset(centreX - laid.size.width / 2f, topY))
    return laid
}

/**
 * Month-to-month approved spending, as columns.
 *
 * Ends are rounded and anchored to the baseline, and a 2px gap separates
 * neighbouring bars so adjacent months never read as one block.
 */
@Composable
fun MonthlyColumns(
    months: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp
) {
    if (months.isEmpty()) {
        Text("No data yet.", style = TextStyle(fontSize = 13.sp), color = ChartColors.secondary())
        return
    }

    val measurer = rememberTextMeasurer()
    val fill = ChartColors.series1()
    val grid = ChartColors.grid()
    val baselineColor = ChartColors.baseline()
    val mutedInk = ChartColors.muted()
    val ceiling = niceCeiling(months.maxOf { it.second })

    Canvas(modifier.fillMaxWidth().height(height)) {
        val labelBand = 18.dp.toPx()
        val valueBand = 14.dp.toPx()
        val plotTop = valueBand
        val plotBottom = size.height - labelBand
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        // Recessive gridlines at quarters of the scale.
        for (i in 0..4) {
            val y = plotTop + plotHeight * (i / 4f)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawLine(
            baselineColor,
            Offset(0f, plotBottom),
            Offset(size.width, plotBottom),
            strokeWidth = 2f
        )

        val gap = 2.dp.toPx()
        val slot = size.width / months.size
        val barWidth = (slot - gap * 2).coerceAtLeast(3f)
        val radius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())

        months.forEachIndexed { index, (label, value) ->
            val frac = (value / ceiling).toFloat().coerceIn(0f, 1f)
            val barHeight = plotHeight * frac
            val left = slot * index + (slot - barWidth) / 2f

            if (barHeight > 0.5f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, plotBottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius
                )
                drawLabel(
                    measurer, shortMoney(value),
                    left + barWidth / 2f, plotBottom - barHeight - valueBand, mutedInk
                )
            }
            drawLabel(
                measurer, label.takeLast(2).let { monthAbbrev(it) },
                left + barWidth / 2f, plotBottom + 4f, mutedInk
            )
        }
    }
}

private fun monthAbbrev(mm: String): String = when (mm) {
    "01" -> "Jan"; "02" -> "Feb"; "03" -> "Mar"; "04" -> "Apr"
    "05" -> "May"; "06" -> "Jun"; "07" -> "Jul"; "08" -> "Aug"
    "09" -> "Sep"; "10" -> "Oct"; "11" -> "Nov"; "12" -> "Dec"
    else -> mm
}

/**
 * Spending against contributions received, over the same months.
 *
 * Both series are rupee amounts, so they share one axis — two scales on one
 * chart would let the crossing point imply a relationship that the numbers do
 * not support. Two series means a legend is always present.
 */
@Composable
fun TrendLines(
    months: List<Triple<String, Double, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp
) {
    if (months.size < 2) {
        Text(
            "Not enough months to show a trend yet.",
            style = TextStyle(fontSize = 13.sp),
            color = ChartColors.secondary()
        )
        return
    }

    val measurer = rememberTextMeasurer()
    val spentColor = ChartColors.series1()
    val receivedColor = ChartColors.series2()
    val grid = ChartColors.grid()
    val baselineColor = ChartColors.baseline()
    val mutedInk = ChartColors.muted()
    val surface = ChartColors.surface()
    val ceiling = niceCeiling(maxOf(months.maxOf { it.second }, months.maxOf { it.third }))

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(spentColor, "Spent")
            LegendItem(receivedColor, "Received")
        }
        Spacer(Modifier.height(10.dp))

        Canvas(Modifier.fillMaxWidth().height(height)) {
            val labelBand = 18.dp.toPx()
            val plotTop = 8.dp.toPx()
            val plotBottom = size.height - labelBand
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val stepX = size.width / (months.size - 1).coerceAtLeast(1)

            for (i in 0..4) {
                val y = plotTop + plotHeight * (i / 4f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            drawLine(
                baselineColor,
                Offset(0f, plotBottom),
                Offset(size.width, plotBottom),
                strokeWidth = 2f
            )

            fun yOf(value: Double): Float =
                plotBottom - plotHeight * (value / ceiling).toFloat().coerceIn(0f, 1f)

            fun series(values: List<Double>, color: Color) {
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = stepX * i
                    val y = yOf(v)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))

                // A surface-coloured ring keeps markers legible where the two
                // series cross.
                values.forEachIndexed { i, v ->
                    val centre = Offset(stepX * i, yOf(v))
                    drawCircle(surface, radius = 5.dp.toPx(), center = centre)
                    drawCircle(color, radius = 3.5.dp.toPx(), center = centre)
                }
            }

            series(months.map { it.second }, spentColor)
            series(months.map { it.third }, receivedColor)

            months.forEachIndexed { i, (label, _, _) ->
                // Only the ends and the middle are labelled; a label on every
                // point is noise at this width.
                if (i == 0 || i == months.size - 1 || i == months.size / 2) {
                    drawLabel(
                        measurer, monthAbbrev(label.takeLast(2)),
                        (stepX * i).coerceIn(14.dp.toPx(), size.width - 14.dp.toPx()),
                        plotBottom + 4f, mutedInk
                    )
                }
            }
        }
    }
}
