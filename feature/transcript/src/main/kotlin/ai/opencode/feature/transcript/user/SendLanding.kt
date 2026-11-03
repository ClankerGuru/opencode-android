package ai.opencode.feature.transcript.user

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize

/**
 * The list's half of a send's flight into its bubble (iOS `SendLandingProbe` markers and the
 * hand-off in `TranscriptListViewController+SendFlights.swift`). The session screen owns the
 * flight (an overlay over the transcript and the composer, iOS `ComposerSendTransition`), and
 * provides one of these as [LocalSendLanding]:
 *
 * - **Markers.** A user message's bubble (its message ID), the surfaces above it (images, files,
 *   a location: `#surfaces`) and its pending label (`#label`) report where they sit (window px)
 *   and record their drawing into a [GraphicsLayer] while hidden, the snapshots iOS takes of the
 *   cell. A hidden message draws nothing in place: the flight draws those layers instead, above
 *   the composer, wherever the list carries the bubble each frame.
 * - **Sends.** The list hands each send over as it arrives ([onSend], iOS `scheduleSendLanding`):
 *   whether it flies (a following reader, no jump glide), whether it went to the queued pill, and
 *   its words. The host answers whether it will fly into the bubble; the list then leaves that
 *   row's entrance to the flight.
 *
 * A plain object (per-frame values, no reducer state).
 */
@Stable
class SendLanding {
    /** A marker's recorded drawing and where it sits (window px, unclipped). */
    class Marker internal constructor(val layer: GraphicsLayer) {
        var bounds: Rect = Rect.Zero
            internal set
    }

    /** A send handed over by the list (iOS `landSentMessage`'s inputs). */
    data class Send(
        /** The sent message's ID (its bubble's marker), iOS `sentItemID`. */
        val itemID: String?,
        /** Whether its row is in the list (iOS `stableID != nil`). */
        val hasRow: Boolean,
        /** A following reader's send flies; a browsing reader's fades while the list returns to the end. */
        val flies: Boolean,
        /** It went into the queued pill above the composer. */
        val isQueued: Boolean,
        /** The words it shows (its bubble's, or the queued pill's), for a send the composer didn't capture. */
        val text: String?,
        /**
         * The list follows the end as it lands (iOS `behavior.mode == .following`): a send's photos
         * fly even when its words fade (a send under a jump's glide).
         */
        val isFollowing: Boolean = flies,
    )

    private val markers = HashMap<String, Marker>()
    private val hidden = mutableStateMapOf<String, Boolean>()

    /**
     * The session's flight host: lands [Send]. Returns true when the message will fly into its
     * bubble (its row's own entrance then stays out of the way). Null: no flights.
     */
    var onSend: ((Send) -> Boolean)? = null

    /** Whether the host holds a send's words waiting for the list (iOS `sendTransition.isPrepared`). */
    var isAwaitingSend: () -> Boolean = { false }

    /**
     * Where a new last row's bottom will rest once the follower brings it in (window px): the
     * list's visible area above its bottom padding. A flight whose bubble is still below the
     * screen aims here at once (iOS lays the off-screen bubble out at the end, `moveOffset`),
     * and retargets onto the bubble as it comes into view.
     */
    var listEnd: androidx.compose.ui.geometry.Rect? = null

    /** The last send generation a list saw, so a list made for the session a draft's send created still lands it. */
    var lastSendGeneration: Int = 0

    /** The marker [key] (a message ID, optionally with [SURFACES_SUFFIX] or [LABEL_SUFFIX]), if it's on screen. */
    fun marker(key: String): Marker? = markers[key]

    /** Hides [itemID]'s row parts in place: the flight draws them (iOS `hide(cell.contentView)`). */
    fun hide(itemID: String) {
        if (hidden[itemID] != true) hidden[itemID] = true
    }

    /** Shows [itemID]'s row again where it is (iOS `finishLanding`). */
    fun show(itemID: String) {
        hidden.remove(itemID)
    }

    /** Every hidden row shows again (the screen is going away). */
    fun showAll() {
        hidden.clear()
    }

    /** Whether [itemID]'s row is hidden for its flight. Snapshot state: draw phases re-run when it changes. */
    fun isHidden(itemID: String): Boolean = hidden[itemID] == true

    internal fun register(key: String, marker: Marker) {
        markers[key] = marker
    }

    internal fun unregister(key: String, marker: Marker) {
        if (markers[key] === marker) markers.remove(key)
    }

    internal fun send(send: Send): Boolean = onSend?.invoke(send) ?: false

    companion object {
        /** Marks where a message's surfaces start (iOS `SendLandingProbe.surfacesSuffix`). */
        const val SURFACES_SUFFIX = "#surfaces"

        /** Marks a pending label above a message (iOS `SendLandingProbe.labelSuffix`). */
        const val LABEL_SUFFIX = "#label"

        /**
         * iOS `SendLandingProbe.imageID`: a sent message's image slot, by its index among the
         * message's images (the wire order), for the composer's `SentImageFlight` to land on.
         */
        fun imageID(messageID: String, index: Int): String = "$messageID#image-$index"
    }
}

/** The session's [SendLanding]; null draws every row in place and nothing flies. */
val LocalSendLanding = staticCompositionLocalOf<SendLanding?> { null }

/** One part of a message's landing marker (iOS `SendLandingProbe(itemID: itemID + suffix)`). */
@Stable
internal class SendLandingProbe(val landing: SendLanding, val itemID: String, val marker: SendLanding.Marker)

/** The marker for [itemID] + [suffix] while a [LocalSendLanding] is provided; null otherwise. */
@Composable
internal fun rememberSendLandingProbe(itemID: String?, suffix: String = ""): SendLandingProbe? {
    val landing = LocalSendLanding.current
    if (landing == null || itemID == null) return null
    val layer = rememberGraphicsLayer()
    val key = itemID + suffix
    val probe = remember(landing, key, layer) { SendLandingProbe(landing, itemID, SendLanding.Marker(layer)) }
    DisposableEffect(probe) {
        landing.register(key, probe.marker)
        onDispose { landing.unregister(key, probe.marker) }
    }
    return probe
}

/**
 * A frame-only marker for [key] while a [LocalSendLanding] is provided (iOS `SendLandingProbe`
 * on a sent image's slot): it reports where it sits and never records, since the message's
 * surfaces marker already draws the slot for the flight.
 */
@Composable
internal fun rememberSendLandingMarker(key: String?): SendLanding.Marker? {
    val landing = LocalSendLanding.current
    if (landing == null || key == null) return null
    val layer = rememberGraphicsLayer()
    val marker = remember(landing, key, layer) { SendLanding.Marker(layer) }
    DisposableEffect(landing, key, marker) {
        landing.register(key, marker)
        onDispose { landing.unregister(key, marker) }
    }
    return marker
}

/** Reports where a frame-only [marker] sits (window px, unclipped). */
internal fun Modifier.sendLandingMarker(marker: SendLanding.Marker?): Modifier =
    if (marker == null) this else onGloballyPositioned { marker.bounds = Rect(it.positionInWindow(), it.size.toSize()) }

/**
 * Reports where this part of [probe]'s message sits, and records its drawing instead of
 * drawing in place while the message is hidden for its flight.
 */
internal fun Modifier.sendLandingProbe(probe: SendLandingProbe?): Modifier {
    if (probe == null) return this
    val marker = probe.marker
    return this
        .onGloballyPositioned { marker.bounds = Rect(it.positionInWindow(), it.size.toSize()) }
        .drawWithContent {
            if (probe.landing.isHidden(probe.itemID)) {
                marker.layer.record { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }
}
