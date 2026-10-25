package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** iOS `.quaternary` on a hairline: the separator colour at its quietest. */
@Composable
internal fun quaternaryLine(): Color = OpenCodeTheme.colors.border

/**
 * A centred label between two hairlines that share what's left of the width: the label takes
 * what it needs (iOS `layoutPriority(1)`), leaving each line at least [minLine].
 */
@Composable
internal fun RuleRow(
    lineColor: Color,
    modifier: Modifier = Modifier,
    minLine: Dp = 0.dp,
    label: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.background(lineColor))
            Box { label() }
            Box(Modifier.background(lineColor))
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val spacing = TranscriptMetrics.standard.roundToPx()
        val line = 1.dp.roundToPx().coerceAtLeast(1)
        val labelMax = (width - 2 * spacing - 2 * minLine.roundToPx()).coerceAtLeast(0)
        val labelPlaceable = measurables[1].measure(Constraints(maxWidth = labelMax))
        val lineWidth = ((width - 2 * spacing - labelPlaceable.width) / 2).coerceAtLeast(0)
        val left = measurables[0].measure(Constraints.fixed(lineWidth, line))
        val right = measurables[2].measure(Constraints.fixed(lineWidth, line))
        val height = maxOf(labelPlaceable.height, line)
        layout(width, height) {
            left.place(0, (height - line) / 2)
            labelPlaceable.place(lineWidth + spacing, (height - labelPlaceable.height) / 2)
            right.place(width - lineWidth, (height - line) / 2)
        }
    }
}

/** A labelled horizontal rule, like the TUI's compaction marker (iOS `TranscriptRule`). */
@Composable
fun TranscriptRule(
    modifier: Modifier = Modifier,
    lineColor: Color = quaternaryLine(),
    labelColor: Color,
    label: @Composable RowScope.() -> Unit,
) {
    RuleRow(lineColor, modifier) {
        CompositionLocalProvider(LocalContentColor provides labelColor, LocalTextStyle provides OpenCodeTextStyles.caption1.copy(color = labelColor)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = label)
        }
    }
}

/**
 * Where the reader left off (iOS `NewMessagesDivider`): a labelled rule like a compaction's,
 * above the first row that arrived while they were away.
 */
@Composable
fun NewMessagesDivider(modifier: Modifier = Modifier) {
    TranscriptRule(
        modifier.padding(vertical = TranscriptMetrics.sectionHalf).clearAndSetSemantics { contentDescription = "New messages" },
        labelColor = OpenCodeTheme.colors.secondaryText,
    ) {
        Text("New")
    }
}
