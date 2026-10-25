package ai.opencode.feature.media

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.model.AgentFileReference
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * STUB (images helper): iOS `AgentFileCards` (Composer/FileAttachmentViews.swift, with
 * `FileAttachmentCard`). The gallery shows file cards between previews; composer sits above
 * media, so the lead decides where the real port lives. Minimal, behaviour-faithful version:
 * a horizontal row of 260 dp (one) / 220 dp (several) cards that open the file.
 */
@Composable
internal fun AgentFileCards(files: List<AgentFileReference>, onOpen: (AgentFileReference) -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
    ) {
        for (file in files) {
            Row(
                Modifier
                    .width(if (files.size == 1) 260.dp else 220.dp)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(colors.userBubble, shape)
                    .mediaZoomSource(file.zoomSourceID, TranscriptMetrics.cardCornerRadius)
                    .clickable(role = Role.Button) { onOpen(file) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Open ${file.name}"
                        stateDescription = file.typeLabel
                    }
                    .testTag("agent-file-card")
                    .padding(horizontal = TranscriptMetrics.standard, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(file.symbolName, contentDescription = null, size = 22.dp, tint = colors.secondaryText, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        file.name,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(file.typeLabel, style = OpenCodeTextStyles.caption1, color = colors.secondaryText, maxLines = 1)
                }
                OpenCodeIcon(
                    if (file.kind.isTimeBased) "play.fill" else "chevron.right",
                    contentDescription = null,
                    size = 12.dp,
                    tint = colors.tertiaryText,
                )
            }
        }
    }
}
