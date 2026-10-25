package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.OpenCodeTheme
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A screen pushed over the session (a subagent, or a web page), keyed by its identity (iOS
 * `SessionStackPage`). The page's UI comes from [SessionPageStack]'s `content` slot, not from
 * this value.
 *
 * @property id the page's identity; a page keeps its composition (scroll, tasks) while its id stays.
 * @property paneTitle what TalkBack announces when the stack comes to rest with this page on top
 *   (Android's form of iOS's `UIAccessibility.screenChanged`); null announces nothing.
 * @property yieldsBackSwipe while true the stack's back gestures stand aside on this page: a web
 *   page with its own history goes back through it first (its own back handler and pan), as
 *   Safari does. Read when the stack recomposes and when a pan starts.
 */
@Stable
class SessionStackPage(
    val id: Int,
    val paneTitle: String? = null,
    val yieldsBackSwipe: () -> Boolean = { false },
)

/**
 * Subagent (and browser) screens pushed over the session, like a navigation stack (iOS
 * `SessionPageStack`): a push slides the new screen in from the trailing edge over its parent,
 * which parallaxes and dims beneath; going back follows the finger (an in-app rightward pan from
 * anywhere under [BackSwipeRules], or the system's predictive back) and settles on the drawer's
 * spring ([Motion.drawerSlide]).
 *
 * Covered screens stay composed (each under its own [androidx.compose.runtime.saveable.SaveableStateHolder]
 * entry), only invisible once a transition lands, so their transcript keeps its scroll position and
 * their effects never end and restart: going back is instant. Covered screens are hidden from
 * TalkBack and their own back handlers are parked on an inert dispatcher, so only the top screen
 * handles system back.
 *
 * Under Reduce Motion pushes and pops cross-fade (0.25 s) instead of sliding, with no parallax,
 * dim or shadow.
 *
 * @param pages the screens over the root, bottom first. New ids push (the last with the push
 *   motion), missing ids pop (the top with the pop motion), and pages that stay take the new
 *   value. A page removed while it animates out is still drawn through [content] until it has
 *   left, so the host must be able to draw a page that's no longer in its list.
 * @param onPop the reader went back (back gesture or system back) until `depth` pages remain; the
 *   host removes the pages above `depth` from its state. The page is already animating out.
 * @param modifier the stack's frame (the session card); pages fill it and are clipped to it.
 * @param enabled whether the back gestures may run (iOS `isEnabled`: not under the open drawer or
 *   Catch Up).
 * @param rootPaneTitle what TalkBack announces when the stack comes back to rest on the root.
 * @param onTopChanged a push or pop moved the screen on top.
 * @param root the screen at the bottom (the session), always composed.
 * @param content draws a pushed page.
 */
@Composable
fun SessionPageStack(
    pages: List<SessionStackPage>,
    onPop: (depth: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rootPaneTitle: String? = null,
    onTopChanged: () -> Unit = {},
    root: @Composable () -> Unit,
    content: @Composable (page: SessionStackPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()
    val frameDuration = rememberFrameDurationSeconds()
    val currentOnPop by rememberUpdatedState(onPop)
    val currentOnTopChanged by rememberUpdatedState(onTopChanged)
    val motion = remember { SessionPageStackMotion(scope, pages) }
    SideEffect {
        motion.reducedMotion = reduceMotion
        motion.frameDuration = frameDuration
        motion.onPopped = { currentOnPop(it) }
        motion.onTopChanged = { currentOnTopChanged() }
        motion.onRemoved = { holder.removeState(it) }
        motion.clearFocus = { focusManager.clearFocus() }
    }
    LaunchedEffect(pages) { motion.update(pages) }

    val mounted = motion.pages
    val backActive = enabled && motion.canGoBack && !motion.isPopping && mounted.lastOrNull()?.yieldsBackSwipe?.invoke() != true
    PredictiveBackHandler(enabled = backActive) { progress ->
        var began = false
        try {
            progress.collect { event ->
                if (!began) {
                    began = motion.dragBegan()
                    if (!began) return@collect
                }
                motion.dragChanged(event.progress * motion.width)
            }
            // Committed: a gesture lands from where it is; a button or TalkBack back pops.
            if (began) motion.dragEnded(velocity = 0f, ended = true, forceBack = true) else motion.popTop()
        } catch (cancelled: CancellationException) {
            if (began) motion.dragEnded(velocity = 0f, ended = false)
            throw cancelled
        }
    }

    val realOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inertOwner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val canvas = OpenCodeTheme.colors.canvas
    val panEnabled by rememberUpdatedState(enabled)

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { motion.width = with(density) { it.width.toDp().value } }
            .pointerInput(motion, rtl) {
                backPan(motion, rtl) { panEnabled }
            },
    ) {
        val count = mounted.size + 1
        for (index in 0 until count) {
            val page = if (index == 0) null else mounted[index - 1]
            val stateKey: Any = page?.id ?: ROOT_KEY
            key(stateKey) {
                val isTop = index == count - 1
                val isBeneath = index == count - 2
                val visible = isTop || (isBeneath && motion.beneathShown)
                val semantics = when {
                    !isTop -> Modifier.clearAndSetSemantics {}
                    motion.atRest -> (page?.paneTitle ?: rootPaneTitle)?.let { title -> Modifier.semantics { paneTitle = title } } ?: Modifier
                    else -> Modifier
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val width = max(motion.width, 1f)
                            val progress = min(max(motion.offset / width, 0f), 1f)
                            val direction = if (rtl) -1f else 1f
                            when {
                                !visible -> {
                                    alpha = 0f
                                    translationX = 0f
                                    shadowElevation = 0f
                                }
                                isTop -> {
                                    alpha = motion.topAlpha
                                    translationX = motion.offset.dp.toPx() * direction
                                    val shadow = count > 1 && !motion.reducedMotion && !motion.atRest
                                    shadowElevation = if (shadow) SHADOW_RADIUS.dp.toPx() else 0f
                                    shape = RectangleShape
                                    val shadowColor = Color.Black.copy(alpha = if (shadow) 1 - progress else 0f)
                                    ambientShadowColor = shadowColor
                                    spotShadowColor = shadowColor
                                }
                                else -> {
                                    alpha = 1f
                                    shadowElevation = 0f
                                    translationX = if (motion.reducedMotion) 0f
                                    else -width.dp.toPx() * PARALLAX * (1 - progress) * direction
                                }
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (isBeneath && motion.dimShown && !motion.reducedMotion) {
                                val width = max(motion.width, 1f)
                                val progress = min(max(motion.offset / width, 0f), 1f)
                                drawRect(Color.Black, alpha = DIM_ALPHA * (1 - progress))
                            }
                        }
                        .then(if (page != null) Modifier.background(canvas) else Modifier)
                        // Takes the hit so touches never fall through to a covered screen.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .then(semantics),
                ) {
                    val owner = if (isTop) realOwner else inertOwner
                    val provided = if (owner != null) arrayOf(LocalOnBackPressedDispatcherOwner provides owner) else emptyArray()
                    CompositionLocalProvider(*provided) {
                        holder.SaveableStateProvider(stateKey) {
                            if (page == null) root() else content(page)
                        }
                    }
                }
            }
        }
    }
}

/** iOS `SessionPageStack.parallax` (`SettingsPresentation.parallax`). */
private const val PARALLAX = 0.3f

/** iOS `SessionPageStack.dimAlpha` (`SettingsPresentation.dim`). */
private const val DIM_ALPHA = 0.12f

/** The pushed page's shadow radius (iOS 10 pt; its opacity, 0.18, fades with the slide). */
private const val SHADOW_RADIUS = 10f

/** iOS `UIView.animate(withDuration: 0.25)`: Reduce Motion's push/pop cross-fade. */
private const val REDUCED_FADE_SECONDS = 0.25

private const val ROOT_KEY = "ai.opencode.session.stack.root"

/**
 * The rightward back pan over a pushed page (iOS `BackPanGestureRecognizer`), from anywhere,
 * under [BackSwipeRules]: an edge touch always takes it; elsewhere a drag already claimed by
 * content under the finger (a horizontal scroller, a slider) or begun after a rest keeps doing
 * that. It watches after the content (main pass) until it chooses, then takes the touch (initial
 * pass, consuming), so content under the finger lets go.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.backPan(
    motion: SessionPageStackMotion,
    rtl: Boolean,
    enabled: () -> Boolean,
) {
    val direction = if (rtl) -1f else 1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        if (!enabled() || !motion.panActive()) return@awaitEachGesture
        val edge = BackSwipeRules.edgeWidth.toPx()
        val atEdge = if (rtl) down.position.x >= size.width - edge else down.position.x <= edge
        val decisionPx = BackSwipeRules.decisionDistance.toPx()
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var claimed = down.isConsumed
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (change.isConsumed) claimed = true
            tracker.addPointerInputChange(change)
            val dx = (change.position.x - down.position.x) * direction
            val dy = change.position.y - down.position.y
            val held = (change.uptimeMillis - down.uptimeMillis) / 1000.0
            when (BackSwipeRules.decision(dx, dy, decisionPx, atEdge, claimed, held, holdBegan = false)) {
                BackSwipeRules.Decision.Wait -> continue
                BackSwipeRules.Decision.Fail -> return@awaitEachGesture
                BackSwipeRules.Decision.Begin -> {}
            }
            change.consume()
            if (!motion.dragBegan()) return@awaitEachGesture
            motion.dragChanged(dx.toDp().value)
            break
        }
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    finished = true
                    motion.dragEnded(velocity = 0f, ended = false)
                    return@awaitEachGesture
                }
                tracker.addPointerInputChange(change)
                change.consume()
                if (!change.pressed) {
                    val velocity = (tracker.calculateVelocity().x * direction).toDp().value
                    finished = true
                    motion.dragEnded(velocity = velocity, ended = true)
                    return@awaitEachGesture
                }
                motion.dragChanged(((change.position.x - down.position.x) * direction).toDp().value)
            }
        } finally {
            if (!finished) motion.dragEnded(velocity = 0f, ended = false)
        }
    }
}

/**
 * The plain object behind [SessionPageStack] (iOS `SessionPageStack`'s UIKit controller): which
 * pages are composed, the top page's offset (dp; 0 in place, the width off screen), and the
 * transitions and back drag that move it. Per-frame motion runs here on Compose's frame clock;
 * the host only sees [onPopped].
 */
@Stable
internal class SessionPageStackMotion(
    private val scope: CoroutineScope,
    initial: List<SessionStackPage>,
) {
    /** The composed pages, bottom first (pages animating out included). */
    var pages by mutableStateOf(initial)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var width by mutableFloatStateOf(0f)
    var reducedMotion by mutableStateOf(false)
    var frameDuration = 1.0 / 60.0

    /** The screen beneath the top shows (a push, a pop or a drag back). */
    var beneathShown by mutableStateOf(false)
        private set

    /** The dim over the screen beneath (a pop or a drag back; a push has none). */
    var dimShown by mutableStateOf(false)
        private set

    /** The top page's opacity: Reduce Motion's cross-fade. */
    var topAlpha by mutableFloatStateOf(1f)
        private set

    /** Only the top screen shows; no transition runs. */
    var atRest by mutableStateOf(true)
        private set

    /** A pop is animating out; the back gestures wait for it. */
    var isPopping by mutableStateOf(false)
        private set

    var onPopped: (Int) -> Unit = {}
    var onTopChanged: () -> Unit = {}
    var onRemoved: (Int) -> Unit = {}
    var clearFocus: () -> Unit = {}

    val canGoBack: Boolean get() = pages.isNotEmpty()

    /** Pages already taken off by the reader, waiting for the state to follow. */
    private val poppedIDs = mutableSetOf<Int>()

    /** What the running transition does when it lands (or is cut short). */
    private var landing: (() -> Unit)? = null
    private var job: Job? = null
    private var fading = false
    private var dragging = false
    private var dragStart = 0f

    /** iOS `pan.isActive`, minus `isEnabled` (the composable's `enabled`). */
    fun panActive(): Boolean = canGoBack && !isPopping && pages.lastOrNull()?.yieldsBackSwipe?.invoke() != true

    /**
     * Matches the pages to [items], bottom first: new ones push (the last with the push motion),
     * missing ones pop (the top with the pop motion), and pages that stay take their new value.
     */
    fun update(items: List<SessionStackPage>) {
        val ids = items.map { it.id }.toSet()
        poppedIDs.retainAll(ids)
        val wanted = items.filter { it.id !in poppedIDs }
        var common = 0
        while (common < pages.size && common < wanted.size && pages[common].id == wanted[common].id) common++
        if (common > 0 && (0 until common).any { pages[it] !== wanted[it] }) {
            pages = wanted.take(common) + pages.drop(common)
        }
        val leaving = pages.drop(common)
        val arriving = wanted.drop(common)
        if (leaving.isEmpty() && arriving.isEmpty()) return
        // The top is already animating out: an update meanwhile (the call retargeting, a
        // streamed batch) lets it finish rather than snapping it off.
        if (isPopping && arriving.isEmpty() && leaving.size == 1 && leaving[0] === pages.last()) return
        val animated = width > 0
        if (arriving.isEmpty()) {
            pop(common, animated)
            return
        }
        // A replacement (rare: the stack changed under a transition) lands at once.
        finishTransition()
        val leavingIDs = leaving.map { it.id }.toSet()
        pages.filter { it.id in leavingIDs }.forEach { onRemoved(it.id) }
        pages = pages.filter { it.id !in leavingIDs }
        arriving.forEachIndexed { index, item -> push(item, animated && index == arriving.lastIndex) }
    }

    /** System back without a gesture (a button, TalkBack) goes back one level. */
    fun popTop(): Boolean {
        if (!canGoBack || isPopping) return false
        poppedIDs.add(pages.last().id)
        pop(pages.size - 1, animated = width > 0) { onPopped(pages.size) }
        return true
    }

    private fun push(item: SessionStackPage, animated: Boolean) {
        supersedeDrag()
        // The parent gives up the keyboard, as a navigation push does.
        clearFocus()
        pages = pages + item
        onTopChanged()
        atRest = false
        beneathShown = true
        if (!animated) {
            offset = 0f
            settleAtRest()
            return
        }
        if (reducedMotion) {
            offset = 0f
            topAlpha = 0f
            landing = { settleAtRest() }
            fade(to = 1f)
            return
        }
        offset = width
        animate(to = 0f) { settleAtRest() }
    }

    /** Takes pages off until [depth] remain; only the top one animates. */
    private fun pop(depth: Int, animated: Boolean, completion: () -> Unit = {}) {
        supersedeDrag()
        // A transition still running lands first, so the count below is what stays.
        finishTransition()
        if (pages.size <= depth) return completion()
        // Pages below the top that are going too leave at once.
        while (pages.size > depth + 1) {
            val gone = pages[pages.size - 2]
            onRemoved(gone.id)
            pages = pages.filter { it !== gone }
        }
        val top = pages.last()
        val finish = takeOff(top, completion)
        if (!animated) return finish()
        revealBeneath()
        isPopping = true
        if (reducedMotion) {
            landing = finish
            fade(to = 0f)
            return
        }
        animate(to = width, then = finish)
    }

    /** Removes [top] once it has left, and rests on the page beneath. */
    private fun takeOff(top: SessionStackPage, completion: () -> Unit): () -> Unit = {
        isPopping = false
        onRemoved(top.id)
        pages = pages.filter { it !== top }
        offset = 0f
        settleAtRest()
        onTopChanged()
        completion()
    }

    /** Moves the top page toward [to] on the drawer's spring, then lands. */
    private fun animate(to: Float, velocity: Float = 0f, then: () -> Unit) {
        stopMotion()
        landing = then
        val spring = PageStackSpring(position = offset, velocity = velocity, target = to, upper = max(width, 0f))
        job = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                val settled = spring.step(tick.dt)
                offset = spring.position
                if (settled) {
                    job = null
                    land()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    /** Reduce Motion's cross-fade of the top page to [to], then lands. */
    private fun fade(to: Float) {
        stopMotion()
        val from = topAlpha
        fading = true
        var elapsed = 0.0
        job = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                val t = min(elapsed / REDUCED_FADE_SECONDS, 1.0).toFloat()
                topAlpha = from + (to - from) * Motion.EaseInOut.transform(t)
                if (t >= 1f) {
                    job = null
                    fading = false
                    land()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    private fun land() {
        val land = landing
        landing = null
        land?.invoke()
    }

    private fun stopMotion() {
        job?.cancel()
        job = null
        // A cross-fade cut short ends where it was going, as UIKit's animation would.
        if (fading) {
            fading = false
            topAlpha = 1f
        }
    }

    /** A drag in progress gives way to a push or pop the app makes. */
    private fun supersedeDrag() {
        dragging = false
    }

    /** A transition still running lands where it was going. */
    private fun finishTransition() {
        stopMotion()
        val land = landing ?: return
        landing = null
        offset = 0f
        land()
    }

    // The back drag (in-app pan or predictive back)

    /** The drag begins; false when it may not (nothing to go back from, or a pop is running). */
    fun dragBegan(): Boolean {
        if (width <= 0 || !canGoBack || isPopping) return false
        // Caught mid-push: the drag takes the page from where it is.
        stopMotion()
        landing = null
        dragging = true
        dragStart = offset
        atRest = false
        revealBeneath()
        clearFocus()
        return true
    }

    /** The finger's horizontal travel since it touched down (dp, toward the trailing side). */
    fun dragChanged(translation: Float) {
        if (!dragging) return
        offset = min(max(dragStart + translation, 0f), width)
    }

    /**
     * The drag ends: [ended] for a release (false for a cancel), [velocity] in dp/s toward the
     * trailing side, [forceBack] when the system already committed the back.
     */
    fun dragEnded(velocity: Float, ended: Boolean, forceBack: Boolean = false) {
        if (!dragging) return
        dragging = false
        val goesBack = forceBack || (ended && BackSwipeRules.goesBack(offset, width, velocity))
        val top = pages.lastOrNull()
        if (goesBack && top != null) {
            poppedIDs.add(top.id)
            val finish = takeOff(top) { onPopped(pages.size) }
            if (reducedMotion) return finish()
            isPopping = true
            animate(to = width, velocity = velocity, then = finish)
        } else {
            if (reducedMotion) {
                offset = 0f
                return settleAtRest()
            }
            animate(to = 0f, velocity = velocity) { settleAtRest() }
        }
    }

    /** The screen beneath the top shows, under the dim (for a pop or a drag back). */
    private fun revealBeneath() {
        if (pages.isEmpty()) return
        atRest = false
        beneathShown = true
        dimShown = true
    }

    /**
     * At rest: only the top screen shows, takes touches, and reaches TalkBack (its pane title
     * announces the change); those beneath stay composed but invisible.
     */
    private fun settleAtRest() {
        beneathShown = false
        dimShown = false
        topAlpha = 1f
        atRest = true
    }
}

/**
 * The drawer's spring for one page slide (iOS `BackSwipeSpring` driving `DrawerSpring.step`):
 * critically damped at [Motion.drawerSlide]'s ω, stopping on the edge rather than passing it.
 * Distances in dp (iOS points), velocity in dp/s.
 */
private class PageStackSpring(position: Float, var velocity: Float, target: Float, upper: Float) {
    private val lower = 0f
    private val upper = max(upper, 0f)
    val target = target.coerceIn(lower, this.upper)
    var position = position.coerceIn(lower, this.upper)
        private set
    private var hasArrived = false

    /** Advances [dt] seconds; true once at rest on the target. */
    fun step(dt: Double): Boolean {
        val x0 = position - target
        val (value, v) = SpringSolver.evaluate(
            from = position.toDouble(),
            to = target.toDouble(),
            initialVelocity = velocity.toDouble(),
            omega = Motion.drawerSlide.omega,
            zeta = 1.0,
            elapsed = dt,
        )
        var x = (value - target).toFloat()
        var nextVelocity = v.toFloat()
        if ((x0 != 0f && x * x0 < 0) || target + x !in lower..upper) {
            // A hard flick reaches the edge before the spring would, or a release right at the
            // edge still moves outwards: stop on the edge.
            x = 0f
            nextVelocity = 0f
        }
        position = target + x
        velocity = nextVelocity
        if (!hasArrived && abs(x) <= ARRIVAL_TOLERANCE) hasArrived = true
        if (hasArrived && abs(x) < 0.25f && abs(nextVelocity) < 8f) {
            position = target
            velocity = 0f
            return true
        }
        return false
    }

    private companion object {
        const val ARRIVAL_TOLERANCE = 1f
    }
}
