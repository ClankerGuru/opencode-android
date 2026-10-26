package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.transcriptDragSource
import ai.opencode.server.model.OpenCodeImageAttachment
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded tool images by attachment ID (iOS `CachedImage.decoded`), bounded by bytes. A view
 * cache, like iOS's: `:feature:media`'s `DecodedImageCache` replaces it once that port lands.
 */
private object ToolImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(id: String): ImageBitmap? = cache.get(id)

    /** Decodes off the main thread; null for bytes that aren't an image. */
    suspend fun decoded(attachment: OpenCodeImageAttachment): ImageBitmap? {
        cache.get(attachment.id)?.let { return it }
        val image = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size)?.asImageBitmap() }.getOrNull()
        } ?: return null
        cache.put(attachment.id, image)
        return image
    }
}

/**
 * iOS `TranscriptImagePreview.init?(attachments:selectedID:label:)`: every decoded image, in
 * order, opened at [selectedID]; null when the selected one didn't decode.
 */
fun transcriptImagePreview(decoded: List<Pair<String, ImageBitmap>>, selectedID: String, label: String): TranscriptImagePreview? =
    TranscriptImagePreview.fromAttachments(decoded.map { (id, image) -> id to image.asAndroidBitmap() }, selectedID, label)

/**
 * Every image a tool read, whole and in order, so it can be read without opening it (iOS
 * `ToolImageMosaic`): justified rows ([JustifiedImageRows]) where each row's images share a
 * height and together fill the width, nothing cropped or hidden. Tapping one opens them all in
 * the gallery.
 */
@Composable
fun ToolImageMosaic(images: List<OpenCodeImageAttachment>, modifier: Modifier = Modifier, onPreview: (TranscriptImagePreview) -> Unit) {
    val decoded by produceState(initialValue = images.map { ToolImageCache.cached(it.id) }, images) {
        value = images.map { ToolImageCache.decoded(it) }
    }
    val aspects = decoded.map { image ->
        if (image == null || image.width <= 0 || image.height <= 0) 1f else image.width.toFloat() / image.height
    }
    val shape = RoundedCornerShape(JustifiedImageRows.cornerRadius.dp)
    JustifiedImagesLayout(aspects, modifier) {
        images.forEachIndexed { index, attachment ->
            val image = decoded.getOrNull(index)
            Box(
                Modifier
                    .transcriptDragSource(
                        TranscriptDragPayload.ImageAttachment(attachment.id, attachment.data, "tool-image-${index + 1}"),
                        cornerRadius = JustifiedImageRows.cornerRadius.dp,
                    )
                    .clip(shape)
                    // Behind transparent images, and while one decodes.
                    .background(OpenCodeTheme.colors.userBubble)
                    // Keeps a white image's edge visible on a white transcript.
                    .border(0.5.dp, OpenCodeTheme.colors.border.copy(alpha = OpenCodeTheme.colors.border.alpha * 0.6f), shape)
                    .clickable(role = Role.Image) {
                        val pages = images.mapIndexedNotNull { i, a -> decoded.getOrNull(i)?.let { a.id to it } }
                        transcriptImagePreview(pages, attachment.id, "Tool image")?.let(onPreview)
                    }
                    .semantics { contentDescription = "Open tool image ${index + 1} of ${images.size}" },
            ) {
                if (image != null) {
                    Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

/** Lays out images by [JustifiedImageRows], each at its exact frame (iOS `JustifiedImagesLayout`). */
@Composable
fun JustifiedImagesLayout(
    aspects: List<Float>,
    modifier: Modifier = Modifier,
    /** How tall a lone image may stand. */
    loneMaxHeight: Float = JustifiedImageRows.singleMaxHeight,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 360.dp.toPx()
        val rows = JustifiedImageRows(aspects, width / density, loneMaxHeight)
        val placeables = measurables.mapIndexedNotNull { index, measurable ->
            val frame = rows.frames.getOrNull(index) ?: return@mapIndexedNotNull null
            val w = (frame.width * density).roundToInt()
            val h = (frame.height * density).roundToInt()
            measurable.measure(Constraints.fixed(max(0, w), max(0, h))) to frame
        }
        val height = (rows.height * density).roundToInt()
        layout(width.roundToInt(), height.coerceIn(constraints.minHeight, max(constraints.minHeight, constraints.maxHeight))) {
            for ((placeable, frame) in placeables) placeable.place((frame.left * density).roundToInt(), (frame.top * density).roundToInt())
        }
    }
}

/**
 * Justified rows (iOS `JustifiedImageRows`): images keep their shapes, a row's images share one
 * height and fill the width, and the breaks between rows are chosen (in order) to keep every row
 * near a comfortable height. A wide panorama gets a row to itself; a tall screenshot pairs with
 * something square. A row that can't fill the width without growing past the cap (a lone tall
 * screenshot) stays at the cap, leading-aligned. Units are dp.
 */
@Immutable
class JustifiedImageRows(rawAspects: List<Float>, width: Float, loneMaxHeight: Float = singleMaxHeight) {
    val frames: List<Rect>
    val height: Float

    init {
        val aspects = rawAspects.map { if (it.isFinite() && it > 0) min(max(it, aspectRange.start), aspectRange.endInclusive) else 1f }
        if (aspects.isEmpty() || width <= 0) {
            frames = emptyList()
            height = 0f
        } else if (aspects.size == 1) {
            val h = min(width / aspects[0], loneMaxHeight)
            frames = listOf(Rect(0f, 0f, min(width, h * aspects[0]), h))
            height = h
        } else {
            val target = width * 0.6f
            val maxHeight = width * 0.9f
            // A row's height when it fills the width, and its cost: how far it is from the
            // target, and a steep price for a row that can't fill.
            fun rowHeight(start: Int, end: Int): Float {
                val gaps = spacing * (end - start - 1)
                var sum = 0f
                for (i in start until end) sum += aspects[i]
                return (width - gaps) / sum
            }
            fun cost(start: Int, end: Int): Float {
                val h = rowHeight(start, end)
                var c = (h - target) * (h - target)
                if (h > maxHeight) c += (h - maxHeight) * (h - maxHeight) * 20
                if (end - start > 4) c += 1e9f
                return c
            }
            // Best breaks for the first i images, then walk back.
            val n = aspects.size
            val best = FloatArray(n + 1) { Float.POSITIVE_INFINITY }
            val from = IntArray(n + 1)
            best[0] = 0f
            for (end in 1..n) {
                for (start in max(0, end - 4) until end) {
                    if (best[start] == Float.POSITIVE_INFINITY) continue
                    val total = best[start] + cost(start, end)
                    if (total < best[end]) {
                        best[end] = total
                        from[end] = start
                    }
                }
            }
            val ranges = ArrayDeque<IntRange>()
            var end = n
            while (end > 0) {
                ranges.addFirst(from[end] until end)
                end = from[end]
            }
            val result = mutableListOf<Rect>()
            var y = 0f
            for (range in ranges) {
                val h = min(rowHeight(range.first, range.last + 1), maxHeight)
                var x = 0f
                for (index in range) {
                    val w = h * aspects[index]
                    result += Rect(x, y, x + w, y + h)
                    x += w + spacing
                }
                y += h + spacing
            }
            frames = result
            height = max(0f, y - spacing)
        }
    }

    override fun equals(other: Any?): Boolean = other is JustifiedImageRows && other.frames == frames && other.height == height
    override fun hashCode(): Int = frames.hashCode() * 31 + height.hashCode()

    companion object {
        const val spacing: Float = 4f
        const val cornerRadius: Float = 14f
        /** Shapes are laid out within this range: a stranger one gets a tile at its edge. */
        val aspectRange: ClosedFloatingPointRange<Float> = 0.2f..8f
        /** A lone image may stand this tall (a phone screenshot would otherwise run to two screens). */
        const val singleMaxHeight: Float = 520f
    }
}
