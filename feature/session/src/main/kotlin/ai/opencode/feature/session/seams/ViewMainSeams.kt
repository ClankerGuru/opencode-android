package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Seams for the session screen scaffold (group VIEW-MAIN). Each is the minimum SessionView,
// SessionTranscriptView and the Working line read.

/**
 * SEAM: real type belongs to :feature:transcript (iOS `OpenCode/Transcript/TranscriptListView.swift`,
 * `SessionScrollEdgeBridge`); delete when that area merges.
 *
 * What the transcript list and the session chrome share without re-rendering the session: the
 * composer's height (the list's bottom inset), how far content has scrolled under the header
 * (the backdrop's reveal), a jump-to-latest request, and when this screen first drew each
 * Working-line event (so a remount never replays Starting Up or a finale). Plain object, not a
 * reducer: values change per frame.
 */
@Stable
class SessionScrollEdgeBridge {
    /** The composer area's height (dp), the list's bottom content inset. */
    var composerHeight by mutableFloatStateOf(0f)

    /** Content scrolled under the header (dp), capped at the reveal distance and rounded. */
    var scrolledUnderHeader by mutableFloatStateOf(0f)
        private set

    /** Bumped to scroll the list to its latest row. */
    var jumpRequest by mutableIntStateOf(0)

    private val workingRunStamps = HashMap<String, Double>()
    private val workingRunOrder = ArrayDeque<String>()

    /** When this screen first drew the event [token] (seconds, `AgentActivityClock`); keeps 64. */
    fun workingRunTime(token: String, now: Double): Double {
        workingRunStamps[token]?.let { return it }
        workingRunStamps[token] = now
        workingRunOrder.addLast(token)
        while (workingRunOrder.size > 64) workingRunStamps.remove(workingRunOrder.removeFirst())
        return now
    }

    fun scrolled(underHeader: Float, revealDistance: Float) {
        val capped = min(max(underHeader, 0f), max(revealDistance, MINIMUM_TRACKED_REVEAL)).roundToInt().toFloat()
        if (capped != scrolledUnderHeader) scrolledUnderHeader = capped
    }

    /** A new screen starts without the jump button (iOS `resetShowsJumpButton`). */
    fun resetShowsJumpButton() = Unit

    companion object {
        /** Tracked even when nothing reveals with the scroll, so a band that takes over draws at once. */
        const val MINIMUM_TRACKED_REVEAL = 24f
    }
}

/**
 * SEAM: real type belongs to :feature:transcript (iOS `OpenCode/Transcript/TranscriptEntryView.swift`,
 * `TranscriptMetrics`); delete when that area merges. Shipped tunable defaults.
 */
object TranscriptMetrics {
    const val compact = 4f
    const val clustered = 8f
    const val standard = 12f
    const val turn = 20f
    /** Reply row half-gap (`transcript.row.flowHalf`, default 8). */
    const val flowHalf = 8f
    /** Turn half-gap (`transcript.row.turnHalf`, default 12). */
    const val turnHalf = 12f

    /** Used N, answered question rows, and Working: body at the shipped 17 pt meta size. */
    fun metaFont(weight: FontWeight = FontWeight.Normal): TextStyle = OpenCodeTextStyles.body.copy(fontWeight = weight)
}

/**
 * SEAM: real type belongs to :feature:transcript (iOS `OpenCode/Transcript/TranscriptEntryView.swift`,
 * `ReplyCopyGlyph`); delete when that area merges.
 *
 * The reply's copy glyph in its 44 pt slot; the Working line hands off to it.
 */
object ReplyCopyGlyph {
    /** The glyph's centre sits 9 pt in from the text edge: its 44 pt slot starts 13 pt out. */
    const val leading = -13f
}

/** SEAM: see [ReplyCopyGlyph]. The copy glyph, secondary, in its 44 dp slot. */
@Composable
fun ReplyCopyGlyphView(modifier: Modifier = Modifier) {
    Box(modifier.width(44.dp), contentAlignment = Alignment.Center) {
        OpenCodeIcon(SFSymbol.SquareOnSquare, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.secondaryText)
    }
}

/**
 * SEAM: real logic belongs to :feature:transcript (iOS `TranscriptEntry.build(from:isRunning:)` and
 * its last entry being `.replyEnd`); delete when that area merges.
 *
 * Whether a finished turn ends in a reply's copy row: some reply text arrived after the turn's
 * last user message. (iOS also ends a reply at a model switch that a later send applied; that
 * nuance comes with the real builder.)
 */
fun TranscriptEntry.endsInReply(items: List<OpenCodeTranscriptItem>): Boolean {
    for (item in items.asReversed()) {
        when (item) {
            is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles -> return false
            is OpenCodeTranscriptItem.AssistantMarkdown -> if (item.text.isNotBlank()) return true
            else -> continue
        }
    }
    return false
}

/**
 * SEAM: real type belongs to :feature:media (iOS `Core/DecodedImageCache.swift`, the
 * `transcriptImageCache` dependency); delete when that area merges.
 *
 * Decoded images by key, so a row never decodes the same bytes twice.
 */
class TranscriptImageCache(private val maxEntries: Int) {
    private val cache = LruCache<String, ImageBitmap>(maxEntries)

    fun image(key: String): ImageBitmap? = cache.get(key)

    fun insert(image: ImageBitmap, key: String) {
        cache.put(key, image)
    }

    companion object Key : DependencyKey<TranscriptImageCache> {
        override val liveValue = TranscriptImageCache(64)
        override val testValue = TranscriptImageCache(8)
    }
}
