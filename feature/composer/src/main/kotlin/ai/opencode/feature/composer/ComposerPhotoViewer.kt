package ai.opencode.feature.composer

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.feature.media.MediaZoomSources
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * The composer's pending photos in the transcript's full-screen viewer (iOS
 * `ComposerPhotoViewer`): the same flight out of the tile and back, paging and zoom, plus each
 * photo's dimensions and size and a Remove action.
 *
 * The viewer's Remove (`TranscriptImageViewerFeature.State.allowsRemove`, `removeTapped`, the
 * `removed` delegate, `isDiscarded`) and the flight's accessories (`MediaFlightAccessory`) are
 * `:feature:media`'s; [ComposerPhotoViewerFeature] isn't ported yet.
 */
object ComposerPhotoViewer {
    private const val PREFIX = "composer-photo-"

    /** The tile's media ID. Prefixed so it never matches a sent image that keeps the attachment's ID. */
    fun mediaID(id: String): String = PREFIX + id

    /** Whether the photo is up full screen, its tile standing aside. */
    fun isShowing(id: String): Boolean = MediaZoomSources.shared.isHidden(listOf(mediaID(id)))

    /** Where the tile's × sits: its 44 pt (48 dp) target nudged out past the corner. */
    val removeBadgeOffset = DpOffset(8.dp, (-8).dp)

    /** The attachment a viewer page stands for: only a prefixed UUID. */
    fun attachmentID(mediaID: String): String? {
        if (!mediaID.startsWith(PREFIX)) return null
        val id = mediaID.removePrefix(PREFIX)
        return runCatching { UUID.fromString(id) }.getOrNull()?.let { id }
    }

    /** Every decodable pending photo, opened on [selected]. */
    fun preview(
        attachments: List<OpenCodeOutgoingAttachment>,
        selected: String,
        decode: (OpenCodeOutgoingAttachment) -> Bitmap? = ::decode,
    ): TranscriptImagePreview? {
        val images = attachments.filter { it.isImage }.mapNotNull { attachment -> decode(attachment)?.let { attachment to it } }
        if (images.none { it.first.id == selected }) return null
        val pages = images.mapIndexed { index, (attachment, image) ->
            TranscriptImagePreview.Page(
                id = mediaID(attachment.id),
                image = image,
                label = attachment.name ?: "Image attachment ${index + 1} of ${images.size}",
                detail = detail(attachment, image),
            )
        }
        return TranscriptImagePreview(mediaID(selected), pages)
    }

    /** “JPEG · 1568 × 1176 · 412 KB”. */
    fun detail(attachment: OpenCodeOutgoingAttachment, pixelWidth: Int, pixelHeight: Int): String {
        val format = attachment.mime.split("/").lastOrNull()?.uppercase() ?: "Image"
        val size = AttachmentFileType.byteCount(attachment.byteCount ?: attachment.data.size.toLong())
        return "$format · $pixelWidth × $pixelHeight · $size"
    }

    /** [detail] for a decoded [image]. */
    fun detail(attachment: OpenCodeOutgoingAttachment, image: Bitmap): String = detail(attachment, image.width, image.height)

    /** The attachment's bytes, decoded (iOS `CachedImage.decoded`; a `media:<hash>` reference with no bytes yet decodes to nothing). */
    private fun decode(attachment: OpenCodeOutgoingAttachment): Bitmap? =
        if (attachment.data.isEmpty()) null else runCatching { BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size) }.getOrNull()
}
