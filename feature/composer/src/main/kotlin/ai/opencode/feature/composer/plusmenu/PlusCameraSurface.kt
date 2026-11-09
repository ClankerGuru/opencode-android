package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.composer.AttachmentCameraView
import ai.opencode.feature.composer.ComposerMorphTunables
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The camera viewfinder opened from the photo sheet's camera tile: inactive while browsing
 * photos so opening the sheet never starts the camera, and expanded from the tile's measured
 * frame on tap. iOS `PlusCameraPresentation`. Frames are in screen pixels.
 */
class PlusCameraPresentation {
    /** The camera tile's frame; written on layout, read only when the viewfinder opens. */
    var tile: Rect = Rect.Zero
    var viewport: Rect = Rect.Zero
    var expanded: Boolean by mutableStateOf(false)
    var mounted: Boolean by mutableStateOf(false)

    fun reset() {
        expanded = false
        mounted = false
        tile = Rect.Zero
        viewport = Rect.Zero
    }

    fun shouldActivateCamera(sheetIsOpen: Boolean): Boolean = sheetIsOpen && expanded

    /** iOS `handleScenePhase(.background)`: going to the background folds the viewfinder. */
    fun handleBackground() {
        expanded = false
    }

    /**
     * Where a captured photo's flight leaves from: a square inset in the open viewfinder, else
     * the tile, else [fallback]. [density] converts the dp [inset].
     */
    fun captureSourceFrame(density: Float, fallback: Rect = Rect.Zero, inset: Float = PlusCameraTunables.captureInset): Rect {
        if (expanded && viewport.width > 0 && viewport.height > 0) {
            val side = max(min(viewport.width, viewport.height) - inset * density * 2, PhotoFlights.SLOT_SIDE * density)
            return Rect(Offset(viewport.center.x - side / 2, viewport.center.y - side / 2), Size(side, side))
        }
        if (tile != Rect.Zero) return tile
        return fallback
    }
}

/** iOS `PlusCameraTunables` (keys `composer.camera.*`). */
object PlusCameraTunables {
    /** `composer.camera.captureInset` (dp): the shutter flight's inset. */
    const val captureInset = 20f

    /** `composer.camera.captureRadius` (dp): the shutter flight's corner radius. */
    const val captureRadius = 24f
}

/**
 * The sheet's inline viewfinder (iOS `PlusCameraSurface`): when [PlusCameraPresentation.expanded]
 * it grows from the camera tile to full screen over the sheet and only then activates the
 * camera ([PlusCameraPresentation.shouldActivateCamera]); a capture goes straight to
 * [onCapture] with no review and folds the viewfinder back. Reduce Motion fades it in place.
 */
@Composable
fun PlusCameraSurface(
    presentation: PlusCameraPresentation,
    sheetIsOpen: Boolean,
    onCapture: (ByteArray) -> Unit,
    /** The sheet's content reveal (iOS `sheet.content`): the viewfinder fades with the sheet's content. */
    sheetContent: () -> Float = { 1f },
) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember { Animatable(0f) }
    val active = presentation.shouldActivateCamera(sheetIsOpen)

    DisposableEffect(lifecycle, presentation) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) presentation.handleBackground()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            presentation.reset()
        }
    }
    LaunchedEffect(sheetIsOpen) { if (!sheetIsOpen) presentation.expanded = false }
    LaunchedEffect(active) {
        val target = if (active) 1f else 0f
        val phase = if (active) MotionPhase.Enter else MotionPhase.Exit
        val spec = Motion.panelOpen.animation<Float>(phase, reduceMotion)
        if (!sheetIsOpen) progress.snapTo(target) else progress.animateTo(target, spec)
    }

    fun close() {
        presentation.expanded = false
    }

    if (!active && progress.value <= 0f) return
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var origin by remember { mutableStateOf(Offset.Zero) }
        var full by remember { mutableStateOf(Size.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    origin = it.positionOnScreen()
                    full = Size(it.size.width.toFloat(), it.size.height.toFloat())
                },
        ) {
            val p = progress.value
            val fullRect = Rect(Offset.Zero, full)
            val tile = presentation.tile.translate(-origin.x, -origin.y)
            val viewport = presentation.viewport.translate(-origin.x, -origin.y)
            val unmeasured = full == Size.Zero
            // The viewfinder grows from the tile to the whole screen…
            val rect = if (reduceMotion || tile == Rect.Zero || unmeasured) fullRect else lerp(tile, fullRect, p.coerceIn(0f, 1.2f))
            // …through a mask that grows from the sheet's grid (its top corners) to the screen.
            val mask = if (reduceMotion || presentation.viewport == Rect.Zero || unmeasured) fullRect else lerp(viewport, fullRect, p.coerceIn(0f, 1f))
            val radius = if (reduceMotion) 0f else ComposerMorphTunables.topRadius.toFloat() * (1 - p).coerceIn(0f, 1f)
            val dp = LocalDensity.current
            Box(
                Modifier
                    .offset { IntOffset(mask.left.roundToInt(), mask.top.roundToInt()) }
                    .size(with(dp) { max(mask.width, 1f).toDp() }, with(dp) { max(mask.height, 1f).toDp() })
                    // iOS `.opacity(active ? sheet.content : 0)` on the expand/collapse animation.
                    .graphicsLayer { alpha = p.coerceIn(0f, 1f) * sheetContent().coerceIn(0f, 1f) }
                    .clip(RoundedCornerShape(radius.dp)),
            ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset((rect.left - mask.left).roundToInt(), (rect.top - mask.top).roundToInt()) }
                    .requiredSize(with(dp) { max(rect.width, 1f).toDp() }, with(dp) { max(rect.height, 1f).toDp() })
                    .clip(androidx.compose.ui.graphics.RectangleShape)
                    .background(Color.Black),
            ) {
                AttachmentCameraView(
                    onCapture = { data ->
                        onCapture(data)
                        close()
                    },
                    onBack = ::close,
                    isEmbeddedInSheet = true,
                    isActive = active,
                )
            }
            }
        }
    }
}
