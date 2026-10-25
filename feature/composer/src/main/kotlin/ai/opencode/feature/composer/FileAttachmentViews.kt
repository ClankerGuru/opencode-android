package ai.opencode.feature.composer

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.PastedTextSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * The transcript metrics these cards share (iOS `TranscriptMetrics`, owned by
 * `:feature:transcript`, which this module can't see): the card corner radius and the
 * clustered / standard spacing.
 */
object FileAttachmentMetrics {
    val cardCornerRadius: Dp = 14.dp
    val clustered: Dp = 8.dp
    val standard: Dp = 12.dp
}

/**
 * The two-line file card desktop uses for attachments (session-ui `AttachmentCard`): the name
 * above a type label, here with a type glyph (iOS `FileAttachmentCard`). [symbolName] is an
 * SF Symbol name.
 */
@Composable
fun FileAttachmentCard(
    name: String,
    symbolName: String,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp? = null,
    detailLines: Int = 1,
    /** A quiet glyph after the text, such as play for media. */
    trailingSymbolName: String? = null,
    detail: @Composable () -> Unit,
) {
    Row(
        modifier
            .width(width)
            .heightIn(min = height ?: 0.dp)
            .padding(horizontal = FileAttachmentMetrics.standard, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(symbolName, contentDescription = null, size = OpenCodeIconSize.medium, tint = OpenCodeTheme.colors.secondaryText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides OpenCodeTheme.colors.secondaryText,
                androidx.compose.material3.LocalTextStyle provides OpenCodeTextStyles.caption1.copy(color = OpenCodeTheme.colors.secondaryText),
                FileAttachmentDetailLines provides detailLines,
            ) { detail() }
        }
        if (trailingSymbolName != null) {
            OpenCodeIcon(trailingSymbolName, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.tertiaryText)
        }
    }
}

/** How many lines a card's detail may take (iOS `.lineLimit(detailLines)`). */
internal val FileAttachmentDetailLines = androidx.compose.runtime.compositionLocalOf { 1 }

/** A card's detail text with the card's line limit. */
@Composable
fun FileAttachmentDetail(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = modifier,
        color = color,
        maxLines = FileAttachmentDetailLines.current,
        overflow = TextOverflow.Ellipsis,
    )
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

/**
 * A file the agent pointed at, as its card shows it (the fields of `AgentFileReference`,
 * which lives in `:feature:media`).
 */
@Immutable
data class AgentFileCardItem(
    /** The path; also the stable key. */
    val id: String,
    val name: String,
    val symbolName: String,
    val typeLabel: String,
    /** Video or audio: the card trails play rather than a chevron. */
    val isTimeBased: Boolean,
)

/**
 * Media and documents the agent pointed at, beneath the paragraph that names them (iOS
 * `AgentFileCards`). Each opens in the viewer.
 */
@Composable
fun AgentFileCards(files: List<AgentFileCardItem>, onOpen: (AgentFileCardItem) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(FileAttachmentMetrics.cardCornerRadius)
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FileAttachmentMetrics.clustered)) {
        items(files, key = { it.id }) { file ->
            FileAttachmentCard(
                name = file.name,
                symbolName = file.symbolName,
                width = if (files.size == 1) 260.dp else 220.dp,
                trailingSymbolName = if (file.isTimeBased) "play.fill" else "chevron.right",
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(role = Role.Button) { onOpen(file) }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = "Open ${file.name}"
                        stateDescription = file.typeLabel
                        testTag = "agent-file-card"
                        onClick { onOpen(file); true }
                    },
            ) { FileAttachmentDetail(file.typeLabel) }
        }
    }
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
