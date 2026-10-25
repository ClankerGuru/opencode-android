package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Tunables for the Call Tray docking in the drawer (OC-473, D-114 option 2), baked at the iOS defaults. */
object CallTrayDockTunables {
    /** `call.tray.dock.enabled` */ const val enabled = true

    /** `call.tray.dock.gap`: between New Session and Settings and the tray beneath them. */
    const val gap = 8f

    /** `call.tray.dock.widthLead`: how much sooner the tray's width reaches the drawer's than its place does (1 together). */
    const val widthLead = 1.3f

    /** `call.tray.dock.originEase`: how long the tray's start eases down when opening the drawer puts the keyboard away. */
    const val originEase = 0.35
}

/**
 * The drawer as the tray sees it, in window space (dp).
 *
 * @property slot the tray's slot beneath New Session and Settings, at the drawer's open size.
 * @property bounds the drawer, full height.
 * @property recedes the drawer recedes behind the card as it closes (`DrawerDepth`).
 * @property screen the whole window.
 */
data class CallTrayDockGeometry(val slot: Rect, val bounds: Rect, val recedes: Boolean, val screen: Rect = Rect.Zero)

/**
 * The Call Tray docking in the drawer (OC-473, D-114 option 2): during a call, opening the
 * drawer carries the tray, frame by frame with the drawer, out of the session card and into
 * the drawer's bottom slot beneath New Session and Settings, which stand above it; closing
 * brings it back. While it travels or rests in the drawer, Home draws it
 * ([CallTrayDockOverlay]) and the session's tray steps aside; with the drawer shut the
 * session's tray is exactly as before.
 *
 * A plain object owned by [CallTrayController.dock]; observed fields are snapshot state.
 * Frames are window-space dp. UIKit's presentation-layer reads become Compose's own layout:
 * the session's tray anchor ([CallTraySessionAnchor]) reports its laid-out frame every frame
 * the keyboard (`WindowInsets.ime`) moves it, so the return tail compares that frame between
 * frames instead of presentation against layout.
 */
class CallTrayDock {
    /** How far the drawer is open (0...1), each frame it moves. Only the travelling tray reads it. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** The tray may dock: the Call Tray is on, rests open beneath the composer, and the phone's drawer slides (no sidebar). */
    @set:JvmName("assignArmed")
    var isArmed by mutableStateOf(false)
        private set

    /** Home draws the tray for this visit to the drawer; the session's steps aside. */
    @set:JvmName("assignTravelling")
    var isTravelling by mutableStateOf(false)
        private set

    /**
     * The session's tray steps aside: from a trip's start until the drawer is shut again, so a
     * hang-up in the drawer shrinks away there alone rather than the session's tray
     * reappearing behind it.
     */
    var hidesSessionTray by mutableStateOf(false)
        private set

    /** New Session and Settings stand above the tray's slot. Set while the drawer is shut, so the drawer's layout never moves under the finger. */
    var liftsDrawerControls by mutableStateOf(false)
        private set

    /** Where the tray rests in the session, in window space with the card shut. */
    var sessionTrayFrame by mutableStateOf(Rect.Zero)
        private set

    /** The drawer's bottom slot in window space, at the drawer's open size. */
    var drawerSlot by mutableStateOf(Rect.Zero)
        private set

    /** The screen: a session's tray seen off it (a subagent sliding away on Back) is no place to start a trip from. */
    private var screenBounds = Rect.Zero

    /**
     * The tray's start eases from here when the session's tray moves while the drawer is open
     * (the keyboard going away). Observed, so the travelling tray keeps following it while the
     * finger rests mid-drag.
     */
    var originEase by mutableStateOf<OriginEase?>(null)
        private set

    /** The session anchor's actual frame this frame (window space), while it is mounted. */
    private var sessionAnchorFrame by mutableStateOf<Rect?>(null)
    private var lastPresentedTop: Float? = null
    private var returnScope: CoroutineScope? = null
    private var returnLink: Job? = null
    private var settledReturnFrames = 0

    // Phone diagnostics: counters only, no per-frame log or additional clock.
    private var trip = 0
    private var samples = 0
    private var ownerlessSamples = 0
    private var doubledOwners = 0
    private var rejectedFrames = 0
    private var acceptedFrames = 0
    private var direction = 0
    private var reversals = 0
    private var travellerMounts = 0
    private var context = TraceContext()

    data class TraceContext(val hasVoice: Boolean = false, val live: Boolean = false, val focused: Boolean = false)

    data class OriginEase(val from: Rect, val start: Double)

    /** A frame clock for the return tail (the host's `rememberCoroutineScope()`). */
    fun attach(scope: CoroutineScope) {
        returnScope = scope
    }

    /**
     * The drawer moved to [progress] (0 shut, 1 open). [travels] is false when the drawer
     * appears or goes without moving (Catch Up taking the screen or handing it back): no trip then.
     */
    fun drawerMoved(progress: Float, travels: Boolean = true) {
        val p = progress.coerceIn(0f, 1f)
        if (p == this.progress) return
        val wasShut = this.progress <= 0
        val nextDirection = if (p > this.progress) 1 else -1
        if (wasShut) {
            trip += 1
            samples = 0
            ownerlessSamples = 0
            doubledOwners = 0
            rejectedFrames = 0
            acceptedFrames = 0
            reversals = 0
            direction = nextDirection
        }
        val reversed = nextDirection != direction
        direction = nextDirection
        if (reversed) reversals += 1
        this.progress = p
        if (p <= 0) {
            originEase = null
            if (isTravelling && sessionAnchorFrame != null) {
                // The drawer can settle before the keyboard. Keep the same visible traveller
                // until the session's tray has stopped moving.
                settledReturnFrames = 0
                lastPresentedTop = null
                val scope = returnScope?.takeIf { it.isActive }
                if (scope == null) {
                    finishReturn()
                } else if (returnLink?.isActive != true) {
                    returnLink = scope.launch {
                        motionFrameLoop { if (finishReturnIfSettled()) MotionFrameAction.Stop else MotionFrameAction.Continue }
                    }
                }
            } else {
                finishReturn()
            }
        } else if (wasShut && travels) {
            returnLink?.cancel()
            returnLink = null
            // A trip starts only from a shut drawer, where the session's tray is exactly where
            // the travelling one begins.
            setTravelling(isArmed && sessionTrayFrame.width > 1 && drawerSlot.width > 1)
            if (isTravelling) hidesSessionTray = true
        }
        refreshLift()
        samples += 1
        if (hidesSessionTray && !isTravelling) ownerlessSamples += 1
        if (!hidesSessionTray && isTravelling) doubledOwners += 1
        when {
            wasShut -> traceHandoff("start")
            p <= 0 -> traceHandoff("shut")
            p >= 1 -> traceHandoff("open")
            reversed && reversals <= 12 -> traceHandoff("reverse")
        }
    }

    fun setArmed(armed: Boolean) {
        if (armed == isArmed) return
        isArmed = armed
        if (!armed) {
            returnLink?.cancel()
            returnLink = null
            if (progress <= 0) finishReturn() else setTravelling(false)
        }
        refreshLift()
        traceHandoff("armed")
    }

    fun setDrawer(drawer: CallTrayDockGeometry) {
        screenBounds = drawer.screen
        if (drawer.slot != drawerSlot) drawerSlot = drawer.slot
    }

    /**
     * The session's tray laid out at [frame]. With the card moving aside only its height can
     * change (the keyboard going down), so its sideways place is kept from when the card was
     * shut, and the tray's start eases there.
     */
    fun noteSessionTray(frame: Rect) {
        sessionAnchorFrame = frame
        val screen = screenBounds
        val inside = screen.width <= 0f ||
            (frame.left >= screen.left - 0.5f && frame.top >= screen.top - 0.5f && frame.right <= screen.right + 0.5f && frame.bottom <= screen.bottom + 0.5f)
        if (frame.width <= 1 || frame.height <= 1 || !inside) {
            rejectedFrames += 1
            return
        }
        var next = frame
        if (progress > 0 && sessionTrayFrame.width > 1) {
            next = Rect(sessionTrayFrame.left, frame.top, sessionTrayFrame.left + sessionTrayFrame.width, frame.bottom)
        }
        val current = sessionTrayFrame
        if (abs(next.left - current.left) <= 0.25f && abs(next.top - current.top) <= 0.25f &&
            abs(next.width - current.width) <= 0.25f && abs(next.height - current.height) <= 0.25f
        ) return
        if (isTravelling && current.width > 1) {
            val now = nowSeconds()
            originEase = OriginEase(from = origin(now), start = now)
        }
        sessionTrayFrame = next
        acceptedFrames += 1
    }

    /** The session anchor left composition. */
    fun noteSessionAnchorRemoved() {
        sessionAnchorFrame = null
    }

    /** The tray's start is still easing to the session's tray at [now]. */
    fun isEasingOrigin(now: Double): Boolean {
        val ease = originEase ?: return false
        return now - ease.start < originEaseDuration
    }

    /** Where the travelling tray is at [now], in window space. */
    fun frame(now: Double): Rect {
        val presented = presentedSessionFrame
        val start = presented ?: origin(now)
        // This is a Home overlay, not a child of the receding drawer. Blending toward an
        // endpoint that itself moves with progress makes a curved, reversing path even when
        // both resting vertical anchors are identical.
        var frame = travelFrame(origin = start, slot = drawerSlot, progress = progress, widthLead = CallTrayDockTunables.widthLead)
        if (presented != null && progress < 1) {
            // The keyboard's lift is not multiplied by the drawer's progress. The whole tray
            // rides with the session anchor as soon as the keys rise; blending toward the
            // bottom slot would leave it behind the keyboard.
            val top = min(frame.top, min(start.top, drawerSlot.top))
            frame = Rect(frame.left, top, frame.right, top + frame.height)
        }
        return frame
    }

    private val presentedSessionFrame: Rect?
        get() {
            val anchor = sessionAnchorFrame ?: return null
            if (anchor.width <= 1 || anchor.height <= 1 || !anchor.top.isFinite()) return null
            // The card translates sideways. Only its vertical place belongs to the return
            // destination; the traveller owns the horizontal interpolation.
            return Rect(sessionTrayFrame.left, anchor.top, sessionTrayFrame.left + sessionTrayFrame.width, anchor.top + anchor.height)
        }

    /** Called only during the short return tail, never while the tray rests. */
    fun finishReturnIfSettled(): Boolean {
        if (progress > 0) return true
        val presented = presentedSessionFrame
        if (presented != null) {
            val previousTop = lastPresentedTop
            lastPresentedTop = presented.top
            // A host briefly moved past the bottom edge (keys interrupted while showing) is not
            // a return target; nor is one still moving with the keyboard.
            if (presented.bottom > drawerSlot.bottom + 0.5f ||
                (previousTop != null && abs(previousTop - presented.top) > 0.5f) ||
                abs(frame(nowSeconds()).top - presented.top) > 0.5f
            ) {
                settledReturnFrames = 0
                return false
            }
        }
        settledReturnFrames += 1
        if (settledReturnFrames < 2) return false
        finishReturn()
        return true
    }

    private fun finishReturn() {
        returnLink?.cancel()
        returnLink = null
        setTravelling(false)
        if (hidesSessionTray) hidesSessionTray = false
        refreshLift()
        traceHandoff("returned")
    }

    private val originEaseDuration: Double get() = max(CallTrayDockTunables.originEase, 0.01)

    private fun origin(now: Double): Rect {
        val ease = originEase ?: return sessionTrayFrame
        val t = ((now - ease.start) / originEaseDuration).coerceIn(0.0, 1.0)
        if (t >= 1) return sessionTrayFrame
        // Ease out, like the keyboard.
        val k = (1 - (1 - t).pow(3)).toFloat()
        return CallTrayController.lerpRect(ease.from, sessionTrayFrame, k)
    }

    private fun setTravelling(travelling: Boolean) {
        if (travelling == isTravelling) return
        isTravelling = travelling
    }

    private fun refreshLift() {
        val lifts = isTravelling || (isArmed && progress <= 0)
        if (lifts != liftsDrawerControls) liftsDrawerControls = lifts
    }

    fun noteTraceContext(next: TraceContext) {
        if (context == next) return
        context = next
        traceHandoff("context")
    }

    fun noteTravellerMounted(mounted: Boolean) {
        travellerMounts += if (mounted) 1 else -1
        traceHandoff(if (mounted) "mount" else "unmount")
    }

    private fun traceHandoff(event: String) {
        if (!(context.hasVoice || isArmed || hidesSessionTray || travellerMounts > 0 || event == "unmount")) return
        AppLog.voice.notice {
            "tray_handoff event=$event trip=$trip p1000=${(progress * 1000).toInt()} armed=$isArmed travelling=$isTravelling " +
                "hidden=$hidesSessionTray voice=${context.hasVoice} live=${context.live} focused=${context.focused} " +
                "mounts=$travellerMounts samples=$samples ownerless=$ownerlessSamples doubled=$doubledOwners " +
                "accepted=$acceptedFrames rejected=$rejectedFrames reversals=$reversals " +
                "originValid=${sessionTrayFrame.width > 1} slotValid=${drawerSlot.width > 1}"
        }
    }

    companion object {
        /**
         * The tray may dock in the drawer: the Call Tray is on, rests open, and the phone's
         * drawer slides over a session (no sidebar, no Catch Up).
         */
        fun mayDock(usesTray: Boolean, usesSidebar: Boolean, isCatchingUp: Boolean, isResting: Boolean): Boolean =
            usesTray && !usesSidebar && !isCatchingUp && isResting && CallTrayDockTunables.enabled

        /**
         * The session's place [progress] of the way to the drawer's slot. Its width gets there a
         * little sooner ([widthLead]), so the tray narrows into the drawer rather than sliding
         * in at the session's width; both edges still travel one way the whole trip.
         */
        fun travelFrame(origin: Rect, slot: Rect, progress: Float, widthLead: Float): Rect {
            val p = progress.coerceIn(0f, 1f)
            val wp = 1 - (1 - p).pow(max(widthLead, 1f))
            val width = CallTrayController.lerp(origin.width, slot.width, wp)
            val minX = CallTrayController.lerp(origin.left, slot.left, p)
            val minY = CallTrayController.lerp(origin.top, slot.top, p)
            val height = CallTrayController.lerp(origin.height, slot.height, p)
            return Rect(minX, minY, minX + width, minY + height)
        }

        internal fun nowSeconds(): Double = System.nanoTime() / 1e9
    }
}

/**
 * The travelling tray, over Home (iOS `CallTrayDockOverlay`). Only it reads the drawer's
 * progress, so nothing else redraws each frame.
 *
 * Drawer hosts it full-window over Home (it ignores insets itself) and feeds it:
 * - [drawerProgress]: how far the drawer is open (0...1), read inside a snapshot (a drawer
 *   `Animatable`/state), forwarded to [CallTrayDock.drawerMoved] as it changes (iOS
 *   `SessionDrawerContainer.onDrawerProgress`);
 * - [drawerTravels]: false while the drawer appears or goes without moving (Catch Up taking
 *   the screen or handing it back);
 * - [mayDock]: [CallTrayDock.mayDock] for this screen (tray on, no sidebar, not catching up,
 *   [CallTrayController.isResting]);
 * - [drawer]: the drawer's slot geometry ([CallTrayDockSlot]'s window frame at the drawer's
 *   open size, the drawer and the window), or `null` until measured.
 *
 * @param store the call, or `null` with none.
 */
@Composable
fun CallTrayDockOverlay(
    controller: CallTrayController,
    store: StoreOf<VoiceFeature.State, VoiceFeature.Action>?,
    reducedMotion: Boolean,
    drawerProgress: () -> Float,
    modifier: Modifier = Modifier,
    drawerTravels: () -> Boolean = { true },
    mayDock: Boolean = false,
    drawer: CallTrayDockGeometry? = null,
) {
    val dock = controller.dock
    val scope = rememberCoroutineScope()
    val progress by rememberUpdatedState(drawerProgress)
    val travels by rememberUpdatedState(drawerTravels)
    DisposableEffect(dock, scope) {
        dock.attach(scope)
        onDispose {}
    }
    LaunchedEffect(dock) {
        snapshotFlow { progress() to travels() }.collect { (p, t) -> dock.drawerMoved(p, travels = t) }
    }
    SideEffect {
        if (drawer != null) dock.setDrawer(drawer)
        dock.setArmed(mayDock)
        dock.noteTraceContext(
            CallTrayDock.TraceContext(hasVoice = store != null, live = controller.phase == CallTrayController.Phase.live, focused = controller.isComposerFocused),
        )
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() / density },
    ) {
        val visible = (dock.isArmed || dock.isTravelling) && store != null
        // Back in the session (the drawer shut) the session's tray takes over in place, on the
        // same frame. Hanging up in the drawer shrinks it away into End.
        val exit: ExitTransition = if (dock.progress <= 0) ExitTransition.None
        else fadeOut(if (reducedMotion) Motion.easeOut(Motion.reducedFade) else CallTrayTunables.close.spec()) +
            scaleOut(if (reducedMotion) Motion.easeOut(Motion.reducedFade) else CallTrayTunables.close.spec(), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0.5f))
        AnimatedVisibility(visible = visible, enter = EnterTransition.None, exit = exit) {
            // Mounted, unseen, while it may dock, so its scope is drawing the frame it takes over.
            if (store != null) {
                CallTrayDockTraveller(controller, store, reducedMotion, origin, overlayHeight = maxHeight)
            }
        }
    }
}

@Composable
private fun CallTrayDockTraveller(
    controller: CallTrayController,
    store: StoreOf<VoiceFeature.State, VoiceFeature.Action>,
    reducedMotion: Boolean,
    origin: Offset,
    overlayHeight: Dp,
) {
    val dock = controller.dock
    DisposableEffect(dock) {
        dock.noteTravellerMounted(true)
        onDispose { dock.noteTravellerMounted(false) }
    }
    // Follow a moving session anchor's ease while the finger rests mid-drag. Resting open
    // needs only the keyboard insets.
    var tick by remember { mutableLongStateOf(0L) }
    val easing = dock.originEase
    LaunchedEffect(easing, dock.isTravelling) {
        if (easing == null || !dock.isTravelling) return@LaunchedEffect
        motionFrameLoop {
            tick += 1
            if (dock.isEasingOrigin(CallTrayDock.nowSeconds())) MotionFrameAction.Continue else MotionFrameAction.Stop
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val raw = dock.frame(CallTrayDock.nowSeconds()).translate(-origin.x, -origin.y)
    // The traveller stays above the keyboard: its bottom never passes the keys' top edge.
    val keyboardTop = overlayHeight.value - imeBottom.value
    val top = if (imeBottom.value > 0f) min(raw.top, keyboardTop - raw.height) else raw.top
    val frame = Rect(raw.left, top, raw.right, top + raw.height)
    val shows = dock.isTravelling
    val rests = shows && dock.progress > 0.999f
    val shape = RoundedCornerShape(CallTrayTunables.cornerRadius.dp)
    Box(
        Modifier
            .offset { IntOffset((frame.left * density.density).roundToInt(), (frame.top * density.density).roundToInt()) }
            .size(max(1f, frame.width).dp, max(1f, frame.height).dp)
            .alpha(if (shows) 1f else 0f)
            .callSurface(shape, VoiceCallSurfaceDefaults.color)
            .clip(shape)
            // TalkBack reaches the call through the drawer's slot ([CallTrayDockSlot]).
            .clearAndSetSemantics {},
    ) {
        CallTrayBar(
            store = store,
            fullScopeWidth = max(dock.sessionTrayFrame.width, frame.width),
            visibleWidth = frame.width,
            trayHeight = frame.height,
            parts = CallTrayBarDefaults.restingParts,
            scanPending = controller.scanPending,
            canToggleCaptions = false,
            reducedMotion = reducedMotion,
            isInteractive = rests,
            scopeRenderer = controller.scopeRenderer,
            scopePaused = !shows,
        )
    }
}

/**
 * A non-rendering anchor in the session's layout where its tray rests ([frame], stage space
 * dp, inside the stage overlay). It reports its window frame to the dock every frame the
 * keyboard moves it, without another frame loop or any reducer actions.
 */
@Composable
fun CallTraySessionAnchor(dock: CallTrayDock, frame: Rect) {
    val density = LocalDensity.current.density
    DisposableEffect(dock) { onDispose { dock.noteSessionAnchorRemoved() } }
    Box(
        Modifier
            .offset { IntOffset((frame.left * density).roundToInt(), (frame.top * density).roundToInt()) }
            .size(max(0f, frame.width).dp, max(0f, frame.height).dp)
            .semantics { hideFromAccessibility() }
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow() / density
                val size = coordinates.size
                dock.noteSessionTray(Rect(position.x, position.y, position.x + size.width / density, position.y + size.height / density))
            },
    )
}

/**
 * The drawer's bottom slot the tray rests in: it lifts New Session and Settings above it and
 * takes the touches beneath the tray. The travelling tray drawn over Home is hidden from
 * TalkBack, so the slot stands in for it there (“Voice call”, its status, and Mute / End Call
 * actions). Drawer lays it out under New Session and Settings while
 * [CallTrayDock.liftsDrawerControls] and reports its window frame as [CallTrayDockGeometry.slot].
 */
@Composable
fun CallTrayDockSlot(store: StoreOf<VoiceFeature.State, VoiceFeature.Action>?, modifier: Modifier = Modifier) {
    val status = store?.observe { it.statusText }?.value ?: ""
    val isMuted = store?.observe { it.isMuted }?.value == true
    Box(
        modifier
            .padding(top = CallTrayDockTunables.gap.dp)
            .fillMaxWidth()
            .height(CallTrayTunables.height.dp)
            .semantics {
                if (store == null) {
                    hideFromAccessibility()
                } else {
                    contentDescription = "Voice call"
                    stateDescription = status
                    customActions = listOf(
                        CustomAccessibilityAction(if (isMuted) "Unmute" else "Mute") { store.send(VoiceFeature.Action.MuteTapped); true },
                        CustomAccessibilityAction("End Call") { store.send(VoiceFeature.Action.EndPressed); true },
                    )
                }
            },
    )
}
