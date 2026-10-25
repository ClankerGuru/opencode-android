package ai.opencode.feature.drawer.switcher

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.DrawerPanExclusion
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.feature.drawer.motion.drawerPanSwitcherStrip
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Port of iOS `OpenCode/Drawer/SessionSwitcherController.swift`. UIKit's view controller becomes
// a plain state holder ([SessionSwitcherController]) whose snapshot state is read only in layout
// and draw blocks, so a swipe never recomposes the session or the cards' contents, plus the
// composables that draw it ([SessionSwitcherStage]: cards, captions, page dots, the TalkBack
// stack) and the finger ([sessionSwitcherGestures]). Navigation and presentation cross back to
// Home as semantic events, never frame ticks.

/**
 * UIKit's controller (iOS `SessionSwitcherController`): owns only the finger, the cards and one
 * motion clock.
 *
 * One spatial model: sessions are cards in a row, newest first. Swiping along the bottom edge
 * slides the row at full size (the card under the finger shrinks a little and its neighbour
 * rides beside it); swiping up gathers the same row into a stack, the current card in front and
 * older ones tucked behind it. Every frame is a pure function of one value: the swipe offset,
 * the zoom progress, or the stack's focus. Geometry is in px.
 */
@Stable
class SessionSwitcherController internal constructor(private val scope: CoroutineScope) {
    internal data class Entry(
        val id: String,
        /** Null for the screen that was showing when it isn't a listed session (a draft). */
        val card: SessionSwitcherCard?,
    )

    @Suppress("EnumEntryName")
    enum class Mode { idle, swipe, zoom, deck, landing }

    /** A card's page (iOS `setContent`'s view). */
    internal sealed interface Content {
        /** A screen captured as it was left. */
        data class Capture(val image: ImageBitmap) : Content
        /** A cold page drawn from the transcript cache. */
        data class Render(val image: ImageBitmap) : Content
        /** The live screen itself, until its capture (taken as the gesture starts) is ready. */
        data object Live : Content
        /** Not drawn yet: its title where the session's header shows it. */
        data class Placeholder(val title: String) : Content
    }

    /** iOS `SessionSwitcherCardView`'s placed state, read in its layer block. */
    @Stable
    internal class CardState(val id: String, val label: String, content: Content) {
        var content by mutableStateOf(content)
        var pose by mutableStateOf(SessionSwitcherPose(Offset.Zero, 1f))
        var alpha by mutableFloatStateOf(1f)
        var hidden by mutableStateOf(true)
        var z by mutableIntStateOf(0)
    }

    /** iOS `SessionSwitcherCaptionView`'s placed state. */
    @Stable
    internal class CaptionState(val card: SessionSwitcherCard) {
        var frame by mutableStateOf(Rect.Zero)
        var visibleWidth by mutableFloatStateOf(0f)
        var alpha by mutableFloatStateOf(0f)
        var hidden by mutableStateOf(true)
    }

    /** One stack card for TalkBack: the part not covered by a newer card (iOS `accessibilityFrame`). */
    internal data class AccessibilityElement(val id: String, val order: Int, val frame: Rect, val label: String, val selected: Boolean)

    // Environment (set by the composables).
    internal var liveLayer: GraphicsLayer? = null
    internal var exclusion: SessionSwitcherExclusion? = null
    internal var rootCoordinates: LayoutCoordinates? = null
    internal var frameDuration = 1.0 / 60.0
    internal var density = 1f
    internal var safeTop = 0f
    internal var keyboardUp: () -> Boolean = { false }
    internal var touchExploration: () -> Boolean = { false }
    /** Whether a point (px in [rootCoordinates]) is on the card's text field (the composer's). */
    internal var isOnTextField: (LayoutCoordinates?, Offset) -> Boolean = { _, _ -> false }
    internal var warmLayer: GraphicsLayer? = null
    private var size = Size.Zero
    private var lastSize = Size.Zero
    private var lastDark: Boolean? = null

    private var model = SessionSwitcherHost.inert
    private var initialized = false

    // Drawn state.
    internal var mode by mutableStateOf(Mode.idle)
        private set
    internal var liveHidden by mutableStateOf(false)
        private set
    internal var backdropHidden by mutableStateOf(true)
        private set
    internal var backdropAlpha by mutableFloatStateOf(1f)
        private set
    internal var stageHidden by mutableStateOf(true)
        private set
    internal var stageAlpha by mutableFloatStateOf(1f)
        private set
    internal var captionsHidden by mutableStateOf(true)
        private set
    internal var captionsAlpha by mutableFloatStateOf(1f)
        private set
    internal var pagerCount by mutableIntStateOf(0)
        private set
    internal var pagerPage by mutableIntStateOf(0)
        private set
    internal var pagerAlpha by mutableFloatStateOf(0f)
        private set
    internal var pagerCenterY by mutableFloatStateOf(0f)
        private set
    internal val cards = mutableStateMapOf<String, CardState>()
    internal val captions = mutableStateMapOf<String, CaptionState>()
    internal var accessibilityElements by mutableStateOf<List<AccessibilityElement>>(emptyList())
        private set
    /** The cold page being drawn ahead of time (iOS `render(_:)`), drawn offscreen by the stage. */
    internal var warming by mutableStateOf<Pair<SessionSwitcherCard, List<OpenCodeTranscriptItem>>?>(null)
        private set

    private var stacked: List<Int> = emptyList()
    private var entries: List<Entry> = emptyList()
    private var activeIndex = 0
    /** Screens captured as sessions are left: crisp and cheap, but full resolution, so only the most recent few are kept. */
    private val captures = SessionSwitcherCache<ImageBitmap>(limit = 4)
    /** Sessions never seen this launch, drawn once from the transcript cache. */
    private val renders = SessionSwitcherCache<ImageBitmap>(limit = 8)
    private var cacheGeneration = 0
    private var warmJob: Job? = null
    private var lastCapture: Pair<String, Double>? = null
    /** The active entry whose capture is on its way; its card shows the live screen until then. */
    private var liveCaptureFor: String? = null

    private var layout = SessionSwitcherLayout(Size.Zero)
    /** Swipe offset in px, zoom progress (0 full screen … 1 stack), or the stack's focus. */
    private var value = 0f
    private var start = 0f
    private var slop = Offset.Zero
    private var lastMove: Pair<Offset, Double> = Offset.Zero to 0.0
    private var dragging = false
    private var neighbourIndex: Int? = null
    private var armed = false
    private var zoomTarget = 0
    private var zoomFocus = 0f
    private var pendingIndex: Int? = null
    private var landing: SessionSwitcherLanding? = null
    private var landingJob: Job? = null
    private var revealScheduled = false
    private var fadesWhenSelected = false
    private var revealJob: Job? = null
    private var spring = SpringSolver(SessionSwitcherTunables.zoomSpring)
    private var clock: Job? = null
    private val clockRunning: Boolean get() = clock?.isActive == true
    private val haptics: HapticsClient by lazy { dependency(HapticsClient) }

    private fun now(): Double = System.nanoTime() / 1e9

    // MARK: Lifecycle

    /** iOS `viewDidLayoutSubviews`: a new size or appearance drops every page and ends what's moving. */
    internal fun layoutChanged(newSize: Size, dark: Boolean) {
        size = newSize
        if (newSize != lastSize || dark != lastDark) {
            clearCaches()
            if (mode != Mode.idle) finish()
            lastSize = newSize
            lastDark = dark
        }
    }

    /** The appearance changed (iOS `traitCollection.userInterfaceStyle`): pages drawn in the other one are dropped. */
    internal fun appearanceChanged(dark: Boolean) = layoutChanged(size, dark)

    /** iOS `updateUIViewController` → `update(_:)`. */
    internal fun update(next: SessionSwitcherHost) {
        if (!initialized) {
            initialized = true
            model = next
            if (next.available) scheduleWarm()
            return
        }
        val previous = model
        val changedServer = previous.serverID != next.serverID
        val presentationChanged = previous.isPresented != next.isPresented
        // A switch with the screen at rest (a link, a notification): the screen still shows the
        // session being left until this update draws.
        val left = previous.selectedID
        if (size.width > 0 && mode == Mode.idle && !changedServer && previous.available && left != null && left != next.selectedID) {
            captureScreen(left, force = true)
        }
        model = next
        if (changedServer) {
            clearCaches()
            if (size.width > 0) finish()
        }
        if (!next.available && mode != Mode.idle && mode != Mode.landing) finish()
        if (presentationChanged && next.available && next.isPresented && mode == Mode.idle && size.width > 0) {
            prepare()
            beginZoom(activeIndex, activeIndex.toFloat())
            settle(1f, 0f, SessionSwitcherTunables.zoomSpring)
        } else if (presentationChanged && !next.isPresented && mode == Mode.deck) {
            open(pendingIndex ?: activeIndex)
        }
        dropDeleted()
        checkLanding()
        if (mode == Mode.idle && next.available) scheduleWarm()
    }

    /** The experiment was turned off: nothing may stay over the live screen. */
    internal fun disable() {
        if (mode != Mode.idle) finish()
        stop()
        initialized = false
        model = SessionSwitcherHost.inert
    }

    /** iOS `dismantleUIViewController` → `stop()`. */
    internal fun stop() {
        clock?.cancel()
        warmJob?.cancel()
        warmJob = null
        landingJob?.cancel()
    }

    // MARK: Gestures

    /** iOS `gestureRecognizer(_:shouldReceive:)` for the pan, at [point] (px in the switcher). */
    internal fun shouldReceive(point: Offset): Boolean {
        if (!model.available || touchExploration()) return false
        if (mode == Mode.deck || (mode == Mode.zoom && clockRunning)) return true
        val edge = BackSwipeRules.edgeWidth.value * density
        if (point.x <= edge || point.x >= size.width - edge) return false
        if (point.y < size.height - SessionSwitcherTunables.gestureHeight.value * density) return false
        if (mode == Mode.swipe) return clockRunning
        if (mode != Mode.idle) return false
        // With the keyboard up its editing gestures keep the composer. The strip becomes
        // available again when the keyboard is dismissed.
        if (keyboardUp()) return false
        // Controls on the card that own a sideways drag (`excludedFromDrawerPan`) keep it.
        if (exclusion?.contains(rootCoordinates, point) == true) return false
        // A text field keeps its own editing gestures (iOS's `UITextView` ancestor walk).
        if (isOnTextField(rootCoordinates, point)) return false
        return true
    }

    /** iOS tap recognizer's `shouldReceive`: only the stack takes taps. */
    internal fun receivesTap(): Boolean = model.available && !touchExploration() && mode == Mode.deck

    /** iOS `gestureRecognizerShouldBegin`: at rest, a sideways or upward swipe. [direction] is the velocity, or the travel when it has none. */
    internal fun shouldBegin(direction: Offset): Boolean {
        if (mode != Mode.idle) return true
        return abs(direction.x) > abs(direction.y) || direction.y < 0
    }

    internal enum class Phase { Began, Changed, Ended, Cancelled }

    /** iOS `drag(_:)`. [fingerTranslation] is the travel since touch-down; [measured] the tracker's velocity, px/s. */
    internal fun drag(phase: Phase, fingerTranslation: Offset, measured: Offset) {
        if (phase == Phase.Began) slop = fingerTranslation
        val delta = caughtUp(fingerTranslation)
        val now = now()
        if (phase == Phase.Began || hypot(delta.x - lastMove.first.x, delta.y - lastMove.first.y) > density) lastMove = delta to now
        // A finger held still before letting go has no velocity, whatever the tracker last measured.
        val velocity = if (now - lastMove.second > 0.08) Offset.Zero else measured
        when (phase) {
            Phase.Began, Phase.Changed -> {
                if (phase == Phase.Began) {
                    clock?.cancel()
                    dragging = true
                    when (mode) {
                        Mode.idle -> {
                            prepare()
                            if (abs(velocity.x) > abs(velocity.y) || (velocity == Offset.Zero && abs(delta.x) > abs(delta.y))) {
                                beginSwipe()
                            } else {
                                beginZoom(activeIndex, activeIndex.toFloat())
                            }
                        }
                        Mode.deck -> if (abs(velocity.y) > abs(velocity.x) || (velocity == Offset.Zero && abs(delta.y) > abs(delta.x))) {
                            // Pulling the stack down grows the nearest card back to full screen.
                            beginZoom(nearest(value), value)
                            value = 1f
                        }
                        else -> Unit
                    }
                    if (mode == Mode.swipe) {
                        haptics.prepare(Haptic.threshold)
                        armed = isArmed(value)
                    }
                    start = value
                }
                when (mode) {
                    Mode.swipe -> {
                        val raw = start + delta.x
                        neighbourIndex = neighbour(raw) ?: neighbourIndex
                        value = if (neighbour(raw) == null) SessionSwitcherLayout.rubberBand(raw, size.width * 0.25f) else raw
                        val nowArmed = isArmed(value)
                        if (nowArmed && !armed) haptics.play(Haptic.threshold)
                        armed = nowArmed
                    }
                    Mode.zoom -> {
                        val raw = start - delta.y / layout.zoomReach
                        value = if (raw > 1) 1 + SessionSwitcherLayout.rubberBand(raw - 1, 0.12f) else max(0f, raw)
                    }
                    Mode.deck -> {
                        val raw = start + delta.x / layout.deckStride
                        val last = (entries.size - 1).toFloat()
                        value = when {
                            raw < 0 -> SessionSwitcherLayout.rubberBand(raw, 0.4f)
                            raw > last -> last + SessionSwitcherLayout.rubberBand(raw - last, 0.4f)
                            else -> raw
                        }
                    }
                    else -> Unit
                }
                draw()
            }
            Phase.Ended, Phase.Cancelled -> {
                dragging = false
                val cancelled = phase != Phase.Ended
                when (mode) {
                    Mode.swipe -> {
                        val projected = value + velocity.x * SessionSwitcherTunables.projection
                        val commit = !cancelled && neighbour(value) != null &&
                            abs(projected) > size.width * SessionSwitcherTunables.threshold && projected * value > 0
                        settle(if (commit) (if (value < 0) -1 else 1) * layout.rowStride else 0f, velocity.x, SessionSwitcherTunables.swipeSpring)
                    }
                    Mode.zoom -> {
                        val v = -velocity.y / layout.zoomReach
                        val projected = value + v * SessionSwitcherTunables.projection
                        val toStack = if (cancelled) value > 0.5f else projected > 0.5f
                        if (!toStack) select(zoomTarget)
                        settle(if (toStack) 1f else 0f, v, SessionSwitcherTunables.zoomSpring)
                    }
                    Mode.deck -> {
                        val v = velocity.x / layout.deckStride
                        val projected = value + v * SessionSwitcherTunables.projection
                        settle(nearest(projected).toFloat(), v, SessionSwitcherTunables.deckSpring)
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * The pan begins once the finger has already moved a little. Start from where things are and
     * close that gap over the next 48 dp, so the card neither jumps on the first frame nor trails
     * the finger.
     */
    private fun caughtUp(translation: Offset): Offset {
        val moved = hypot(translation.x - slop.x, translation.y - slop.y)
        val remaining = max(0f, 1 - moved / (48 * density))
        return Offset(translation.x - slop.x * remaining, translation.y - slop.y * remaining)
    }

    /** iOS `tapped(_:)`: the topmost card under the finger opens; elsewhere, the one in front. */
    internal fun tapped(point: Offset) {
        if (mode != Mode.deck) return
        // Topmost first: newer cards lie over older ones.
        val hit = stacked.asReversed().firstOrNull { index ->
            val card = cards[entries[index].id] ?: return@firstOrNull false
            !card.hidden && card.pose.frame(size).contains(point)
        }
        open(hit ?: pendingIndex ?: activeIndex)
    }

    /** iOS `stage.onEscape` (Back while the stack is up): back to the session you came from. */
    internal fun escape(): Boolean {
        if (mode != Mode.deck) return false
        open(pendingIndex ?: activeIndex)
        return true
    }

    /** iOS `stage.onScroll`: TalkBack pages the stack one card ([step] +1 older, −1 newer). */
    internal fun accessibilityScroll(step: Int): Boolean {
        if (mode != Mode.deck || step == 0) return false
        val target = min(max(0f, Math.round(value + step).toFloat()), (entries.size - 1).toFloat())
        if (target == Math.round(value).toFloat()) return false
        settle(target, 0f, SessionSwitcherTunables.deckSpring)
        entries[target.toInt()].card?.title?.let { dependency(AccessibilityAnnouncer).announce(it, false) }
        return true
    }

    // MARK: Transitions

    private fun prepare() {
        layout = SessionSwitcherLayout.current(size, density)
        val listed = model.cards.map { Entry(it.id, it) }
        val selected = model.selectedID
        val index = selected?.let { id -> listed.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            entries = listed
            activeIndex = index
        } else {
            entries = listOf(Entry(CURRENT_ID, null)) + listed
            activeIndex = 0
        }
        val keep = entries.map { it.id }.toSet()
        captures.keep(keep)
        renders.keep(keep)
        // The live screen as it is this frame, so the first frame doesn't move.
        liveCaptureFor = entries[activeIndex].id
        captureScreen(entries[activeIndex].id, force = true)
        value = 0f
        pendingIndex = null
        neighbourIndex = null
        revealScheduled = false
        fadesWhenSelected = false
        revealJob?.cancel()
        backdropHidden = false
        backdropAlpha = 1f
        stageHidden = false
        stageAlpha = 1f
        captionsHidden = false
        captionsAlpha = 1f
        pagerCount = entries.size
        pagerPage = activeIndex
        pagerAlpha = 0f
    }

    private fun beginSwipe() {
        mode = Mode.swipe
        liveHidden = true
    }

    private fun beginZoom(target: Int, focus: Float) {
        mode = Mode.zoom
        zoomTarget = target
        zoomFocus = focus
        pagerAlpha = 0f
        // Reduce Motion keeps the live screen and fades the stack over it.
        liveHidden = !model.reducedMotion
        updateAccessibility()
    }

    /** Opens a card from the stack: it grows back to full screen on the same path it took to get there. */
    private fun open(index: Int) {
        if (mode != Mode.deck) return
        clock?.cancel()
        if (model.reducedMotion && entries[index].id != model.selectedID && entries[index].card != null) {
            // Fade only once the live screen already shows the chosen session.
            fadesWhenSelected = true
            zoomTarget = index
            zoomFocus = value
            mode = Mode.landing
            select(index)
            checkLanding()
            return
        }
        beginZoom(index, value)
        value = 1f
        select(index)
        settle(0f, 0f, SessionSwitcherTunables.zoomSpring)
    }

    /** Starts loading the chosen session while its card is still travelling. */
    private fun select(index: Int) {
        pendingIndex = index
        val card = entries[index].card ?: return
        if (card.id == model.selectedID) return
        landing = SessionSwitcherLanding(card.id, now())
        landingJob?.cancel()
        landingJob = scope.launch {
            delay((SessionSwitcherLanding.TIMEOUT * 1000).toLong())
            checkLanding()
        }
        model.send(SessionSwitcherFeature.Action.Selected(card.id))
    }

    /** Blends into the live screen once it shows the chosen session; gives up (back to whatever the live screen shows) if that never happens. */
    private fun checkLanding() {
        val landing = landing
        if (mode != Mode.landing || revealScheduled || landing == null) return
        val listed = model.cards.any { it.id == landing.id }
        when (landing.outcome(model.selectedID, listed, now())) {
            SessionSwitcherLanding.Outcome.waiting -> return
            SessionSwitcherLanding.Outcome.arrived -> {
                revealScheduled = true
                // Home has the new screen. Keep the landing card over it until it has drawn,
                // then blend into the live screen.
                scope.launch {
                    withFrameNanos { }
                    selectionArrived()
                }
            }
            SessionSwitcherLanding.Outcome.abandoned -> {
                AppLog.app.info { "Session switcher landing abandoned listed=$listed" }
                revealScheduled = true
                fadesWhenSelected = false
                revealLive()
            }
        }
        this.landing = null
        landingJob?.cancel()
        landingJob = null
    }

    private fun settle(target: Float, velocity: Float, tunable: MotionSpring) {
        val points = mode == Mode.swipe
        spring = SpringSolver(
            tunable,
            value = value.toDouble(),
            velocity = velocity.toDouble(),
            target = target.toDouble(),
            positionEpsilon = if (points) 0.25 * density else 0.0005,
            velocityEpsilon = if (points) 4.0 * density else 0.005,
        )
        clock?.cancel()
        clock = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                val step = spring.step(tick.dt)
                value = step.value.toFloat()
                draw()
                if (step.isSettled) {
                    // `arrived` may start the next spring; let it replace this loop.
                    clock = null
                    arrived()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    private fun arrived() {
        when (mode) {
            Mode.swipe -> {
                val neighbour = neighbourIndex
                if (value != 0f && neighbour != null) land(neighbour) else finish()
            }
            Mode.zoom -> if (value >= 1) {
                mode = Mode.deck
                value = zoomFocus
                draw()
                updateAccessibility()
                if (!model.isPresented) model.send(SessionSwitcherFeature.Action.PresentationChanged(true))
                val settled = nearest(value).toFloat()
                if (settled != value) settle(settled, 0f, SessionSwitcherTunables.deckSpring)
            } else {
                land(zoomTarget)
            }
            Mode.deck -> updateAccessibility()
            Mode.idle, Mode.landing -> Unit
        }
    }

    private fun land(index: Int) {
        mode = Mode.landing
        select(index)
        if (entries[index].card == null || entries[index].id == model.selectedID) {
            revealScheduled = true
            revealLive()
        } else {
            checkLanding()
        }
    }

    private fun selectionArrived() {
        if (fadesWhenSelected) {
            fadesWhenSelected = false
            beginZoom(zoomTarget, zoomFocus)
            value = 1f
            settle(0f, 0f, SessionSwitcherTunables.zoomSpring)
        } else {
            revealLive()
        }
    }

    private fun revealLive() {
        if (mode != Mode.landing && mode != Mode.zoom) return
        mode = Mode.landing
        liveHidden = false
        val index = pendingIndex ?: activeIndex
        val card = cards[entries[index].id]
        if (card == null || card.hidden || stageAlpha <= 0.01f) {
            finish()
            return
        }
        // Everything else is behind the full-screen card or off screen.
        for ((id, other) in cards) if (id != entries[index].id) other.hidden = true
        backdropHidden = true
        captionsHidden = true
        val from = card.alpha
        revealJob?.cancel()
        revealJob = scope.launch {
            var elapsed = 0.0
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                val t = min(1.0, elapsed / Motion.fadeFast)
                card.alpha = from * (1 - Motion.EaseInOut.transform(t.toFloat()))
                if (t >= 1) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
            finish()
        }
    }

    private fun finish() {
        clock?.cancel()
        clock = null
        revealJob?.cancel()
        revealJob = null
        mode = Mode.idle
        value = 0f
        dragging = false
        pendingIndex = null
        landing = null
        landingJob?.cancel()
        landingJob = null
        neighbourIndex = null
        armed = false
        revealScheduled = false
        fadesWhenSelected = false
        liveHidden = false
        liveCaptureFor = null
        cards.clear()
        captions.clear()
        stacked = emptyList()
        accessibilityElements = emptyList()
        backdropHidden = true
        stageHidden = true
        captionsHidden = true
        pagerAlpha = 0f
        scheduleWarm()
        if (model.isPresented) {
            scope.launch { model.send(SessionSwitcherFeature.Action.PresentationChanged(false)) }
        }
    }

    // MARK: Drawing

    private fun draw() {
        when (mode) {
            Mode.swipe -> drawSwipe()
            Mode.zoom -> drawZoom()
            Mode.deck -> drawDeck()
            Mode.idle, Mode.landing -> Unit
        }
    }

    private fun drawSwipe() {
        val (current, neighbourPose) = layout.swipe(value)
        val centre = SessionSwitcherPose(Offset(size.width / 2, size.height / 2), 1f)
        val active = card(activeIndex)
        val shown = mutableListOf(activeIndex)
        active.apply(if (model.reducedMotion) centre else current, 1f)
        val neighbour = neighbourIndex
        if (neighbour != null && neighbour(value) == neighbour) {
            val card = card(neighbour)
            if (model.reducedMotion) {
                card.apply(centre, min(1f, abs(value) / layout.rowStride))
                shown.add(neighbour)
            } else {
                card.apply(neighbourPose, 1f)
                shown.add(0, neighbour)
            }
        }
        show(shown, ordered = true)
        for (caption in captions.values) caption.hidden = true
        val lift = if (model.reducedMotion) min(1f, abs(value) / (layout.rowStride * 0.2f)) else current.lift
        pagerPage = if (armed) (neighbourIndex ?: activeIndex) else activeIndex
        pagerAlpha = lift * (if (entries.size > 1) 1f else 0f)
        // iOS: frame.minY = height − max(gap, 22)/2 − pagerHeight/2 − 4, so its centre is:
        val gap = size.height * (1 - current.scale) / 2
        pagerCenterY = size.height - max(gap, 22 * density) / 2 - 4 * density
    }

    private fun drawZoom() {
        val progress = value
        val low = max(0, min(zoomTarget, floor(zoomFocus).toInt()) - 2)
        val high = min(entries.size - 1, max(zoomTarget, ceil(zoomFocus).toInt()) + 5)
        if (low > high) return
        val poses = (low..high).map { index ->
            index to if (model.reducedMotion) layout.deck(index - zoomFocus) else layout.zoom(index, zoomTarget, zoomFocus, progress)
        }
        if (model.reducedMotion) {
            val fade = min(1f, max(0f, progress))
            stageAlpha = fade
            backdropAlpha = fade
            captionsAlpha = fade
        }
        place(poses)
    }

    private fun drawDeck() {
        val low = max(0, floor(value).toInt() - 2)
        val high = min(entries.size - 1, ceil(value).toInt() + 5)
        if (low > high) return
        place((low..high).map { it to layout.deck(it - value) })
    }

    private fun place(poses: List<Pair<Int, SessionSwitcherPose>>) {
        val screen = Rect(-2 * density, -2 * density, size.width + 2 * density, size.height + 2 * density)
        val visible = poses.filter { it.second.frame(size).overlaps(screen) }
        for ((index, pose) in visible) card(index).apply(pose, 1f)
        show(visible.map { it.first }, ordered = false)
        // Captions travel with their card, and fade out where a newer card covers them.
        val frames = visible.associate { it.first to it.second.frame(size) }
        val captioned = HashSet<String>()
        for ((index, pose) in visible) {
            val card = entries[index].card ?: continue
            val frame = frames[index] ?: continue
            val cover = frames[index - 1]?.let { if (it.left > frame.left) it.left - frame.left - 12 * density else 0f } ?: frame.width
            val width = min(frame.width, cover)
            val alpha = pose.lift * pose.lift * min(1f, max(0f, (width - 28 * density) / (36 * density))) * (1 - pose.dim)
            if (alpha <= 0.01f) continue
            val caption = caption(card)
            caption.hidden = false
            caption.alpha = alpha
            caption.frame = frame
            caption.visibleWidth = width
            captioned.add(card.id)
        }
        for ((id, caption) in captions) if (id !in captioned) caption.hidden = true
    }

    /** Hides cards not in [indices] and keeps newer cards above older ones. */
    private fun show(indices: List<Int>, ordered: Boolean) {
        val set = indices.toSet()
        entries.forEachIndexed { index, entry -> if (index !in set) cards[entry.id]?.hidden = true }
        val order = if (ordered) indices else indices.sortedDescending()
        if (order != stacked) {
            order.forEachIndexed { z, index -> cards[entries[index].id]?.z = z }
            stacked = order
        }
        for (index in indices) cards[entries[index].id]?.hidden = false
    }

    private fun CardState.apply(pose: SessionSwitcherPose, alpha: Float) {
        this.pose = pose
        this.alpha = alpha
    }

    private fun card(index: Int): CardState {
        val entry = entries[index]
        cards[entry.id]?.let { return it }
        val label = entry.card?.let { listOf(it.title, it.project, it.status).filter(String::isNotEmpty).joinToString(", ") } ?: "Current screen"
        val card = CardState(entry.id, label, content(entry))
        cards[entry.id] = card
        stacked = emptyList()
        return card
    }

    private fun content(entry: Entry): Content {
        if (entry.id == liveCaptureFor) return Content.Live
        captures[entry.id]?.let {
            captures.touch(entry.id)
            return Content.Capture(it)
        }
        entry.card?.let { card ->
            renders[card.id]?.let {
                renders.touch(card.id)
                return Content.Render(it)
            }
        }
        // Never draw a page mid-gesture: a card not drawn yet is a quiet page with its title
        // where the header sits, and is drawn once things are still.
        return Content.Placeholder(entry.card?.title.orEmpty())
    }

    private fun caption(card: SessionSwitcherCard): CaptionState =
        captions[card.id] ?: CaptionState(card).also { captions[card.id] = it }

    private fun updateAccessibility() {
        if (mode != Mode.deck) {
            accessibilityElements = emptyList()
            return
        }
        // Newest first; a card's frame is the part not covered by a newer card.
        val ordered = stacked.sorted()
        val bounds = Rect(Offset.Zero, size)
        val elements = ArrayList<AccessibilityElement>()
        for (index in ordered) {
            val card = cards[entries[index].id] ?: continue
            if (card.hidden) continue
            var frame = card.pose.frame(size)
            val newer = ordered.lastOrNull { it < index }?.let { cards[entries[it].id] }
            if (newer != null) {
                val newerFrame = newer.pose.frame(size)
                if (newerFrame.left > frame.left) frame = frame.copy(right = frame.left + min(frame.width, newerFrame.left - frame.left))
            }
            frame = frame.intersect(bounds)
            if (frame.width <= 0 || frame.height <= 0) continue
            elements += AccessibilityElement(card.id, elements.size, frame, card.label, selected = index == nearest(value))
        }
        accessibilityElements = elements
    }

    /** A stack card was activated by TalkBack (iOS `accessibilityActivate`). */
    internal fun activate(id: String): Boolean {
        if (mode != Mode.deck) return false
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        open(index)
        return true
    }

    // MARK: Captures

    /**
     * Every way off a session starts with a touch while it's at rest (the drawer's edge or
     * button, a row, a notice), so the screen is captured on touch-down, before anything moves
     * or the drawer changes its insets.
     */
    internal fun touchBegan() {
        val id = model.selectedID ?: return
        if (mode != Mode.idle || !model.available || keyboardUp()) return
        captureScreen(id, force = false)
    }

    private fun captureScreen(id: String, force: Boolean) {
        val now = now()
        val last = lastCapture
        if (!force && last != null && last.first == id && now - last.second < 0.5) return
        val layer = liveLayer ?: return
        if (liveHidden || size.width <= 0) return
        lastCapture = id to now
        val generation = cacheGeneration
        // Undispatched: the layer is read now, before the next frame draws what comes next.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val image = try {
                layer.toImageBitmap()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            if (generation != cacheGeneration) return@launch
            if (image != null) captures.set(image, id)
            if (liveCaptureFor == id) {
                liveCaptureFor = null
                // The card that showed the live screen keeps the screen as it was left.
                cards[id]?.let { card -> if (card.content == Content.Live) card.content = image?.let(Content::Capture) ?: Content.Placeholder("") }
            }
        }
    }

    private fun clearCaches() {
        cacheGeneration++
        captures.removeAll()
        renders.removeAll()
        warming = null
    }

    /** Draws the nearest cold cards ahead of time, one at a time while the switcher is at rest. */
    private fun scheduleWarm(delaySeconds: Double = 1.0) {
        if (warmJob?.isActive == true) return
        warmJob = scope.launch {
            delay((delaySeconds * 1000).toLong())
            warmJob = null
            warmNext()
        }
    }

    private fun warmNext() {
        if (mode != Mode.idle || !model.available || size.width <= 0 || warming != null) return
        val list = model.cards
        if (list.isEmpty()) return
        val selected = model.selectedID?.let { id -> list.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        // Nearest first, the same window the stack and the swipe can show.
        val nearby = (max(0, selected - 2)..min(list.size - 1, selected + 4))
            .sortedBy { abs(it - selected) }.map { list[it] }
        val next = nearby.firstOrNull { it.id != model.selectedID && captures[it.id] == null && renders[it.id] == null } ?: return
        warming = next to model.transcript(next.id)
    }

    /** The offscreen page for [id] has drawn (iOS `ImageRenderer.uiImage`). */
    internal suspend fun warmDrawn(id: String) {
        val layer = warmLayer
        val generation = cacheGeneration
        val image = try {
            layer?.toImageBitmap()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (generation == cacheGeneration && image != null) renders.set(image, id)
        if (warming?.first?.id == id) warming = null
        scheduleWarm(0.15)
    }

    // MARK: Row

    /**
     * Sessions deleted while the switcher is up leave the row. One the gesture is heading for
     * ends the gesture; a landing one gives up in [checkLanding].
     */
    private fun dropDeleted() {
        if (mode == Mode.idle || mode == Mode.landing || entries.isEmpty()) return
        val prune = SessionSwitcherPrune(entries.map { it.card?.id }, model.cards.map { it.id }.toSet())
        if (prune.isEmpty) return
        val heading = when (mode) {
            Mode.swipe -> listOf(activeIndex) + listOfNotNull(neighbourIndex)
            Mode.zoom -> listOf(zoomTarget)
            else -> emptyList()
        }
        if (prune.remaining <= 0 || !heading.all { prune.index(it) != null }) {
            finish()
            return
        }
        for (index in prune.removed) {
            val id = entries[index].id
            cards.remove(id)
            captions.remove(id)
        }
        entries = entries.indices.filter { prune.index(it) != null }.map { entries[it] }
        activeIndex = prune.focus(activeIndex.toFloat()).toInt()
        zoomTarget = prune.focus(zoomTarget.toFloat()).toInt()
        zoomFocus = prune.focus(zoomFocus)
        neighbourIndex = neighbourIndex?.let(prune::index)
        pendingIndex = pendingIndex?.let(prune::index)
        stacked = emptyList()
        pagerCount = entries.size
        pagerPage = activeIndex
        if (mode == Mode.deck) {
            clock?.cancel()
            value = prune.focus(value)
            draw()
            updateAccessibility()
            if (!dragging) settle(nearest(value).toFloat(), 0f, SessionSwitcherTunables.deckSpring)
        } else {
            draw()
        }
    }

    private fun neighbour(offset: Float): Int? {
        if (offset == 0f) return null
        val index = activeIndex + if (offset > 0) 1 else -1
        return if (index in entries.indices) index else null
    }

    private fun isArmed(offset: Float): Boolean =
        neighbour(offset) != null && abs(offset) >= size.width * SessionSwitcherTunables.threshold

    private fun nearest(focus: Float): Int = min(max(0, focus.roundToInt()), max(0, entries.size - 1))

    internal companion object {
        const val CURRENT_ID = "session-switcher-current"
        /** iOS `UIPageControl` (minimal) dot pitch and size. */
        val PAGER_PITCH = 16.dp
        val PAGER_DOT = 7.dp
    }
}

/** Least-recently-used, memory-only, keyed by session ID (iOS `SessionSwitcherCache`). */
class SessionSwitcherCache<V>(val limit: Int) {
    private val values = HashMap<String, V>()
    private val order = ArrayList<String>()

    operator fun get(id: String): V? = values[id]

    fun set(value: V, id: String) {
        values[id] = value
        touch(id)
        while (order.size > limit) values.remove(order.removeAt(0))
    }

    fun touch(id: String) {
        if (values[id] == null) return
        order.remove(id)
        order.add(id)
    }

    fun keep(ids: Set<String>) {
        order.retainAll(ids)
        values.keys.retainAll(ids)
    }

    fun removeAll() {
        values.clear()
        order.clear()
    }
}

/**
 * `LocalDrawerPanExclusion` for the live screen (iOS `DrawerPanExclusionSpace`, which both the
 * drawer's pan and the switcher's read): forwards every region to the drawer's and keeps them so
 * the switcher's strip yields to the same controls.
 */
@Stable
internal class SessionSwitcherExclusion(private val parent: DrawerPanExclusion?) : DrawerPanExclusion {
    private val regions = HashMap<Any, LayoutCoordinates>()

    override fun updateExclusion(id: Any, coordinates: LayoutCoordinates?) {
        if (coordinates == null) regions.remove(id) else regions[id] = coordinates
        parent?.updateExclusion(id, coordinates)
    }

    override fun updateHorizontalScroller(
        id: Any,
        coordinates: LayoutCoordinates?,
        canScrollBackward: () -> Boolean,
        canScrollForward: () -> Boolean,
    ) {
        parent?.updateHorizontalScroller(id, coordinates, canScrollBackward, canScrollForward)
    }

    fun contains(root: LayoutCoordinates?, point: Offset): Boolean {
        if (root == null || !root.isAttached) return false
        return regions.values.any { region ->
            if (!region.isAttached) return@any false
            val local = region.localPositionOf(root, point)
            local.x >= 0 && local.y >= 0 && local.x < region.size.width && local.y < region.size.height
        }
    }
}

/**
 * The switcher's finger (iOS `SessionSwitcherPan` + the tap recognizer +
 * `SessionSwitcherTouchObserver`), on the switcher's root in the `Initial` pass so it sees every
 * touch before the session's own controls:
 * - every touch-down is reported ([SessionSwitcherController.touchBegan]) without taking it;
 * - the pan takes one finger once it has moved past the touch slop in a direction it begins
 *   for, after which it consumes the pointer (children cancel, iOS `cancelsTouchesInView`);
 * - a finger that rested 0.45 s first, or under which a hold already began, keeps its hold;
 * - while the switcher is up (stack, flight, landing) it consumes every touch, and a tap on the
 *   stack opens the card under it.
 */
internal fun Modifier.sessionSwitcherGestures(controller: SessionSwitcherController): Modifier = composed {
    val density = LocalDensity.current
    controller.density = density.density
    Modifier.pointerInput(controller) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            controller.touchBegan()
            val pans = controller.shouldReceive(down.position)
            val taps = controller.receivesTap()
            // While the switcher is up (stack, flight, landing), the hidden live screen takes no touches.
            val blocks = controller.mode != SessionSwitcherController.Mode.idle
            if (!pans && !taps && !blocks) return@awaitEachGesture
            if (blocks) down.consume()
            val pointerId = down.id
            val origin = down.position
            val downTime = down.uptimeMillis
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var began = false
            var tapCancelled = false
            var holdBegan = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) {
                    if (began) {
                        if (change != null) {
                            tracker.addPointerInputChange(change)
                            change.consume()
                            controller.drag(SessionSwitcherController.Phase.Ended, change.position - origin, tracker.velocity())
                        } else {
                            controller.drag(SessionSwitcherController.Phase.Cancelled, Offset.Zero, Offset.Zero)
                        }
                    } else if (taps && !tapCancelled && change != null) {
                        change.consume()
                        controller.tapped(change.position)
                    } else if (blocks && change != null) {
                        change.consume()
                    }
                    return@awaitEachGesture
                }
                tracker.addPointerInputChange(change)
                val translation = change.position - origin
                if (began) {
                    change.consume()
                    controller.drag(SessionSwitcherController.Phase.Changed, translation, tracker.velocity())
                    continue
                }
                if (blocks) change.consume()
                if (translation.getDistance() < slop) {
                    if (!blocks) {
                        // Children see this move next; one that consumes it first began a hold.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { it.id == pointerId && it.positionChange() != Offset.Zero && it.isConsumed }) holdBegan = true
                    }
                    continue
                }
                tapCancelled = true
                if (!pans) continue
                val heldFor = (change.uptimeMillis - downTime) / 1000.0
                if (!blocks && BackSwipeRules.yieldsToHold(heldFor, holdBegan)) return@awaitEachGesture
                val velocity = tracker.velocity()
                val direction = if (velocity == Offset.Zero) translation else velocity
                if (!controller.shouldBegin(direction)) {
                    if (blocks) continue else return@awaitEachGesture
                }
                began = true
                change.consume()
                controller.drag(SessionSwitcherController.Phase.Began, translation, velocity)
            }
        }
    }
}

private fun VelocityTracker.velocity(): Offset = calculateVelocity().let { Offset(it.x, it.y) }

/**
 * Everything the switcher draws over (and the TalkBack stack beside) the live screen: cards,
 * captions, page dots, the offscreen cold page, and the drawer-pan exclusions of its strip.
 */
@Composable
internal fun SessionSwitcherStage(controller: SessionSwitcherController) {
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val context = LocalContext.current
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    val statusBars = WindowInsets.statusBars
    controller.safeTop = statusBars.getTop(density).toFloat()
    controller.keyboardUp = { ime.getBottom(density) > navigation.getBottom(density) + density.density }
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    controller.touchExploration = { touchExploration }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    LaunchedEffect(colors.isDark) { controller.appearanceChanged(colors.isDark) }

    BackHandler(enabled = controller.mode == SessionSwitcherController.Mode.deck) { controller.escape() }

    Box(Modifier.fillMaxSize().onSizeChanged { controller.layoutChanged(Size(it.width.toFloat(), it.height.toFloat()), colors.isDark) }) {
        // The strip the switcher's pan owns at rest, and everything while it's up, are kept from
        // the drawer's pan (iOS makes the drawer's pan wait for this one).
        val mode = controller.mode
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SessionSwitcherTunables.gestureHeight)
                .padding(horizontal = BackSwipeRules.edgeWidth)
                .drawerPanSwitcherStrip(isEnabled = mode == SessionSwitcherController.Mode.idle && !touchExploration && !controller.keyboardUp()),
        )
        Box(Modifier.fillMaxSize().excludedFromDrawerPan(isEnabled = mode != SessionSwitcherController.Mode.idle))

        // The stage: cards, newer above older.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (controller.stageHidden) 0f else controller.stageAlpha }
                .clearAndSetSemantics { },
        ) {
            for (card in controller.cards.values) {
                key(card.id) { SessionSwitcherCardView(card, controller) }
            }
        }
        // Captions above their cards.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (controller.captionsHidden) 0f else controller.captionsAlpha }
                .clearAndSetSemantics { },
        ) {
            for (caption in controller.captions.values) {
                key(caption.card.id) { SessionSwitcherCaptionView(caption) }
            }
        }
        SessionSwitcherPager(controller)
        SessionSwitcherAccessibilityStack(controller)
        SessionSwitcherWarmPage(controller)
    }
}

/** iOS `SessionSwitcherCardView`: a captured or cold page sized to the screen and placed by one scale transform. */
@Composable
private fun SessionSwitcherCardView(card: SessionSwitcherController.CardState, controller: SessionSwitcherController) {
    val colors = OpenCodeTheme.colors
    val dark = colors.isDark
    val density = LocalDensity.current
    val cornerPx = with(density) { SessionSwitcherTunables.cornerRadius.toPx() }
    val shadowPx = with(density) { 24.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(card.z.toFloat())
            .graphicsLayer {
                val pose = card.pose
                translationX = pose.center.x - size.width / 2
                translationY = pose.center.y - size.height / 2
                scaleX = pose.scale
                scaleY = pose.scale
                alpha = if (card.hidden) 0f else card.alpha
                val lift = pose.lift.coerceIn(0f, 1f)
                // In layer units: the scale shrinks the screen's own corner radius with the card.
                shape = RoundedCornerShape(cornerPx * lift)
                clip = true
                // The shadow only shows once the card has its corners.
                val opacity = (SessionSwitcherTunables.shadow * lift * (if (dark) 1.6f else 1f)).coerceIn(0f, 1f)
                shadowElevation = shadowPx * lift
                spotShadowColor = Color.Black.copy(alpha = min(1f, opacity * 2.5f))
                ambientShadowColor = Color.Black.copy(alpha = min(1f, opacity * 1.5f))
            }
            .background(colors.canvas)
            .drawWithContent {
                drawContent()
                val pose = card.pose
                if (pose.dim > 0f) drawRect(Color.Black, alpha = pose.dim)
                val lift = pose.lift.coerceIn(0f, 1f)
                if (lift > 0.01f) {
                    val width = 2f / max(pose.scale, 0.1f)
                    val color = if (dark) Color.White.copy(alpha = 0.14f * lift) else Color.Black.copy(alpha = 0.08f * lift)
                    drawRoundRect(
                        color,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius(cornerPx * lift),
                        style = Stroke(width),
                    )
                }
            },
    ) {
        when (val content = card.content) {
            is SessionSwitcherController.Content.Capture -> Image(content.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            is SessionSwitcherController.Content.Render -> Image(content.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            SessionSwitcherController.Content.Live -> Spacer(
                Modifier.fillMaxSize().drawWithContent { controller.liveLayer?.let { drawLayer(it) } },
            )
            is SessionSwitcherController.Content.Placeholder -> SessionSwitcherPlaceholder(content.title, controller.safeTop)
        }
    }
}

/** iOS `SessionSwitcherPlaceholderView`: a session not drawn yet, its title where the session's header shows it. */
@Composable
private fun SessionSwitcherPlaceholder(title: String, safeTopPx: Float) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Text(
            title,
            style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth * 0.6f).roundToInt(), minHeight = 0))
                val centreY = max(safeTopPx, 20 * density.density) + 26 * density.density
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((constraints.maxWidth - placeable.width) / 2, (centreY - placeable.height / 2f).roundToInt())
                }
            },
        )
    }
}

/** iOS `SessionSwitcherCaptionView`: a card's title above its top-left corner, fading out where it runs past its card or under a newer one. */
@Composable
private fun SessionSwitcherCaptionView(caption: SessionSwitcherController.CaptionState) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val d = density.density
    val tint = when (caption.card.indicator) {
        HomeFeature.State.Indicator.attention -> if (colors.isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
        HomeFeature.State.Indicator.running, HomeFeature.State.Indicator.unread -> colors.accent
        HomeFeature.State.Indicator.none -> null
    }
    Box(
        Modifier
            .layout { measurable, constraints ->
                // The title at its natural width (measured here, not every placement on iOS),
                // placed 4 dp in from its card's left edge and 8 dp above it.
                val placeable = measurable.measure(Constraints())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val frame = caption.frame
                    placeable.place(IntOffset((frame.left + 4 * d).roundToInt(), (frame.top - placeable.height - 8 * d).roundToInt()))
                }
            }
            .graphicsLayer {
                alpha = if (caption.hidden) 0f else caption.alpha
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // iOS frame width: the dot's inset plus the text, never wider than the card.
                val width = max(1f, min(caption.frame.width, size.width))
                val end = min(1f, max(0.01f, (caption.visibleWidth - 4 * d) / width))
                val ramp = min(end, 24 * d / width)
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black, (end - ramp) to Color.Black, end to Color.Transparent, startX = 0f, endX = width),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tint != null) {
                Canvas(Modifier.padding(end = 5.dp).size(7.dp)) { drawCircle(tint) }
            }
            Text(
                caption.card.title,
                style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** iOS `pager` (`UIPageControl`, minimal, hidden for one page): the row's dots under the swiping card. */
@Composable
private fun SessionSwitcherPager(controller: SessionSwitcherController) {
    val colors = OpenCodeTheme.colors
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = controller.pagerAlpha }
            .clearAndSetSemantics { },
    ) {
        val count = controller.pagerCount
        if (count <= 1 || controller.pagerAlpha <= 0f) return@Canvas
        val pitch = SessionSwitcherController.PAGER_PITCH.toPx()
        val radius = SessionSwitcherController.PAGER_DOT.toPx() / 2
        val total = pitch * count
        // Only as many dots as fit, like UIPageControl, centred on the current page.
        val maxDots = max(1, ((size.width - 32.dp.toPx()) / pitch).toInt())
        val shown = min(count, maxDots)
        val first = (controller.pagerPage - shown / 2).coerceIn(0, count - shown)
        val startX = (size.width - min(total, pitch * shown)) / 2 + pitch / 2
        val y = controller.pagerCenterY
        for (i in 0 until shown) {
            val page = first + i
            drawCircle(
                if (page == controller.pagerPage) colors.text else colors.text.copy(alpha = 0.3f),
                radius = radius,
                center = Offset(startX + i * pitch, y),
            )
        }
    }
}

/**
 * The stack for TalkBack (iOS `SessionSwitcherStageView` as an accessibility container): one
 * element per visible card, newest first, framed to the part a newer card doesn't cover;
 * paging moves the stack one card and Back returns to the session you came from.
 */
@Composable
private fun SessionSwitcherAccessibilityStack(controller: SessionSwitcherController) {
    val elements = controller.accessibilityElements
    Box(
        Modifier
            .fillMaxSize()
            .testTag("session-switcher")
            .semantics {
                isTraversalGroup = true
                if (elements.isNotEmpty()) {
                    pageLeft { controller.accessibilityScroll(1) }
                    pageRight { controller.accessibilityScroll(-1) }
                }
            },
    ) {
        for (element in elements) {
            key(element.id) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val frame = element.frame
                            val w = frame.width.roundToInt().coerceAtLeast(1)
                            val h = frame.height.roundToInt().coerceAtLeast(1)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(frame.left.roundToInt(), frame.top.roundToInt())
                            }
                        }
                        .semantics {
                            contentDescription = element.label
                            role = Role.Button
                            selected = element.selected
                            traversalIndex = element.order.toFloat()
                            testTag = "session-switcher-card-${element.id}"
                            onClick { controller.activate(element.id) }
                        },
                )
            }
        }
    }
}

/**
 * Draws one cold page offscreen (iOS `SessionSwitcherCard.render`, `ImageRenderer`): composed
 * at screen size, recorded into a layer that is never drawn, then turned into an image once it
 * has drawn. Never mid-gesture: the controller asks only while at rest.
 */
@Composable
private fun SessionSwitcherWarmPage(controller: SessionSwitcherController) {
    val warming = controller.warming ?: return
    val layer = rememberGraphicsLayer()
    controller.warmLayer = layer
    val density = LocalDensity.current
    val (card, items) = warming
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
    ) {
        SessionSwitcherCachedPage(
            card = card,
            items = items,
            topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() },
            bottomInset = with(density) { WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(density).toDp() },
        )
    }
    LaunchedEffect(card.id) {
        // Composed this frame, recorded when it draws: wait for that frame, and one more.
        withFrameNanos { }
        withFrameNanos { }
        controller.warmDrawn(card.id)
    }
}
