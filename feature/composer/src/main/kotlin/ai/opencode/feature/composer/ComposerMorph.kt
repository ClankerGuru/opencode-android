package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of iOS `ComposerMorphTunables` (`Composer/ComposerMorph.swift`, `composer.morph.*`): the
 * composer, or a control in it, morphing into a panel fitted to the keyboard's region (OC-318,
 * OC-354; Grow from Composer, WIP). Baked defaults (lengths in dp, times in seconds); the
 * Tuning Panel registration and the Plus Menu Sheet descriptor lists are n/a.
 */
object ComposerMorphTunables {
    /** Kit's morph spring (chip to panel), open and close. */
    val open = MotionSpring(0.42, 0.12)
    val close = MotionSpring(0.36, 0.0)
    val photoOpen = MotionSpring(0.3, 0.08)
    val photoReturn = MotionSpring(0.28, 0.0)
    const val photoContentDelay = 0.0
    const val photoBackdropBlur = 0.0
    const val photoBackdropDim = 0.0
    /** How long a close stays drawn, to let its spring land. */
    const val settle = 0.42
    /** Going from narrow to wide, the width leads the height. */
    const val widthLead = 1.6
    /** The content keeps its own clock: it arrives a beat after the glass starts to grow and leaves faster than it came. */
    val contentIn = MotionSpring(0.36, 0.0)
    const val contentDelay = 0.05
    val contentOut = MotionSpring(0.28, 0.0)
    /** What it grows out of dissolves into a blur this strong (n/a: no blur) and grows this much with the glass. */
    const val outgoingBlur = 10.0
    const val outgoingGrowth = 0.12
    const val contentBlur = 14.0
    const val exitBlur = 10.0
    const val backdropBlur = 18.0
    const val dimDark = 0.3
    const val dimLight = 0.12
    const val topRadius = 30.0
    /** Concentric with the display's corners, a sheet's inset in. */
    const val bottomRadius = 46.0
    const val bottomInset = 8.0
    const val sideInset = 8.0
    /** How far above the composer a sheet stands without a software keyboard to take the height of. */
    const val leastLift = 300.0
    /** Drawn down this far, or thrown down this fast (dp/s), it closes. */
    const val dragCloses = 90.0
    const val throwCloses = 700.0
    const val photoExpandDrag = 36.0
    const val photoExpandVelocity = 450.0
    const val photoCollapseDrag = 48.0
    const val photoScrollExpand = 28.0
    /** `composer.morph.photoDismissDrop`. */
    const val dismissDrop = 88.0
    /** `composer.morph.photoComposerSink`. */
    const val composerSink = 28.0

    /** iOS `animation(opening:reduceMotion:)`: Reduce Motion is a 0.2 s ease-in-out. */
    fun animation(opening: Boolean, reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) Motion.reducedFadeSpec() else (if (opening) open else close).spec()
}

/**
 * Port of iOS `ComposerMorph`: whether a morph is open and how far it has grown. A plain
 * object, as on iOS: the surface reads it and only the surface redraws as a finger drags it.
 * One glass travel ([progress]), its bottom edge's own ([bottomProgress], so whatever rides a
 * keyboard stays under the glass), and the content's own clock ([content]) are animated here
 * on [scope]; opening and closing in any order only retarget them.
 */
@Stable
class ComposerMorph(private val scope: CoroutineScope) {
    /** `Armed`: drawn on its source, its content built but not yet shown, for the frame before it grows. */
    enum class Phase { Closed, Armed, Open, Closing }

    var phase by mutableStateOf(Phase.Closed)
        private set
    /** The glass's travel from its source (0) to its rest (1). */
    val progress = Animatable(0f)
    /** The bottom edge's travel (iOS `bottomOpen`). */
    val bottomProgress = Animatable(0f)
    /** How much of its content shows (0–1), on its own clock. */
    val content = Animatable(0f)
    /** How far a finger has drawn it down (dp; 0 at rest). */
    var drag by mutableFloatStateOf(0f)
    /** Bumped on every open, close and reset: deferred work from an earlier one checks it and does nothing ([after]). */
    var generation by mutableIntStateOf(0)
        private set
    /** Open, its fold starting on the next turn ([launchThenFold]). */
    var foldPending by mutableStateOf(false)
        private set
    private var settleTask: Job? = null
    private var dragTask: Job? = null

    val isOpen: Boolean get() = phase == Phase.Open
    /** Drawn: armed, open, or shrinking back into its source. */
    val isShown: Boolean get() = phase != Phase.Closed
    /** Open or about to be: what a tap on its source toggles. */
    val isOpening: Boolean get() = (phase == Phase.Open && !foldPending) || phase == Phase.Armed

    /** Open at once, with no growth. */
    fun open() {
        generation += 1
        settleTask?.cancel()
        phase = Phase.Open
        scope.launch { progress.snapTo(1f); bottomProgress.snapTo(1f); content.snapTo(1f) }
    }

    /** Opens on [animation], its bottom edge on [edge] if given. */
    private fun open(animation: FiniteAnimationSpec<Float>, edge: FiniteAnimationSpec<Float>?, contentDelay: Double?) {
        generation += 1
        settleTask?.cancel()
        phase = Phase.Open
        scope.launch { progress.animateTo(1f, animation) }
        scope.launch { bottomProgress.animateTo(1f, edge ?: animation) }
        val delayMs = ((contentDelay ?: ComposerMorphTunables.contentDelay) * 1000).roundToInt()
        scope.launch {
            delay(delayMs.toLong())
            content.animateTo(1f, ComposerMorphTunables.contentIn.spec())
        }
    }

    /**
     * Opens in two steps. In the tap it stands on its source while [prepare] builds its
     * content; on the next frame [launch] runs (a keyboard going down) and the spring starts
     * right after, so no frame of it is skipped. Reopened mid-fold, it grows back from where it
     * is, at once.
     */
    fun armThenOpen(
        animation: FiniteAnimationSpec<Float>,
        edge: FiniteAnimationSpec<Float>? = null,
        contentDelay: Double? = null,
        prepare: () -> Unit,
        launch: () -> Unit,
        opened: () -> Unit,
    ) {
        if (phase == Phase.Closing || foldPending) {
            foldPending = false
            prepare()
            launch()
            open(animation, edge, contentDelay)
            return opened()
        }
        generation += 1
        settleTask?.cancel()
        // Where it stands and rests is set, not animated from where it was.
        phase = Phase.Armed
        prepare()
        after(1.milliseconds) {
            if (phase != Phase.Armed) return@after
            launch()
            open(animation, edge, contentDelay)
            opened()
        }
    }

    /** Folds back into its source on [animation] from wherever a finger drew it, its bottom edge on [edge] if given. */
    fun fold(animation: FiniteAnimationSpec<Float>, edge: FiniteAnimationSpec<Float>? = null, reduceMotion: Boolean) {
        foldPending = false
        if (phase == Phase.Open && content.value > 0f) {
            scope.launch { content.animateTo(0f, ComposerMorphTunables.contentOut.spec()) }
        }
        animateDrag(0f, animation)
        close(reduceMotion, movesEdge = edge == null, animation = animation)
        if (edge != null && phase == Phase.Closing) scope.launch { bottomProgress.animateTo(0f, edge) }
    }

    /**
     * Folds in two steps, like [armThenOpen]: [launch] runs in the tap (a keyboard coming back
     * lays the screen out again), and the fold starts on the next frame, after that commit.
     * Reopened meanwhile, it stays open.
     */
    fun launchThenFold(
        animation: FiniteAnimationSpec<Float>,
        reduceMotion: Boolean,
        launch: () -> Unit,
        edge: () -> FiniteAnimationSpec<Float>?,
    ) {
        if (foldPending) return
        if (phase != Phase.Open) {
            launch()
            return close(reduceMotion)
        }
        generation += 1
        foldPending = true
        scope.launch { content.animateTo(0f, ComposerMorphTunables.contentOut.spec()) }
        launch()
        after(1.milliseconds) {
            if (!foldPending) return@after
            fold(animation, edge(), reduceMotion)
        }
    }

    /**
     * Shrinks back into its source, drawn until its spring lands. Armed, it hadn't grown: it's
     * gone at once. Without [animation] the glass snaps (iOS: a close outside `withAnimation`).
     */
    fun close(reduceMotion: Boolean, movesEdge: Boolean = true, animation: FiniteAnimationSpec<Float>? = null) {
        if (phase == Phase.Armed) return reset()
        if (phase != Phase.Open) return
        generation += 1
        phase = Phase.Closing
        scope.launch { if (animation != null) progress.animateTo(0f, animation) else progress.snapTo(0f) }
        if (movesEdge) scope.launch { if (animation != null) bottomProgress.animateTo(0f, animation) else bottomProgress.snapTo(0f) }
        if (animation == null) scope.launch { content.snapTo(0f) }
        val rawSettle = if (reduceMotion) 0.22 else maxOf(
            ComposerMorphTunables.settle,
            ComposerMorphTunables.close.duration * 1.45,
            ComposerMorphTunables.photoReturn.duration * 1.45,
        )
        settleTask?.cancel()
        settleTask = scope.launch {
            delay((rawSettle * 1000).roundToInt().toLong())
            if (phase != Phase.Closing) return@launch
            phase = Phase.Closed
            drag = 0f
            progress.snapTo(0f)
            bottomProgress.snapTo(0f)
            content.snapTo(0f)
        }
    }

    /** Gone at once, with no shrink (a session switch, a call, a request). */
    fun reset() {
        generation += 1
        settleTask?.cancel()
        dragTask?.cancel()
        foldPending = false
        phase = Phase.Closed
        drag = 0f
        scope.launch { progress.snapTo(0f); bottomProgress.snapTo(0f); content.snapTo(0f) }
    }

    /** Animates [drag] (a release springing back, or a fold). */
    fun animateDrag(to: Float, animation: FiniteAnimationSpec<Float>?) {
        dragTask?.cancel()
        if (animation == null) {
            drag = to
            return
        }
        val from = drag
        dragTask = scope.launch {
            val a = Animatable(from)
            a.animateTo(to, animation) { drag = value }
        }
    }

    /** Runs [work] after [delay] unless the morph opened, closed or reset meanwhile. */
    fun after(delay: Duration, work: () -> Unit) {
        val expected = generation
        scope.launch {
            delay(delay)
            if (generation == expected) work()
        }
    }

    companion object {
        /** Whether a drag let go closes it: drawn past `dragCloses`, or thrown down (dp, dp/s). */
        fun closes(translation: Double, predicted: Double, velocity: Double): Boolean =
            translation > ComposerMorphTunables.dragCloses ||
                (velocity > ComposerMorphTunables.throwCloses && predicted > translation)
    }
}

/** A remembered [ComposerMorph] on the composition's scope. */
@Composable
fun rememberComposerMorph(): ComposerMorph {
    val scope = rememberCoroutineScope()
    return remember { ComposerMorph(scope) }
}

/**
 * Port of iOS `ComposerMorphGeometry`: where a morph rests, as frames in one coordinate space
 * (root px). Radii in px.
 */
data class ComposerMorphGeometry(
    /** Where it grows from and folds back into: kept live, so a close retargets if the source moves. */
    val source: Rect,
    val sourceRadius: Float,
    /** Where it rests open. */
    val target: Rect,
    val topRadius: Float,
    val bottomRadius: Float,
) {
    /** The same, its rest's top moved to [y]; [keepingBottom] keeps the bottom edge at `target.bottom`. */
    fun resting(y: Float, keepingBottom: Boolean = false, minHeightPx: Float): ComposerMorphGeometry {
        val bottom = target.bottom
        val height = if (keepingBottom) max(bottom - y, minHeightPx) else target.height
        return copy(target = Rect(target.left, y, target.right, y + height))
    }

    data class Frame(val rect: Rect, val top: Float, val bottom: Float)

    /** The morph's frame and corners at [progress] (0 the source, 1 the target, past 1 a spring's overshoot), drawn down by [drag]. */
    fun frame(progress: Float, bottom: Float? = null, drag: Float): Frame {
        val p = progress
        // Narrow to wide, the width gets there first.
        val lead = ComposerMorphTunables.widthLead
        val pw = if (p >= 1) p else (1 - (1 - max(p, 0f)).toDouble().pow(lead)).toFloat()
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val minX = lerp(source.left, target.left, pw)
        val maxX = lerp(source.right, target.right, pw)
        val minY = lerp(source.top, target.top, p) + drag
        val maxY = lerp(source.bottom, target.bottom, bottom ?: p) + drag
        val rect = Rect(minX, minY, minX + max(maxX - minX, 1f), minY + max(maxY - minY, 1f))
        val clamped = p.coerceIn(0f, 1f)
        val limit = min(rect.width, rect.height) / 2
        return Frame(rect, min(lerp(sourceRadius, topRadius, clamped), limit), min(lerp(sourceRadius, bottomRadius, clamped), limit))
    }

    data class UnifiedMenuState(
        val usesDownwardDismiss: Boolean,
        val closingToMenu: Boolean,
        val menuProgress: Float,
        val menuInteractive: Boolean,
        val entranceProgress: Float,
    )

    companion object {
        /**
         * A sheet over the keyboard's region: as wide as [column] (or the screen, inset), from
         * [top] to the screen's bottom, or to a keyboard's top when one is up ([keyboardTop]),
         * its height then kept and its top raised, never above [ceiling]. [density] converts the
         * dp tunables.
         */
        fun sheet(top: Float, column: Rect?, screen: Rect, keyboardTop: Float?, ceiling: Float, density: Float): Rect {
            val side = (ComposerMorphTunables.sideInset * density).toFloat()
            val inset = (ComposerMorphTunables.bottomInset * density).toFloat()
            val least = 120 * density
            val x = column?.left ?: (screen.left + side)
            val width = column?.width ?: (screen.width - side * 2)
            val floor = screen.bottom - inset
            val height = max(floor - top, least)
            val bottom = keyboardTop?.let { min(it - inset, floor) } ?: floor
            val rested = max(bottom - height, ceiling)
            return Rect(x, rested, x + width, rested + max(bottom - rested, least))
        }

        /** Interpolates the single `+` menu / photo sheet surface between its `+` seed (0) and its resting menu card (1). */
        fun menuSource(
            progress: Float,
            seed: Rect,
            seedRadius: Float,
            menu: Rect,
            menuRadius: Float,
            dragOffset: Float = 0f,
            reduceMotion: Boolean,
        ): Pair<Rect, Float> {
            val offsetMenu = menu.translate(0f, dragOffset)
            if (reduceMotion || seed == Rect.Zero) return offsetMenu to menuRadius
            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
            val left = lerp(seed.left, offsetMenu.left, progress)
            val top = lerp(seed.top, offsetMenu.top, progress)
            val rect = Rect(
                left, top,
                left + max(lerp(seed.width, offsetMenu.width, progress), 1f),
                top + max(lerp(seed.height, offsetMenu.height, progress), 1f),
            )
            val clamped = progress.coerceIn(0f, 1f)
            val limit = min(rect.width, rect.height) / 2
            return rect to min(lerp(seedRadius, menuRadius, clamped), limit)
        }

        /** Stretches or compresses a sheet's top edge by [pull] keeping its bottom edge when [stretchesUpward]. Returns the rect and the remaining downward offset. */
        fun stretched(rect: Rect, pull: Float, stretchesUpward: Boolean, minHeightPx: Float): Pair<Rect, Float> {
            if (!stretchesUpward) return rect to pull
            val minHeight = min(rect.height, minHeightPx)
            val height = max(rect.height - pull, minHeight)
            return Rect(rect.left, rect.bottom - height, rect.right, rect.bottom) to 0f
        }

        /** Glass opacity for a morphing sheet (see iOS `glassFade`). */
        fun glassFade(
            progress: Float,
            menuProgress: Float = 0f,
            shown: Boolean = true,
            armed: Boolean,
            continuousGlass: Boolean,
            dismissesDown: Boolean = false,
            reduceMotion: Boolean,
        ): Float {
            if (!shown) {
                val clampedMenu = menuProgress.coerceIn(0f, 1f)
                return if (reduceMotion) clampedMenu else (menuProgress / 0.35f).coerceIn(0f, 1f)
            }
            val clamped = progress.coerceIn(0f, 1f)
            if (dismissesDown) return clamped
            if (continuousGlass) return 1f
            if (reduceMotion) return clamped
            if (armed) return 0f
            return (progress / 0.1f).coerceIn(0f, 1f)
        }

        /** Effective opacity of the outgoing stand-in content (the + menu's rows dissolving into the photo sheet). */
        fun outgoingOpacity(reveal: Float, shown: Boolean, fade: Float): Float {
            val clamped = reveal.coerceIn(0f, 1f)
            val showing = if (shown) 1 - clamped else 0f
            return showing * fade
        }

        /** Where a sheet dismissing downward lands as progress goes from 1 to 0 ([dropPx] = `dismissDrop` in px). */
        fun downwardDismissSource(target: Rect, dropPx: Float): Rect = target.translate(0f, dropPx)

        /** The composer's offset (dp) and opacity while a photo sheet sits above it or springs back on dismiss. */
        fun composerUnderSheetPose(
            isOpen: Boolean,
            foldPending: Boolean,
            reduceMotion: Boolean,
            sink: Double = ComposerMorphTunables.composerSink,
        ): Pair<Double, Float> {
            val hidden = isOpen && !foldPending
            val offsetY = if (hidden && !reduceMotion) sink else 0.0
            return offsetY to (if (hidden) 0f else 1f)
        }

        /** State of the unified `+` menu / photo sheet surface (closed, menu, photos, back to menu, downward dismiss). */
        fun unifiedMenuState(menuShown: Boolean, morph: ComposerMorph, dismissesDown: Boolean): UnifiedMenuState {
            val shown = morph.isShown
            val sheetActive = morph.isOpening || morph.foldPending
            val usesDownwardDismiss = shown && dismissesDown && !sheetActive
            val closingToMenu = shown && !dismissesDown && !sheetActive
            val menuProgress = if (menuShown || sheetActive || (closingToMenu && menuShown)) 1f else 0f
            val menuInteractive = menuShown && !sheetActive
            val entranceProgress = if (menuShown || sheetActive) 1f else 0f
            return UnifiedMenuState(usesDownwardDismiss, closingToMenu, menuProgress, menuInteractive, entranceProgress)
        }
    }
}

/**
 * Port of iOS `ComposerMorphSurface`, simplified: the morph on screen, always mounted (it draws
 * nothing closed) so opening grows it from the source. A dim scrim (iOS: a progressive blur
 * plus dim; no blur imitation) grows with the glass and takes a tap to dismiss; one M3 surface
 * travels from [source] to its rest on the morph's spring (width leading, corners
 * interpolating), drawn down by the finger's drag; the content is laid out once where it rests
 * and revealed by the surface's shape, fading in on its own clock.
 *
 * Partial (see the parity row): no content blur, no outgoing cross-fade (`outgoing`), no
 * unified `+` menu phase (`seed`/`menuRows`), no foot slot riding the bottom edge; a keyboard
 * under the content raises the rest by the IME inset without riding its curve.
 *
 * Frames are root px. [target] receives the surface's own frame and the keyboard's top (or null).
 */
@Composable
fun ComposerMorphSurface(
    morph: ComposerMorph,
    source: Rect,
    sourceRadiusDp: Double,
    target: (screen: Rect, keyboardTop: Float?) -> Rect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    openedFrom: Rect? = null,
    landsOn: Rect? = null,
    contentHasKeyboard: Boolean = false,
    stretchesUpward: Boolean = false,
    backdropDim: Double? = null,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    var screen by remember { mutableStateOf(Rect.Zero) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val shown = morph.isShown
    val sheetActive = morph.isOpening || morph.foldPending
    // Until its fold starts it is still open, so it keeps growing from where it opened.
    val from = if (sheetActive) openedFrom ?: source else landsOn ?: source

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { screen = it.boundsInRoot() },
    ) {
        if (!shown || screen == Rect.Zero) return@Box
        val keyboardTop = if (contentHasKeyboard && morph.isOpen && imeBottom > 0) screen.bottom - imeBottom else null
        val rest = target(screen, keyboardTop)
        val px = density.density
        val geometry = ComposerMorphGeometry(
            source = from.translate(-screen.left, -screen.top),
            sourceRadius = (sourceRadiusDp * px).toFloat(),
            target = rest.translate(-screen.left, -screen.top),
            topRadius = (ComposerMorphTunables.topRadius * px).toFloat(),
            bottomRadius = (ComposerMorphTunables.bottomRadius * px).toFloat(),
        )
        val progress = morph.progress.value
        val travel = if (reduceMotion) 1f else progress
        val bottomTravel = if (reduceMotion) 1f else morph.bottomProgress.value
        val frame = geometry.frame(travel, bottomTravel, 0f)
        val pull = if (reduceMotion) 0f else morph.drag * px
        val (stretchedRect, downward) = ComposerMorphGeometry.stretched(frame.rect, pull, stretchesUpward, 120 * px)
        val (stretchedTarget, _) = ComposerMorphGeometry.stretched(geometry.target, pull, stretchesUpward, 120 * px)
        val fade = ComposerMorphGeometry.glassFade(
            progress = progress, armed = morph.phase == ComposerMorph.Phase.Armed,
            continuousGlass = false, reduceMotion = reduceMotion,
        )
        val dim = (backdropDim ?: if (dark) ComposerMorphTunables.dimDark else ComposerMorphTunables.dimLight).toFloat()

        // The backdrop: everything above the sheet dims, growing with the morph.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = dim * progress.coerceIn(0f, 1f)))
                .let { if (morph.isOpen) it.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss) else it }
                .clearAndSetSemantics {},
        )
        val shape = RoundedCornerShape(
            topStart = with(density) { frame.top.toDp() }, topEnd = with(density) { frame.top.toDp() },
            bottomStart = with(density) { frame.bottom.toDp() }, bottomEnd = with(density) { frame.bottom.toDp() },
        )
        val glassRect = stretchedRect.translate(0f, downward)
        val contentY = min(stretchedTarget.top, stretchedRect.top)
        val reveal = if (reduceMotion) progress.coerceIn(0f, 1f) else morph.content.value
        Box(
            Modifier
                .placeAt(glassRect.topLeft)
                .size(with(density) { glassRect.width.toDp() }, with(density) { glassRect.height.toDp() })
                .graphicsLayer { alpha = fade }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { if (!morph.isOpening) invisibleToUser() },
        ) {
            // The content, where it rests, in the glass's own frame: the glass's shape reveals it.
            Box(
                Modifier
                    .placeAt(Offset(stretchedTarget.left - stretchedRect.left, contentY - stretchedRect.top))
                    .size(with(density) { stretchedTarget.width.toDp() }, with(density) { stretchedTarget.height.toDp() })
                    .alpha(reveal.coerceIn(0f, 1f)),
            ) {
                content()
                // Taps reach the content only once it has mostly grown (iOS `allowsHitTesting(progress > 0.5)`).
                if (progress <= 0.5f && !reduceMotion) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

private fun Modifier.placeAt(offset: Offset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    layout(placeable.width, placeable.height) { placeable.place(offset.x.roundToInt(), offset.y.roundToInt()) }
}

/**
 * iOS `View.handsGlassOver(to:...)`: the morph's source under it hands its surface over to the
 * morph as the morph starts to grow (fading out in 0.12 s), and takes it back as the morph lands
 * on it (after `close.duration × 0.62`, or [closeDelay]). [sinksUnderSheet]: the composer sinks
 * [ComposerMorphTunables.composerSink] and fades under a photo sheet instead.
 */
fun Modifier.handsGlassOver(
    morph: ComposerMorph,
    reduceMotion: Boolean,
    isEnabled: Boolean = true,
    returnsImmediately: Boolean = false,
    closeDelay: Double? = null,
    sinksUnderSheet: Boolean = false,
): Modifier = composed {
    if (!isEnabled) return@composed this
    val density = LocalDensity.current
    if (sinksUnderSheet) {
        val hidden = morph.isOpen && !morph.foldPending
        val motion: FiniteAnimationSpec<Float> = if (reduceMotion) tween(200, easing = Motion.EaseInOut)
        else (if (hidden) ComposerMorphTunables.photoOpen else ComposerMorphTunables.photoReturn).spec()
        val p by animateFloatAsState(if (hidden) 1f else 0f, motion, label = "under-sheet")
        val clamped = p.coerceIn(0f, 1f)
        val offsetY = if (reduceMotion) 0f else clamped * ComposerMorphTunables.composerSink.toFloat() * density.density
        val opacity = if (reduceMotion) 1 - clamped else 1 - (clamped / 0.65f).coerceIn(0f, 1f)
        this
            .graphicsLayer { translationY = offsetY; alpha = opacity }
            .semantics { if (clamped >= 0.65f) invisibleToUser() }
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
