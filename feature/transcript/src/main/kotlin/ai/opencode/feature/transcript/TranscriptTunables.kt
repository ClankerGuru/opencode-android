package ai.opencode.feature.transcript

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The transcript's layout and motion tunables (iOS `TranscriptTunables`, Developer Mode →
 * Tuning Panel → Transcript). Android bakes the defaults: the Tuning Panel is not ported
 * (`:devtools`), so each value is the iOS `default:`. Points port 1:1 to dp/sp.
 */
object TranscriptTunables {
    // Arrivals
    const val entranceDuration = 0.25
    const val entranceRise = 6f
    const val settlingDuration = 0.4
    const val textFadeDuration = 0.2
    const val workingFinale = 0.55
    const val workingHandoff = 0.34

    // Growth
    const val growthMinDuration = 0.22
    const val growthMaxDuration = 0.55
    const val growthMaxFrameChange = 1.5f
    const val growthSoftEdge = 28f
    const val disclosureOpen = 0.3
    const val disclosureClose = 0.24

    // Follow
    const val arrivalOmega = 20.0
    const val viewportOmega = 18.0
    const val maxFrameStep = 18.0
    const val arrivalReach = 2.5f

    // Promotion
    const val promotionDuration = 0.4
    const val promotionLabelRise = 6f

    // Send
    const val sendFlightOmega = 14.0
    const val sendPillDuration = 0.45

    // Jump
    const val jumpDuration = 0.5
    const val jumpReach = 2.5f
    const val jumpLead = 1.5f

    // Reveal
    const val newMinDuration = 0.28
    const val newMaxDuration = 0.62
    const val agentGlowRise = 0.07
    const val agentGlowDuration = 1.39

    // Row, bubble & card layout
    const val flowHalf = 8f
    const val turnHalf = 12f
    const val sectionHalf = 16f
    const val contentGutter = 16f
    const val bubbleVertical = 10f
    const val bubbleCornerRadius = 18f
    const val bubbleMaxFraction = 0.78f
    const val cardCornerRadius = 14f

    // Typography (D-52)
    const val bodySize = 17f
    const val bodyLeading = 4.5f
    const val bodyTone = 0.92
    const val h1Size = 22f
    const val h2Size = 20f
    const val h3Size = 18f
    val h3Weight = FontWeight.Bold
    const val metaSize = 17f
    const val listNumSize = 13f
    const val codeLeading = 3f

    /** `0` inherits `flowHalf * 2` (`MarkdownStyle.inheritedParagraphGap`). */
    const val paragraphGap = 0f

    /** Backwards-compatible alias for `maxFrameStep * 60` (points per second). */
    const val arrivalSpeedPointsPerSecond = maxFrameStep * 60

    fun resolvedParagraphGap(paragraphGap: Float, flowHalf: Float): Float =
        if (paragraphGap > 0) paragraphGap else flowHalf * 2

    fun layoutSnapshot(imageLinkPreviews: Boolean = false): TranscriptLayoutTuning =
        TranscriptLayoutTuning(imageLinkPreviews = imageLinkPreviews)
}

/**
 * Snapshot of layout-affecting transcript tunables (iOS `TranscriptLayoutTuning`). Motion-only
 * tunables are excluded so adjusting one never re-lays rows out.
 */
@Immutable
data class TranscriptLayoutTuning(
    val flowHalf: Float = TranscriptTunables.flowHalf,
    val turnHalf: Float = TranscriptTunables.turnHalf,
    val sectionHalf: Float = TranscriptTunables.sectionHalf,
    val contentGutter: Float = TranscriptTunables.contentGutter,
    val bubbleVertical: Float = TranscriptTunables.bubbleVertical,
    val bubbleCornerRadius: Float = TranscriptTunables.bubbleCornerRadius,
    val bubbleMaxFraction: Float = TranscriptTunables.bubbleMaxFraction,
    val cardCornerRadius: Float = TranscriptTunables.cardCornerRadius,
    val bodySize: Float = TranscriptTunables.bodySize,
    val bodyLeading: Float = TranscriptTunables.bodyLeading,
    val bodyTone: Double = TranscriptTunables.bodyTone,
    val h1Size: Float = TranscriptTunables.h1Size,
    val h2Size: Float = TranscriptTunables.h2Size,
    val h3Size: Float = TranscriptTunables.h3Size,
    val h3Weight: FontWeight = TranscriptTunables.h3Weight,
    val metaSize: Float = TranscriptTunables.metaSize,
    val listNumSize: Float = TranscriptTunables.listNumSize,
    val codeLeading: Float = TranscriptTunables.codeLeading,
    val paragraphGap: Float = TranscriptTunables.resolvedParagraphGap(TranscriptTunables.paragraphGap, TranscriptTunables.flowHalf),
    val imageLinkPreviews: Boolean = false,
    /** `AgentImageTunables.linkMaxHeight` default. */
    val linkMaxHeight: Float = 220f,
)

/** iOS `EnvironmentValues.transcriptLayoutTuning`. */
val LocalTranscriptLayoutTuning = staticCompositionLocalOf { TranscriptLayoutTuning() }

/**
 * The transcript's complete spacing vocabulary (iOS `TranscriptMetrics`). Rows own their outer
 * rhythm; renderers only use values from this scale so padding never compounds.
 */
object TranscriptMetrics {
    val compact: Dp = 4.dp
    val clustered: Dp = 8.dp
    val bubbleVertical: Dp = TranscriptTunables.bubbleVertical.dp
    val standard: Dp = 12.dp
    val contentGutter: Dp = TranscriptTunables.contentGutter.dp
    val turn: Dp = 20.dp

    val bubbleCornerRadius: Dp = TranscriptTunables.bubbleCornerRadius.dp
    /** Content surfaces: code, tables, cards, and images. */
    val cardCornerRadius: Dp = TranscriptTunables.cardCornerRadius.dp
    /** Controls inside sheets and cards: choices and fields. */
    val controlCornerRadius: Dp = 10.dp

    const val maximumUserBubbleFraction: Float = TranscriptTunables.bubbleMaxFraction

    /** The widest the transcript and composer grow, gutters included (tablets centre at it). */
    val readableColumnWidth: Dp = 720.dp

    /** Within a reply, paragraphs, Used N, notices and Working are 16 apart. */
    val flowHalf: Dp = TranscriptTunables.flowHalf.dp
    /** A user message is 20 from the reply around it. */
    val turnHalf: Dp = TranscriptTunables.turnHalf.dp
    /** A compaction is a context boundary: 24 from its neighbours. */
    val sectionHalf: Dp = TranscriptTunables.sectionHalf.dp

    /** iOS `MinimumHitRect.side` (44 pt) → Android's 48 dp minimum target. */
    val minimumHitSide: Dp = 48.dp
}

/** iOS `InlineRowMetrics`: Used N rows inside the band sit on a 28 pt pitch. */
object InlineRowMetrics {
    /** Above and below each row's line inside the band. */
    val rowPadding: Dp = 4.dp
    /** iOS `TranscriptBandStyle.verticalPadding` (12) minus [rowPadding]. */
    val bandPadding: Dp = 8.dp
}

/** A row's outer halves (iOS `rowInsets`), in dp. */
data class RowInsets(val top: Dp, val bottom: Dp)
