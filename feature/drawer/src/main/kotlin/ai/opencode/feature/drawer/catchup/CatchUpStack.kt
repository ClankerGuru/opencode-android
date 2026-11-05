package ai.opencode.feature.drawer.catchup

import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.LocalContainerInsets
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.motion.DirectionalPan
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** Creates the stack's motion owner, bound to this composition's frame clock. */
@Composable
fun rememberCatchUpStackController(): CatchUpStackController {
    val scope = rememberCoroutineScope()
    return remember { CatchUpStackController().also { it.scope = scope } }
}

/**
 * The Catch Up card stack (iOS `CatchUpStackController` hosted in `SessionDrawerContainer`): the
 * live session card inset between the chrome's header and footer, the next session's preview
 * beneath it, and the snapshots that fly away. Drag the card right to Mark as Read, left to Keep
 * Unread; the chrome's buttons play the same flight through [proxy].
 *
 * Draw it edge to edge over [CatchUpChrome]; it insets the card itself (system bars + header,
 * system bars + footer, `sideInset` at the sides). The live card and the preview consume those
 * insets and provide them as [LocalContainerInsets], so the hosted session lays out against the
 * card's edges as in iOS's locked safe area (`CatchUpCardSurfaceView`), and the keyboard reaches
 * into the card only as far as it overlaps it (iOS `safeAreaRegions = .keyboard`).
 *
 * Swiping never recomposes [card] or [preview]: motion is snapshot state read in `graphicsLayer`.
 *
 * @param stage the queue and its actions. Changes are diffed: a new queue animates (rise, crossfade,
 *   fade) unless a flight or drag is running, which settles against the latest stage when it lands.
 * @param revealsPreviewOnBegin reveal the card behind as soon as the stack begins. A host that
 *   morphs into Catch Up (the session container) passes `false` and calls
 *   [CatchUpStackController.revealPreview] when its morph lands, as iOS does.
 * @param handlesPan the stack runs its own two-axis pan. Pass `false` when the drawer container's
 *   pan routes Catch Up swipes to [CatchUpStackController.handlePan].
 * @param panRegions the drawer's pan regions: a horizontal scroller in the card that can still move
 *   the way the finger goes keeps the drag (iOS `cardSwipeDecision`).
 * @param card the live card's body for the open session (`null` once the stack is empty).
 * @param preview an inert preview of the next session, with the host's preview revision so it can
 *   skip re-rendering for unrelated changes (iOS `CatchUpPreviewCard`'s equality).
 */
@Composable
fun CatchUpStack(
    stage: CatchUpStage,
    modifier: Modifier = Modifier,
    controller: CatchUpStackController = rememberCatchUpStackController(),
    proxy: CatchUpStageProxy? = null,
    previewRevision: Int = 0,
    revealsPreviewOnBegin: Boolean = true,
    handlesPan: Boolean = true,
    panRegions: DrawerPanRegions? = null,
    /** What [proxy]'s buttons do (the drawer container lands its morph first, iOS `dismissCard`/`undoCard`). */
    dismissCard: (CatchUpDisposition) -> Unit = controller::fling,
    undoCard: () -> Unit = controller::undo,
    card: @Composable (sessionID: String?) -> Unit,
    preview: @Composable (sessionID: String, revision: Int) -> Unit,
) {
    val reducedMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val isDark = colors.isDark
    val frameDuration = rememberFrameDurationSeconds()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val liveLayer = rememberGraphicsLayer()
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val insetPadding = insets.asPaddingValues()
    // The window's edges outside the card: the system bars plus the header, footer and sides. The
    // hosted session lays out against the card's edges, as in iOS's locked safe area, and the
    // keyboard reaches into the card only as far as it overlaps it (UIKit's keyboard safe area).
    val cardInsets = remember(insets) {
        insets.add(
            WindowInsets(
                left = CatchUpMetrics.sideInset,
                top = CatchUpMetrics.headerHeight,
                right = CatchUpMetrics.sideInset,
                bottom = CatchUpMetrics.footerHeight,
            ),
        )
    }
    val cornerRadius = CatchUpMetrics.cornerRadius
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val borderWidth: Dp = if (isDark) 1.dp else with(density) { 1f.toDp() }
    val baseBorder = CatchUpMetrics.borderColor(isDark, colors.border)
    val canvas = colors.canvas

    SideEffect {
        controller.reducedMotion = reducedMotion
        controller.isDark = isDark
        controller.frameDuration = frameDuration
        controller.endEditing = { focusManager.clearFocus() }
        controller.liveLayer = liveLayer
        proxy?.dismiss = dismissCard
        proxy?.undo = undoCard
        val current = controller.currentStage
        when {
            current == null -> {
                controller.begin(stage)
                if (revealsPreviewOnBegin) controller.revealPreview(animated = !reducedMotion)
            }
            current.queue != stage.queue || current.undoDirection != stage.undoDirection -> controller.update(stage)
            else -> controller.refreshCallbacks(stage)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.end()
            controller.dispose()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val width = size.width / density.density
                if (width != controller.containerWidth) {
                    controller.containerWidth = width
                    controller.refreshTuning()
                }
            },
    ) {
        Box(
            Modifier
                .padding(insetPadding)
                .padding(
                    start = CatchUpMetrics.sideInset,
                    end = CatchUpMetrics.sideInset,
                    top = CatchUpMetrics.headerHeight,
                    bottom = CatchUpMetrics.footerHeight,
                )
                .fillMaxSize()
                .onSizeChanged { size ->
                    val height = size.height / density.density
                    if (height != controller.cardHeight) {
                        controller.cardHeight = height
                        controller.refreshTuning()
                    }
                }
                .then(if (handlesPan) Modifier.catchUpPan(controller, panRegions) else Modifier),
        ) {
            // The card behind: the next session, dimmed, inert and hidden from TalkBack.
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        controller.preview.applyTo(this)
                        if (controller.isPreviewHidden) alpha = 0f
                        this.shape = shape
                        clip = true
                    }
                    .background(canvas),
            ) {
                val previewID = controller.shownPreviewID
                if (previewID != null) {
                    // Laid out as the live card is, so it doesn't shift when it becomes the live one.
                    Box(Modifier.matchParentSize().consumeWindowInsets(cardInsets)) {
                        CompositionLocalProvider(LocalContainerInsets provides cardInsets) { preview(previewID, previewRevision) }
                    }
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .drawBehind {
                            drawRect(Color.Black.copy(alpha = controller.previewDim))
                            drawRoundRect(
                                color = baseBorder.copy(alpha = baseBorder.alpha * controller.previewBorderFactor),
                                cornerRadius = CornerRadius(cornerRadius.toPx()),
                                style = Stroke(borderWidth.toPx()),
                            )
                        },
                )
            }

            for (flying in controller.flyingCards) {
                if (!flying.aboveLive) key(flying.id) { CatchUpFlyingCardView(flying, shape, cornerRadius, borderWidth, baseBorder) }
            }

            // The live card: the only interactive one.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        controller.live.applyTo(this)
                        this.shape = shape
                        clip = true
                    },
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawWithContent {
                            liveLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(liveLayer)
                        }
                        .background(canvas)
                        .consumeWindowInsets(cardInsets),
                ) {
                    CompositionLocalProvider(LocalContainerInsets provides cardInsets) { card(stage.cardID) }
                }
                CatchUpSwipeOverlay(controller.readBadge, opaque = colors.highContrast)
                CatchUpSwipeOverlay(controller.keepBadge, opaque = colors.highContrast)
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawRoundRect(
                                color = controller.dragBorderColor ?: baseBorder,
                                cornerRadius = CornerRadius(cornerRadius.toPx()),
                                style = Stroke(borderWidth.toPx()),
                            )
                        },
                )
                if (!controller.isContentEnabled) {
                    // iOS `content.isUserInteractionEnabled = false`: touches stop here; the stack's pan still sees them.
                    Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                }
            }

            for (flying in controller.flyingCards) {
                if (flying.aboveLive) key(flying.id) { CatchUpFlyingCardView(flying, shape, cornerRadius, borderWidth, baseBorder) }
            }
        }
    }
}

/** A snapshot in flight: inert, hidden from TalkBack, with a shadow like iOS's (0.16, 24 pt, y 10). */
@Composable
private fun CatchUpFlyingCardView(
    flying: CatchUpFlyingCard,
    shape: RoundedCornerShape,
    cornerRadius: Dp,
    borderWidth: Dp,
    baseBorder: Color,
) {
    val bitmap = flying.bitmap ?: return
    val colors = OpenCodeTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .graphicsLayer {
                flying.layer.applyTo(this)
                this.shape = shape
                clip = true
                if (flying.shadow) {
                    shadowElevation = 16.dp.toPx()
                    ambientShadowColor = Color.Black.copy(alpha = 0.16f)
                    spotShadowColor = Color.Black.copy(alpha = 0.16f)
                }
            },
    ) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        flying.badge?.let { CatchUpSwipeOverlay(it, opaque = colors.highContrast) }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (flying.dim > 0f) drawRect(Color.Black.copy(alpha = flying.dim))
                    drawRoundRect(
                        color = flying.borderColor ?: baseBorder,
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(borderWidth.toPx()),
                    )
                },
        )
    }
}

/**
 * The stack's pan (iOS `DirectionalPanGestureRecognizer` with `isCardSwipe`): waits 8 dp before
 * choosing an axis, yields at once to a vertical move (the transcript scrolls), and once horizontal
 * wins it consumes the touch so a pending button in the card never fires. While the card is inert
 * (a flight or settle running) it reads touches before the card's content does.
 */
private fun Modifier.catchUpPan(controller: CatchUpStackController, regions: DrawerPanRegions?): Modifier = composed {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    onGloballyPositioned { coordinates[0] = it }.pointerInput(controller, regions, rtl) { catchUpPanGestures(controller, regions, coordinates, rtl) }
}

private suspend fun PointerInputScope.catchUpPanGestures(
    controller: CatchUpStackController,
    regions: DrawerPanRegions?,
    coordinates: Array<LayoutCoordinates?>,
    rtl: Boolean,
) {
    val slop = 8.dp.toPx()
    val scale = density
    fun dp(offset: Offset) = Offset(offset.x / scale, offset.y / scale)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!controller.canBeginDrag) return@awaitEachGesture
        val pointerID = down.id
        // A horizontal scroller under the finger keeps the drags it can still scroll; the stack
        // decides first (Initial pass) so the scroller never takes one that is the card's.
        val pan = coordinates[0]
        val scrollers = if (regions != null && pan != null && pan.isAttached) regions.scrollersAt(pan, down.position) else emptyList()
        val origin = DirectionalPan.CardSwipeOrigin(
            onCard = true,
            scrollerOwnsRightwardDrag = scrollers.any { if (rtl) it.canScrollForward() else it.canScrollBackward() },
            scrollerOwnsLeftwardDrag = scrollers.any { if (rtl) it.canScrollBackward() else it.canScrollForward() },
        )
        var last: PointerInputChange = down
        var travelled: Offset
        while (true) {
            val pass = if (controller.isContentEnabled && scrollers.isEmpty()) PointerEventPass.Main else PointerEventPass.Initial
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull { it.id == pointerID } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (pass == PointerEventPass.Main && change.isConsumed) return@awaitEachGesture
            travelled = change.position - down.position
            last = change
            when (DirectionalPan.cardSwipeDecision(origin, travelled.x, travelled.y, slop)) {
                DirectionalPan.Decision.Wait -> Unit
                DirectionalPan.Decision.Begin -> break
                else -> return@awaitEachGesture
            }
        }
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        tracker.addPosition(last.uptimeMillis, last.position)
        last.consume()
        if (!controller.panBegan(dp(travelled))) return@awaitEachGesture
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerID }
                if (change == null) {
                    controller.panCancelled()
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val pan = dp(change.position - down.position)
                if (!change.pressed) {
                    val velocity = tracker.calculateVelocity()
                    controller.panEnded(pan, Offset(velocity.x / scale, velocity.y / scale))
                    return@awaitEachGesture
                }
                change.consume()
                controller.panChanged(pan)
            }
        } catch (cancellation: CancellationException) {
            controller.panCancelled()
            throw cancellation
        }
    }
}
