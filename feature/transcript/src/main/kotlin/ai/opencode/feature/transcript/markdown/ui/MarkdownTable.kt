package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.InlineText
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * A table on the full-width band (iOS `MarkdownTable`). Columns size to their content up to
 * 220 (iOS `CappedWidthLayout`: short cells keep their natural width, longer ones wrap). Rules
 * under the header and between rows run the band's full width and stay put while the columns
 * scroll.
 */
@Composable
fun MarkdownTable(header: List<InlineText>, rows: List<List<InlineText>>, modifier: Modifier = Modifier) {
    val style = currentMarkdownStyle()
    val colors = style.colors
    val density = LocalDensity.current
    val targets = LocalSessionLinks.current.targets
    val listener = rememberLinkListener()
    val rules = remember { TableRules() }
    val cellStyle = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum")
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    fun present(cell: InlineText, isHeader: Boolean) = MarkdownPresentation.present(
        cell,
        PresentationOptions(
            foreground = if (isHeader) colors.secondaryText else colors.text,
            textSize = cellStyle.fontSize,
            tonesBody = false,
        ),
        style, targets, density, listener, siteIcon = null,
    )
    val presentedHeader = remember(header, targets, colors, density) { header.map { present(it, true) } }
    val presentedRows = remember(rows, targets, colors, density) { rows.map { row -> row.map { present(it, false) } } }
    ReportsSessionLinks((presentedHeader + presentedRows.flatten()).flatMap { it.sessionIDs }.distinct())
    val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    MarkdownBand(modifier, rules = rules) {
        Layout(
            content = {
                for (row in listOf(presentedHeader) + presentedRows) {
                    for (column in 0 until columns) {
                        val cell = row.getOrNull(column)
                        val isHeader = row === presentedHeader
                        if (cell == null) {
                            Box(Modifier)
                        } else {
                            ArrivingText(cell, emptyList(), if (isHeader) headerStyle else cellStyle, if (isHeader) colors.secondaryText else colors.text)
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val cap = 220.dp.roundToPx()
            val hSpacing = TranscriptMetrics.turn.roundToPx()
            val vSpacing = TranscriptMetrics.bubbleVertical.roundToPx()
            val rule = (0.5.dp).roundToPx().coerceAtLeast(1)
            val rowCount = measurables.size / maxOf(columns, 1)
            val placeables = measurables.map { measurable ->
                val width = minOf(measurable.maxIntrinsicWidth(Constraints.Infinity), cap)
                measurable.measure(Constraints(minWidth = 0, maxWidth = width.coerceAtLeast(0)))
            }
            val widths = IntArray(columns) { column -> (0 until rowCount).maxOfOrNull { placeables[it * columns + column].width } ?: 0 }
            val heights = IntArray(rowCount) { row -> (0 until columns).maxOfOrNull { placeables[row * columns + it].height } ?: 0 }
            val tops = IntArray(rowCount)
            val ruleTops = mutableListOf<Float>()
            var y = 0
            for (row in 0 until rowCount) {
                if (row > 0) {
                    y += vSpacing
                    ruleTops += y.toFloat()
                    y += rule + vSpacing
                }
                tops[row] = y
                y += heights[row]
            }
            val totalWidth = widths.sum() + hSpacing * (columns - 1).coerceAtLeast(0)
            rules.offsets = ruleTops
            layout(totalWidth, y) {
                for (row in 0 until rowCount) {
                    var x = 0
                    for (column in 0 until columns) {
                        placeables[row * columns + column].place(x, tops[row])
                        x += widths[column] + hSpacing
                    }
                }
            }
        }
    }
}
