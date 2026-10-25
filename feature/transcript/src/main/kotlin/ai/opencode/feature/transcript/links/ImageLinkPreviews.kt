package ai.opencode.feature.transcript.links

import ai.opencode.core.log.AppLog
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptImagePreview
import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.toBitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Direct image links are an opt-in exception to page-metadata fetching (iOS `ImageLinkPolicy`,
 * `Media/ImageLinkPreviewClient.swift`). The exact URL is used (query intact); it never goes to
 * an icon service.
 */
object ImageLinkPolicy {
    /** At most three image links per message. */
    const val maximumPerMessage = 3

    /**
     * One plan for the entire message, before rendering its individual blocks: each block's
     * image links by block ID, deduplicated across the message.
     */
    fun plan(blocks: List<OpenCodeMarkdownBlock>, growing: Boolean): Map<Int, List<String>> {
        val seen = LinkedHashSet<String>()
        val result = LinkedHashMap<Int, MutableList<String>>()
        val lastID = blocks.lastOrNull()?.id
        for (block in blocks) {
            for (url in links(block.kind, growing = growing && block.id == lastID)) {
                if (seen.size >= maximumPerMessage) return result
                if (seen.add(url)) result.getOrPut(block.id) { ArrayList() }.add(url)
            }
        }
        return result
    }

    fun allows(url: String): Boolean {
        val parsed = WebURL.parse(url) ?: return false
        val last = parsed.pathSegments.lastOrNull { it.isNotEmpty() } ?: return false
        val dot = last.lastIndexOf('.')
        val extension = if (dot > 0) last.substring(dot + 1).lowercase() else ""
        return extension in setOf("png", "jpg", "jpeg", "gif", "webp") &&
            LinkPreviewPolicy.isPublicWebURL(url, allowsTailnetImages = true)
    }

    /**
     * A growing block's last link may still gain a query or path suffix. Keep it as text until
     * another character, or settlement, proves it whole.
     */
    fun links(kind: OpenCodeMarkdownBlock.Kind, growing: Boolean): List<String> {
        val text = when (kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text
            is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text
            is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text
            is OpenCodeMarkdownBlock.Kind.Heading -> kind.text
            else -> return emptyList()
        }
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        fun add(link: String, endsText: Boolean) {
            if (result.size >= maximumPerMessage || !allows(link) || (growing && endsText) || !seen.add(link)) return
            result.add(link)
        }
        val lastRun = text.runs.lastIndex
        text.runs.forEachIndexed { index, run ->
            val endsRun = index == lastRun
            val link = run.link
            if (link != null) {
                add(link, endsText = endsRun)
            } else if (!run.code) {
                val plain = run.text
                if (!plain.contains("://")) return@forEachIndexed
                for (match in LinkDetector.matches(plain)) {
                    add(match.url, endsText = endsRun && match.end == plain.length)
                }
            }
        }
        return result
    }

    /** A block an image's growth can start in: only these are ever clipped (iOS `MarkdownBlocksView.holdsImages`). */
    fun holdsImages(block: OpenCodeMarkdownBlock, imageLinks: List<String> = emptyList()): Boolean {
        if (imageLinks.isNotEmpty()) return true
        return block.kind is OpenCodeMarkdownBlock.Kind.Files || block.kind is OpenCodeMarkdownBlock.Kind.Image
    }
}

/**
 * The message's image-link plan when the off-by-default Image Link Previews experiment is on and
 * this surface previews images (iOS `MarkdownBlocksView`: `imagePreviews && imageLinkPreviews ?
 * ImageLinkPolicy.plan(blocks, growing:) : [:]`); empty otherwise.
 */
@Composable
fun rememberImageLinkPlan(blocks: List<OpenCodeMarkdownBlock>, isGrowing: Boolean): Map<Int, List<String>> {
    val enabled = LocalTranscriptLayoutTuning.current.imageLinkPreviews && LocalTranscriptRowContext.current.imagePreviews
    return remember(blocks, isGrowing, enabled) { if (enabled) ImageLinkPolicy.plan(blocks, isGrowing) else emptyMap() }
}

/**
 * Thumbnails for the complete direct-image links in [block]'s prose, under it (iOS
 * `BlockImageLinks`). [urls] is the block's entry in [rememberImageLinkPlan]; images open in the
 * zoom viewer through [onPreview] (`TranscriptAction.PreviewImage` by default).
 */
@Composable
fun BlockImageLinks(
    block: OpenCodeMarkdownBlock,
    urls: List<String>,
    modifier: Modifier = Modifier,
    onPreview: ((TranscriptImagePreview) -> Unit)? = null,
) {
    if (urls.isEmpty()) return
    val tuning = LocalTranscriptLayoutTuning.current
    val row = LocalLinkPreviewRow.current
    val send = LocalTranscriptRowContext.current.send
    ImageLinkPreviews(
        urls = urls,
        occurrence = "${row ?: ""}|${block.id}",
        maxHeight = tuning.linkMaxHeight,
        onPreview = onPreview ?: { send(TranscriptAction.PreviewImage(it)) },
        modifier = modifier,
    )
}

/**
 * The prose keeps its original links, including while a read fails or waits (iOS
 * `ImageLinkPreviews`). A loaded image takes its aspect-fit space; the list's `RowGrowth` eases
 * the row to it.
 */
@Composable
fun ImageLinkPreviews(
    urls: List<String>,
    occurrence: String,
    maxHeight: Float,
    onPreview: (TranscriptImagePreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        for (url in urls) {
            key(url) {
                ImageLinkThumbnail(url, mediaID = "image-link:$occurrence|$url", maxHeight = maxHeight, onPreview = onPreview)
            }
        }
    }
}

/** iOS `ImageLinkLoader` constants. */
internal object ImageLinkLoader {
    /** Enough for the inline display at 3× without decoding a full-size original. */
    const val maxPixelSize = 1200

    fun key(url: String): String = "image-link:$url"
}

@Composable
private fun ImageLinkThumbnail(url: String, mediaID: String, maxHeight: Float, onPreview: (TranscriptImagePreview) -> Unit) {
    val context = LocalPlatformContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    var image by remember(url) {
        mutableStateOf<ImageBitmap?>(loader.memoryCache?.get(MemoryCache.Key(ImageLinkLoader.key(url)))?.image?.toBitmap()?.asImageBitmap())
    }
    // Composed rows are the visible ones (plus the list's prefetch): load while shown.
    LaunchedEffect(url) {
        if (image != null || !ImageLinkPolicy.allows(url)) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(ImageLinkLoader.key(url))
            .size(ImageLinkLoader.maxPixelSize)
            .precision(Precision.INEXACT)
            .build()
        val result = loader.execute(request)
        if (result is SuccessResult) {
            image = result.image.toBitmap().asImageBitmap()
        } else {
            AppLog.network.debug { "Image link preview unavailable" }
        }
    }
    val loaded = image ?: return
    // iOS `url.lastPathComponent`, decoded.
    val label = remember(url) { WebURL.parse(url)?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: url }
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    val open = { onPreview(TranscriptImagePreview(id = mediaID, image = loaded, label = label)) }
    ImageLinkThumbnailLayout(
        width = loaded.width,
        height = loaded.height,
        maxHeight = maxHeight,
        modifier = Modifier.padding(top = TranscriptMetrics.standard),
    ) {
        Image(
            loaded,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .clickable(onClick = open)
                .clearAndSetSemantics {
                    contentDescription = "Open $label"
                    role = Role.Button
                    testTag = "image-link-preview"
                    onClick { open(); true }
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Exactly the thumbnail's aspect-fit size within the column's width and [maxHeight] (iOS
 * `ImageLinkThumbnailLayout` + `MediaZoomGeometry.fittedSize`).
 */
@Composable
private fun ImageLinkThumbnailLayout(width: Int, height: Int, maxHeight: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val boundsWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else width.toFloat()
        val boundsHeight = maxHeight.dp.toPx()
        val (w, h) = if (width <= 0 || height <= 0 || boundsWidth <= 0 || boundsHeight <= 0) {
            0 to 0
        } else {
            val scale = min(boundsWidth / width, boundsHeight / height)
            (width * scale).roundToInt() to (height * scale).roundToInt()
        }
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(w, h) { placeables.forEach { it.place(0, 0) } }
    }
}
