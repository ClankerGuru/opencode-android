package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.markdown.rememberMarkdownBlocks
import ai.opencode.feature.transcript.markdown.ui.MarkdownBlocksView
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS `TranscriptEntryView`: every non-user row (assistant Markdown, tools and Used N groups,
 * compaction, notices, model switches, run errors, time stamps, reply end). Skeleton until the
 * rows port lands. [rowID] is the row's stable identity (keys a group's disclosure).
 */
@Composable
fun TranscriptEntryView(
    entry: TranscriptEntry,
    rowID: String,
    props: RowProps,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is TranscriptEntry.Single -> when (val item = entry.item) {
            is OpenCodeTranscriptItem.AssistantMarkdown -> {
                val blocks = rememberMarkdownBlocks(item.id, item.text)
                MarkdownBlocksView(blocks, modifier, rowID = rowID, isGrowing = props.isGrowing)
            }
            else -> Text(item.id, modifier, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        }
        else -> Text(entry.layoutKind, modifier, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
    }
}
