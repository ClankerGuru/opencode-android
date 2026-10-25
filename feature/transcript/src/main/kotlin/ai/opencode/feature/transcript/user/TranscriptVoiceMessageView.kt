package ai.opencode.feature.transcript.user

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.server.model.OpenCodeDelivery
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * How a Voice message says it came from Voice (iOS `VoiceMessageIndicator`). [CornerBadge] is
 * the design; [LabelRow] is the fallback: flip [TranscriptVoiceMessage.indicator] to switch.
 */
enum class VoiceMessageIndicator {
    /** A waveform badge on the bubble's top leading corner, like a tapback; the label row only ever holds the pending label. */
    CornerBadge,
    /** “↗ Steering  〰 Voice” above the bubble: the pending part leads, so delivery removes it without moving Voice. */
    LabelRow;

    sealed interface LabelPart {
        data class Pending(val delivery: OpenCodeDelivery) : LabelPart
        data object Voice : LabelPart
    }

    /** What a Voice message shows besides its bubble. */
    @Immutable
    data class Chrome(
        val badge: Boolean,
        /** The label row above the bubble, leading to trailing; empty means none. */
        val labels: List<LabelPart>,
        /** “Call log attached”, a quiet caption under the bubble, so it never joins the label row above. */
        val callLogCaption: Boolean = false,
    )

    fun chrome(pending: OpenCodeDelivery?, callLogAttached: Boolean = false): Chrome {
        val pendingPart = pending?.let { listOf(LabelPart.Pending(it)) } ?: emptyList()
        return when (this) {
            CornerBadge -> Chrome(badge = true, labels = pendingPart, callLogCaption = callLogAttached)
            LabelRow -> Chrome(badge = false, labels = pendingPart + LabelPart.Voice, callLogCaption = callLogAttached)
        }
    }
}

/** iOS `TranscriptVoiceMessageView.indicator`. */
object TranscriptVoiceMessage {
    val indicator = VoiceMessageIndicator.CornerBadge
}

/**
 * The voice agent prompting the session on the user's behalf, in its own framing: the user's
 * bubble ([VoiceMessageStyle]) marked as Voice ([VoiceMessageIndicator]), which is all that sets
 * it apart. The prompt itself (the user's words and the call's transcript) is a long press away
 * (iOS `TranscriptVoiceMessageView`).
 */
@Composable
fun TranscriptVoiceMessageView(
    delegation: VoiceHandoff.Delegation,
    /** The full prompt as the session received it. */
    prompt: String,
    pending: OpenCodeDelivery? = null,
    /** When it arrived, if the list saw it arrive: its reveal runs from then. */
    arrival: Double? = null,
    /** While it waits as steering: Move to Queue and Delete join its menu. */
    pendingActions: TranscriptPendingActions? = null,
    onCopy: (String) -> Unit = {},
    run: UserMessageRun = UserMessageRun.alone,
    messageID: String = "",
    style: VoiceMessageStyle = VoiceMessageStyle.current,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val morphs = LocalPromotionMorph.current
    val value = listOfNotNull(delegation.displayText, if (delegation.callLogAttached) "Call log attached" else null, pending?.pendingLabel).joinToString(", ")
    val actions = buildList {
        add(action("Copy") { onCopy(delegation.displayText) })
        add(action("Copy Full Message") { onCopy(prompt) })
        pendingActions?.let { addAll(it.accessibilityActions().filter { a -> a.label != "Copy" }) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Voice message"
                stateDescription = value
                customActions = actions
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        AgentMessageRevealClock(arrival) { reveal ->
            VoiceMessage(delegation, prompt, pending, pendingActions, onCopy, run, style, reveal, reduceMotion, morphs)
        }
    }
}

@Composable
private fun VoiceMessage(
    delegation: VoiceHandoff.Delegation,
    prompt: String,
    pending: OpenCodeDelivery?,
    pendingActions: TranscriptPendingActions?,
    onCopy: (String) -> Unit,
    run: UserMessageRun,
    style: VoiceMessageStyle,
    reveal: AgentMessageReveal.Frame,
    reduceMotion: Boolean,
    morphs: Boolean,
) {
    val chrome = TranscriptVoiceMessage.indicator.chrome(if (run.joinsAbove) null else pending, delegation.callLogAttached)
    val corners = run.bubbleCorners(hasAttachments = false)
    val isPending = pending != null
    val density = LocalDensity.current
    val badgeDiameter = min(20f * density.fontScale, VoiceMessageBadge.maximumDiameter.value).dp
    val overhang = VoiceMessageBadge.overhang(badgeDiameter)
    val pendingAlpha by animateFloatAsState(if (isPending) 0.6f else 1f, PromotionMotion.animation(reduceMotion, morphs), label = "pending")
    var menu by remember { mutableStateOf(false) }
    val badgeRoom by animateDpAsState(if (chrome.badge && !run.joinsAbove) overhang else 0.dp, PromotionMotion.animation(reduceMotion, morphs), label = "badge")
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact),
    ) {
        // The labels it last showed, so they fade out whole.
        val shownLabels = remember { mutableStateOf(chrome.labels) }
        if (chrome.labels.isNotEmpty()) shownLabels.value = chrome.labels
        AnimatedVisibility(
            visible = chrome.labels.isNotEmpty(),
            enter = when {
                morphs -> EnterTransition.None
                reduceMotion -> fadeIn(Motion.reducedFadeSpec())
                else -> PromotionMotion.pendingLabelEnter()
            },
            exit = when {
                morphs -> ExitTransition.None
                reduceMotion -> fadeOut(Motion.reducedFadeSpec())
                else -> PromotionMotion.pendingLabelExit(density.density)
            },
        ) {
            // Trailing-aligned, so a leading pending part leaves without moving Voice.
            Row(
                Modifier
                    .padding(end = TranscriptMetrics.contentGutter)
                    .graphicsLayer { alpha = reveal.body.toFloat() },
                horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
            ) {
                for (part in shownLabels.value) {
                    when (part) {
                        is VoiceMessageIndicator.LabelPart.Pending -> TranscriptPendingLabel(part.delivery)
                        VoiceMessageIndicator.LabelPart.Voice -> SmallGlyphLabel(
                            "Voice",
                            "waveform",
                            style = OpenCodeTextStyles.caption1,
                            color = OpenCodeTheme.colors.secondaryText,
                        )
                    }
                }
            }
        }
        // The badge overhangs the bubble's top; keep it inside the row, except in a run, where it
        // overlaps the 4 pt gap like a tapback rather than widening it.
        TrailingFractionLayout(
            TranscriptMetrics.maximumUserBubbleFraction,
            Modifier.padding(top = badgeRoom),
        ) {
            Box {
                Text(
                    delegation.displayText,
                    style = UserBubbleTextStyle,
                    color = OpenCodeTheme.colors.text,
                    modifier = Modifier
                        // Long press opens the menu, not text selection.
                        .transcriptDragSource(payload = null, onLift = { menu = true })
                        .voiceMessageBackground(style, isPending, reveal, corners)
                        .padding(horizontal = TranscriptMetrics.contentGutter, vertical = TranscriptMetrics.bubbleVertical)
                        .graphicsLayer { alpha = pendingAlpha * reveal.text.toFloat() },
                )
                if (chrome.badge) {
                    VoiceMessageBadge(
                        badgeDiameter,
                        Modifier
                            .offset(x = -overhang, y = -overhang)
                            .graphicsLayer { alpha = reveal.body.toFloat() },
                    )
                }
                // TODO(parity): iOS previews the full prompt (code font, 340 × ≤480) above the menu.
                UserMessageMenu(menu, { menu = false }) {
                    val dismiss = { menu = false }
                    UserMenuItem("Copy", "square.on.square", dismiss) { onCopy(delegation.displayText) }
                    UserMenuItem("Copy Full Message", "doc.plaintext", dismiss) { onCopy(prompt) }
                    pendingActions?.QueueAndDelete(dismiss)
                }
            }
        }
        if (chrome.callLogCaption) {
            SmallGlyphLabel(
                "Call log attached",
                "paperclip",
                style = OpenCodeTextStyles.caption1,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier
                    .padding(end = TranscriptMetrics.contentGutter)
                    .graphicsLayer { alpha = reveal.body.toFloat() },
            )
        }
    }
}

/**
 * A Voice message's mark: a small waveform in a circle on the bubble's top leading corner, like a
 * tapback but quieter, ringed in the transcript's background so it reads against the bubble.
 * Opaque; Increase Contrast adds an edge and darkens the glyph (iOS `VoiceMessageBadge`).
 */
@Composable
private fun VoiceMessageBadge(diameter: Dp, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val increased = colors.highContrast
    Box(
        modifier
            .background(colors.canvas, CircleShape)
            .padding(VoiceMessageBadge.ring)
            .size(diameter)
            .background(colors.userBubble, CircleShape)
            .background(colors.text.copy(alpha = 0.07f), CircleShape)
            .then(if (increased) Modifier.border(1.dp, colors.border, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(
            "waveform",
            contentDescription = null,
            size = diameter * 0.6f,
            tint = if (increased) colors.text else colors.secondaryText,
        )
    }
}

private object VoiceMessageBadge {
    /** Larger, and its circle would reach the bubble's first line. */
    val maximumDiameter: Dp = 22.dp
    val ring: Dp = 2.dp
    /** The badge's centre sits this far inside the bubble's corner, at every size. */
    val inset: Dp = 4.dp

    /** How far the ringed badge reaches past the bubble's top and leading edges. */
    fun overhang(diameter: Dp): Dp = diameter / 2 + ring - inset
}
