package ai.opencode.feature.media

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.rememberAppAppearance
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Sources

/**
 * Which corners of a source are rounded (iOS `CACornerMask`): a collage tile rounds only the
 * collage's own. Absolute (not start/end), like the mask and the flight's window frame.
 */
data class MediaCorners(
    val topLeft: Boolean = true,
    val topRight: Boolean = true,
    val bottomLeft: Boolean = true,
    val bottomRight: Boolean = true,
) {
    fun radii(radius: Float): List<Float> = listOf(
        if (topLeft) radius else 0f,
        if (topRight) radius else 0f,
        if (bottomRight) radius else 0f,
        if (bottomLeft) radius else 0f,
    )

    fun roundRect(rect: Rect, radius: Float): RoundRect {
        val r = radii(max(radius, 0f))
        return RoundRect(
            rect = rect,
            topLeft = CornerRadius(r[0]),
            topRight = CornerRadius(r[1]),
            bottomRight = CornerRadius(r[2]),
            bottomLeft = CornerRadius(r[3]),
        )
    }

    fun shape(radius: Dp): RoundedCornerShape = RoundedCornerShape(
        topStart = if (topLeft) radius else 0.dp,
        topEnd = if (topRight) radius else 0.dp,
        bottomEnd = if (bottomRight) radius else 0.dp,
        bottomStart = if (bottomLeft) radius else 0.dp,
    )

    companion object {
        val All = MediaCorners()
    }
}

/**
 * The views full-screen media zooms out of and back into (iOS `MediaZoomSources`). Thumbnails
 * register their layout coordinates by media ID; lists reuse items, so each source carries the
 * media it shows now and lookups check it. A UI registry like iOS's `MediaZoomSources.shared`
 * (not a business service), only touched on the main thread.
 */
@Stable
class MediaZoomSources internal constructor() {
    /** A registered view (iOS `MediaZoomSourceView`). */
    class Source internal constructor() {
        /** The first is the media this view shows; the rest are pages it stands in for, like the collage's “+2” tile. */
        var mediaIDs: List<String> = emptyList()
            internal set
        internal var coordinates: LayoutCoordinates? = null
        internal var cornerRadius: Dp = 0.dp
        internal var corners: MediaCorners = MediaCorners.All
        internal var density: Float = 1f

        /** Its exact frame in the window, unclipped. */
        val windowRect: Rect?
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
                val origin = coordinates.positionInWindow()
                return Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            }

        /** On screen: a view scrolled out of sight is no place to land. */
        internal val isOnScreen: Boolean
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return false
                val visible = coordinates.boundsInWindow()
                return visible.width > 0f && visible.height > 0f
            }

        val cornerRadiusPx: Float get() = cornerRadius.value * density
    }

    private val sources = LinkedHashSet<Source>()

    /** The media up full screen. */
    var hiddenID: String? by mutableStateOf(null)
        private set

    internal fun add(source: Source) {
        sources.add(source)
    }

    internal fun remove(source: Source) {
        sources.remove(source)
    }

    fun isHidden(ids: List<String>): Boolean = hiddenID?.let(ids::contains) ?: false

    /** Out of the transcript while its media is up full screen, so a drag never shows it twice; an item recomposed meanwhile comes up hidden too. */
    fun hide(id: String?) {
        if (id == hiddenID) return
        hiddenID = id
    }

    /** The on-screen view showing [id], preferring one that shows it over one standing in for it. */
    fun view(id: String?): Source? {
        if (id == null) return null
        val candidates = sources.filter { it.mediaIDs.contains(id) && it.isOnScreen }
        return candidates.firstOrNull { it.mediaIDs.firstOrNull() == id } ?: candidates.firstOrNull()
    }

    companion object {
        /** The process's one registry (iOS `MediaZoomSources.shared`). */
        val shared: MediaZoomSources = MediaZoomSources()
    }
}

private class MediaZoomSourceElement(
    val ids: List<String>,
    val cornerRadius: Dp,
    val corners: MediaCorners,
) : ModifierNodeElement<MediaZoomSourceNode>() {
    override fun create() = MediaZoomSourceNode(ids, cornerRadius, corners)

    override fun update(node: MediaZoomSourceNode) {
        node.source.mediaIDs = ids
        node.source.cornerRadius = cornerRadius
        node.source.corners = corners
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mediaZoomSource"
        properties["ids"] = ids
    }

    override fun equals(other: Any?) =
        other is MediaZoomSourceElement && other.ids == ids && other.cornerRadius == cornerRadius && other.corners == corners

    override fun hashCode() = (ids.hashCode() * 31 + cornerRadius.hashCode()) * 31 + corners.hashCode()
}

private class MediaZoomSourceNode(
    ids: List<String>,
    cornerRadius: Dp,
    corners: MediaCorners,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    val source = MediaZoomSources.Source().also {
        it.mediaIDs = ids
        it.cornerRadius = cornerRadius
        it.corners = corners
    }

    override fun onAttach() {
        MediaZoomSources.shared.add(source)
    }

    override fun onDetach() {
        MediaZoomSources.shared.remove(source)
        source.coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        source.coordinates = coordinates
        source.density = currentValueOf(LocalDensity).density
    }
}

/**
 * Marks a card or row that opens full-screen media, so the viewer zooms out of it and back
 * into it (iOS `.mediaZoomSource(id:cornerRadius:)`). Only its frame and shape are used; the
 * card itself stays visible.
 */
fun Modifier.mediaZoomSource(id: String, cornerRadius: Dp = 0.dp): Modifier =
    this then MediaZoomSourceElement(listOf(id), cornerRadius, MediaCorners.All)

/**
 * An image the full-screen viewer zooms out of and back into (iOS `MediaThumbnail`), so the
 * flight can start and land on exactly its frame and shape. It leaves the transcript (alpha 0)
 * while its media is up full screen. Decorative: the button around it carries the label and
 * takes the taps.
 *
 * @param ids the media this shows, then any pages it stands in for.
 * @param fits fill crops to the frame; fit sizes itself to the image, at most [maxHeight] tall.
 * @param corners the rounded corners: a collage tile rounds only the collage's own.
 */
@Composable
fun MediaThumbnail(
    image: Bitmap,
    ids: List<String>,
    modifier: Modifier = Modifier,
    fits: Boolean = false,
    maxHeight: Dp = Dp.Infinity,
    cornerRadius: Dp = 0.dp,
    corners: MediaCorners = MediaCorners.All,
) {
    val bitmap = remember(image) { image.asImageBitmap() }
    val shape = remember(cornerRadius, corners) { corners.shape(cornerRadius) }
    val sources = MediaZoomSources.shared
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = if (fits) ContentScale.Fit else ContentScale.Crop,
        filterQuality = FilterQuality.High,
        modifier = modifier
            .thumbnailSize(image, fits, maxHeight)
            .then(MediaZoomSourceElement(ids, cornerRadius, corners))
            .graphicsLayer {
                alpha = if (sources.isHidden(ids)) 0f else 1f
                this.shape = shape
                clip = true
            },
    )
}

/** iOS `sizeThatFits`: fill takes the proposal (the image's size where unspecified); fit fits the image, at most [maxHeight] tall. */
private fun Modifier.thumbnailSize(image: Bitmap, fits: Boolean, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    // iOS sizes decoded originals at scale 1: pixels as points.
    val imageSize = Size(image.width.dp.toPx(), image.height.dp.toPx())
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else imageSize.width
    val proposedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY
    val size = if (fits) {
        val cap = if (maxHeight == Dp.Infinity) Float.POSITIVE_INFINITY else maxHeight.toPx()
        MediaZoomGeometry.fittedSize(imageSize, Size(width, min(proposedHeight, cap)))
    } else {
        Size(width, if (proposedHeight.isInfinite()) imageSize.height else proposedHeight)
    }
    val w = size.width.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
    val h = size.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    layout(w, h) { placeable.place(0, 0) }
}

// MARK: - Viewer

/**
 * What the presentation needs from the full-screen content, and what the content needs from
 * the presentation (iOS `MediaViewerState`). Content reads it from [LocalMediaViewer].
 */
@Stable
class MediaViewerState(sourceID: String?) {
    /** What's on screen, for the flight back into the transcript. */
    var sourceID: String? by mutableStateOf(sourceID)
        private set

    /** The image on screen, flown in and out in place of the content (not observed). */
    var image: Bitmap? = null
        private set

    /** A zoomed-in image pans instead of dismissing. */
    var isZoomed by mutableStateOf(false)

    /** Close and the page position step aside while a drag dismisses. */
    var chromeHidden by mutableStateOf(false)
        internal set

    /** How long the last chrome change fades (0.15 s hiding, 0.25 s showing), for content that animates its own chrome. */
    var chromeFadeSeconds: Double = 0.25
        internal set

    /** Whether the status bar is hidden while the dark room is up (such as while photo viewer controls are hidden). */
    var statusBarHidden by mutableStateOf(false)
        internal set

    /** The image on screen is drawn by the flight above the content: while it flies in, while it's dragged, and while it flies home. */
    var mediaHidden by mutableStateOf(false)
        internal set

    /** Where each page lays its image out, by media ID, so flights land exactly on it. */
    val mediaFrames: MutableMap<String, Rect> = HashMap()

    val mediaFrame: Rect? get() = sourceID?.let { mediaFrames[it] }

    var dismiss: () -> Unit = {}
        internal set
    internal var onShow: () -> Unit = {}

    /** The content moved to another page or finished loading its image. */
    fun show(sourceID: String?, image: Bitmap?) {
        val moved = sourceID != this.sourceID
        this.sourceID = sourceID
        this.image = image
        isZoomed = false
        if (moved) onShow()
    }

    fun setStatusBarHidden(hidden: Boolean) {
        if (statusBarHidden == hidden) return
        statusBarHidden = hidden
    }
}

/** iOS `EnvironmentValues.mediaViewer`. */
val LocalMediaViewer = staticCompositionLocalOf<MediaViewerState?> { null }

/**
 * Full-screen media and files (iOS `MediaPresenter`). Media opens the Photos way: it flies out
 * of what was tapped, follows a drag down while shrinking, rounding toward the thumbnail's
 * corners and letting the session show through, and flies back on release (or springs back
 * from a short drag). Documents zoom out of their source. Under Reduce Motion both
 * cross-fade, and a drag down fades them out. One viewer at a time.
 *
 * [MediaView] owns the presenter and provides it as [LocalMediaPresenter] to the content it
 * hosts; the transcript image viewer goes through the store ([presentImage]).
 */
@Stable
class MediaPresenter internal constructor() {
    internal class Presentation(
        val sourceID: String?,
        val isMedia: Boolean,
        val reducesMotion: Boolean,
        val screenName: String,
        val content: @Composable (MediaViewerState) -> Unit,
    )

    internal var presentation: Presentation? by mutableStateOf(null)
    internal var imageViewerIsUp: () -> Boolean = { false }
    internal var sendImage: (TranscriptImagePreview) -> Unit = {}

    val isPresenting: Boolean get() = presentation != null || imageViewerIsUp()

    /** Presents [content] full screen; ignored while a viewer is up. */
    fun present(
        sourceID: String?,
        isMedia: Boolean,
        reducesMotion: Boolean,
        screenName: String? = null,
        content: @Composable (MediaViewerState) -> Unit,
    ): Boolean {
        // One viewer at a time.
        if (isPresenting) return false
        presentation = Presentation(sourceID, isMedia, reducesMotion, screenName ?: if (isMedia) "Media Viewer" else "File Viewer", content)
        return true
    }

    /** iOS `SessionView.presentImage`: the transcript image viewer, through [MediaFeature]. */
    fun presentImage(preview: TranscriptImagePreview) {
        if (presentation != null) return
        sendImage(preview)
    }

    companion object {
        /**
         * Media plays in a dark room; documents and text viewers follow the app's appearance
         * (iOS `interfaceStyle(isMedia:)`; the DEBUG lab override is iOS-only).
         */
        fun isDarkRoom(isMedia: Boolean): Boolean = isMedia
    }
}

/** The presenter of the [MediaView] above this content, or `null` outside one. */
val LocalMediaPresenter = staticCompositionLocalOf<MediaPresenter?> { null }

/**
 * The Photos drag (iOS `MediaDismissDrag`): how media shrinks, rounds, and lets the session
 * show through as it's pulled down, and when letting go puts it away. Distances in one unit;
 * [dismisses] takes dp and dp/s (iOS points).
 */
data class MediaDismissDrag(val screenHeight: Float) {
    /** How far down the media is at its smallest and the session fully shows. */
    val range: Float = max(screenHeight * 0.5f, 1f)

    /** 0 at rest, 1 at the bottom of the range. */
    fun progress(distance: Float): Float = min(max(distance / range, 0f), 1f)

    fun scale(distance: Float): Float = 1 - (1 - minimumScale) * progress(distance)

    /** The drag distance a scale corresponds to, to pick up a spring-back. */
    fun distance(atScale: Float): Float = min(max((1 - atScale) / (1 - minimumScale), 0f), 1f) * range

    fun backdropAlpha(distance: Float): Float = 1 - progress(distance)

    /** Square at full screen, rounding toward the thumbnail's corners with the scale, so a release flies home already the thumbnail's shape. */
    fun cornerRadius(distance: Float, toward: Float): Float = toward * progress(distance)

    companion object {
        const val minimumScale: Float = 0.6f
        const val dismissDistance: Float = 120f
        const val dismissVelocity: Float = 900f

        /** Far enough, or flicked down; a flick back up keeps it. */
        fun dismisses(distance: Float, velocity: Float): Boolean =
            (distance > dismissDistance && velocity > -300f) || velocity > dismissVelocity
    }
}

/** The viewer's springs (iOS `UIView.animate(springDuration:bounce:)` values). */
internal object MediaMotion {
    /** Fly in, content open, content dismiss (0.4 s, critically damped). */
    val open: MotionSpring = Motion.settle
    /** A short drag springs back (0.4 s, 0.12 bounce). */
    val springBack = MotionSpring(0.4, 0.12)
    /** Flying home into the thumbnail (0.42 s, critically damped). */
    val home = MotionSpring(0.42, 0.0)
    /** Nowhere to land: carried on and faded (0.35 s). */
    val away = MotionSpring(0.35, 0.0)
    /** The backdrop's fade in (0.3 s ease-out). */
    const val backdropIn = 0.3
    /** Fade style's dismissal and recovery (0.2 s). */
    const val fade = 0.2
}

/** The content's transform about the host's centre (iOS `host.view.transform`). */
internal data class HostTransform(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    companion object {
        val Identity = HostTransform()
    }
}

/** The image in flight, above the content, which hides its own copy. */
internal class Flight(image: Bitmap, frame: Rect) {
    var image: Bitmap by mutableStateOf(image)
    var frame: Rect by mutableStateOf(frame)
    var radius by mutableFloatStateOf(0f)
    var corners: MediaCorners by mutableStateOf(MediaCorners.All)
    var alpha by mutableFloatStateOf(1f)
    var scale by mutableFloatStateOf(1f)
}

/**
 * Drives one presentation (iOS `MediaViewerController`): the flight, the backdrop, the
 * content's transform, the dismiss drag and the status bar. A plain object owning per-frame
 * motion; the host composable draws its state.
 */
@Stable
internal class MediaViewerController(
    val state: MediaViewerState,
    private val isMedia: Boolean,
    reducesMotion: Boolean,
    private val scope: CoroutineScope,
    private val density: Density,
    private val onDismissed: () -> Unit,
) {
    enum class Style {
        /** Flies out of its source and is dragged down to dismiss. */
        Photos,
        /** The system zoom, for documents. */
        Zoom,
        /** Reduce Motion: cross-fades, and a drag down fades it out. */
        Fade,
    }

    val style: Style = if (reducesMotion) Style.Fade else if (isMedia) Style.Photos else Style.Zoom

    var backdropAlpha by mutableFloatStateOf(1f)
    var hostAlpha by mutableFloatStateOf(1f)
    var hostTransform by mutableStateOf(HostTransform.Identity)
    /** Fade style: the whole viewer's opacity. */
    var viewAlpha by mutableFloatStateOf(1f)
    var flight: Flight? by mutableStateOf(null)
    /** Light over the dark room; the session's own once it shows through. */
    var statusBarLight by mutableStateOf(isMedia && reducesMotion)
    var isLeaving by mutableStateOf(false)
        private set

    /** The host's size and where it sits in the window (sources are in window coordinates). */
    var bounds by mutableStateOf(Rect.Zero)
    var originInWindow = Offset.Zero

    private var hasOpened = false
    private var finished = false
    /** Bumped by each flight animation, so an interrupted one's end doesn't hand the image back to the content. */
    private var flightGeneration = 0
    private var flightJob: Job? = null
    private var backdropJob: Job? = null

    // A drag, relative to where the image was when it began.
    private var dragAnchor = Offset.Zero
    private var dragStart = Rect.Zero
    private var dragStartDistance = 0f
    private var dragCarry = Offset.Zero
    private var lastFadeDistance = 0f

    private val trace = MediaTransitionTrace()

    init {
        state.dismiss = { close() }
        when (style) {
            Style.Photos -> {
                // The image flies in first; the content shows it once it lands.
                state.mediaHidden = true
                state.chromeHidden = true
                backdropAlpha = 0f
                hostAlpha = 0f
                state.onShow = { hideSource() }
            }
            Style.Fade -> viewAlpha = 0f
            Style.Zoom -> hostAlpha = 0f
        }
    }

    /** Whether the status bar is hidden (iOS `prefersStatusBarHidden`). */
    val statusBarHidden: Boolean
        get() {
            if (isLeaving || !state.statusBarHidden) return false
            return if (style == Style.Photos) statusBarLight else true
        }

    private fun updateStatusBarLight(light: Boolean) {
        if (style != Style.Photos) return
        if (statusBarLight != light) statusBarLight = light
    }

    /** iOS `viewDidAppear`: the presentation runs once the content has laid out. */
    fun open() {
        if (hasOpened) return
        hasOpened = true
        when (style) {
            Style.Photos -> flyIn()
            Style.Fade -> scope.launch { animate(viewAlpha, 1f, animationSpec = tween(millis(MediaMotion.fade))) { v, _ -> viewAlpha = v } }
            Style.Zoom -> zoomIn()
        }
    }

    // MARK: Photos

    private val source: MediaZoomSources.Source? get() = MediaZoomSources.shared.view(state.sourceID)

    /** Like Photos, the thumbnail leaves the transcript while its media is up. */
    private fun hideSource() {
        MediaZoomSources.shared.hide(state.sourceID)
    }

    /** Where the image rests: where the page lays it out, or else fitted to the screen. */
    private val restingFrame: Rect
        get() {
            val image = state.image ?: return bounds
            if (image.width <= 0 || image.height <= 0) return bounds
            state.mediaFrame?.let { return it }
            val fitted = MediaZoomGeometry.fittedSize(Size(image.width.toFloat(), image.height.toFloat()), bounds.size)
            return Rect(Offset(bounds.center.x - fitted.width / 2, bounds.center.y - fitted.height / 2), fitted)
        }

    private fun sourceFrame(source: MediaZoomSources.Source): Rect? =
        source.windowRect?.translate(-originInWindow.x, -originInWindow.y)

    private fun shape(source: MediaZoomSources.Source?): Pair<Float, MediaCorners> =
        if (source == null) 0f to MediaCorners.All else source.cornerRadiusPx to source.corners

    /** The image in flight, created at [frame] the first time it's needed; caught where it is otherwise. */
    private fun flightView(image: Bitmap, frame: Rect): Flight {
        flightJob?.cancel()
        flight?.let {
            if (it.image !== image) it.image = image
            return it
        }
        return Flight(image, frame).also { flight = it }
    }

    /** The content draws the image again; the flight goes once it has. */
    private fun land(generation: Int) {
        if (generation != flightGeneration || isLeaving) return
        state.mediaHidden = false
        flight = null
    }

    private fun catchBackdrop() {
        backdropJob?.cancel()
        backdropJob = null
    }

    private fun flyIn() {
        val source = source
        hideSource()
        hostAlpha = 1f
        updateStatusBarLight(true)
        setChromeHidden(false)
        backdropJob = scope.launch {
            animate(backdropAlpha, 1f, animationSpec = tween(millis(MediaMotion.backdropIn), easing = Motion.EaseOut)) { v, _ -> backdropAlpha = v }
        }
        val image = state.image
        val from = source?.let(::sourceFrame)
        if (source != null && from != null && image != null) {
            val (radius, corners) = shape(source)
            val rest = restingFrame
            val flight = flightView(image, from)
            flight.radius = radius
            flight.corners = corners
            flightGeneration += 1
            val generation = flightGeneration
            trace.begin("open", scope, density, source = from to radius, rest = rest, state = state, sample = ::traceSample)
            flightJob = scope.launch {
                spring(MediaMotion.open) { p ->
                    flight.frame = lerp(from, rest, p)
                    flight.radius = radius + (0f - radius) * p
                }
                trace.end("open")
                land(generation)
            }
        } else {
            state.mediaHidden = false
            hostAlpha = 0f
            val start = from?.let { transform(restingFrame, it) } ?: HostTransform(scale = 0.94f)
            hostTransform = start
            flightJob = scope.launch {
                spring(MediaMotion.open) { p ->
                    hostTransform = lerp(start, HostTransform.Identity, p)
                    hostAlpha = p.coerceIn(0f, 1f)
                }
            }
        }
    }

    /** Documents: the system zoom out of their source (or a small scale-up without one). */
    private fun zoomIn() {
        val from = source?.let(::sourceFrame)
        val start = from?.let { transform(bounds, it) } ?: HostTransform(scale = 0.94f)
        hostTransform = start
        hostAlpha = 0f
        flightJob = scope.launch {
            spring(MediaMotion.open) { p ->
                hostTransform = lerp(start, HostTransform.Identity, p)
                hostAlpha = p.coerceIn(0f, 1f)
            }
        }
    }

    /** The transform about the view's centre that maps [rect] onto [target]. */
    private fun transform(rect: Rect, target: Rect): HostTransform {
        val scale = min(target.width / max(rect.width, 1f), target.height / max(rect.height, 1f))
        val center = bounds.center
        return HostTransform(
            scale = scale,
            tx = target.center.x - center.x - scale * (rect.center.x - center.x),
            ty = target.center.y - center.y - scale * (rect.center.y - center.y),
        )
    }

    private val drag: MediaDismissDrag get() = MediaDismissDrag(screenHeight = bounds.height)

    // MARK: Drag

    /** iOS `gestureRecognizerShouldBegin`: down and steeper than sideways; never on a zoomed image. */
    fun shouldBegin(translation: Offset): Boolean {
        if (isLeaving || style == Style.Zoom) return false
        if (translation.y <= abs(translation.x)) return false
        // Documents fade only from a drag their scroll view can't take ([documentFadeConnection]).
        if (!isMedia) return false
        return !state.isZoomed
    }

    fun dragBegan(anchor: Offset) {
        when (style) {
            Style.Photos -> if (state.image != null) dragImageBegan(anchor) else dragContentBegan(anchor)
            Style.Fade -> lastFadeDistance = 0f
            Style.Zoom -> Unit
        }
    }

    fun dragChanged(translation: Offset) {
        when (style) {
            Style.Photos -> if (state.image != null) dragImageChanged(translation) else dragContentChanged(translation)
            Style.Fade -> fadeChanged(max(0f, translation.y))
            Style.Zoom -> Unit
        }
    }

    /** [velocity] in px/s. */
    fun dragEnded(translation: Offset, velocity: Velocity) {
        when (style) {
            Style.Photos -> {
                if (state.image != null) trace.end("drag")
                val distance = translation.y + dragStartDistance
                if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity.y))) {
                    flyOut(velocity)
                } else if (state.image != null) {
                    springBack(velocity)
                } else {
                    contentSpringBack()
                }
            }
            Style.Fade -> fadeEnded(max(0f, translation.y), velocity.y)
            Style.Zoom -> Unit
        }
    }

    private fun toDp(px: Float): Float = px / density.density

    /** An image is dragged as the flight: it shrinks about the finger, so the spot it holds stays under it, and rounds toward the thumbnail. */
    private fun dragImageBegan(anchor: Offset) {
        val image = state.image ?: return
        val rest = restingFrame
        flightGeneration += 1
        val flight = flightView(image, rest)
        catchBackdrop()
        state.mediaHidden = true
        flight.corners = shape(source).second
        dragAnchor = anchor
        dragStart = flight.frame
        dragStartDistance = drag.distance(atScale = flight.frame.width / max(rest.width, 1f))
        setChromeHidden(true)
        trace.begin("drag", scope, density, source = source?.let { s -> sourceFrame(s)?.let { it to s.cornerRadiusPx } }, rest = rest, state = state, sample = ::traceSample)
    }

    private fun dragImageChanged(translation: Offset) {
        val flight = flight ?: return
        val rest = restingFrame
        val distance = translation.y + dragStartDistance
        val scale = drag.scale(distance)
        val relative = scale * rest.width / max(dragStart.width, 1f)
        val center = Offset(
            dragAnchor.x + translation.x + (dragStart.center.x - dragAnchor.x) * relative,
            dragAnchor.y + translation.y + (dragStart.center.y - dragAnchor.y) * relative,
        )
        val size = Size(rest.width * scale, rest.height * scale)
        flight.frame = Rect(Offset(center.x - size.width / 2, center.y - size.height / 2), size)
        flight.radius = drag.cornerRadius(distance, toward = shape(source).first)
        backdropAlpha = drag.backdropAlpha(distance)
        updateStatusBarLight(backdropAlpha > 0.5f)
    }

    private fun springBack(velocity: Velocity) {
        val flight = flight ?: return
        val rest = restingFrame
        flightGeneration += 1
        val generation = flightGeneration
        setChromeHidden(false)
        updateStatusBarLight(true)
        trace.begin("spring", scope, density, source = null, rest = rest, state = state, sample = ::traceSample)
        val speed = springVelocity(velocity, flight.frame, rest)
        val from = flight.frame
        val radius = flight.radius
        val backdrop = backdropAlpha
        flightJob = scope.launch {
            spring(MediaMotion.springBack, speed) { p ->
                flight.frame = lerp(from, rest, p)
                flight.radius = radius * (1 - p)
                backdropAlpha = backdrop + (1 - backdrop) * p
            }
            if (generation == flightGeneration) trace.end("spring")
            land(generation)
        }
    }

    /** Other content (video, a file still loading) moves as a whole. */
    private fun dragContentBegan(anchor: Offset) {
        // Catch a spring-back where it is.
        flightJob?.cancel()
        val current = hostTransform
        catchBackdrop()
        dragAnchor = anchor
        val center = bounds.center
        dragStartDistance = drag.distance(atScale = current.scale)
        dragCarry = Offset(
            current.tx - (dragAnchor.x - center.x) * (1 - current.scale),
            current.ty - (dragAnchor.y - center.y) * (1 - current.scale),
        )
        setChromeHidden(true)
    }

    private fun dragContentChanged(translation: Offset) {
        val distance = translation.y + dragStartDistance
        val scale = drag.scale(distance)
        val center = bounds.center
        hostTransform = HostTransform(
            scale = scale,
            tx = (dragAnchor.x - center.x) * (1 - scale) + translation.x + dragCarry.x,
            ty = (dragAnchor.y - center.y) * (1 - scale) + translation.y + dragCarry.y,
        )
        backdropAlpha = drag.backdropAlpha(distance)
        updateStatusBarLight(backdropAlpha > 0.5f)
    }

    private fun contentSpringBack() {
        val start = hostTransform
        val backdrop = backdropAlpha
        flightJob = scope.launch {
            spring(MediaMotion.springBack) { p ->
                hostTransform = lerp(start, HostTransform.Identity, p)
                backdropAlpha = backdrop + (1 - backdrop) * p
            }
        }
        setChromeHidden(false)
        updateStatusBarLight(true)
    }

    private fun setChromeHidden(hidden: Boolean) {
        if (state.chromeHidden == hidden) return
        state.chromeFadeSeconds = if (hidden) 0.15 else 0.25
        state.chromeHidden = hidden
    }

    private fun close() {
        when (style) {
            Style.Photos -> flyOut(Velocity.Zero)
            Style.Zoom -> zoomOut()
            Style.Fade -> fadeOut()
        }
    }

    /** A spring's initial velocity, in units of the distance to travel. */
    private fun springVelocity(velocity: Velocity, frame: Rect, target: Rect): Float {
        val dx = target.center.x - frame.center.x
        val dy = target.center.y - frame.center.y
        val length = dx * dx + dy * dy
        if (length <= 1f) return 0f
        return min(max((velocity.x * dx + velocity.y * dy) / length, 0f), 8f)
    }

    private fun flyOut(velocity: Velocity) {
        if (isLeaving) return
        isLeaving = true
        flightGeneration += 1
        setChromeHidden(true)
        updateStatusBarLight(false)
        catchBackdrop()
        val source = source
        val target = source?.let(::sourceFrame)
        val backdrop = backdropAlpha
        val image = state.image
        if (image != null) {
            val flight = flightView(image, restingFrame)
            state.mediaHidden = true
            val (radius, corners) = shape(source)
            flight.corners = corners
            trace.begin("close", scope, density, source = target?.let { it to radius }, rest = restingFrame, state = state, sample = ::traceSample)
            val from = flight.frame
            val fromRadius = flight.radius
            if (target != null) {
                val speed = springVelocity(velocity, from, target)
                flightJob = scope.launch {
                    spring(MediaMotion.home, speed) { p ->
                        flight.frame = lerp(from, target, p)
                        flight.radius = fromRadius + (radius - fromRadius) * p
                        backdropAlpha = backdrop * (1 - p)
                    }
                    finish()
                }
            } else {
                // Nowhere to land: carry on the way it was thrown and fade.
                val drop = bounds.height * 0.25f
                flightJob = scope.launch {
                    spring(MediaMotion.away) { p ->
                        flight.frame = from.translate(0f, drop * p)
                        flight.scale = 1 - 0.2f * p
                        flight.alpha = 1 - p
                        backdropAlpha = backdrop * (1 - p)
                    }
                    finish()
                }
            }
        } else {
            flightJob?.cancel()
            val current = hostTransform
            val end = if (target != null) {
                transform(restingFrame, target)
            } else {
                current.copy(ty = current.ty + current.scale * (bounds.height * 0.25f / max(current.scale, 0.1f)))
            }
            val alpha = hostAlpha
            flightJob = scope.launch {
                spring(MediaMotion.open) { p ->
                    hostTransform = lerp(current, end, p)
                    hostAlpha = alpha * (1 - p)
                    backdropAlpha = backdrop * (1 - p)
                }
                finish()
            }
        }
    }

    /** Documents: back into their source (the system zoom's dismissal). */
    private fun zoomOut() {
        if (isLeaving) return
        isLeaving = true
        val target = source?.let(::sourceFrame)
        val start = hostTransform
        val end = target?.let { transform(bounds, it) } ?: HostTransform(scale = 0.94f)
        val alpha = hostAlpha
        flightJob?.cancel()
        flightJob = scope.launch {
            spring(MediaMotion.open) { p ->
                hostTransform = lerp(start, end, p)
                hostAlpha = alpha * (1 - p)
            }
            finish()
        }
    }

    // MARK: Fade

    private fun fadeChanged(distance: Float) {
        lastFadeDistance = distance
        viewAlpha = 1 - (1 - drag.backdropAlpha(distance)) * 0.7f
        setChromeHidden(toDp(distance) > 12f)
    }

    private fun fadeEnded(distance: Float, velocity: Float) {
        if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity))) {
            fadeOut()
        } else {
            val alpha = viewAlpha
            flightJob?.cancel()
            flightJob = scope.launch { animate(alpha, 1f, animationSpec = tween(millis(MediaMotion.fade))) { v, _ -> viewAlpha = v } }
            setChromeHidden(false)
        }
    }

    private fun fadeOut() {
        if (isLeaving) return
        isLeaving = true
        val alpha = viewAlpha
        flightJob?.cancel()
        flightJob = scope.launch {
            animate(alpha, 0f, animationSpec = tween(millis(Motion.reducedFade), easing = Motion.EaseInOut)) { v, _ -> viewAlpha = v }
            finish()
        }
    }

    /** Fade style for documents: a drag down their scroll view can't take. */
    val documentFadeConnection: NestedScrollConnection = object : NestedScrollConnection {
        private var dragging = false
        private var distance = 0f

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (style != Style.Fade || isMedia || isLeaving || source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y <= 0f) return Offset.Zero
            dragging = true
            distance += available.y
            fadeChanged(distance)
            return Offset(0f, available.y)
        }

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!dragging || available.y >= 0f) return Offset.Zero
            val take = max(available.y, -distance)
            distance += take
            fadeChanged(distance)
            return Offset(0f, take)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            dragging = false
            val ended = distance
            distance = 0f
            fadeEnded(ended, available.y)
            return available
        }
    }

    // MARK: Lifecycle

    private fun finish() {
        if (finished) return
        finished = true
        trace.end("close")
        // The thumbnail comes back in the same frame the viewer leaves.
        MediaZoomSources.shared.hide(null)
        onDismissed()
    }

    /** iOS `viewDidDisappear`. */
    fun disposed() {
        flightJob?.cancel()
        backdropJob?.cancel()
        trace.cancel()
        if (style == Style.Photos || MediaZoomSources.shared.hiddenID == state.sourceID) MediaZoomSources.shared.hide(null)
    }

    // MARK: Trace

    private fun traceSample(): MediaTransitionTrace.Sample? {
        val flight = flight ?: return null
        val frame = flight.frame.translate(originInWindow.x, originInWindow.y)
        val sourceAlpha = MediaZoomSources.shared.view(state.sourceID)?.let { if (MediaZoomSources.shared.isHidden(it.mediaIDs)) 0f else 1f } ?: -1f
        return MediaTransitionTrace.Sample(frame, flight.radius, backdropAlpha, sourceAlpha)
    }

    // MARK: Animation

    /** iOS `UIView.animate(springDuration:bounce:initialSpringVelocity:)`: one spring of progress 0 → 1 for every property. */
    private suspend fun spring(spring: MotionSpring, velocity: Float = 0f, update: (Float) -> Unit) {
        animate(0f, 1f, initialVelocity = velocity, animationSpec = spring.spec(visibilityThreshold = 0.0005f)) { value, _ -> update(value) }
        update(1f)
    }

    private fun millis(seconds: Double): Int = (seconds * 1000).roundToInt()

    private fun lerp(a: Rect, b: Rect, p: Float): Rect = Rect(
        a.left + (b.left - a.left) * p,
        a.top + (b.top - a.top) * p,
        a.right + (b.right - a.right) * p,
        a.bottom + (b.bottom - a.bottom) * p,
    )

    private fun lerp(a: HostTransform, b: HostTransform, p: Float) = HostTransform(
        a.scale + (b.scale - a.scale) * p,
        a.tx + (b.tx - a.tx) * p,
        a.ty + (b.ty - a.ty) * p,
    )
}

/**
 * The presented viewer (iOS `MediaViewerController`'s view): a black backdrop (hidden for
 * documents), the content, and the flight drawn above it, over the whole window. It takes
 * every touch while up, hides the content below from TalkBack (the host does), dismisses on
 * back, and runs the dark room's status bar.
 */
@Composable
internal fun MediaViewerHost(
    sourceID: String?,
    isMedia: Boolean,
    reducesMotion: Boolean,
    screenName: String,
    onDismissed: () -> Unit,
    content: @Composable (MediaViewerState) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentDismissed by rememberUpdatedState(onDismissed)
    val state = remember { MediaViewerState(sourceID) }
    val controller = remember {
        MediaViewerController(state, isMedia, reducesMotion, scope, density) { currentDismissed() }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val slop = LocalViewConfiguration.current.touchSlop

    LaunchedEffect(controller) {
        // Opening an image dismisses the keyboard.
        keyboard?.hide()
        focus.clearFocus(force = true)
        snapshotFlow { controller.bounds }.first { it.width > 0f && it.height > 0f }
        // One frame for the pages to lay out and report where their images rest.
        withFrameNanos { }
        controller.open()
    }
    DisposableEffect(controller) { onDispose { controller.disposed() } }
    MediaBackHandler(enabled = !controller.isLeaving) { state.dismiss() }
    StatusBarEffect(controller)

    val dark = MediaPresenter.isDarkRoom(isMedia)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                controller.originInWindow = coordinates.positionInWindow()
                val size = coordinates.size
                val rect = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
                if (controller.bounds != rect) controller.bounds = rect
            }
            .graphicsLayer { alpha = controller.viewAlpha }
            .semantics { paneTitle = screenName }
            .pointerInput(controller) { dismissDrag(controller, slop) }
            .nestedScroll(controller.documentFadeConnection),
    ) {
        if (controller.style != MediaViewerController.Style.Zoom) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = controller.backdropAlpha }
                    .background(Color.Black),
            )
        }
        val themed: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val transform = controller.hostTransform
                        alpha = controller.hostAlpha
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.tx
                        translationY = transform.ty
                        transformOrigin = TransformOrigin.Center
                    }
                    .then(
                        if (controller.style == MediaViewerController.Style.Zoom) {
                            Modifier.background(MaterialTheme.colorScheme.background)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                CompositionLocalProvider(LocalMediaViewer provides state) { content(state) }
            }
        }
        if (dark) {
            val appearance = rememberAppAppearance()
            OpenCodeTheme(theme = appearance.theme, appearance = AppearanceColorScheme.Dark, content = themed)
        } else {
            OpenCodeAppTheme(content = themed)
        }
        FlightLayer(controller)
    }
}

/** The image in flight, drawn above the content in the host's coordinates (only the draw phase reads its frames). */
@Composable
private fun FlightLayer(controller: MediaViewerController) {
    val flight = controller.flight ?: return
    val bitmap: ImageBitmap = remember(flight.image) { flight.image.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        val frame = flight.frame
        if (frame.width <= 0f || frame.height <= 0f || flight.alpha <= 0f) return@Canvas
        withTransform({ scale(flight.scale, flight.scale, pivot = frame.center) }) {
            val path = Path().apply { addRoundRect(flight.corners.roundRect(frame, flight.radius)) }
            clipPath(path) {
                // Aspect fill (iOS `.scaleAspectFill`).
                val imageW = bitmap.width.toFloat()
                val imageH = bitmap.height.toFloat()
                val scale = max(frame.width / imageW, frame.height / imageH)
                val srcW = frame.width / scale
                val srcH = frame.height / scale
                val srcOffset = IntOffset(((imageW - srcW) / 2).roundToInt(), ((imageH - srcH) / 2).roundToInt())
                drawImage(
                    image = bitmap,
                    srcOffset = srcOffset,
                    srcSize = IntSize(srcW.roundToInt().coerceAtMost(bitmap.width), srcH.roundToInt().coerceAtMost(bitmap.height)),
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                    alpha = flight.alpha,
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

/**
 * The dismiss pan (iOS `dismissPan`, one finger): it looks at every touch before the content
 * (the initial pass), and claims a drag heading down more than sideways once it passes the
 * touch slop, unless the image is zoomed. Claimed drags are consumed, so the pager and the
 * zoom never see them: pages turn only once a drag has shown it isn't a dismissal.
 */
private suspend fun PointerInputScope.dismissDrag(controller: MediaViewerController, slop: Float) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (controller.isLeaving) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var anchor = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPosition(change.uptimeMillis, change.position)
            total += change.positionChange()
            if (total.getDistance() > slop) {
                if (!controller.shouldBegin(total)) return@awaitEachGesture
                change.consume()
                anchor = change.position
                break
            }
        }
        controller.dragBegan(anchor)
        var translation = Offset.Zero
        var ended = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    val v = tracker.calculateVelocity()
                    ended = true
                    controller.dragEnded(translation, v)
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                event.changes.forEach { it.consume() }
                translation = change.position - anchor
                controller.dragChanged(translation)
            }
        } finally {
            if (!ended) controller.dragEnded(translation, Velocity.Zero)
        }
    }
}

/**
 * The dark room's status bar (iOS `preferredStatusBarStyle` / `prefersStatusBarHidden`): light
 * icons over the black backdrop, the session's own once it shows through, hidden while the
 * photo viewer's controls are hidden. Restored when the viewer goes.
 */
@Composable
private fun StatusBarEffect(controller: MediaViewerController) {
    val activity = LocalContext.current.findActivity() ?: return
    val window = activity.window
    val insets = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    val originalLight = remember(window) { insets.isAppearanceLightStatusBars }
    val light = controller.statusBarLight
    val hidden = controller.statusBarHidden
    LaunchedEffect(light, hidden) {
        insets.isAppearanceLightStatusBars = if (light) false else originalLight
        if (hidden) insets.hide(WindowInsetsCompat.Type.statusBars()) else insets.show(WindowInsetsCompat.Type.statusBars())
    }
    DisposableEffect(window) {
        onDispose {
            insets.isAppearanceLightStatusBars = originalLight
            insets.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/**
 * Back dismisses the viewer (iOS's accessibility escape). The platform's back dispatcher at
 * overlay priority, so it wins over the app's own handlers while the viewer is up.
 */
@Composable
internal fun MediaBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val callback = OnBackInvokedCallback { currentOnBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
