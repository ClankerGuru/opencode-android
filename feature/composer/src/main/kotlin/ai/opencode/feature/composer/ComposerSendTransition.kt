package ai.opencode.feature.composer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.max
import kotlin.math.min

/**
 * The composer's text handed off to its bubble (iOS `ComposerSendTransition`). On iOS it
 * snapshots the field's pixels and flies them into the transcript cell (`SendLandingProbe`
 * markers). Here the field reports its laid-out text and where it sits ([ComposerTextInput]);
 * [prepare] captures the send's words, their layout and their visible rect as a [Prepared],
 * which the session screen's flight overlay draws and flies toward the bubble (it owns both
 * ends, as the transcript's list and the composer meet there). Flights overlap: each is keyed
 * by the message it lands on, and preparing the next send never touches one already in the air.
 */
@Stable
class ComposerSendTransition {
    /** The next send's words as they leave the composer (window px). */
    data class Prepared(
        val text: String,
        /** The words' visible rect: what iOS snapshots (`usedRect` ∩ the field's bounds). */
        val bounds: Rect,
        /** Whether these are the composer's own laid-out words, not words laid out again where it was. */
        val fromComposer: Boolean,
        /** The words as laid out, drawn with their top-left at [origin] and clipped to [bounds]. */
        val layout: TextLayoutResult? = null,
        val origin: Offset = bounds.topLeft,
    )

    /**
     * The send's photos (iOS `images`): copies of the composer's thumbnails flying into the
     * bubble's image slots. The session's flight drives them alongside the words.
     */
    val images = SentImageFlight()

    /** The field's text and its visible bounds in the window (px), updated by [ComposerTextInput]. */
    var inputText: String = ""
        internal set
    var inputBounds: Rect? = null
        internal set

    /** The field's laid-out text and the window position (px) its layout starts at. */
    internal var textLayout: TextLayoutResult? = null
    internal var textOrigin: Offset? = null

    var prepared: Prepared? by mutableStateOf(null)
        private set

    val isPrepared: Boolean get() = prepared != null

    fun prepare(reduceMotion: Boolean) {
        cancelPrepared()
        val visible = inputBounds
        val layout = textLayout
        val origin = textOrigin
        if (reduceMotion || inputText.isEmpty() || visible == null || layout == null || origin == null || layout.lineCount == 0) return
        var left = Float.MAX_VALUE
        var right = 0f
        for (line in 0 until layout.lineCount) {
            left = min(left, layout.getLineLeft(line))
            right = max(right, layout.getLineRight(line))
        }
        val used = Rect(origin.x + left, origin.y, origin.x + right, origin.y + layout.size.height)
        val bounds = used.intersect(visible)
        if (bounds.isEmpty) return
        prepared = Prepared(inputText, bounds, fromComposer = true, layout = layout, origin = origin)
    }

    /**
     * A send the composer didn't capture as it left: its words laid out as the composer lays
     * them out, starting where the field (or the dictation bar in its place) sits. [composer]
     * is in window px; [measure] lays out [text] at a maximum width (px).
     */
    fun prepare(
        text: String,
        composer: Rect,
        expandedTopInsetPx: Float,
        contentGutterPx: Float,
        measure: (String, Float) -> TextLayoutResult,
    ) {
        cancelPrepared()
        val layout = measure(text, max(composer.width - contentGutterPx * 2, 40f))
        val width = layout.size.width.toFloat()
        val height = layout.size.height.toFloat()
        // The field's first line: the capsule's top when it's tall, its middle when it's one row.
        val top = min(composer.top + expandedTopInsetPx, composer.center.y - height / 2)
        val bounds = Rect(composer.left + contentGutterPx, top, composer.left + contentGutterPx + width, top + height)
        prepared = Prepared(text, bounds, fromComposer = false, layout = layout, origin = bounds.topLeft)
    }

    /** The prepared send taken for its flight (the overlay now owns it); null when nothing was prepared. */
    fun takePrepared(): Prepared? = prepared.also { prepared = null }

    /** Fades a send's capture out where it is: a send that doesn't fly. Only while it's still the prepared one. */
    fun dissolvePrepared(snapshot: Prepared?) {
        if (prepared != null && prepared === snapshot) prepared = null
    }

    /** Drops the next send's capture; flights in the air carry on. */
    fun cancelPrepared() {
        prepared = null
    }

    /** The send's clear's critical-spring ω, until the field takes it ([shrinkOnNextClear]). */
    private var pendingShrinkOmega: Double? = null

    /**
     * iOS `ImagePasteTextView.sendShrinkAnimation`: the send's clear shrinks the field on a
     * critical spring quicker than the list's arrival glide ([sendShrinkOmega]). Both start in
     * the send's update, and the glide aims at the bottom the composer leaves, so the composer
     * must land first: a slower tail would let the glide land and then pull the bubble back
     * down after it. The session calls this as it sends; [ComposerTextInput] takes it on the
     * next text change it didn't make, and animates only a field taller than one line, never
     * under reduce motion.
     */
    fun shrinkOnNextClear(omega: Double) {
        pendingShrinkOmega = omega
    }

    fun takeShrinkOmega(): Double? = pendingShrinkOmega.also { pendingShrinkOmega = null }

    /** Drops a [shrinkOnNextClear] nothing took: the send didn't go, so no clear follows. */
    fun cancelShrink() {
        pendingShrinkOmega = null
    }

    companion object {
        /** The label fades and settles into place over the flight's last half, finishing with it. */
        fun chromeProgress(progress: Float): Float {
            val t = min(1f, max(0f, (progress - 0.5f) / 0.5f))
            return t * t * (3 - 2 * t)
        }

        /**
         * iOS `ImagePasteTextView.sendShrinkOmega`: `ComposerTunables.sendShrinkSpeed` ×
         * `BottomSpring.arrivalOmega`. At the default 1.25, up to three frames' lag behind the
         * glide still lands first.
         */
        fun sendShrinkOmega(arrivalOmega: Double, speed: Double = ComposerTextInputTunables.SEND_SHRINK_SPEED): Double =
            arrivalOmega * speed

        fun interpolate(from: Rect, to: Rect, progress: Float) = Rect(
            from.left + (to.left - from.left) * progress,
            from.top + (to.top - from.top) * progress,
            from.right + (to.right - from.right) * progress,
            from.bottom + (to.bottom - from.bottom) * progress,
        )
    }
}
