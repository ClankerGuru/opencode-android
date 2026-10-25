package ai.opencode.feature.composer

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.FileAttachmentCard
import ai.opencode.feature.media.FileAttachmentDetail
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.PastedTextSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * The transcript metrics these chips share (iOS `TranscriptMetrics`). `FileAttachmentCard`,
 * `FileAttachmentDetail` and `AgentFileCards` live in `:feature:media`, which the transcript
 * and its image gallery use too.
 */
object FileAttachmentMetrics {
    val cardCornerRadius: Dp = 14.dp
    val clustered: Dp = 8.dp
    val standard: Dp = 12.dp
}

/** iOS `OpenCodeTranscriptFile.typeLabel`. */
val OpenCodeTranscriptFile.typeLabel: String get() = AttachmentFileType.label(name, mime)

/** iOS `OpenCodeTranscriptFile.symbolName`. */
val OpenCodeTranscriptFile.symbolName: String get() = AttachmentFileType.symbolName(name, mime)

/**
 * Sent files on a user message, trailing like the message's images (iOS `TranscriptFileChips`).
 * An uploaded file opens in the viewer from the server's copy ([onOpen] gets its path).
 *
 * @param pastedTextCard draws a Pasted Text file (`PastedTextCard`, `PastedTextViews.kt`).
 * @param trailingShape the trailing card's shape, tight where it meets another surface of its
 *   message's run (`UserSurfaceCorners`, transcript area).
 */
@Composable
fun TranscriptFileChips(
    files: List<OpenCodeTranscriptFile>,
    modifier: Modifier = Modifier,
    onOpen: ((path: String) -> Unit)? = null,
    trailingShape: Shape = RoundedCornerShape(FileAttachmentMetrics.cardCornerRadius),
    pastedTextCard: (@Composable (file: OpenCodeTranscriptFile, text: String, summary: PastedTextSummary, shape: Shape) -> Unit)? = null,
) {
    val round = RoundedCornerShape(FileAttachmentMetrics.cardCornerRadius)
    // Anchored trailing: reversed layout keeps the last card at the end.
    LazyRow(
        modifier.fillMaxWidth(),
        reverseLayout = true,
        horizontalArrangement = Arrangement.spacedBy(FileAttachmentMetrics.clustered, Alignment.End),
    ) {
        items(files.asReversed(), key = { it.id }) { file ->
            val shape = if (file.id == files.lastOrNull()?.id) trailingShape else round
            val text = file.text
            val summary = file.pasted
            if (text != null && summary != null && pastedTextCard != null) {
                pastedTextCard(file, text, summary, shape)
            } else {
                TranscriptFileChip(file, shape, onOpen)
            }
        }
    }
}

@Composable
private fun TranscriptFileChip(file: OpenCodeTranscriptFile, shape: Shape, onOpen: ((String) -> Unit)?) {
    val path = file.path
    val opens = onOpen != null && path != null
    FileAttachmentCard(
        name = file.name,
        symbolName = file.symbolName,
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (opens) Modifier.clickable(role = Role.Button) { onOpen!!(path!!) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = "Attached file ${file.name}"
                stateDescription = file.typeLabel
                if (opens) {
                    role = Role.Button
                    onClick(label = "Opens the file") { onOpen!!(path!!); true }
                }
            },
    ) { FileAttachmentDetail(file.typeLabel) }
}

/** A composer file: name, size, and upload progress, or a retry when the upload failed (iOS `ComposerFileChip`). */
object ComposerFileChip {
    val height: Dp = 76.dp
    val width: Dp = 220.dp
    val cornerRadius: Dp = 22.dp

    fun subtitle(file: ComposerFileAttachment): String = AttachmentFileType.subtitle(file.name, file.mime, file.size)

    /** “42%”, in the user's locale. */
    fun percent(value: Double): String =
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }.format(value)

    fun statusDescription(file: ComposerFileAttachment): String = when (val status = file.status) {
        is ComposerFileAttachment.Status.Uploading -> "Uploading, ${percent(file.progress)}"
        is ComposerFileAttachment.Status.Uploaded -> subtitle(file)
        is ComposerFileAttachment.Status.Failed -> status.message
    }
}

@Composable
fun ComposerFileChip(file: ComposerFileAttachment, canRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val retries = file.isFailed && canRetry
    FileAttachmentCard(
        name = file.name,
        symbolName = AttachmentFileType.symbolName(file.name, file.mime),
        width = ComposerFileChip.width,
        height = ComposerFileChip.height,
        detailLines = if (file.isFailed) 2 else 1,
        modifier = modifier
            .clip(RoundedCornerShape(ComposerFileChip.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (retries) Modifier.clickable(role = Role.Button, onClick = onRetry) else Modifier)
            .clearAndSetSemantics {
                contentDescription = if (retries) "Retry uploading ${file.name}" else "File attachment ${file.name}"
                stateDescription = ComposerFileChip.statusDescription(file)
                testTag = "pending-file-${file.name}"
                if (retries) {
                    role = Role.Button
                    onClick { onRetry(); true }
                }
            },
    ) {
        when (val status = file.status) {
            is ComposerFileAttachment.Status.Uploading -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FileAttachmentDetail("Uploading ${ComposerFileChip.percent(file.progress)}")
                LinearProgressIndicator(
                    progress = { file.progress.toFloat() },
                    modifier = Modifier.widthIn(max = 120.dp).fillMaxWidth(),
                )
            }
            is ComposerFileAttachment.Status.Uploaded -> FileAttachmentDetail(ComposerFileChip.subtitle(file))
            is ComposerFileAttachment.Status.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                OpenCodeIcon(
                    if (canRetry) SFSymbol.ArrowClockwise else SFSymbol.ExclamationmarkTriangle,
                    contentDescription = null,
                    size = 14.dp,
                    tint = OpenCodeTheme.colors.destructive,
                )
                FileAttachmentDetail(status.message, color = OpenCodeTheme.colors.destructive)
            }
        }
    }
}
