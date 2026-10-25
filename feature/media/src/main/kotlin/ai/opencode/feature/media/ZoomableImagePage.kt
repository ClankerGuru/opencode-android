package ai.opencode.feature.media

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** UIKit's `UIEdgeInsets`, for [MediaZoomGeometry]. */
data class MediaInsets(val top: Float, val left: Float, val bottom: Float, val right: Float) {
    companion object {
        val Zero = MediaInsets(0f, 0f, 0f, 0f)
    }
}

/**
 * Where a zoomable image sits and how far it zooms, in a page's units (iOS
 * `MediaZoomGeometry`, `Media/ZoomableImagePage.swift`). The image lays out fitted at zoom
 * scale 1, so 1 is fit. Android works in pixels, so callers pass `displayScale = 1` with
 * pixel sizes.
 */
object MediaZoomGeometry {
    /** However small the image, it zooms at least this far. */
    const val smallestMaximumScale: Float = 4f

    /** A double tap on an image that nearly fills the page zooms this far. */
    const val doubleTapScale: Float = 2.5f

    /** The image fitted inside the page, whole. */
    fun fittedSize(image: Size, bounds: Size): Size {
        if (image.width <= 0 || image.height <= 0 || bounds.width <= 0 || bounds.height <= 0) return Size.Zero
        val scale = min(bounds.width / image.width, bounds.height / image.height)
        return Size(image.width * scale, image.height * scale)
    }

    /** Insets that centre content smaller than the page along that axis. */
    fun centeringInsets(content: Size, bounds: Size): MediaInsets {
        val horizontal = max((bounds.width - content.width) / 2, 0f)
        val vertical = max((bounds.height - content.height) / 2, 0f)
        return MediaInsets(top = vertical, left = horizontal, bottom = vertical, right = horizontal)
    }

    /** Far enough to see the image's pixels at twice their size on screen. */
    fun maximumScale(pixelSize: Size, fitted: Size, displayScale: Float): Float {
        if (fitted.width <= 0 || displayScale <= 0) return smallestMaximumScale
        val native = pixelSize.width / (fitted.width * displayScale)
        return max(smallestMaximumScale, native * 2)
    }

    /**
     * Like Photos: an image with bars around it zooms to fill the page; one that already
     * nearly fills it zooms in further.
     */
    fun doubleTapScale(fitted: Size, bounds: Size, maximum: Float): Float {
        if (fitted.width <= 0 || fitted.height <= 0) return 1f
        val fill = max(bounds.width / fitted.width, bounds.height / fitted.height)
        return min(max(fill, doubleTapScale), maximum)
    }

    /**
     * The part of the fitted image to zoom into: the page's shape at [scale], centred on
     * [point] but kept inside the image, and centred on the image along any axis where the
     * image is the smaller.
     */
    fun zoomRect(point: Offset, scale: Float, bounds: Size, content: Size): Rect {
        val size = Size(bounds.width / scale, bounds.height / scale)
        fun origin(point: Float, length: Float, content: Float): Float =
            if (length >= content) (content - length) / 2 else min(max(point - length / 2, 0f), content - length)
        val origin = Offset(origin(point.x, size.width, content.width), origin(point.y, size.height, content.height))
        return Rect(origin, size)
    }

    /**
     * The content offset that puts [point] (in zoomed content) in the middle of the page, kept
     * within the content's edges.
     */
    fun contentOffset(point: Offset, bounds: Size, content: Size, insets: MediaInsets): Offset {
        fun clamp(value: Float, low: Float, high: Float): Float = min(max(value, low), max(low, high))
        return Offset(
            clamp(point.x - bounds.width / 2, -insets.left, content.width - bounds.width + insets.right),
            clamp(point.y - bounds.height / 2, -insets.top, content.height - bounds.height + insets.bottom),
        )
    }

    /**
     * A zoomed image at its left or right edge lets a drag further that way turn the page
     * instead of pulling at the edge, as Photos does.
     */
    fun handsOffToPager(offset: Offset, content: Size, bounds: Size, insets: MediaInsets, translation: Offset): Boolean {
        if (abs(translation.x) <= abs(translation.y)) return false
        if (translation.x > 0) return offset.x <= -insets.left + 0.5f
        return offset.x >= content.width - bounds.width + insets.right - 0.5f
    }
}

/**
 * One page's zoom (iOS `MediaZoomScrollView`): a scroll view's zoom scale and content offset,
 * with the image fitted at scale 1 and centred by insets. A plain object owning per-frame
 * motion; the page composable drives it from gestures.
 */
@Stable
class MediaZoomState internal constructor(image: Bitmap) {
    internal var image: Bitmap = image
        set(value) {
            if (value === field) return
            field = value
            laidOutSize = Size.Zero
            reportedFrame = null
        }

    var zoomScale by mutableFloatStateOf(1f)
        private set
    var contentOffset by mutableStateOf(Offset.Zero)
        private set

    /** The page size the image was last fitted to. */
    private var laidOutSize = Size.Zero
    private var fitted = Size.Zero
    private var reportedFrame: Rect? = null
    private var isZoomedIn = false
    var maximumZoomScale = MediaZoomGeometry.smallestMaximumScale
        private set
    private val minimumZoomScale = 1f

    /** A Reduce Motion cross-fade: the frame the image is fading out of, and how far in. */
    internal var fadingFrom by mutableStateOf<Rect?>(null)
    internal var fade by mutableFloatStateOf(1f)

    internal var onZoomChange: (Boolean) -> Unit = {}
    internal var onFrame: (Rect) -> Unit = {}

    private var animation: Job? = null

    val isZoomed: Boolean get() = zoomScale > minimumZoomScale + 0.01f

    /** Mirrors iOS `isZoomedIn`, which drives accessibility. */
    var zoomedForAccessibility by mutableStateOf(false)
        private set

    val bounds: Size get() = laidOutSize
    val contentSize: Size get() = Size(fitted.width * zoomScale, fitted.height * zoomScale)
    val contentInset: MediaInsets get() = MediaZoomGeometry.centeringInsets(contentSize, laidOutSize)

    /** Where the image is drawn in the page: fitted, or where a zoom has it. */
    val imageFrame: Rect get() = Rect(Offset(-contentOffset.x, -contentOffset.y), contentSize)

    /** iOS `layoutSubviews`: fit when the page size changes. */
    fun layout(size: Size) {
        if (size != laidOutSize) fit(size)
    }

    /**
     * Fits the image to the page, keeping a zoom's scale and the point in the middle of the
     * screen across a size change, like a rotation.
     */
    private fun fit(size: Size) {
        if (size.width <= 0 || size.height <= 0) return
        val content = contentSize
        val keep: Pair<Float, Offset>? = if (isZoomed && content.width > 0 && content.height > 0) {
            zoomScale to Offset(
                (contentOffset.x + laidOutSize.width / 2) / content.width,
                (contentOffset.y + laidOutSize.height / 2) / content.height,
            )
        } else {
            null
        }
        animation?.cancel()
        laidOutSize = size
        zoomScale = 1f
        fitted = MediaZoomGeometry.fittedSize(Size(image.width.toFloat(), image.height.toFloat()), size)
        maximumZoomScale = MediaZoomGeometry.maximumScale(
            pixelSize = Size(image.width.toFloat(), image.height.toFloat()),
            fitted = fitted,
            displayScale = 1f,
        )
        if (keep != null) zoomScale = min(keep.first, maximumZoomScale)
        contentOffset = if (keep != null) {
            MediaZoomGeometry.contentOffset(
                point = Offset(keep.second.x * contentSize.width, keep.second.y * contentSize.height),
                bounds = size,
                content = contentSize,
                insets = contentInset,
            )
        } else {
            Offset(-contentInset.left, -contentInset.top)
        }
        reportZoom()
    }

    private fun reportZoom() {
        val zoomed = isZoomed
        if (zoomed != isZoomedIn) {
            isZoomedIn = zoomed
            zoomedForAccessibility = zoomed
            onZoomChange(zoomed)
        }
        reportFrame()
    }

    /**
     * Reports the image's frame within the full-screen page bounds, whether fitted or zoomed,
     * so flights start and land without safe-area offset.
     */
    fun reportFrame() {
        if (laidOutSize.width <= 0 || laidOutSize.height <= 0) return
        val frame = imageFrame
        if (frame == reportedFrame) return
        reportedFrame = frame
        onFrame(frame)
    }

    // MARK: Offsets

    private fun offsetRange(scale: Float): Pair<Offset, Offset> {
        val content = Size(fitted.width * scale, fitted.height * scale)
        val insets = MediaZoomGeometry.centeringInsets(content, laidOutSize)
        val low = Offset(-insets.left, -insets.top)
        val high = Offset(
            max(-insets.left, content.width - laidOutSize.width + insets.right),
            max(-insets.top, content.height - laidOutSize.height + insets.bottom),
        )
        return low to high
    }

    private fun clamped(offset: Offset, scale: Float): Offset {
        val (low, high) = offsetRange(scale)
        return Offset(offset.x.coerceIn(low.x, high.x), offset.y.coerceIn(low.y, high.y))
    }

    private fun set(scale: Float, offset: Offset) {
        zoomScale = scale
        contentOffset = offset
        reportZoom()
    }

    // MARK: Gestures

    internal fun stopAnimation() {
        animation?.cancel()
        animation = null
    }

    /** A pinch about [centroid] (page coordinates); past either limit with resistance. */
    internal fun pinch(rawScale: Float, centroid: Offset, pan: Offset) {
        val scale = rubberScale(rawScale)
        val origin = Offset(-contentOffset.x, -contentOffset.y)
        val ratio = scale / zoomScale
        val newOrigin = Offset(
            centroid.x - (centroid.x - origin.x) * ratio + pan.x,
            centroid.y - (centroid.y - origin.y) * ratio + pan.y,
        )
        set(scale, clamped(Offset(-newOrigin.x, -newOrigin.y), scale))
    }

    private fun rubberScale(raw: Float): Float = when {
        raw > maximumZoomScale -> maximumZoomScale * (raw / maximumZoomScale).pow(0.35f)
        raw < minimumZoomScale -> minimumZoomScale * (raw / minimumZoomScale).pow(0.35f)
        else -> raw
    }

    /** Pans by [delta] (finger movement), rubber-banding past the content's edges. */
    internal fun pan(rawOffset: Offset) {
        val (low, high) = offsetRange(zoomScale)
        fun band(value: Float, low: Float, high: Float, dimension: Float): Float = when {
            value < low -> low - rubber(low - value, dimension)
            value > high -> high + rubber(value - high, dimension)
            else -> value
        }
        set(zoomScale, Offset(band(rawOffset.x, low.x, high.x, laidOutSize.width), band(rawOffset.y, low.y, high.y, laidOutSize.height)))
    }

    /** UIScrollView's rubber band: `(1 − 1 / (x × 0.55 / d + 1)) × d`. */
    private fun rubber(excess: Float, dimension: Float): Float {
        if (dimension <= 0) return 0f
        return (1 - 1 / (excess * 0.55f / dimension + 1)) * dimension
    }

    /** Release after a pinch: back within the limits, about [focus]. */
    internal fun settleZoom(scope: CoroutineScope, focus: Offset) {
        val target = zoomScale.coerceIn(minimumZoomScale, maximumZoomScale)
        val ratio = target / zoomScale
        val origin = Offset(-contentOffset.x, -contentOffset.y)
        val newOrigin = Offset(focus.x - (focus.x - origin.x) * ratio, focus.y - (focus.y - origin.y) * ratio)
        animateTo(scope, target, clamped(Offset(-newOrigin.x, -newOrigin.y), target), crossFade = false)
    }

    /** Release after a pan: decelerates with momentum, or bounces back flush with the edges. */
    internal fun fling(scope: CoroutineScope, velocity: Offset, density: androidx.compose.ui.unit.Density) {
        val inside = clamped(contentOffset, zoomScale)
        if (inside != contentOffset) {
            animateTo(scope, zoomScale, inside, crossFade = false)
            return
        }
        val (low, high) = offsetRange(zoomScale)
        animation?.cancel()
        animation = scope.launch {
            val animatable = Animatable(contentOffset, Offset.VectorConverter)
            animatable.updateBounds(low, high)
            animatable.animateDecay(Offset(-velocity.x, -velocity.y), splineBasedDecay<Offset>(density)) {
                set(zoomScale, value)
            }
        }
    }

    // MARK: Zooming

    /** Zooms in about [point], in page coordinates. */
    internal fun zoomIn(scope: CoroutineScope, point: Offset, reducesMotion: Boolean) {
        val inImage = Offset((point.x + contentOffset.x) / zoomScale, (point.y + contentOffset.y) / zoomScale)
        val scale = MediaZoomGeometry.doubleTapScale(fitted = fitted, bounds = laidOutSize, maximum = maximumZoomScale)
        val rect = MediaZoomGeometry.zoomRect(point = inImage, scale = scale, bounds = laidOutSize, content = fitted)
        // UIScrollView `zoom(to:)`: the rect fills the page.
        val target = min(laidOutSize.width / rect.width, laidOutSize.height / rect.height).coerceIn(minimumZoomScale, maximumZoomScale)
        val offset = clamped(Offset(rect.left * target, rect.top * target), target)
        animateTo(scope, target, offset, crossFade = reducesMotion)
    }

    internal fun zoomInAtCentre(scope: CoroutineScope, reducesMotion: Boolean) {
        val frame = imageFrame
        zoomIn(scope, frame.center, reducesMotion)
    }

    internal fun zoomOut(scope: CoroutineScope, reducesMotion: Boolean) {
        val insets = MediaZoomGeometry.centeringInsets(fitted, laidOutSize)
        animateTo(scope, minimumZoomScale, Offset(-insets.left, -insets.top), crossFade = reducesMotion)
    }

    /** Pages left behind return to fit. */
    fun resetZoom() {
        if (zoomScale == minimumZoomScale) return
        stopAnimation()
        val insets = MediaZoomGeometry.centeringInsets(fitted, laidOutSize)
        set(minimumZoomScale, Offset(-insets.left, -insets.top))
    }

    /**
     * The scroll view's own zoom animation, which a touch interrupts; under Reduce Motion a
     * 0.2 s cross-fade instead.
     */
    private fun animateTo(scope: CoroutineScope, scale: Float, offset: Offset, crossFade: Boolean) {
        animation?.cancel()
        if (crossFade) {
            fadingFrom = imageFrame
            fade = 0f
            set(scale, offset)
            animation = scope.launch {
                try {
                    animate(0f, 1f, animationSpec = tween(200)) { value, _ -> fade = value }
                } finally {
                    fade = 1f
                    fadingFrom = null
                }
            }
            return
        }
        val startScale = zoomScale
        val startOffset = contentOffset
        animation = scope.launch {
            animate(0f, 1f, animationSpec = Motion.settle.spec()) { p, _ ->
                set(
                    startScale + (scale - startScale) * p,
                    Offset(startOffset.x + (offset.x - startOffset.x) * p, startOffset.y + (offset.y - startOffset.y) * p),
                )
            }
        }
    }
}

/**
 * One image fitted to its page, reporting where so the viewer's flight lands on it (iOS
 * `ZoomableImagePage`). Pinching (about the fingers, past either limit with resistance),
 * panning (with momentum, flush with the edges), and double tapping behave like Photos. At
 * fit, drags belong to the pager and the dismissal; zoomed, a drag from a side edge heading
 * out turns the page ([MediaZoomGeometry.handsOffToPager]).
 *
 * @param isHidden the flight above draws it for now.
 * @param onFrame where the image sits in the viewer's coordinates: fitted, or where a zoom
 *   has it, so a flight home starts from what's on screen.
 */
@Composable
fun ZoomableImagePage(
    image: Bitmap,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    isHidden: Boolean = false,
    onSingleTap: () -> Unit = {},
    onZoomChange: (Boolean) -> Unit = {},
    onFrame: (Rect) -> Unit = {},
) {
    val reducesMotion = rememberReduceMotion()
    val state = remember { MediaZoomState(image) }
    state.image = image
    val currentZoomChange by rememberUpdatedState(onZoomChange)
    val currentFrame by rememberUpdatedState(onFrame)
    val currentTap by rememberUpdatedState(onSingleTap)
    state.onZoomChange = { currentZoomChange(it) }
    state.onFrame = { currentFrame(it) }
    val bitmap: ImageBitmap = remember(image) { image.asImageBitmap() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop

    LaunchedEffect(isSelected) {
        if (!isSelected) state.resetZoom()
        state.reportFrame()
    }

    val zoomed = state.zoomedForAccessibility
    Spacer(
        modifier
            .fillMaxSize()
            .testTag("media-zoom-page")
            .onSizeChanged { size: IntSize -> state.layout(Size(size.width.toFloat(), size.height.toFloat())) }
            .semantics {
                contentDescription = label
                role = Role.Image
                if (zoomed) stateDescription = "Zoomed in"
                customActions = listOf(
                    if (zoomed) {
                        CustomAccessibilityAction("Zoom Out") { state.zoomOut(scope, reducesMotion); true }
                    } else {
                        CustomAccessibilityAction("Zoom In") { state.zoomInAtCentre(scope, reducesMotion); true }
                    },
                )
            }
            .pointerInput(state) {
                detectTapGestures(
                    onDoubleTap = { point ->
                        if (state.isZoomed) state.zoomOut(scope, reducesMotion) else state.zoomIn(scope, point, reducesMotion)
                    },
                    onTap = { currentTap() },
                )
            }
            .pointerInput(state) {
                zoomGestures(state, scope, slop, density)
            }
            .drawBehind {
                if (isHidden) return@drawBehind
                val from = state.fadingFrom
                if (from != null) {
                    drawImageIn(bitmap, from, 1 - state.fade)
                    drawImageIn(bitmap, state.imageFrame, state.fade)
                } else {
                    drawImageIn(bitmap, state.imageFrame, 1f)
                }
            },
    )
}

private fun DrawScope.drawImageIn(bitmap: ImageBitmap, frame: Rect, alpha: Float) {
    if (alpha <= 0f || frame.width <= 0f || frame.height <= 0f) return
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
        dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
}

/**
 * Pinch and pan, the way the page's scroll view takes them: at fit a one-finger drag never
 * begins (the pager and the dismissal own it); zoomed, a drag from a side edge heading out
 * hands off to the pager. Runs after the viewer's dismiss drag (which looks first and, when
 * it claims a drag, consumes it).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.zoomGestures(
    state: MediaZoomState,
    scope: CoroutineScope,
    slop: Float,
    density: androidx.compose.ui.unit.Density,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        // A touch interrupts deceleration and the zoom animation.
        state.stopAnimation()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var mode = 0 // 0 undecided, 1 pan, 2 pinch
        var total = Offset.Zero
        var rawScale = state.zoomScale
        var rawOffset = state.contentOffset
        var focus = down.position
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                when (mode) {
                    1 -> state.fling(scope, tracker.calculateVelocity().let { Offset(it.x, it.y) }, density)
                    2 -> state.settleZoom(scope, focus)
                }
                return@awaitEachGesture
            }
            if (mode == 0 && event.changes.any { it.isConsumed }) return@awaitEachGesture
            if (pressed.size >= 2) {
                if (mode != 2) {
                    mode = 2
                    rawScale = state.zoomScale
                }
                val zoom = event.calculateZoom()
                focus = event.calculateCentroid(useCurrent = true)
                val pan = event.calculateCentroid(useCurrent = true) - event.calculateCentroid(useCurrent = false)
                rawScale *= zoom
                state.pinch(rawScale, focus, pan)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            tracker.addPosition(change.uptimeMillis, change.position)
            when (mode) {
                0 -> {
                    total += change.positionChange()
                    if (total.getDistance() > slop) {
                        if (!state.isZoomed) return@awaitEachGesture
                        if (MediaZoomGeometry.handsOffToPager(
                                offset = state.contentOffset,
                                content = state.contentSize,
                                bounds = state.bounds,
                                insets = state.contentInset,
                                translation = total,
                            )
                        ) return@awaitEachGesture
                        mode = 1
                        rawOffset = state.contentOffset
                        change.consume()
                    }
                }
                1, 2 -> {
                    if (mode == 2) {
                        // One finger lifted from a pinch: carry on as a pan.
                        mode = 1
                        rawOffset = state.contentOffset
                    }
                    if (!state.isZoomed) {
                        change.consume()
                        continue
                    }
                    rawOffset -= change.positionChange()
                    state.pan(rawOffset)
                    change.consume()
                }
            }
        }
    }
}
