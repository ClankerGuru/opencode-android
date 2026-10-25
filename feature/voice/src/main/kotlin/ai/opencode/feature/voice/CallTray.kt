package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Plain-object controller owning the Call Tray's staged 120 Hz choreography (`D-104` option 2:
 * Composer Lifts, matching `call-tray/tray.js`):
 * 1. Tapping the composer's call button lifts the button in place (`l: 0 → 1`) while the
 *    composer dips slightly (`composerDip: 3`) and the record (mic) button scales/blurs out
 *    (`m: 0 → 1`).
 * 2. After `liftHold` (0.15 s), the composer lifts off the call button (`a: 0 → 1`, reserving
 *    `height + gap` once on the session layout) while the tray's surface splits from the
 *    composer's bottom edge and the phone glyph spins 495° clockwise into the red End button.
 * 3. After `openStagger` (0.24 s), the tray winds up 5 dp toward End, shoots out 8 dp past full
 *    width with squash-and-stretch, and settles across the row as Mute and the phosphor
 *    [DotScopeView] slide and sharpen in from the right.
 * 4. Hanging up narrows the tray back toward End (`closeStagger: 0.10 s`), then lowers the
 *    composer back over the ball (`a: 1 → 0`, releasing the session reserve once) as the
 *    surface merges and the phone finishes spinning upright into the composer's call button.
 *
 * Android: the springs are [SpringSolver]s stepped by a [motionFrameLoop] (iOS
 * `MotionDisplayLink`) in the coroutine scope the host passes to [attach] (it must carry
 * Compose's frame clock: `rememberCoroutineScope()`). Discrete flags are snapshot state; the
 * springs' frame-by-frame values are read through [geometry], which reads [frameTick], so only
 * readers of the geometry redraw each frame. One controller per session screen, kept across
 * calls (Session owns it, like iOS's `@State`).
 */
class CallTrayController {
    enum class Phase { idle, live, ending }

    private enum class QueuedAction { unfold, widen, sink, scan, reveal, descend }

    private data class ScheduledEvent(val at: Double, val action: QueuedAction)

    private data class ShootState(val t0: Double, val from: Float, val wind: Float, val over: Float)

    data class PartLook(val opacity: Float, val blur: Float, val scale: Float, val offsetX: Float) {
        companion object {
            val resting = PartLook(opacity = 1f, blur = 0f, scale = 1f, offsetX = 0f)
        }
    }

    data class Seed(
        val x: Float,
        val y: Float,
        val diameter: Float,
        val opacity: Float,
        val angle: Float,
        val drift: Float,
        val iconBlur: Float,
        val blur: Float,
        val dissolve: Float,
        val callness: Float,
        val spread: Float,
        val dir: Float,
        /**
         * 0 draws the composer's own `phone.fill`, 1 End's `phone.down.fill`: swapped mid-spin,
         * so the seed starts and lands as the composer's glyph.
         */
        val phoneGlyph: Float = 0f,
    )

    data class ButtonLook(val disc: Color, val discAlpha: Float, val glyph: Color)

    /** In the stage's coordinate space (`width × stageHeight`, dp), `y = stageHeight` at the composer's resting bottom. */
    data class Geometry(
        val reducedMotion: Boolean,
        val l: Float,
        val a: Float,
        val ca: Float,
        val cw: Float,
        val ce: Float,
        val shown: Boolean,
        val showsGlassSurface: Boolean,
        val composerDip: Float,
        val composerOffsetY: Float,
        val sessionReserve: Float,
        val stageHeight: Float,
        val trayRect: Rect,
        val surf: Rect,
        val radius: Float,
        val trayOpacity: Float,
        val dropletMergeSpacing: Float,
        val energize: Float,
        val lifted: Float,
        val seed: Seed,
        val parts: List<PartLook>,
        val mic: PartLook,
        val transcriptHeight: Float,
        val transcriptOpacity: Float,
        val transcriptGap: Float,
    )

    /**
     * True from the moment the user taps the call button until the closing seed finishes
     * landing back in the composer, so the composer's own call button stays hidden while the
     * tray owns the button.
     */
    var holdsCallButton by mutableStateOf(false)

    /** Bumped when the composer's call button is tapped. */
    var tapGeneration by mutableIntStateOf(0)

    var phase by mutableStateOf(Phase.idle)
        private set
    var isTrayShown by mutableStateOf(false)
        private set
    var isTrayExpanded by mutableStateOf(false)
        private set

    /** Whether the last [isTrayExpanded] flip animates (the reserve spring) or snaps (Reduce Motion, settle). */
    var trayExpansionAnimates by mutableStateOf(false)
        private set
    var isMicFolded by mutableStateOf(false)
        private set
    var scopeMounted by mutableStateOf(false)
        private set
    var scanPending by mutableStateOf(false)
        private set
    var transcriptLingering by mutableStateOf(false)
        private set

    /** [restsOpen], published only when it flips, so screens can follow it without observing the springs every frame. */
    var isResting by mutableStateOf(false)
        private set

    /** Bumped every step: the springs' values are read through it, so readers redraw per frame. */
    var frameTick by mutableLongStateOf(0L)
        private set

    /** Simulation clock in virtual seconds, advanced by the frame loop. */
    var time: Double = 0.0
        private set
    private var touchedAt: Double = -10.0
    private val queue = mutableListOf<ScheduledEvent>()
    private var shoot: ShootState? = null
    private var travelSpan = 294f

    // Springs matching tray.js (`l`, `a`, `w`, `ang`, `c`, `m`, `e`, `k`, `v`, `q`)
    val l = spring(0.24, 0.0005, 0.005)
    val a = spring(0.50, 0.0005, 0.005)
    val w = spring(0.50, 0.0005, 0.005)
    val ang = spring(0.70, 0.08, 0.8)
    val c = spring(0.70, 0.0005, 0.005)
    val m = spring(0.24, 0.0005, 0.005)
    val e = spring(0.40, 0.0005, 0.005)
    val k = spring(0.50, 0.0005, 0.005)
    val v = spring(0.32, 0.0005, 0.005)
    val q = spring(0.12, 0.01, 0.1)

    /** Measured composer frame in window space (dp, without the tray's dip offset). */
    var baseComposerFrame by mutableStateOf(Rect(20f, 754f, 370f, 810f))
        private set
    var restingComposerBottom by mutableFloatStateOf(810f)
        private set
    var isComposerFocused by mutableStateOf(false)
        private set

    var reducedMotion: Boolean = false
        private set

    private var loopScope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var wantsLoop = false

    /** Where the tray goes while the drawer is open ([CallTrayDock]). */
    val dock = CallTrayDock()
    private var callScopeRenderer: DotScopeRenderer? = null

    /**
     * This call's scope renderer, shared by the tray in the session and in the drawer so its
     * trails carry across the hand-off. One per call.
     */
    val scopeRenderer: DotScopeRenderer?
        get() {
            if (callScopeRenderer == null) callScopeRenderer = DotScopeRenderer.make()
            return callScopeRenderer
        }

    /**
     * The tray rests open beneath the composer: live, fully widened, its transcript shut, and
     * nothing still moving. Only then may it travel into the drawer.
     */
    val restsOpen: Boolean
        get() {
            fun near(spring: SpringSolver, value: Double) = abs(spring.value - value) < 0.01 && abs(spring.target - value) < 0.001
            // `l` is the composer's lift off the tray, held up for the whole call: settled is enough.
            return phase == Phase.live && isTrayShown && near(a, 1.0) && near(w, 1.0) && near(k, 1.0) && near(e, 0.0) &&
                abs(l.value - l.target) < 0.01
        }

    /** Gives the controller a frame clock: call from the hosting composable (`rememberCoroutineScope()`). */
    fun attach(scope: CoroutineScope) {
        loopScope = scope
        if (wantsLoop) ensureDisplayLink()
    }

    /** The host left composition: its frame loop stops with its scope. */
    fun detach(scope: CoroutineScope) {
        if (loopScope !== scope) return
        loopJob?.cancel()
        loopJob = null
        loopScope = null
    }

    private fun setTrayExpanded(expanded: Boolean, animated: Boolean) {
        if (isTrayExpanded == expanded) return
        trayExpansionAnimates = animated && !reducedMotion
        isTrayExpanded = expanded
    }

    /** Whether the tray stage overlay should be rendered. */
    val showsSurface: Boolean
        get() = phase != Phase.idle || holdsCallButton || isTrayShown || a.value > 0.001 || l.value > 0.001

    /**
     * Discrete vertical space reserved in the session layout: flips once when the tray unfolds
     * ([isTrayExpanded] true) and once when it descends, never per frame.
     */
    val discreteSessionReserve: Float
        get() {
            if (!isTrayExpanded) return 0f
            if (CallTrayTunables.below == CallTrayBelowMode.home && !isComposerFocused) return 0f
            return CallTrayTunables.height + CallTrayTunables.gap
        }

    fun composerDip(reducedMotion: Boolean? = null): Float {
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val r = reducedMotion ?: this.reducedMotion
        val mode = belowMode
        if (r || mode == null || mode == CallTrayBelowMode.home) return 0f
        val lVal = clamp(l.value.toFloat(), 0f, 1.5f)
        val ca = clamp(a.value.toFloat(), 0f, 1f)
        return CallTrayTunables.composerDip * lVal * (1 - ca)
    }

    /** The composer's laid-out frame (window space, dp) and focus, as the host measures it. */
    fun noteComposer(frame: Rect, isFocused: Boolean) {
        if (frame.width <= 80 || frame.height <= 20) return
        val rawFrame = frame.translate(0f, -composerDip())
        if (baseComposerFrame != rawFrame) baseComposerFrame = rawFrame
        if (isComposerFocused != isFocused) isComposerFocused = isFocused
        if (!isFocused) {
            val bottom = rawFrame.bottom + discreteSessionReserve
            if (abs(restingComposerBottom - bottom) > 0.5f) restingComposerBottom = bottom
        }
    }

    /** Called immediately when the user taps the composer's call button so the button lifts on the very first frame. */
    fun beginFromTap(reducedMotion: Boolean = false) {
        holdsCallButton = true
        start(fromTap = true, alreadyConnected = false, reducedMotion = reducedMotion)
        tapGeneration += 1
    }

    fun reset() {
        loopJob?.cancel()
        loopJob = null
        wantsLoop = false
        queue.clear()
        shoot = null
        callScopeRenderer = null
        phase = Phase.idle
        holdsCallButton = false
        isResting = false
        isTrayShown = false
        trayExpansionAnimates = false
        isTrayExpanded = false
        isMicFolded = false
        scopeMounted = false
        scanPending = false
        transcriptLingering = false
        for (spring in listOf(l, a, w, ang, c, m, e, k, v, q)) snap(spring, 0.0)
        frameTick += 1
    }

    /** Drives the tray's start, end, and reset from the session's voice lifecycle. */
    fun reconcile(voicePhase: VoiceFeature.State.Phase?, reducedMotion: Boolean = false, isSceneActive: Boolean = true) {
        val effectiveReducedMotion = reducedMotion || !isSceneActive
        if (voicePhase != null && !voicePhase.isTerminal && !voicePhase.showsCard) {
            if (phase != Phase.live || !isTrayShown) {
                start(fromTap = false, alreadyConnected = voicePhase == VoiceFeature.State.Phase.Live, reducedMotion = effectiveReducedMotion)
            }
            return
        }
        if (!isSceneActive) {
            if (phase != Phase.idle || holdsCallButton || isTrayShown || isTrayExpanded) reset()
            return
        }
        if (voicePhase == VoiceFeature.State.Phase.Ended && phase == Phase.live && (a.value > 0.05 || w.value > 0.05 || isTrayExpanded)) {
            end(reducedMotion = effectiveReducedMotion)
            return
        }
        if (!effectiveReducedMotion && voicePhase == null && phase == Phase.ending && isAnimating) return
        if (phase != Phase.idle || holdsCallButton || isTrayShown || isTrayExpanded) reset()
    }

    fun start(fromTap: Boolean, alreadyConnected: Boolean, reducedMotion: Boolean) {
        this.reducedMotion = reducedMotion
        if (phase == Phase.live) return
        drop(QueuedAction.unfold, QueuedAction.widen, QueuedAction.sink, QueuedAction.scan, QueuedAction.reveal, QueuedAction.descend)
        shoot = null
        val resume = a.value > 0.05 || w.value > 0.05 || alreadyConnected
        phase = Phase.live
        holdsCallButton = true
        isTrayShown = true
        scopeMounted = true
        touchedAt = time
        scanPending = !resume &&
            CallTrayTunables.scanStart == CallTrayScanStart.widen &&
            CallTrayTunables.morph == CallTrayMorph.staged &&
            !reducedMotion

        if (reducedMotion) {
            setTrayExpanded(true, animated = false)
            isMicFolded = true
            val fade = Motion.reducedFade
            for (spring in listOf(a, w, m, k, v, c)) spring.retarget(1.0, duration = fade, bounce = 0.0)
            l.retarget(0.0, duration = fade, bounce = 0.0)
            ensureDisplayLink()
            return
        }

        m.retarget(1.0, duration = CallTrayTunables.lift.duration, bounce = 0.0)
        v.retarget(1.0, duration = max(0.08, CallTrayTunables.discDissolve), bounce = 0.0)

        if (resume || CallTrayTunables.morph != CallTrayMorph.staged) {
            setTrayExpanded(true, animated = true)
            val openDur = CallTrayTunables.open.duration
            val openBounce = CallTrayTunables.open.bounce
            a.retarget(1.0, duration = openDur, bounce = openBounce)
            w.retarget(1.0, duration = openDur, bounce = openBounce)
            k.retarget(1.0, duration = openDur, bounce = if (resume) openBounce else 0.0)
            l.retarget(0.0, duration = openDur, bounce = openBounce)
            c.retarget(1.0, duration = CallTrayTunables.spinDuration, bounce = 0.0)
            spinOpen(fresh = false)
            ensureDisplayLink()
            return
        }

        l.retarget(1.0, CallTrayTunables.lift)
        later(CallTrayTunables.liftHold, QueuedAction.unfold)
        ensureDisplayLink()
    }

    fun end(reducedMotion: Boolean) {
        this.reducedMotion = reducedMotion
        if (!(phase == Phase.live || holdsCallButton || isTrayShown)) return
        drop(QueuedAction.unfold, QueuedAction.widen, QueuedAction.sink, QueuedAction.scan, QueuedAction.reveal)
        phase = Phase.ending
        // Not on the next step: a tray docked in the drawer hands back now.
        if (isResting) isResting = false
        isMicFolded = false
        touchedAt = time
        scanPending = false
        shoot = null

        if (e.target > 0) {
            if (reducedMotion) e.retarget(0.0, duration = Motion.reducedFade, bounce = 0.0)
            else e.retarget(0.0, CallTrayTunables.transcriptCollapse)
        }

        if (reducedMotion) {
            setTrayExpanded(false, animated = false)
            val fade = Motion.reducedFade
            for (spring in listOf(a, w, l, m, k, v)) spring.retarget(0.0, duration = fade, bounce = 0.0)
            snap(c, 0.0)
            ensureDisplayLink()
            return
        }

        val closeDur = CallTrayTunables.close.duration
        val closeBounce = CallTrayTunables.close.bounce
        k.retarget(0.0, duration = closeDur, bounce = 0.0)
        if (belowMode == CallTrayBelowMode.slide) {
            setTrayExpanded(false, animated = true)
            a.retarget(0.0, duration = closeDur, bounce = closeBounce)
        } else {
            w.retarget(0.0, duration = closeDur, bounce = closeBounce)
            l.retarget(0.0, duration = closeDur, bounce = closeBounce)
        }
        later(if (CallTrayTunables.openStagger > 0) CallTrayTunables.closeStagger else 0.0, QueuedAction.descend)
        ensureDisplayLink()
    }

    fun setTranscript(expanded: Boolean, reducedMotion: Boolean, onExpand: () -> Unit = {}) {
        this.reducedMotion = reducedMotion
        if (expanded) {
            if (phase != Phase.live) return
            onExpand()
            transcriptLingering = true
        }
        val target = if (expanded) 1.0 else 0.0
        if (reducedMotion) e.retarget(target, duration = Motion.reducedFade, bounce = 0.0)
        else e.retarget(target, if (expanded) CallTrayTunables.transcriptExpand else CallTrayTunables.transcriptCollapse)
        ensureDisplayLink()
    }

    /** Advances the choreography by [dt] virtual seconds. Driven by the frame loop. */
    fun step(dt: Double): Boolean {
        if (dt <= 0) return isAnimating
        time += dt

        if (queue.isNotEmpty()) {
            val now = time + 1e-9
            val due = queue.filter { it.at <= now }
            if (due.isNotEmpty()) {
                queue.removeAll { it.at <= now }
                for (item in due) perform(item.action)
            }
        }

        l.step(dt); a.step(dt)
        if (shoot == null) w.step(dt)
        ang.step(dt); c.step(dt); m.step(dt); e.step(dt); k.step(dt); v.step(dt)

        shoot?.let { tw ->
            val u = time - tw.t0
            val h = 1e-3
            val vNext = shootAt(tw, u + h)
            val vPrev = shootAt(tw, max(0.0, u - h))
            w.velocity = ((vNext - vPrev) / (if (u > h) 2 * h else u + h))
            w.value = shootAt(tw, u)
            w.target = 1.0
            if (u >= shootEnd) {
                shoot = null
                snap(w, 1.0)
            }
        }

        val wx = w.value.toFloat()
        val qTarget = if (wx < 0) wx * travelSpan else if (wx > 1) (wx - 1) * travelSpan else 0f
        q.retarget(qTarget.toDouble(), duration = 0.12, bounce = 0.0)
        q.step(dt)

        val expanded = a.target > 0.5
        if (isTrayExpanded != expanded) setTrayExpanded(expanded, animated = !reducedMotion)

        if (phase == Phase.live &&
            queue.none { it.action == QueuedAction.unfold || it.action == QueuedAction.sink } &&
            a.isSettled && m.isSettled && a.value > 0.99 && m.value > 0.99 && !isMicFolded
        ) {
            isMicFolded = true
        }

        if (transcriptLingering && e.target == 0.0 && e.isSettled && e.value < 0.001) transcriptLingering = false

        if (phase == Phase.ending &&
            queue.none { it.action == QueuedAction.descend } &&
            a.isSettled && w.isSettled && l.isSettled && m.isSettled && a.value < 0.001 && m.value < 0.001
        ) {
            phase = Phase.idle
            isTrayShown = false
            setTrayExpanded(false, animated = false)
            isMicFolded = false
            scopeMounted = false
            scanPending = false
            transcriptLingering = false
            holdsCallButton = false
            callScopeRenderer = null
            snap(v, 0.0)
        }

        val rests = restsOpen
        if (rests != isResting) isResting = rests
        frameTick += 1
        return isAnimating
    }

    val isAnimating: Boolean
        get() {
            if (queue.isNotEmpty() || shoot != null) return true
            if (phase == Phase.ending) return true
            if (time - touchedAt < 1.2) return true
            return !(l.isSettled && a.isSettled && w.isSettled && ang.isSettled && c.isSettled && m.isSettled && e.isSettled && k.isSettled && v.isSettled && q.isSettled)
        }

    private fun ensureDisplayLink() {
        wantsLoop = true
        val scope = loopScope ?: return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            motionFrameLoop { tick ->
                if (step(tick.dt)) MotionFrameAction.Continue else MotionFrameAction.Stop.also { wantsLoop = false }
            }
        }
    }

    private fun schedule(delay: Double, action: QueuedAction) {
        queue += ScheduledEvent(time + delay, action)
    }

    private fun later(delay: Double, action: QueuedAction) {
        if (delay > 1e-4) schedule(delay, action) else perform(action)
    }

    private fun drop(vararg actions: QueuedAction) {
        queue.removeAll { it.action in actions }
    }

    private fun perform(action: QueuedAction) {
        val openDur = CallTrayTunables.open.duration
        val openBounce = CallTrayTunables.open.bounce
        val closeDur = CallTrayTunables.close.duration
        val closeBounce = CallTrayTunables.close.bounce

        when (action) {
            QueuedAction.unfold -> {
                if (phase != Phase.live) return
                if (belowMode == CallTrayBelowMode.slide) {
                    spinOpen(fresh = true)
                    c.retarget(1.0, duration = CallTrayTunables.spinDuration, bounce = 0.0)
                    perform(QueuedAction.widen)
                    later(CallTrayTunables.openStagger, QueuedAction.sink)
                    return
                }
                setTrayExpanded(true, animated = !reducedMotion)
                a.retarget(1.0, CallTrayTunables.open)
                if (CallTrayTunables.morph != CallTrayMorph.staged) l.retarget(0.0, CallTrayTunables.open)
                spinOpen(fresh = true)
                c.retarget(1.0, duration = CallTrayTunables.spinDuration, bounce = 0.0)
                later(CallTrayTunables.openStagger, QueuedAction.widen)
            }
            QueuedAction.widen -> {
                if (phase != Phase.live) return
                val usesShoot = CallTrayTunables.widenCurve == CallTrayWidenCurve.shoot
                val mode = belowMode
                if (mode == CallTrayBelowMode.edge) {
                    if (scanPending) perform(QueuedAction.scan)
                    perform(QueuedAction.reveal)
                    return
                }
                if (scanPending && mode != CallTrayBelowMode.slide) later(if (usesShoot) windupTime else 0.0, QueuedAction.scan)
                val trayWidth = max(1f, baseComposerFrame.width)
                val span = max(1f, trayWidth - CallTrayTunables.height)
                travelSpan = span
                if (!usesShoot) {
                    w.retarget(1.0, duration = openDur, bounce = CallTrayTunables.widenBounce)
                    if (mode != CallTrayBelowMode.slide) perform(QueuedAction.reveal)
                    return
                }
                shoot = ShootState(t0 = time, from = w.value.toFloat(), wind = -CallTrayTunables.windup / span, over = CallTrayTunables.stretch / span)
                if (mode != CallTrayBelowMode.slide) later(windupTime, QueuedAction.reveal)
            }
            QueuedAction.sink -> {
                if (phase != Phase.live) return
                setTrayExpanded(true, animated = !reducedMotion)
                a.retarget(1.0, duration = openDur, bounce = openBounce)
                if (scanPending) perform(QueuedAction.scan)
                perform(QueuedAction.reveal)
            }
            QueuedAction.reveal -> {
                if (phase != Phase.live) return
                k.retarget(1.0, duration = openDur, bounce = 0.0)
            }
            QueuedAction.scan -> {
                if (phase != Phase.live) return
                scanPending = false
            }
            QueuedAction.descend -> {
                if (phase != Phase.ending) return
                setTrayExpanded(false, animated = !reducedMotion)
                if (belowMode == CallTrayBelowMode.slide) w.retarget(0.0, duration = closeDur, bounce = closeBounce)
                else a.retarget(0.0, duration = closeDur, bounce = closeBounce)
                l.retarget(0.0, duration = closeDur, bounce = closeBounce)
                c.retarget(0.0, duration = CallTrayTunables.spinDuration * 0.85, bounce = 0.0)
                spinClose()
                m.retarget(0.0, duration = closeDur, bounce = 0.0)
            }
        }
    }

    private fun spinOpen(fresh: Boolean) {
        val degrees = CallTrayTunables.spinDegrees
        if (degrees <= 0) return
        val x = ang.value
        val duration = CallTrayTunables.spinDuration
        val target = if (fresh) {
            Math.round(x / 360).toDouble() * 360 + degrees
        } else {
            val residue = degrees % 360
            atLeast(x + ahead(duration), residue)
        }
        ang.retarget(target, duration = duration, bounce = 0.0)
    }

    private fun spinClose() {
        val degrees = CallTrayTunables.spinDegrees
        val x = ang.value
        val duration = CallTrayTunables.spinDuration * 0.85
        if (abs(x - (x / 360).roundToLong() * 360.0) < 1e-3 && degrees <= 0) return
        val extra = if (degrees > 0) ahead(duration) else 0.0
        val target = ceil((x + extra) / 360) * 360
        ang.retarget(target, duration = duration, bounce = 0.0)
    }

    private fun ahead(duration: Double): Double = max(45.0, max(0.0, ang.velocity) * duration / (2 * Math.PI) * 1.15)

    private fun snap(solver: SpringSolver, value: Double) {
        solver.value = value
        solver.target = value
        solver.velocity = 0.0
    }

    fun composerMicLook(reducedMotion: Boolean? = null): PartLook {
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val mVal = clamp(m.value.toFloat(), 0f, 1f)
        val r = reducedMotion ?: this.reducedMotion
        return PartLook(
            opacity = 1 - mVal,
            blur = if (r) 0f else CallTrayTunables.contentBlur * mVal,
            scale = if (r) 1f else lerp(1f, CallTrayTunables.contentScale, mVal),
            offsetX = 0f,
        )
    }

    /**
     * Computes the tray, seed, and composer geometry in the stage overlay's coordinate space
     * (`width × stageHeight`, dp, where `y = stageHeight` is the composer's resting bottom edge).
     * Reads [frameTick], so callers redraw each frame of the choreography.
     */
    fun geometry(width: Float? = null, isFocused: Boolean? = null, reducedMotion: Boolean? = null): Geometry {
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val r = reducedMotion ?: this.reducedMotion
        val wComposer = max(1f, width ?: baseComposerFrame.width)
        val focused = isFocused ?: isComposerFocused
        val barHeight = CallTrayComposerMetrics.barHeight
        val buttonDiameter = CallTrayComposerMetrics.buttonDiameter
        val buttonTrailingGap = CallTrayComposerMetrics.trailingGap
        val innerHorizontal = CallTrayComposerMetrics.innerHorizontal

        val lVal = clamp(l.value.toFloat(), 0f, 1.5f)
        val aVal = clamp(a.value.toFloat(), 0f, 1.2f)
        val wVal = clamp(w.value.toFloat(), -0.3f, 1.3f)
        val eVal = clamp(e.value.toFloat(), 0f, 1.2f)
        val ca = clamp(aVal, 0f, 1f)
        val cw = clamp(wVal, 0f, 1f)
        val ce = clamp(eVal, 0f, 1f)

        val shown = phase != Phase.idle || isTrayShown
        val mode = belowMode
        val trayH = CallTrayTunables.height
        val gap = CallTrayTunables.gap
        val full = trayH + gap
        val stageHeight = barHeight + full
        val restBottom = stageHeight

        val reserveOpen = if (r) (if (shown) 1f else 0f) else max(0f, aVal)
        val lifted = lVal * (1 - ca)
        val home = mode == CallTrayBelowMode.home
        val trayBottom = if (home && !focused) restBottom + full else restBottom
        val composerDip = if (mode != null && !home && !r) CallTrayTunables.composerDip * lifted else 0f
        val composerLift = (if (home && !focused) 0f else 1f) * reserveOpen * full
        val composerOffsetY = -composerLift + composerDip
        val composerBottom = restBottom + composerOffsetY
        val trayY = trayBottom - trayH
        val trayRect = Rect(0f, trayY, wComposer, trayY + trayH)
        val trayR = trayRect.right
        val endX = trayR - innerHorizontal - CallTrayBarLayout.slot / 2
        val midY = trayRect.center.y

        val anchored = mode == CallTrayBelowMode.lift || mode == CallTrayBelowMode.slide
        val bx = wComposer - buttonTrailingGap - buttonDiameter / 2
        val restButtonY = (if (anchored) restBottom else composerBottom) - barHeight / 2
        val by = restButtonY - CallTrayTunables.liftOffset * lifted
        val bd = buttonDiameter * (1 + (CallTrayTunables.liftScale - 1) * lifted)
        val seedRect = Rect(bx - bd / 2, by - bd / 2, bx + bd / 2, by + bd / 2)

        val riseD = buttonDiameter * (1 + (CallTrayTunables.liftScale - 1) * clamp(lVal, 0f, 1f))
        val riseRect = Rect(bx - riseD / 2, by - riseD / 2, bx + riseD / 2, by + riseD / 2)

        fun widen(rise: Rect): Rect {
            val r0 = rise.right
            val left = if (wVal < 0) rise.left - wVal * (rise.left - trayRect.left) else lerp(rise.left, trayRect.left, wVal)
            val right = if (wVal < 0) r0 else lerp(r0, trayR, wVal)
            val width2 = max(0f, right - left)
            val dh = if (r) 0f else clamp(-CallTrayTunables.squash * q.value.toFloat() * 0.5f, -rise.height * 0.2f, rise.height * 0.2f)
            val top = rise.top - dh / 2
            return Rect(left, top, left + width2, top + rise.height + dh)
        }

        val edgeDrop = full + 44
        var surf: Rect
        var radius: Float
        if (r) {
            surf = trayRect
            radius = CallTrayTunables.cornerRadius
        } else if (mode == CallTrayBelowMode.edge) {
            surf = trayRect.translate(0f, (1 - aVal) * edgeDrop)
            radius = CallTrayTunables.cornerRadius
        } else if (CallTrayTunables.morph == CallTrayMorph.staged) {
            val from = if (mode == CallTrayBelowMode.slide) riseRect.translate(0f, CallTrayTunables.tuck * smoothstep(0f, 1f, cw)) else riseRect
            val ball = Rect(endX - trayH / 2, trayRect.top, endX + trayH / 2, trayRect.top + trayH)
            surf = widen(lerpRect(from, ball, aVal))
            radius = lerp(surf.height / 2, CallTrayTunables.cornerRadius, cw)
        } else {
            val y = lerp(seedRect.top, trayRect.top, aVal)
            val h = lerp(seedRect.height, trayH, aVal)
            surf = widen(Rect(seedRect.left, y, seedRect.left + seedRect.width, y + h))
            radius = lerp(bd / 2, CallTrayTunables.cornerRadius, ca)
        }
        radius = max(0f, min(radius, min(surf.width / 2, surf.height / 2)))

        val toEnd = CallTrayTunables.seedHandoff == CallTraySeedHandoff.end
        val angle = if (r) 0f else ang.value.toFloat()
        val angVelocity = ang.velocity.toFloat()
        val spinSpeed = clamp(abs(angVelocity) / 1800, 0f, 1f)
        val shutter = max(0f, CallTrayTunables.spinShutter.toFloat())
        val spread = if (r) 0f else clamp(abs(angVelocity) * shutter, 0f, 55f)
        val dir = if (angVelocity >= 0) 1f else -1f
        val callness = if (toEnd && !r) smoothstep(0.35f, 1f, clamp(c.value.toFloat(), 0f, 1f)) else 0f

        val seed: Seed = if (r && toEnd) {
            // Reduce Motion: no lift or spin. The disc stays in place and blends its colour into
            // End's while End fades in beneath it, then hands over once the two match.
            Seed(
                x = lerp(bx, endX, ca), y = lerp(by, midY, ca), diameter = lerp(bd, 40f, ca),
                opacity = 1 - smoothstep(0.85f, 1f, ca),
                angle = 0f, drift = 0f, iconBlur = 0f, blur = 0f, dissolve = 0f, callness = ca, spread = 0f, dir = 1f,
            )
        } else if (r) {
            Seed(x = bx, y = by, diameter = bd, opacity = 1 - ca, angle = 0f, drift = 0f, iconBlur = 0f, blur = 0f, dissolve = 0f, callness = 0f, spread = 0f, dir = 1f)
        } else {
            val fade = if (toEnd) 0f else smoothstep(0.08f, 0.52f, ca)
            val isEdge = mode == CallTrayBelowMode.edge
            val seedY = if (CallTrayTunables.morph == CallTrayMorph.staged && !isEdge) surf.center.y
            else lerp(by, midY, if (isEdge) smoothstep(0f, 1f, ca) else ca)
            Seed(
                x = lerp(bx, endX, ca),
                y = seedY,
                diameter = lerp(if (CallTrayTunables.morph == CallTrayMorph.staged) riseD else bd, 40f, ca),
                opacity = 1 - fade,
                angle = angle,
                drift = CallTrayTunables.iconDrift * fade,
                iconBlur = CallTrayTunables.iconBlur * max(spinSpeed, if (CallTrayTunables.iconDrift > 0) fade else 0f),
                blur = 0f,
                dissolve = 0f,
                callness = callness,
                spread = spread,
                dir = dir,
            )
        }

        val reach = if (CallTrayTunables.morph == CallTrayMorph.staged) clamp(k.value.toFloat(), 0f, 1f) else cw
        val stag = CallTrayTunables.contentStagger
        val parts = (0..2).map { index ->
            if (r) return@map PartLook(opacity = ca, blur = 0f, scale = 1f, offsetX = 0f)
            val t0 = index * stag
            val t1 = min(1f, t0 + max(0.25f, 1 - 2 * stag))
            val vv = smoothstep(t0, t1, reach)
            val travel = if (index == 0) 0f else CallTrayTunables.contentTravel * (1 - vv)
            PartLook(opacity = vv, blur = CallTrayTunables.contentBlur * (1 - vv), scale = lerp(CallTrayTunables.contentScale, 1f, vv), offsetX = travel)
        }.toMutableList()

        if (!r && toEnd) {
            parts[0] = PartLook(opacity = 0f, blur = 0f, scale = 1f, offsetX = 0f)
        } else if (!r && CallTrayTunables.handoffOverlap < 1) {
            val a0 = lerp(0.52f, 0.18f, clamp(CallTrayTunables.handoffOverlap, 0f, 1f))
            val vv = smoothstep(a0, min(1f, a0 + 0.54f), reach)
            parts[0] = PartLook(
                opacity = vv,
                blur = CallTrayTunables.contentBlur * (1 - vv),
                scale = lerp(CallTrayTunables.contentScale, 1f, vv),
                offsetX = CallTrayTunables.contentTravel * (1 - vv),
            )
        }

        // Mount the tray's surface once the unfold begins (`a.target > 0` or `w.target > 0`) and
        // unmount it on hang-up once `a` has brought the composer back over the ball
        // (`a <= 0.05`), so no 56 dp surface halos the 40 dp button at rest in the composer.
        val showsGlassSurface = when {
            r -> shown || ca > 0.001f
            phase == Phase.live -> a.target > 0.5 || w.target > 0.5 || ca > 0.01f || cw > 0.01f
            phase == Phase.ending -> ca > 0.05f || cw > 0.02f
            else -> false
        }

        val droplet = !r && CallTrayTunables.glassEmerge == CallTrayGlassEmerge.droplet && mode != CallTrayBelowMode.edge
        val mat = if (r) ca else smoothstep(0.02f, 0.26f, ca)
        val trayOpacity = if (showsGlassSurface) (if (droplet) 1f else mat) else 0f
        val dropletMergeSpacing = if (droplet) CallTrayTunables.glassSpacing else 0f

        val finalSeed = seed.copy(
            dissolve = if (r || !toEnd) 0f else min(clamp(v.value.toFloat(), 0f, 1f), mat),
            phoneGlyph = if (!toEnd) 0f else if (r) smoothstep(0.3f, 0.7f, ca) else smoothstep(0.2f, 0.55f, clamp(c.value.toFloat(), 0f, 1f)),
        )

        val sinceTouch = (time - touchedAt).toFloat()
        val energize = if (r || sinceTouch < 0) 0f
        else CallTrayTunables.glassEnergize * smoothstep(0f, 0.04f, sinceTouch) * exp(-max(0f, sinceTouch - 0.04f) / 0.3f)

        val transcriptH = if (r) (if (e.target > 0.5 || ce > 0.001f) CallTrayTunables.transcriptHeight else 0f)
        else max(0f, eVal) * CallTrayTunables.transcriptHeight
        val transcriptO = if (r) ce else smoothstep(0.01f, 0.18f, ce)

        return Geometry(
            reducedMotion = r,
            l = lVal,
            a = aVal,
            ca = ca,
            cw = cw,
            ce = ce,
            shown = shown,
            showsGlassSurface = showsGlassSurface,
            composerDip = composerDip,
            composerOffsetY = composerOffsetY,
            sessionReserve = discreteSessionReserve,
            stageHeight = stageHeight,
            trayRect = trayRect,
            surf = surf,
            radius = radius,
            trayOpacity = trayOpacity,
            dropletMergeSpacing = dropletMergeSpacing,
            energize = energize,
            lifted = lifted,
            seed = finalSeed,
            parts = parts,
            mic = composerMicLook(),
            transcriptHeight = transcriptH,
            transcriptOpacity = transcriptO,
            transcriptGap = CallTrayTunables.transcriptGap,
        )
    }

    companion object {
        private fun spring(duration: Double, positionEpsilon: Double, velocityEpsilon: Double) =
            SpringSolver(MotionSpring(duration, 0.0), positionEpsilon = positionEpsilon, velocityEpsilon = velocityEpsilon)

        private val belowMode: CallTrayBelowMode?
            get() = if (CallTrayTunables.morph == CallTrayMorph.staged) CallTrayTunables.below else null

        private val windupTime: Double
            get() = if (CallTrayTunables.windup > 0) CallTrayTunables.windupDuration else 0.0

        private val shootEnd: Double
            get() = windupTime + max(0.05, CallTrayTunables.widenDuration)

        private fun atLeast(minVal: Double, residue: Double): Double {
            val mod = ((residue - minVal) % 360 + 360) % 360
            return minVal + mod
        }

        private fun shootAt(tw: ShootState, u: Double): Double {
            val tw0 = windupTime
            if (u < tw0 && tw0 > 0) return lerp(tw.from, tw.from + tw.wind, smoothstep(0f, 1f, (u / tw0).toFloat())).toDouble()
            val start = tw.from + (if (tw0 > 0) tw.wind else 0f)
            val peak = 1 + tw.over
            val pPeak = if (tw.over > 1e-4f) min(max(CallTrayTunables.widenPeak, 0.2f), 0.9f) else 1f
            val kk = ((u - tw0) / max(0.05, CallTrayTunables.widenDuration)).toFloat()
            if (kk < pPeak) {
                val ratio = (1 - kk / pPeak).toDouble()
                val punch = max(1f, CallTrayTunables.widenPunch).toDouble()
                return lerp(start, peak, (1 - ratio.pow(punch)).toFloat()).toDouble()
            }
            if (kk < 1) return lerp(peak, 1f, smoothstep(0f, 1f, (kk - pPeak) / max(0.0001f, 1 - pPeak))).toDouble()
            return 1.0
        }

        fun buttonLook(
            callStyle: CallTrayCallButtonStyle,
            endStyle: CallTrayEndButtonStyle,
            dissolve: Float,
            callness: Float,
            isDark: Boolean,
        ): ButtonLook {
            // Exactly the composer's call button (`.label` disc, `.systemBackground` glyph), so
            // the hand-off between them is the same colour (OC-473).
            val white = if (isDark) doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0) else doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0)
            val red = doubleArrayOf(1.0, 0.231, 0.188, 1.0, 1.0, 1.0, 1.0)
            val endLook = when (endStyle) {
                CallTrayEndButtonStyle.red -> red
                CallTrayEndButtonStyle.white -> white
                CallTrayEndButtonStyle.icon -> doubleArrayOf(red[0], red[1], red[2], 0.0, 1.0, 0.27, 0.23)
            }
            // The disc only dissolves toward an End with no disc of its own (`.icon`). Toward a
            // solid End it stays opaque and blends straight across, so it never passes through
            // the dark backdrop on its way from the call colour to red (OC-473).
            val callAlpha = white[3] * (1 - clamp(dissolve, 0f, 1f) * (1 - endLook[3]))
            val callLook = if (callStyle == CallTrayCallButtonStyle.disc) doubleArrayOf(white[0], white[1], white[2], callAlpha, white[4], white[5], white[6])
            else doubleArrayOf(white[0], white[1], white[2], 0.0, 1.0, 1.0, 1.0)
            val cc = clamp(callness, 0f, 1f).toDouble()
            val glyphMix = smoothstep(0.15f, 0.55f, clamp(callness, 0f, 1f)).toDouble()
            fun mix(i: Int, t: Double) = (callLook[i] + (endLook[i] - callLook[i]) * t).toFloat()
            return ButtonLook(
                disc = Color(mix(0, cc).coerceIn(0f, 1f), mix(1, cc).coerceIn(0f, 1f), mix(2, cc).coerceIn(0f, 1f)),
                discAlpha = mix(3, cc),
                glyph = Color(mix(4, glyphMix).coerceIn(0f, 1f), mix(5, glyphMix).coerceIn(0f, 1f), mix(6, glyphMix).coerceIn(0f, 1f)),
            )
        }

        fun clamp(x: Float, lo: Float, hi: Float): Float = min(hi, max(lo, x))

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = clamp((x - edge0) / max(0.0001f, edge1 - edge0), 0f, 1f)
            return t * t * (3 - 2 * t)
        }

        fun lerpRect(a: Rect, b: Rect, t: Float): Rect {
            val left = lerp(a.left, b.left, t)
            val top = lerp(a.top, b.top, t)
            return Rect(left, top, left + lerp(a.width, b.width, t), top + lerp(a.height, b.height, t))
        }
    }
}
