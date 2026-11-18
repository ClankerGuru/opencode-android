package ai.opencode.feature.drawer.catchup

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionCurve
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.feature.drawer.motion.DrawerPanEvent
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The session card as the top of a Catch Up stack. */
data class CatchUpStage(
    /** Sessions still to show; the first is the open card. */
    val queue: List<String>,
    /** How the most recently handled card left, for Undo to reverse. */
    val undoDirection: CatchUpDisposition? = null,
    val onDismiss: (CatchUpDisposition) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onExit: () -> Unit = {},
    /** The stack emptied: Catch Up closes itself (`CatchUpFinish.kt`). */
    val onFinish: () -> Unit = {},
) {
    val cardID: String? get() = queue.firstOrNull()
    val nextCardID: String? get() = queue.getOrNull(1)
}

/** Lets the chrome's buttons play the same choreography as a swipe. */
class CatchUpStageProxy {
    var dismiss: (CatchUpDisposition) -> Unit = {}
    var undo: () -> Unit = {}

    fun dismissCard(disposition: CatchUpDisposition) = dismiss(disposition)
    fun undoCard() = undo()
}

/** Translation (dp), rotation (radians) and uniform scale about the centre. */
data class CatchUpPose(val tx: Float = 0f, val ty: Float = 0f, val rotation: Float = 0f, val scale: Float = 1f) {
    fun lerp(to: CatchUpPose, t: Float) = CatchUpPose(
        tx + (to.tx - tx) * t,
        ty + (to.ty - ty) * t,
        rotation + (to.rotation - rotation) * t,
        scale + (to.scale - scale) * t,
    )

    companion object {
        val Identity = CatchUpPose()
    }
}

/** Snapshot state for one card surface, read only inside `graphicsLayer {}` (never recomposes content). */
@Stable
class CatchUpCardLayer {
    var pose by mutableStateOf(CatchUpPose.Identity)
    var alpha by mutableFloatStateOf(1f)

    internal fun applyTo(scope: GraphicsLayerScope) = with(scope) {
        val p = pose
        translationX = p.tx * density
        translationY = p.ty * density
        rotationZ = Math.toDegrees(p.rotation.toDouble()).toFloat()
        scaleX = p.scale
        scaleY = p.scale
        this.alpha = this@CatchUpCardLayer.alpha
    }
}

/**
 * A snapshot riding above or below the live card: a dismissed card's flight, Undo's card shrinking
 * back, or a crossfade. [badge] is the flying card's own swipe overlay.
 */
@Stable
class CatchUpFlyingCard internal constructor(
    internal val id: Long,
    /** Above the live card (flights, crossfades) or between the preview and it (Undo). */
    val aboveLive: Boolean,
    val badge: CatchUpBadgeState?,
    val shadow: Boolean = true,
) {
    val layer = CatchUpCardLayer()
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        internal set
    /** Black dim over the snapshot (Undo's card going back behind). */
    var dim by mutableFloatStateOf(0f)
        internal set
    var borderColor by mutableStateOf<Color?>(null)
        internal set
}

/**
 * Owns the stack's choreography: the drag, the fling that hands the next card forward, Undo's
 * return flight, and the preview card beneath. A plain object: per-frame motion lives in snapshot
 * state read by `graphicsLayer`, never in a reducer or a recomposition.
 *
 * The live session card is the only interactive card. The card behind it is a preview of the next
 * session; a dismissal flies a snapshot ([GraphicsLayer.toImageBitmap]) of the live card away while
 * the preview rises into place, and the live card (already showing that same session) replaces the
 * preview once it has landed.
 *
 * Android differences: the live card itself is the drag surface. Until a flight's snapshot is ready
 * (a frame or two) the flight carries the live card itself, then hands over to the snapshot and
 * tells the store.
 */
@Stable
class CatchUpStackController internal constructor() {
    // Environment, set by the composable.
    var reducedMotion = false
    internal var isDark = false
    internal var frameDuration = 1.0 / 60.0
    internal var containerWidth = 1f
    internal var cardHeight = 1f
    internal var endEditing: () -> Unit = {}
    internal var liveLayer: GraphicsLayer? = null
    internal lateinit var scope: CoroutineScope

    private val haptics = dependency(HapticsClient)

    // Render state.
    val live = CatchUpCardLayer()
    val preview = CatchUpCardLayer().also { it.alpha = 1f }
    /** The session the preview card shows, `null` for none. */
    var shownPreviewID by mutableStateOf<String?>(null)
        private set
    var isPreviewHidden by mutableStateOf(true)
        private set
    /** The preview's dim, as alpha. */
    var previewDim by mutableFloatStateOf(0f)
        private set
    /** Border alpha factor of the preview card (0.42 at rest, 1 in front). */
    var previewBorderFactor by mutableFloatStateOf(0.42f)
        private set
    /** The live card's border tinted toward the swipe's side, or `null` for its normal border. */
    var dragBorderColor by mutableStateOf<Color?>(null)
        private set
    val readBadge = CatchUpBadgeState(CatchUpDisposition.markRead)
    val keepBadge = CatchUpBadgeState(CatchUpDisposition.keepUnread)
    val flyingCards = mutableStateListOf<CatchUpFlyingCard>()
    var isContentEnabled by mutableStateOf(false)
        private set

    private var stage: CatchUpStage? = null
    var previewID: String? = null
        private set
    private var dragActive = false
    private var isTransitioning = false
    private var isFinishingTransition = false
    private var isDragging = false
    var dragOffset: Offset = Offset.Zero
        private set
    private var dragStartOffset = Offset.Zero
    private var armedDisposition: CatchUpDisposition? = null
    private var lastMovement = 0L
    private var animator: Running? = null
    private var flight: Running? = null
    private var activeSnapshot: CatchUpFlyingCard? = null
    private var settleJob: Job? = null
    private var finishJob: Job? = null
    private var nextFlyingID = 0L
    private val dismissal = CatchUpNextFrame { scope }

    val isPreviewLocked: Boolean get() = isDragging || isTransitioning || dragActive

    // State

    /** The card behind waits for the open card to settle, then rises into view ([revealPreview]). */
    fun begin(stage: CatchUpStage) {
        this.stage = stage
        cancelFinish()
        finishTransition()
        endDragSurface()
        syncPreview()
        preview.alpha = 0f
        isContentEnabled = stage.cardID != null
    }

    fun revealPreview(animated: Boolean) {
        if (!animated) {
            preview.alpha = 1f
            preview.pose = peekPose(0f)
            setPreviewDepth(0f)
            return
        }
        // Start tucked behind the front surface, then ease down into the stack (`MotionRole.Layout`);
        // under Reduce Motion it fades in where it rests.
        val to = peekPose(0f)
        val from = if (reducedMotion) to else CatchUpPose.Identity
        if (reducedMotion) preview.alpha = 0f
        preview.pose = from
        setPreviewDepth(0f)
        val fromAlpha = preview.alpha
        run(animate(Curve.role(MotionRole.Layout, reduceMotion = reducedMotion), step = { p ->
            preview.alpha = fromAlpha + (1f - fromAlpha) * p
            preview.pose = from.lerp(to, p)
        }))
    }

    /**
     * [keepsPreview]: the card behind stays to fade out with the stage; [releasePreview] once it has.
     * [keepsFlight]: the last card's flight carries on over the drawer coming back.
     */
    fun end(keepsPreview: Boolean = false, keepsFlight: Boolean = false) {
        cancelFinish()
        if (keepsFlight) {
            val running = flight
            if (running != null && !running.done) {
                // Its completion removes its snapshot.
                flight = null
                activeSnapshot = null
            }
        }
        finishTransition()
        // Left mid-drag: the live card shows again, as after Undo.
        if (dragActive) live.alpha = if (stage?.cardID == null) 0f else 1f
        endDragSurface()
        isDragging = false
        dragStartOffset = Offset.Zero
        stage = null
        setFeedback(0f)
        if (!keepsPreview) releasePreview()
    }

    /**
     * Emptied without a flight (another device read the last card): Catch Up closes itself
     * `closeDelay` later, counted on display frames (slowed with the animation time scale), so the
     * close lands on a frame in step with the card fading out. A flung last card closes it at once.
     */
    private fun scheduleFinish(finish: () -> Unit) {
        if (finishJob != null) return
        finishJob = scope.launch {
            var remaining = CatchUpFinishTunables.closeDelay
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                remaining -= tick.dt
                if (remaining > 0) MotionFrameAction.Continue else MotionFrameAction.Stop
            }
            finishJob = null
            finish()
        }
    }

    private fun cancelFinish() {
        finishJob?.cancel()
        finishJob = null
    }

    fun releasePreview() {
        isPreviewHidden = true
        preview.alpha = 1f
        previewID = null
        shownPreviewID = null
    }

    fun update(stage: CatchUpStage) {
        val previous = this.stage
        this.stage = stage
        if (stage.cardID == null) scheduleFinish(stage.onFinish) else cancelFinish()
        // A running flight settles against the latest stage when it lands.
        if (isPreviewLocked) return
        syncPreview()
        if (stage.cardID == previous?.cardID) return
        when {
            stage.cardID == null -> {
                fade(0f)
                isContentEnabled = false
            }
            previous?.cardID == null -> rise()
            // Moved by another device (deleted or reordered): a quiet crossfade.
            else -> crossfadeCard()
        }
    }

    /** Keeps the latest callbacks without treating it as a change (closures are new each composition). */
    internal fun refreshCallbacks(stage: CatchUpStage) {
        this.stage = stage
    }

    internal val currentStage: CatchUpStage? get() = stage

    private fun syncPreview() {
        val next = stage?.nextCardID
        isPreviewHidden = next == null
        if (next == previewID) return
        previewID = next
        shownPreviewID = next
        preview.pose = peekPose(0f)
        setPreviewDepth(0f)
    }

    /** Re-applies stack geometry. */
    fun refreshTuning() {
        if (isDragging || settleJob != null) {
            applyDrag(dragOffset, isSettling = settleJob != null)
        } else if (!isTransitioning) {
            preview.pose = peekPose(0f)
            setPreviewDepth(0f)
        }
    }

    fun refreshPreviewAppearance() {
        if (isPreviewLocked) return
        setPreviewDepth(0f)
    }

    // Geometry

    private val dimAlpha: Float get() = if (isDark) 0.62f else 0.24f

    private fun setPreviewDepth(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        previewDim = dimAlpha * (1 - clamped)
        previewBorderFactor = 0.42f + 0.58f * clamped
    }

    private val threshold: Float get() = max(containerWidth * CatchUpTunables.commitFraction, 1f)

    private val hapticDistance: Float get() = max(threshold * CatchUpTunables.hapticThreshold, 1f)

    /** The back card scales and brightens across the full card travel, not just to the commit threshold. */
    private val revealDistance: Float get() = max(containerWidth * CatchUpTunables.revealFraction, 1f)

    /** Smaller and lower, so a band of it shows beneath the open card. */
    private fun peekPose(progress: Float): CatchUpPose {
        if (reducedMotion) return CatchUpPose.Identity
        val clamped = progress.coerceIn(0f, 1f)
        val baseScale = CatchUpTunables.backScale
        val scale = baseScale + (1 - baseScale) * clamped
        val height = max(cardHeight, 1f)
        val lift = (height * CatchUpTunables.backLiftRatio + CatchUpTunables.backOffset) * (1 - clamped)
        val angle = CatchUpTunables.backRotation * (1 - clamped)
        return CatchUpPose(tx = 0f, ty = lift, rotation = angle, scale = scale)
    }

    private fun dragPose(translation: Offset): CatchUpPose {
        if (reducedMotion) return CatchUpPose(tx = translation.x)
        val rotation = -translation.x / max(containerWidth, 1f) * CatchUpTunables.dragRotation
        return CatchUpPose(tx = translation.x, ty = translation.y * CatchUpTunables.rubberBand, rotation = rotation)
    }

    private fun offscreenPose(
        disposition: CatchUpDisposition,
        startOffset: Offset = Offset.Zero,
        velocity: Offset = Offset.Zero,
    ): CatchUpPose {
        val direction = if (disposition == CatchUpDisposition.markRead) 1f else -1f
        val width = max(containerWidth, 1f)
        val targetX = direction * width * 1.85f
        val startY = startOffset.y * CatchUpTunables.rubberBand
        val remainingX = abs(targetX - startOffset.x)
        val flightTime = min(max(remainingX / max(abs(velocity.x), 900f), 0.12f), 0.35f)
        val targetY = (startY + velocity.y * CatchUpTunables.rubberBand * flightTime).coerceIn(-140f, 140f)
        val maxRotation = max(CatchUpTunables.dragRotation * 1.5f, 0.08f)
        return CatchUpPose(tx = targetX, ty = targetY, rotation = -direction * maxRotation)
    }

    private fun beginDragSurface() {
        live.pose = CatchUpPose.Identity
        if (live.alpha < 0.01f) live.alpha = 1f
        dragActive = true
        dragOffset = Offset.Zero
    }

    private fun endDragSurface() {
        dragActive = false
        live.pose = CatchUpPose.Identity
        dragOffset = Offset.Zero
        setFeedback(0f, animatedPop = false)
    }

    private fun setFeedback(offset: Float, animatedPop: Boolean = true) {
        val targetDistance = hapticDistance
        val progress = min(abs(offset) / targetDistance, 1f)
        val popScope = if (::scope.isInitialized) scope else null
        readBadge.update(if (offset > 0) offset else 0f, targetDistance, reducedMotion, animatedPop, popScope)
        keepBadge.update(if (offset < 0) -offset else 0f, targetDistance, reducedMotion, animatedPop, popScope)
        dragBorderColor = if (progress > 0.01f) {
            val tone = if (offset >= 0) CatchUpMetrics.readColor else CatchUpMetrics.keepColor
            tone.copy(alpha = 0.45f + 0.50f * progress)
        } else {
            null
        }
    }

    private fun dispositionPastHapticThreshold(offsetX: Float): CatchUpDisposition? {
        if (abs(offsetX) < hapticDistance) return null
        return if (offsetX > 0) CatchUpDisposition.markRead else CatchUpDisposition.keepUnread
    }

    fun applyDrag(translation: Offset, isSettling: Boolean = false) {
        if (!dragActive && translation != Offset.Zero) beginDragSurface()
        dragOffset = translation
        live.pose = dragPose(translation)
        val previewProgress = min(abs(translation.x) / revealDistance, 1f)
        preview.pose = peekPose(previewProgress)
        setPreviewDepth(previewProgress)
        setFeedback(translation.x, animatedPop = !isSettling)
        if (isSettling) return
        val armed = dispositionPastHapticThreshold(translation.x)
        if (armed != armedDisposition) {
            armedDisposition = armed
            if (armed != null) haptics.play(Haptic.threshold)
        }
    }

    // Drag

    internal val canBeginDrag: Boolean get() = stage?.cardID != null || (settleJob != null && dragActive)

    /** The pan began with [pan] already travelled. Returns whether the stack took it. */
    fun panBegan(pan: Offset): Boolean {
        val caughtMidSettle = settleJob != null && dragActive
        val caughtOffset = if (caughtMidSettle) dragOffset else Offset.Zero
        if (caughtMidSettle) {
            stopSettleSpring()
            isTransitioning = false
        } else {
            finishTransition(syncingPreview = false)
            if (stage?.cardID == null) return false
            beginDragSurface()
            syncPreview()
            preview.alpha = 1f
        }
        if (stage?.cardID == null) return false
        isDragging = true
        dragStartOffset = caughtOffset
        haptics.prepare(Haptic.threshold)
        armedDisposition = dispositionPastHapticThreshold(caughtOffset.x)
        endEditing()
        isContentEnabled = false
        lastMovement = System.nanoTime()
        applyDrag(dragStartOffset + pan)
        return true
    }

    fun panChanged(pan: Offset) {
        if (!isDragging) return
        lastMovement = System.nanoTime()
        applyDrag(dragStartOffset + pan)
    }

    fun panEnded(pan: Offset, velocity: Offset) {
        if (!isDragging) return
        isDragging = false
        val total = dragStartOffset + pan
        dragStartOffset = Offset.Zero
        // The last movement's speed lingers after the finger comes to rest; a held finger is not a flick.
        val rested = (System.nanoTime() - lastMovement) / 1e9 > 0.08
        val releaseVelocity = if (rested) Offset.Zero else velocity
        val width = max(containerWidth, 1f)
        val disposition = CatchUpSwipeMath.decision(total, releaseVelocity, width)
        if (disposition != null && stage?.cardID != null) {
            fling(disposition, releaseVelocity)
        } else {
            settleBack(releaseVelocity)
        }
    }

    /**
     * For a host that routes the drawer container's pan here. That pan is horizontal only, so the card gets no vertical rubber
     * band; [CatchUpStack]'s own pan (its default) carries both axes.
     */
    fun handlePan(event: DrawerPanEvent) {
        when (event) {
            is DrawerPanEvent.Began -> {
                lastPanX = event.fingerTranslation
                panBegan(Offset(event.fingerTranslation, 0f))
            }
            is DrawerPanEvent.Changed -> {
                lastPanX = event.fingerTranslation
                panChanged(Offset(event.fingerTranslation, 0f))
            }
            is DrawerPanEvent.Ended -> panEnded(Offset(lastPanX, 0f), Offset(event.velocity, 0f))
            is DrawerPanEvent.Cancelled -> panCancelled()
        }
    }

    private var lastPanX = 0f

    fun panCancelled() {
        if (!isDragging) return
        isDragging = false
        dragStartOffset = Offset.Zero
        settleBack(Offset.Zero)
    }

    private fun settleBack(velocity: Offset) {
        armedDisposition = null
        val startOffset = dragOffset
        if (hypot(startOffset.x, startOffset.y) < 0.5f) {
            finishSettle()
            return
        }
        isTransitioning = true
        if (reducedMotion) {
            run(
                animate(Curve.role(MotionRole.Release, reduceMotion = true), step = { p ->
                    applyDrag(Offset(startOffset.x * (1 - p), startOffset.y * (1 - p)), isSettling = true)
                }) { finishSettle() },
            )
            return
        }
        // Preserve inward release velocity toward center; if the finger lifted while still moving
        // outward short of threshold, start the return from the turnaround apex so it never steps
        // outward after release.
        val inwardVx = if (velocity.x * startOffset.x < 0) velocity.x else 0f
        val inwardVy = if (velocity.y * startOffset.y < 0) velocity.y else 0f
        stopSettleSpring()
        val spring = CatchUpSpring(
            position = startOffset,
            velocity = Offset(inwardVx, inwardVy),
            target = Offset.Zero,
        )
        settleJob = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                val settled = spring.step(max(tick.dt, 1.0 / 120.0).toFloat())
                applyDrag(spring.position, isSettling = true)
                if (settled) {
                    settleJob = null
                    finishSettle()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    fun cancelPendingAnimations() = finishTransition(syncingPreview = false)

    /**
     * Catch Up ended under the finger: the rest of the drag, or the snap-back it started, moves
     * nothing and can't dismiss a card.
     */
    internal fun dropDrag() {
        if (!isDragging && settleJob == null) return
        if (settleJob != null) {
            stopSettleSpring()
            isTransitioning = false
        }
        isDragging = false
        dragStartOffset = Offset.Zero
        armedDisposition = null
        endDragSurface()
    }

    private fun stopSettleSpring() {
        settleJob?.cancel()
        settleJob = null
    }

    private fun finishSettle() {
        stopSettleSpring()
        isTransitioning = false
        live.alpha = if (stage?.cardID == null) 0f else 1f
        endDragSurface()
        isContentEnabled = stage?.cardID != null
        syncPreview()
    }

    // Dismiss

    /** Flings the open card away along the finger's path and brings the next one forward. Buttons share the flight. */
    fun fling(disposition: CatchUpDisposition, velocity: Offset = Offset.Zero) {
        if (settleJob != null) {
            stopSettleSpring()
            isTransitioning = false
        } else {
            finishTransition(syncingPreview = false)
            if (!dragActive && stage?.cardID != null) beginDragSurface()
        }
        val stage = stage ?: return
        if (stage.cardID == null) return
        val next = stage.nextCardID
        if (previewID != next) {
            previewID = next
            shownPreviewID = next
        }
        preview.alpha = 1f
        if (!dragActive) beginDragSurface()
        val direction = if (disposition == CatchUpDisposition.markRead) 1f else -1f
        val startOffset = dragOffset
        // A swipe that crossed the threshold already ticked; a fast short flick or a button tap
        // commits with one now, at the moment of commit.
        if (armedDisposition != disposition) haptics.play(Haptic.threshold)
        armedDisposition = null
        val startBadgeDistance = if (abs(startOffset.x) > 1) abs(startOffset.x) else hapticDistance
        val targetBadgeDistance = hapticDistance
        val flyingBadge = CatchUpBadgeState(disposition)
        val flying = CatchUpFlyingCard(nextFlyingID++, aboveLive = true, badge = flyingBadge)
        flyingBadge.update(startBadgeDistance, hapticDistance, reducedMotion, animatedPop = false, scope = null)
        flying.borderColor = dragBorderColor
        val startPose = live.pose
        flying.layer.pose = startPose
        isTransitioning = true
        endEditing()
        isContentEnabled = false
        isPreviewHidden = next == null
        // The live card carries the flight until its snapshot is ready.
        live.pose = startPose
        val liveBadge = if (disposition == CatchUpDisposition.markRead) readBadge else keepBadge
        liveBadge.update(startBadgeDistance, hapticDistance, reducedMotion, animatedPop = false, scope = null)
        (if (disposition == CatchUpDisposition.markRead) keepBadge else readBadge)
            .update(0f, hapticDistance, reducedMotion, animatedPop = false, scope = null)
        var handedOver = false
        var dismissed = false
        fun dismissNow() {
            if (dismissed) return
            dismissed = true
            stage.onDismiss(disposition)
        }
        fun handOver(bitmap: ImageBitmap?) {
            if (handedOver) return
            handedOver = true
            if (bitmap != null) {
                flying.layer.pose = live.pose
                flying.layer.alpha = live.alpha
                flyingBadge.copyFrom(liveBadge)
                flying.bitmap = bitmap
                flyingCards += flying
                activeSnapshot = flying
            }
            endDragSurface()
            live.alpha = 0f
            live.pose = CatchUpPose.Identity
            // The store hears it a frame later, once the flight is on screen. The last card takes
            // Catch Up with it: the stage starts leaving on the same frame, under the flight,
            // never waiting for it to land.
            dismissal.schedule {
                dismissNow()
                if (next == null) stage.onFinish()
            }
        }

        val target = offscreenPose(disposition, startOffset, velocity)
        val distance = max(abs(target.tx - startPose.tx), 1f)
        val vToward = velocity.x * direction
        val relative = if (vToward > 0) min(vToward / distance, 6f) else 0f
        val badgeStartWash = flyingBadge.washDistance

        val running = Running()
        running.finishNow = {
            if (!handedOver) {
                handOver(null)
                dismissal.flush()
            }
            flyingCards.remove(flying)
            if (activeSnapshot === flying) activeSnapshot = null
            if (flight === running) flight = null
        }
        running.job = scope.launch {
            val bitmapJob = launch {
                val bitmap = captureLive()
                handOver(bitmap)
            }
            // The card flies off on `MotionRole.Flight` carrying the flick's speed, and the next
            // rises on the same spring (a fade under Reduce Motion).
            val curve = Curve.role(MotionRole.Flight, reduceMotion = reducedMotion, initialVelocity = relative)
            var elapsed = 0.0
            fun frame(p: Float) {
                // Without a snapshot the live card already left for the next session: nothing flies.
                if (handedOver && flying.bitmap == null) return
                val layer = if (handedOver) flying.layer else live
                val badge = if (handedOver) flyingBadge else liveBadge
                badge.setRing(targetBadgeDistance, reducedMotion, animatedPop = false, scope = null)
                badge.washDistance = badgeStartWash + (targetBadgeDistance - badgeStartWash) * p
                if (reducedMotion) layer.alpha = 1f - p else layer.pose = startPose.lerp(target, p)
            }
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                frame(curve.progress(elapsed))
                if (curve.isDone(elapsed)) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
            bitmapJob.join()
            running.complete()
        }
        flight = running

        val previewStart = preview.pose
        val previewStartDim = previewDim
        val previewEndDim = 0f
        val previewStartFactor = previewBorderFactor
        run(
            animate(Curve.role(MotionRole.Flight, reduceMotion = reducedMotion), step = { p ->
                preview.pose = previewStart.lerp(CatchUpPose.Identity, p)
                previewDim = previewStartDim + (previewEndDim - previewStartDim) * p
                previewBorderFactor = previewStartFactor + (1f - previewStartFactor) * p
            }) { land() },
        )
    }

    /** The preview now sits exactly where the open card belongs, showing the session the live card loaded; swap them. */
    private fun land() {
        val stage = stage
        if (stage == null || stage.cardID == null) {
            isTransitioning = false
            isPreviewHidden = true
            isContentEnabled = false
            return
        }
        isContentEnabled = true
        val from = live.alpha
        run(
            animate(Curve.role(MotionRole.Swap, reduceMotion = reducedMotion), step = { p -> live.alpha = from + (1f - from) * p }) {
                isTransitioning = false
                // Behind the open card again, now showing the card after it.
                previewID = null
                if (this.stage?.nextCardID == null) shownPreviewID = null
                if (!isFinishingTransition) {
                    syncPreview()
                    revealPreview(animated = true)
                }
            },
        )
    }

    // Undo

    /** Returns the last handled card from the side it left, while the open card shrinks back behind it. */
    fun undo() {
        finishTransition()
        if (dragActive) {
            live.alpha = if (stage?.cardID == null) 0f else 1f
            endDragSurface()
        }
        val stage = stage ?: return
        val direction = stage.undoDirection
        if (direction == null || reducedMotion) {
            if (direction != null) crossfadeCard()
            stage.onUndo()
            return
        }
        val hadCard = stage.cardID != null
        isTransitioning = true
        var undone = false
        var snapshot: CatchUpFlyingCard? = null
        val running = Running()
        val offscreen = offscreenPose(direction)
        fun startUndo(bitmap: ImageBitmap?) {
            if (undone) return
            undone = true
            if (bitmap != null) {
                snapshot = CatchUpFlyingCard(nextFlyingID++, aboveLive = false, badge = null).also {
                    it.bitmap = bitmap
                    it.layer.pose = CatchUpPose.Identity
                    flyingCards += it
                    activeSnapshot = it
                }
            }
            if (hadCard) {
                // The card now behind the returning one is the current card.
                previewID = stage.cardID
                shownPreviewID = stage.cardID
                preview.pose = peekPose(0f)
                setPreviewDepth(0f)
                isPreviewHidden = false
            }
            stage.onUndo()
            live.alpha = 1f
            live.pose = offscreen
            isContentEnabled = true
        }
        running.finishNow = {
            startUndo(null)
            live.pose = CatchUpPose.Identity
            snapshot?.let { flyingCards.remove(it); if (activeSnapshot === it) activeSnapshot = null }
            isTransitioning = false
            syncPreview()
            if (animator === running) animator = null
        }
        running.job = scope.launch {
            startUndo(if (hadCard) captureLive() else null)
            val peek = peekPose(0f)
            // The card the person asked back arrives on `MotionRole.PanelOpen`.
            val curve = Curve.role(MotionRole.PanelOpen, reduceMotion = false)
            var elapsed = 0.0
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                val p = curve.progress(elapsed)
                live.pose = offscreen.lerp(CatchUpPose.Identity, p)
                snapshot?.layer?.pose = CatchUpPose.Identity.lerp(peek, p)
                snapshot?.dim = dimAlpha * p
                if (curve.isDone(elapsed)) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
            running.complete()
        }
        animator = running
    }

    // Arrivals

    /**
     * A card appears after the stack was finished: it rises from the back (`MotionRole.Layout`),
     * or fades in where it rests under Reduce Motion.
     */
    private fun rise() {
        val from = if (reducedMotion) CatchUpPose.Identity else peekPose(0f)
        live.pose = from
        live.alpha = 0f
        isContentEnabled = true
        run(
            animate(Curve.role(MotionRole.Layout, reduceMotion = reducedMotion), step = { p ->
                live.pose = from.lerp(CatchUpPose.Identity, p)
                live.alpha = p
            }),
        )
    }

    /** The card's old picture leaves on swap's exit over the new one. */
    private fun crossfadeCard() {
        if (live.alpha <= 0.01f) return
        val card = CatchUpFlyingCard(nextFlyingID++, aboveLive = true, badge = null, shadow = false)
        card.layer.pose = live.pose
        val running = Running()
        running.finishNow = {
            flyingCards.remove(card)
            if (animator === running) animator = null
        }
        running.job = scope.launch {
            val bitmap = captureLive() ?: return@launch running.complete()
            card.bitmap = bitmap
            flyingCards += card
            val curve = Curve.role(MotionRole.Swap, MotionPhase.Exit, reduceMotion = reducedMotion)
            var elapsed = 0.0
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                card.layer.alpha = 1f - curve.progress(elapsed)
                if (curve.isDone(elapsed)) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
            running.complete()
        }
        animator = running
    }

    private fun fade(to: Float) {
        val from = live.alpha
        val phase = if (to > from) MotionPhase.Enter else MotionPhase.Exit
        run(animate(Curve.role(MotionRole.Swap, phase, reduceMotion = reducedMotion), step = { p -> live.alpha = from + (to - from) * p }))
    }

    // Helpers

    /** The live card's content (without its swipe overlay) as a bitmap. */
    private suspend fun captureLive(): ImageBitmap? {
        val layer = liveLayer ?: return null
        return try {
            layer.toImageBitmap()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    /** A running piece of choreography that can be jumped to its end. */
    private class Running {
        var job: Job? = null
        var finishNow: () -> Unit = {}
        var done = false
            private set

        fun complete() {
            if (done) return
            done = true
            finishNow()
        }
    }

    private sealed interface Curve {
        fun progress(t: Double): Float
        fun isDone(t: Double): Boolean

        data class Tween(val duration: Double, val easing: Easing) : Curve {
            override fun progress(t: Double) = easing.transform((t / duration).coerceIn(0.0, 1.0).toFloat())
            override fun isDone(t: Double) = t >= duration
        }

        /**
         * A spring on a motion role's curve, run until it settles; [initialVelocity] is in units
         * of the whole travel per second.
         */
        data class RoleSpring(val omega: Double, val zeta: Double, val initialVelocity: Float) : Curve {
            override fun progress(t: Double): Float {
                if (isDone(t)) return 1f
                return SpringSolver.evaluate(
                    from = 0.0,
                    to = 1.0,
                    initialVelocity = initialVelocity.toDouble(),
                    omega = omega,
                    zeta = zeta,
                    elapsed = t,
                ).first.toFloat()
            }

            override fun isDone(t: Double): Boolean {
                if (t >= MAX_SECONDS) return true
                val (x, v) = SpringSolver.evaluate(
                    from = 0.0,
                    to = 1.0,
                    initialVelocity = initialVelocity.toDouble(),
                    omega = omega,
                    zeta = zeta,
                    elapsed = t,
                )
                return t > 0 && abs(1 - x) < 0.001 && abs(v) < 0.01
            }
        }

        companion object {
            const val MAX_SECONDS = 2.0

            /** The role's curve as a [Curve]. */
            fun role(role: MotionRole, phase: MotionPhase = MotionPhase.Enter, reduceMotion: Boolean, initialVelocity: Float = 0f): Curve {
                val curve = role.curve(phase, reduceMotion)
                return when (curve) {
                    is MotionCurve.EaseOut -> Tween(curve.duration, Motion.EaseOut)
                    is MotionCurve.EaseInOut -> Tween(curve.duration, Motion.EaseInOut)
                    is MotionCurve.Spring -> {
                        val parameters = curve.springParameters
                        RoleSpring(parameters.omega, parameters.zeta, if (reduceMotion) 0f else initialVelocity)
                    }
                }
            }
        }
    }

    private fun animate(curve: Curve, step: (Float) -> Unit, completion: () -> Unit = {}): Running {
        val running = Running()
        running.finishNow = {
            step(1f)
            completion()
            if (animator === running) animator = null
        }
        running.job = scope.launch {
            var elapsed = 0.0
            step(0f)
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                step(curve.progress(elapsed))
                if (curve.isDone(elapsed)) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
            running.complete()
        }
        return running
    }

    private fun run(running: Running) {
        animator = running
    }

    /** Jumps a running flight to its end so a new gesture starts from rest. */
    private fun finishTransition(syncingPreview: Boolean = true) {
        dismissal.flush()
        if (settleJob != null) finishSettle()
        flight?.let { running ->
            flight = null
            if (!running.done) {
                running.job?.cancel()
                running.complete()
            }
        }
        isFinishingTransition = true
        try {
            // A landing chains a short reveal, so settle until nothing is running.
            while (true) {
                val running = animator ?: break
                animator = null
                if (running.done) continue
                running.job?.cancel()
                running.complete()
            }
        } finally {
            isFinishingTransition = false
        }
        activeSnapshot?.let { flyingCards.remove(it) }
        activeSnapshot = null
        if (syncingPreview && stage != null) {
            syncPreview()
            preview.alpha = 1f
        }
    }

    internal fun dispose() {
        stopSettleSpring()
        cancelFinish()
        dismissal.cancel()
    }
}

/** One piece of work on the next display frame, or sooner with [flush]. */
internal class CatchUpNextFrame(private val scope: () -> CoroutineScope) {
    private var body: (() -> Unit)? = null
    private var job: Job? = null

    val isPending: Boolean get() = body != null

    /** Replaces any work still waiting, which runs first. */
    fun schedule(body: () -> Unit) {
        flush()
        this.body = body
        job = scope().launch {
            withFrameNanos { }
            flush()
        }
    }

    /** Runs the waiting work now. */
    fun flush() {
        val body = body
        cancel()
        body?.invoke()
    }

    /** Drops the waiting work. */
    fun cancel() {
        job?.cancel()
        job = null
        body = null
    }
}
