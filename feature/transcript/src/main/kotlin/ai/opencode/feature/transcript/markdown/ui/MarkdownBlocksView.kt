package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.model.AgentFileReference
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * iOS `MarkdownBlocksView`: a run of blocks with the transcript's spacing between them
 * (`MarkdownStyle.gap(after:before:)`).
 *
 * @param rowID the row's identity: keys the reveal records when [revealItem] isn't given.
 * @param isGrowing whether the reply can still grow (its session is running). Once it can't,
 *   text that streamed in settles.
 * @param revealItem a streamed reply's item: text that arrives fades in. Null shows the
 *   blocks as history (compaction summaries, the file viewer).
 * @param imageScope the reply these blocks belong to, when they're only part of it.
 * @param selectionItemID the reply row these blocks are (iOS `selectionItemID`): their text
 *   joins the list's one selection across the reply. Null keeps each block selectable alone.
 */
@Composable
fun MarkdownBlocksView(
    blocks: List<OpenCodeMarkdownBlock>,
    modifier: Modifier = Modifier,
    rowID: String? = null,
    isGrowing: Boolean = false,
    revealItem: String? = rowID,
    @Suppress("UNUSED_PARAMETER") imageScope: AgentImageScope? = null,
    selectionItemID: String? = null,
) {
    val style = currentMarkdownStyle()
    // The list's reveal when it provides one; otherwise this row's own (a row LazyColumn
    // brings back starts as history, which is what iOS's list-owned records give it too).
    val reveal = LocalTranscriptTextReveal.current ?: remember(rowID) { TranscriptTextReveal() }
    val arrivals = if (revealItem != null) reveal.arrivals(revealItem, blocks, reveal.now()) else emptyMap()
    if (revealItem != null && !isGrowing) reveal.settle(revealItem)
    // Image Link Previews (experimental): thumbnails under prose blocks with direct-image links.
    val imageLinks = ai.opencode.feature.transcript.links.rememberImageLinkPlan(blocks, isGrowing)
    val imageGrowth = ai.opencode.feature.media.LocalAgentImageRowGrowth.current
    val imageDensity = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.runtime.CompositionLocalProvider(LocalTranscriptTextReveal provides reveal) {
        Column(modifier) {
            for ((index, block) in blocks.withIndex()) {
                key(block.id) {
                    androidx.compose.runtime.CompositionLocalProvider(ai.opencode.feature.media.LocalAgentImageBlock provides block.id) {
                    Column(Modifier.agentImageGrowthHold(
                        lift = { (imageGrowth?.lift(block.id) ?: 0f) * imageDensity },
                        cut = { (imageGrowth?.cut(listOf(block.id)) ?: 0f) * imageDensity },
                        clips = imageGrowth != null && (block.kind is OpenCodeMarkdownBlock.Kind.Files || block.kind is OpenCodeMarkdownBlock.Kind.Image || imageLinks[block.id] != null),
                        softEdge = index < blocks.lastIndex,
                    )) {
                    MarkdownBlockView(
                        block,
                        Modifier.padding(top = style.gap(if (index == 0) null else blocks[index - 1].kind, block.kind)),
                        arrivals = arrivals[block.id].orEmpty(),
                        isGrowingTail = isGrowing && index == blocks.size - 1,
                        selectionItemID = selectionItemID,
                    )
                    imageLinks[block.id]?.let { ai.opencode.feature.transcript.links.BlockImageLinks(block, it) }
                    }
                    }
                }
            }
        }
    }
}

/** The images the blocks name, in reading order (iOS `MarkdownBlocksView.namedImages(in:)`). */
fun namedImages(blocks: List<OpenCodeMarkdownBlock>): List<AgentFileReference> = blocks.flatMap { block ->
    when (val kind = block.kind) {
        is OpenCodeMarkdownBlock.Kind.Files -> kind.files.filter { it.kind == AgentFileReference.Kind.Image }
        is OpenCodeMarkdownBlock.Kind.Image -> listOfNotNull(AgentFileReference.fromLink(kind.source))
        else -> emptyList()
    }
}

/**
 * iOS `AgentImageGrowthEdge.depth`: the soft edge's depth, no deeper than what's shown or
 * still to come.
 */
fun agentImageGrowthDepth(height: Float, cut: Float, fade: Float): Float {
    if (cut <= 0.5f) return 0f
    return maxOf(0f, minOf(fade, cut, height - cut))
}

/**
 * iOS `AgentImageGrowthHoldModifier`: while a row grows at its images, a block below an image
 * is drawn up by what the images above it still have to grow ([lift]) and an image's block is
 * cut off by its own ([cut]) through the transcript's soft edge (`growthSoftEdge` deep), so
 * everything below an image holds still while the row's edge eases. Drawing only. The growth
 * values come from media's `AgentImageRowGrowth`, converted to pixels by the caller.
 */
fun Modifier.agentImageGrowthHold(lift: () -> Float, cut: () -> Float, clips: Boolean, softEdge: Boolean = true): Modifier =
    graphicsLayer {
        translationY = -lift()
        if (clips) compositingStrategy = CompositingStrategy.Offscreen
    }.then(
        if (!clips) {
            Modifier
        } else {
            Modifier.drawWithContent {
                drawContent()
                val c = cut()
                if (c <= 0.5f) return@drawWithContent
                val bottom = maxOf(0f, size.height - c)
                val depth = agentImageGrowthDepth(size.height, c, if (softEdge) TranscriptTunables.growthSoftEdge * density else 0f)
                if (depth > 0f) {
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = bottom - depth, endY = bottom),
                        topLeft = Offset(0f, bottom - depth),
                        size = androidx.compose.ui.geometry.Size(size.width, depth),
                        blendMode = BlendMode.DstOut,
                    )
                }
                drawRect(Color.Black, topLeft = Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(size.width, c), blendMode = BlendMode.DstOut)
            }
        },
    )
