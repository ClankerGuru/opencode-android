package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS `View.handsGlassOver(to:...)`: the morph's source under it hands its surface over to the
 * morph as the morph starts to grow (fading out in 0.12 s), and takes it back as the morph lands
 * on it (after `close.duration × 0.62`, or [closeDelay]). [sinksUnderSheet]: the composer sinks
 * [ComposerMorphTunables.composerSink] and fades under a photo sheet instead; [pulledDown] (dp
 * the sheet is pulled below its rest) raises it back with the finger instead of popping it in
 * on release. Handed over, the source is invisible: the composer hides itself from TalkBack
 * under the sheet (the session's composer).
 *
 * iOS also sets the `attachmentMenuAnchorHold` environment here; Compose modifiers can't
 * provide a composition local, so the host provides [attachmentMenuAnchorHold] around the
 * composer through [LocalAttachmentMenuAnchorHold].
 */
fun Modifier.handsGlassOver(
    morph: ComposerMorph,
    reduceMotion: Boolean,
    isEnabled: Boolean = true,
    returnsImmediately: Boolean = false,
    closeDelay: Double? = null,
    sinksUnderSheet: Boolean = false,
    pulledDown: (() -> Float)? = null,
): Modifier = composed {
    if (!isEnabled) return@composed this
    val density = LocalDensity.current
    if (sinksUnderSheet) {
        val hidden = morph.isOpen && !morph.foldPending
        val motion: FiniteAnimationSpec<Float> = if (reduceMotion) tween(200, easing = Motion.EaseInOut)
        else (if (hidden) ComposerMorphTunables.photoOpen else ComposerMorphTunables.photoReturn).spec()
        // iOS `ComposerUnderSheetPlacement`: `hidden ? 1 − rise : 0`, animated only as `hidden`
        // changes; the rise follows the finger frame by frame.
        val placement = remember { Animatable(if (hidden) 1f else 0f) }
        val lastShown = remember { floatArrayOf(placement.value) }
        LaunchedEffect(hidden, reduceMotion) {
            // A release from a pull goes on from where the finger left it.
            if (!hidden) placement.snapTo(lastShown[0])
            placement.animateTo(if (hidden) 1f else 0f, motion)
        }
        val currentPulled = pulledDown
        fun progress(): Float {
            val pulled = if (hidden && !reduceMotion) currentPulled?.invoke() ?: 0f else 0f
            val rise = (pulled / max(ComposerMorphTunables.composerRise.toFloat(), 1f)).coerceIn(0f, 1f)
            val value = if (hidden) minOf(placement.value, 1 - rise) else placement.value
            lastShown[0] = value
            return value
        }
        val hiddenFromAccessibility = placement.value.coerceIn(0f, 1f) >= 0.65f
        this
            .graphicsLayer {
                val clamped = progress().coerceIn(0f, 1f)
                translationY = if (reduceMotion) 0f else clamped * ComposerMorphTunables.composerSink.toFloat() * density.density
                alpha = if (reduceMotion) 1 - clamped else 1 - (clamped / 0.65f).coerceIn(0f, 1f)
            }
            // Once faded out, hidden from accessibility but still focusable, so the message
            // keyboard can be raised in the tap before the fold starts.
            .semantics { if (hiddenFromAccessibility) hideFromAccessibility() }
    } else {
        val open = morph.isOpen
        val delaySeconds = closeDelay ?: (ComposerMorphTunables.close.duration * 0.62)
        val spec: FiniteAnimationSpec<Float> = when {
            reduceMotion -> tween(200, easing = Motion.EaseInOut)
            returnsImmediately -> tween(140, easing = Motion.EaseInOut)
            else -> tween(120, delayMillis = if (open) 0 else (delaySeconds * 1000).roundToInt(), easing = Motion.EaseInOut)
        }
        val a by animateFloatAsState(if (open) 0f else 1f, spec, label = "hands-glass-over")
        this.graphicsLayer { alpha = a }
    }
}

/**
 * iOS `HandsGlassOver.anchorHold`: how the composer's + menu anchors follow it under a photo
 * sheet (frame by frame when no sheet is up or under Reduce Motion; held while it folds back).
 */
fun ComposerMorph.attachmentMenuAnchorHold(reduceMotion: Boolean): AttachmentMenuAnchorHold {
    if (!isShown || reduceMotion) return AttachmentMenuAnchorHold.Follows
    return if (phase == ComposerMorph.Phase.Closing || foldPending) AttachmentMenuAnchorHold.Folding else AttachmentMenuAnchorHold.Resting
}
