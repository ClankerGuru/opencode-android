package ai.opencode.feature.transcript.user

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.rowInsets
import ai.opencode.feature.transcript.rows.TranscriptEntryView
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * iOS `TranscriptMessageRow`: the one root view for message rows, so a pending message and the
 * delivered message it promotes into keep their identity. User messages draw here; every other
 * entry goes to [TranscriptEntryView]. Skeleton until the user message port lands.
 */
@Composable
fun TranscriptMessageRow(
    rowID: String,
    entry: TranscriptEntry?,
    queued: OpenCodeQueuedInput?,
    failure: String?,
    deliveryNote: String?,
    props: RowProps,
    run: UserMessageRun,
    revealArrival: Double?,
    modifier: Modifier = Modifier,
) {
    val insets = entry?.rowInsets ?: queued?.rowInsets
    var top = insets?.top ?: TranscriptMetrics.turnHalf
    var bottom = insets?.bottom ?: TranscriptMetrics.turnHalf
    if (run.joinsAbove) top = TranscriptMetrics.turnHalf
    if (run.joinsBelow) bottom = UserMessageRun.spacing - TranscriptMetrics.turnHalf
    val padded = modifier.fillMaxWidth().padding(top = top, bottom = bottom.coerceAtLeast(0.dp))
    when {
        entry is TranscriptEntry.UserGroup || (entry == null && queued != null) -> {
            val text = (entry as? TranscriptEntry.UserGroup)?.items?.filterIsInstance<OpenCodeTranscriptItem.UserText>()?.firstOrNull()?.text
                ?: queued?.text.orEmpty()
            Box(padded, contentAlignment = Alignment.CenterEnd) {
                Text(
                    text,
                    Modifier.wrapContentWidth()
                        .background(OpenCodeTheme.colors.userBubble, RoundedCornerShape(TranscriptMetrics.bubbleCornerRadius))
                        .padding(horizontal = 14.dp, vertical = TranscriptMetrics.bubbleVertical),
                    style = OpenCodeTextStyles.body,
                    color = OpenCodeTheme.colors.text,
                )
            }
        }
        entry != null -> TranscriptEntryView(entry, rowID, props, padded)
    }
}
