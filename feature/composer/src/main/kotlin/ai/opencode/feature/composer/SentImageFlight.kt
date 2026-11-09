package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.feature.composer.plusmenu.PhotoFlights
import ai.opencode.feature.composer.plusmenu.drawAspectFill
import ai.opencode.feature.media.SendImageTunables
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Window-space copies owned by one send, independent of row reuse and server attachment IDs
 * (iOS `SentImageFlight`). The outgoing order is the wire order; landing markers use that index
 * after the server replaces the phone's IDs ([imageID]).
 *
 * iOS moves UIKit snapshot views; here the copies are poses drawn by [SentImageFlightOverlay],
 * in root (window) pixels. The composer's thumbnails register with [sentImageSource], the
 * outgoing row hides with [sentImageDeparture], and the send flight (`:feature:session`'s
 * `SessionSendFlight`) reads each landing slot from the transcript row ([begin] / [step]
 * take a marker lookup). The remainder snapshot (`carryRemainder`, `maskImages`) is the row's
 * own pixels and stays with the session's flight.
 */
@Stable
class SentImageFlight {
    /** One copy: its wire [index], the decoded [image], where it left from and where it lands. */
    class Tile internal constructor(
        val index: Int,
        val image: ImageBitmap,
        val origin: Rect,
        var destination: Rect,
        /** The scrolled strip's visible band around the slot (iOS `destinationClip`, `.infinite` when null). */
        var destinationClip: Rect? = null,
        /** The thumbnail's corner radius in dp. */
        val radius: Float,
    ) {
        /** This frame's pose. */
        var frame: Rect = origin
            internal set
        var cornerRadius: Float = radius
            internal set
        var alpha: Float = 1f
            internal set
        var clip: Rect? = null
            internal set
    }

    private class Flight(
        var tiles: List<Tile>,
        val reducesMotion: Boolean,
        var progress: Float = 0f,
        var handoffStarted: Double? = null,
        /** iOS hides the row's `contentView` while its copies fly. */
        var hidesContent: Boolean = true,
    )

    private val sources = mutableMapOf<String, Rect>()
    private var prepared: List<Tile> = emptyList()
    private val flights = mutableMapOf<String, Flight>()
    private var dissolving: List<Tile> = emptyList()

    var reducesMotion = false
        private set

    /** Only send boundaries recompose; per-frame geometry only redraws ([frameTick]). */
    var isSending by mutableStateOf(false)
        private set

    /** Bumped on every step so the overlay redraws without recomposing. */
    internal var frameTick by mutableIntStateOf(0)
        private set

    internal var dissolveGeneration by mutableIntStateOf(0)
        private set

    /** A token for the prepared copies (iOS `preparedSnapshot`): a later [dissolvePrepared] only dissolves these. */
    val preparedToken: Any? get() = prepared.firstOrNull()

    val isActive: Boolean get() = prepared.isNotEmpty() || flights.isNotEmpty()

    /** Every copy on screen now, prepared or flying (not the ones dissolving). */
    val tiles: List<Tile> get() = prepared + flights.values.flatMap { it.tiles }

    /** Prepared copies fading after their send gave up ([dissolvePrepared]). */
    internal val dissolvingTiles: List<Tile> get() = dissolving

    /** Whether [itemID]'s row hides while its copies own the photos' pixels. */
    fun hidesContent(itemID: String): Boolean = flights[itemID]?.hidesContent == true

    /** A composer thumbnail's unclipped frame in root pixels. */
    fun register(id: String, frame: Rect) {
        sources[id] = frame
    }

    fun unregister(id: String) {
        sources.remove(id)
    }

    /**
     * Copies every visible image thumbnail at its place. [window] is the root's bounds: a
     * thumbnail scrolled out of the strip must not fly across the screen.
     */
    fun prepare(
        attachments: List<OpenCodeOutgoingAttachment>,
        reduceMotion: Boolean,
        window: Rect,
        decode: (OpenCodeOutgoingAttachment) -> ImageBitmap? = ::decode,
    ) {
        cancelPrepared()
        reducesMotion = reduceMotion
        prepared = attachments.filter { it.isImage }.mapIndexedNotNull { index, attachment ->
            val frame = sources[attachment.id] ?: return@mapIndexedNotNull null
            val image = decode(attachment) ?: return@mapIndexedNotNull null
            if (frame.isEmpty || !window.contains(frame.center)) return@mapIndexedNotNull null
            Tile(index, image, origin = frame, destination = frame, radius = PhotoFlights.SLOT_RADIUS)
        }
        changed()
    }

    /**
     * Whether any prepared copy's landing slot under [itemID] has been laid out yet: Compose
     * reports a new row's slots a frame after it arrives, where iOS lays the cell out on demand,
     * so the session's flight waits for them before it [begin]s.
     */
    fun hasDestination(itemID: String, markers: (String) -> Rect?): Boolean =
        prepared.any { markers(imageID(itemID, it.index)) != null }

    /**
     * Starts at the current slot. [markers] gives a landing slot's frame in root pixels by
     * [imageID]; a copy without one is dropped, and with none the send has no image flight.
     */
    fun begin(itemID: String, markers: (String) -> Rect?): Boolean {
        if (prepared.isEmpty()) return false
        finish(itemID)
        val tiles = prepared.mapNotNull { tile ->
            markers(imageID(itemID, tile.index))?.let { tile.destination = it; tile }
        }
        prepared = emptyList()
        if (tiles.isEmpty()) {
            changed()
            return false
        }
        flights[itemID] = Flight(tiles, reducesMotion)
        step(itemID, markers, progress = 0f)
        changed()
        return true
    }

    /**
     * One frame at the send clock's [progress]. Every photo shares the bubble's progress: a
     * second rect spring would lag the bubble, especially while the list scrolls. The
     * endpoints follow the visible slot ([markers]) as the composer and follower move; [clips]
     * gives a slot's scroll band so a copy can't hang over the transcript gutter. [lift] (px)
     * raises every photo's aim with the words the text flight holds level (iOS `SendLift`).
     */
    fun step(itemID: String, markers: (String) -> Rect?, progress: Float, clips: (String) -> Rect? = { null }, lift: Float = 0f) {
        val flight = flights[itemID] ?: return
        flight.progress = progress.coerceIn(0f, 1f)
        val p = flight.progress
        for (tile in flight.tiles) {
            val id = imageID(itemID, tile.index)
            markers(id)?.let { tile.destination = it; tile.destinationClip = clips(id) }
            val settled = flight.reducesMotion || flight.handoffStarted != null
            tile.frame = if (settled) {
                tile.destination
            } else {
                ComposerSendTransition.interpolate(tile.origin, tile.destination.translate(0f, -lift), p)
            }
            tile.cornerRadius = if (flight.reducesMotion) CARD_CORNER_RADIUS else tile.radius + (CARD_CORNER_RADIUS - tile.radius) * p
            tile.alpha = if (flight.reducesMotion) progress else 1f
            // Four photos live in a horizontal strip: meet its clipping band as well as the frame.
            // A lifted photo's band goes up with it, so nothing is cut off.
            val clipProgress = if (flight.reducesMotion) 1f else max(0f, (p - 0.8f) / 0.2f)
            tile.clip = tile.destinationClip?.let { band ->
                val lifted = if (settled) band else band.translate(0f, -lift)
                ComposerSendTransition.interpolate(union(tile.frame, lifted), lifted, clipProgress)
            }
        }
        frameTick++
    }

    fun isLanded(itemID: String): Boolean {
        val flight = flights[itemID] ?: return true
        return flight.handoffStarted != null || flight.reducesMotion || flight.progress == 1f
    }

    /**
     * Reveals the live row under exactly landed copies, then fades the copies over
     * `MotionTunables.reducedDuration`. Returns whether the hand-off is complete.
     */
    fun handOff(itemID: String, now: Double): Boolean {
        val flight = flights[itemID] ?: return true
        val landed = flight.tiles.all { tile ->
            abs(tile.frame.left - tile.destination.left) < 0.001f && abs(tile.frame.top - tile.destination.top) < 0.001f &&
                abs(tile.frame.width - tile.destination.width) < 0.001f && abs(tile.frame.height - tile.destination.height) < 0.001f
        }
        if (!landed) return false
        val start = flight.handoffStarted ?: now
        flight.handoffStarted = start
        val progress = min(1.0, (now - start) / MotionTunables.reducedDuration.value).toFloat()
        flight.hidesContent = false
        for (tile in flight.tiles) tile.alpha = 1 - progress
        frameTick++
        return progress == 1f
    }

    fun finish(itemID: String) {
        flights.remove(itemID) ?: return
        changed()
    }

    /** Fades the prepared copies when the send they belong to ([token]) gives up before landing. */
    fun dissolvePrepared(token: Any?) {
        val first = prepared.firstOrNull() ?: return
        if (first !== token) return
        dissolving = dissolving + prepared
        prepared = emptyList()
        dissolveGeneration++
        changed()
    }

    internal fun dissolved(tiles: List<Tile>) {
        dissolving = dissolving.filterNot { tile -> tiles.any { it === tile } }
        changed()
    }

    fun cancelPrepared() {
        prepared = emptyList()
        changed()
    }

    fun cancel() {
        cancelPrepared()
        flights.keys.toList().forEach(::finish)
    }

    private fun changed() {
        isSending = isActive
        frameTick++
    }

    companion object {
        /** iOS `SendLandingProbe.imageID`: a sent message's per-image landing marker. */
        fun imageID(messageID: String, index: Int): String = "$messageID#image-$index"

        /** iOS `TranscriptTunables.sendImageDuration` (“transcript.send.imageDuration”), seconds: the live knob. */
        val sendImageDuration: Double get() = SendImageTunables.duration

        /** iOS `TranscriptMetrics.cardCornerRadius`, dp. */
        val CARD_CORNER_RADIUS: Float = FileAttachmentMetrics.cardCornerRadius.value

        private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

        private fun decode(attachment: OpenCodeOutgoingAttachment): ImageBitmap? =
            if (attachment.data.isEmpty()) {
                null
            } else {
                runCatching { BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size)?.asImageBitmap() }.getOrNull()
            }
    }
}

/** iOS `SentImageSource`: registers a composer thumbnail's unclipped root frame under its attachment [id]. */
fun Modifier.sentImageSource(flight: SentImageFlight, id: String): Modifier = composed {
    DisposableEffect(flight, id) { onDispose { flight.unregister(id) } }
    onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInRoot()
        flight.register(id, Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height))
    }
}

/**
 * iOS `SentImageDeparture`: the outgoing row can stay mounted while its layout contracts; the
 * copies already own its pixels, so it stays transparent, untouchable and unread while they fly.
 */
fun Modifier.sentImageDeparture(flight: SentImageFlight): Modifier =
    if (!flight.isSending) {
        this
    } else {
        graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
    }

/** Draws the flight's copies over the whole window (iOS installs them in the key window). */
@Composable
fun SentImageFlightOverlay(flight: SentImageFlight, modifier: Modifier = Modifier) {
    val dissolve = remember { Animatable(1f) }
    val reduceMotion = rememberReduceMotion()
    val generation = flight.dissolveGeneration
    LaunchedEffect(generation) {
        if (generation == 0) return@LaunchedEffect
        val tiles = flight.dissolvingTiles
        dissolve.snapTo(1f)
        dissolve.animateTo(0f, Motion.microOut.animation(reduceMotion = reduceMotion))
        flight.dissolved(tiles)
        dissolve.snapTo(1f)
    }
    if (!flight.isSending && flight.dissolvingTiles.isEmpty()) return
    // The copies' frames are root px; the overlay may sit anywhere under the root.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        @Suppress("UNUSED_EXPRESSION")
        flight.frameTick
        val fading = dissolve.value
        translate(-origin.x, -origin.y) {
            for ((tile, alpha) in flight.dissolvingTiles.map { it to it.alpha * fading } + flight.tiles.map { it to it.alpha }) {
                val frame = tile.frame
                val radius = tile.cornerRadius * density
                val clip = tile.clip ?: Rect(-1e6f, -1e6f, 1e6f, 1e6f)
                clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                    val path = Path().apply { addRoundRect(RoundRect(frame, CornerRadius(radius))) }
                    clipPath(path) { drawAspectFill(tile.image, frame, alpha = alpha) }
                }
            }
        }
    }
}
