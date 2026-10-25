package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * iOS `MarkdownBlocksView`: a reply's blocks, spaced per `MarkdownStyle`. Skeleton until the
 * renderer port lands. [rowID] keys per-row state (link previews, session links); [isGrowing]
 * is the running reply's last text row (streamed text holds its lines).
 */
@Composable
fun MarkdownBlocksView(
    blocks: List<OpenCodeMarkdownBlock>,
    modifier: Modifier = Modifier,
    rowID: String? = null,
    isGrowing: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (block in blocks) {
            val text = (block.kind as? OpenCodeMarkdownBlock.Kind.Paragraph)?.text?.text ?: block.kind.toString()
            Text(text, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.defaultBodyText)
        }
    }
}
