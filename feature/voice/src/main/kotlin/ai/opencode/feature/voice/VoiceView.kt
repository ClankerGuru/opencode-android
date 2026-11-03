package ai.opencode.feature.voice

import ai.opencode.core.ui.excludedFromDrawerPan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** The call's store as the voice views take it. */
typealias VoiceStoreOf = StoreOf<VoiceFeature.State, VoiceFeature.Action>

/** One derived value of an optional call; `null` without a call. */
@Composable
internal fun <V> VoiceStoreOf?.watch(select: (VoiceFeature.State) -> V): V? {
    if (this == null) return null
    return observe(select).value
}

/** iOS `EnvironmentValues.composerGlassHostedByCallTray`: true while [CallTrayHost] is mounted on the composer capsule. */
val LocalComposerGlassHostedByCallTray = staticCompositionLocalOf { false }

/**
 * iOS `EnvironmentValues.composerGlassMergedInCallTray`: true while [CallTrayHost] draws the
 * composer's surface itself, behind the tray, so [VoiceCallSurface] draws none.
 */
val LocalComposerGlassMergedInCallTray = staticCompositionLocalOf { false }

/**
 * The call's surfaces (iOS Liquid Glass) as Material 3 tonal surfaces: no blur or glass
 * imitation.
 */
object VoiceCallSurfaceDefaults {
    /** The composer capsule, the tray and the call's cards. */
    val color: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** `UIColor.tertiarySystemFill`: Mute's soft disc. */
    val tertiaryFill: Color
        @Composable @ReadOnlyComposable get() =
            Color(0.4627f, 0.4627f, 0.502f, if (OpenCodeTheme.colors.isDark) 0.24f else 0.12f)

    /** `UIColor.systemOrange`: needs input, a weak connection, the failure's title. */
    val orange: Color
        @Composable @ReadOnlyComposable get() =
            if (OpenCodeTheme.colors.isDark) Color(1f, 0.624f, 0.039f) else Color(1f, 0.584f, 0f)

    /** `UIColor.systemRed`: End. */
    val red: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.destructive
}

/** A call surface: tonal fill with a soft shadow (iOS `openCodeGlass(in:)`). */
fun Modifier.callSurface(shape: Shape, color: Color): Modifier = graphicsLayer {
    this.shape = shape
    shadowElevation = 3.dp.toPx()
    clip = false
}.background(color, shape)

/** Animated geometry is observed by the layer/draw scopes, never by composition. */
private fun Modifier.callSurface(shape: () -> Shape, color: Color): Modifier = graphicsLayer {
    this.shape = shape()
    shadowElevation = 3.dp.toPx()
    clip = false
}.drawBehind {
    drawOutline(shape().createOutline(size, layoutDirection, this), color)
}

/** Touches on this element and its children are dropped (iOS `.allowsHitTesting(false)` over content that shouldn't act). */
internal fun Modifier.blocksTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * The call in the session's composer slot. Session places it where the composer goes while a
 * call holds the composer (iOS `SessionView` + `composerCapsule`): the failure card
 * ([VoiceCallFailure]) or the dropped-call card ([VoiceCallEnded]) when the phase shows a card,
 * otherwise the call bar ([VoiceCallComposer]) on its surface ([VoiceCallSurface]), whose
 * transcript grows up out of it. Sessions that put the composer and the call in one capsule
 * use [VoiceCallSurface] and [VoiceCallComposer] directly.
 *
 * Its appearance starts the call once (`.startIfNeeded`); only Try Again and Rejoin start it
 * again.
 */
@Composable
fun VoiceView(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    // Once per call: the call keeps running while the drawer swaps sessions.
    LaunchedEffect(store) { store.send(VoiceFeature.Action.StartIfNeeded) }
    val phase by store.observe { it.phase }
    when (val current = phase) {
        is VoiceFeature.State.Phase.Failed -> VoiceCallFailure(
            store = store,
            message = current.message,
            modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        )
        VoiceFeature.State.Phase.Dropped -> VoiceCallEnded(store, modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
        else -> VoiceCallSurface(store, modifier) { VoiceCallComposer(store) }
    }
}

/**
 * A live call takes the composer's place: the dot scope fills the capsule behind Mute and End.
 * Tapping it grows the capsule's surface up into the call's transcript ([VoiceCallSurface]);
 * the bar itself never moves or changes height. The host supplies the surface, shared with
 * the composer, so one capsule morphs between them; the scope scans in while the call
 * connects and sweeps out on End.
 */
@Composable
fun VoiceCallComposer(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    val isTerminal by store.observe { it.phase.isTerminal }
    val isMuted by store.observe { it.isMuted }
    val showsPoorConnection by store.observe { it.showsPoorConnection }
    val scopeStage by store.observe { it.scopeStage }
    val isLive = scopeStage == DotScopeSimulation.Stage.live
    val reduceMotion = rememberReduceMotion()
    // A weak connection quiets the scope so its note reads cleanly.
    val scopeAlpha by animateFloatAsState(
        if (showsPoorConnection) 0.25f else 1f,
        if (reduceMotion) Motion.reducedFadeSpec() else MotionSpring(0.4).spec(),
        label = "voiceScopePoor",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(CallTrayComposerMetrics.barHeight.dp)
            .clip(RoundedCornerShape(CallTrayComposerMetrics.cornerRadius.dp))
            // An ended call is only sweeping out.
            .then(if (isTerminal) Modifier.blocksTouches() else Modifier),
    ) {
        DotScopeView(DotScopeMode.Call(scopeStage), Modifier.fillMaxSize().graphicsLayer { alpha = scopeAlpha })
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = CallTrayComposerMetrics.innerHorizontal.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val muteLabel = if (isMuted) "Unmute" else "Mute"
            Box(
                Modifier
                    .then(if (isLive) Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) { store.send(VoiceFeature.Action.MuteTapped) } else Modifier)
                    .openCodeTapTarget()
                    .semantics { contentDescription = muteLabel; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                // A soft disc balances End's red circle on the other side.
                Box(
                    Modifier
                        .size(44.dp)
                        .background(VoiceCallSurfaceDefaults.tertiaryFill, CircleShape)
                        .graphicsLayer { alpha = if (isLive) 1f else 0.4f },
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(if (isMuted) SFSymbol.MicSlashFill else SFSymbol.MicFill, null, tint = OpenCodeTheme.colors.text, size = 22.dp)
                }
            }
            VoiceCallCenterButton(store = store, canToggleCaptions = true, animation = if (reduceMotion) Motion.reducedFadeSpec() else MotionSpring(0.25).spec())
            Box(
                Modifier
                    .clickable(interactionSource = null, indication = null, role = Role.Button) { store.send(VoiceFeature.Action.EndPressed) }
                    .openCodeTapTarget()
                    .semantics { contentDescription = "End voice call"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp).background(VoiceCallSurfaceDefaults.red, CircleShape), contentAlignment = Alignment.Center) {
                    OpenCodeIcon(SFSymbol.PhoneDownFill, null, tint = Color.White, size = 22.dp)
                }
            }
        }
    }
}

/**
 * Something the composer's own surface grows into, above the bar (the model list). Its
 * content is laid out once at the full open height, its top riding the surface's top edge
 * like a sheet's, while the bar's own contents fade out beneath it. It can rest at more than
 * one height (detents): a drag moves the surface with the finger, and settling springs it to
 * its next rest with the finger's speed. Composer builds it; [VoiceCallSurface] draws it.
 *
 * @property isOpen grown, or growing.
 * @property isShown drawn: open, or shrinking back into the bar.
 * @property extent how far above the bar the surface reaches when open, as it is now: its rest,
 *   or under a finger (dp).
 * @property fullExtent the tallest it can rest at: the content is laid out at this height.
 * @property isTracking a finger moves it: the surface follows at once.
 * @property settle bumped as it settles to a rest (a drag let go, a detent chosen), so the
 *   surface springs there, from [settleVelocity] (dp a second, up).
 * @property drawn where the surface is drawn, frame by frame: a drag that begins mid-spring
 *   starts from there, so a finger catches the moving surface.
 * @property reach how far the surface reaches, held by whoever opens it, so the opening can
 *   start it in its own frame instead of waiting for the surface to notice.
 * @property ridesKeyboard opening while the keyboard leaves, or folding while it comes back,
 *   follows the keyboard's motion rather than the detent spring.
 */
data class ComposerExpansion(
    val isOpen: Boolean,
    val isShown: Boolean,
    val extent: Float,
    val fullExtent: Float,
    val content: @Composable () -> Unit,
    val onDismiss: () -> Unit,
    val isTracking: Boolean = false,
    val settle: Int = 0,
    val settleVelocity: Float = 0f,
    val drawn: ComposerExpansionDrawn? = null,
    val reach: ComposerExpansionReach? = null,
    val ridesKeyboard: Boolean = false,
) {
    companion object {
        /** Between rests, like a sheet's detents: quick, barely bouncing (`modelPicker.glass.rest`). */
        val detentSpring = MotionSpring(0.42, 0.06)

        /**
         * The surface growing out of the bar and shrinking back, like the call's transcript:
         * unbouncy, quicker to close (`modelPicker.glass.open` / `.close`). Under Reduce Motion
         * the surface doesn't grow (`null`): it cross-fades.
         */
        fun animation(opening: Boolean, reduceMotion: Boolean): MotionSpring? {
            if (reduceMotion) return null
            return if (opening) MotionSpring(0.42, 0.1) else MotionSpring(0.3, 0.0)
        }

        /**
         * How far above the bar the surface has to be for the bar's own row to show (below it)
         * or the expansion's content (above it). Both are read from the surface's reach as
         * drawn, frame by frame, so the two never show at once. The ranges overlap a little
         * (the content arriving out of a blur), so the surface is never empty between them.
         * (`modelPicker.reveal.barFadeStart/End`, `contentFadeStart/End`.)
         */
        val barFade: ClosedFloatingPointRange<Float> = range(10f, 50f)
        val contentFade: ClosedFloatingPointRange<Float> = range(34f, 150f)

        /** About how long the opening or closing spring takes to settle (`modelPicker.glass.settle`). */
        const val settle = 0.6

        /** `modelPicker.reveal.blur`. */
        const val revealBlur = 8f

        /** `modelPicker.glass.dimDark` / `dimLight`. */
        const val dimDark = 0.35f
        const val dimLight = 0.18f

        private fun range(start: Float, end: Float) = min(start, end)..max(max(start, end), min(start, end) + 1)
    }
}

/** How far an expansion's surface reaches (the value it animates to), held by the opener. */
class ComposerExpansionReach {
    val animatable = Animatable(0f)
    val value: Float get() = animatable.value
}

/** How far an expansion's surface reaches as drawn this frame ([ComposerExpansion.drawn]). */
class ComposerExpansionDrawn {
    var reach: Float = 0f
    private val pending = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Runs once, just after the frame where the surface first draws growth: work that would hold that frame back waits for it. */
    fun afterFirstGrowth(work: () -> Unit) {
        pending += work
    }

    fun cancelPending() {
        pending.clear()
    }

    internal fun grew(reach: Float) {
        if (reach <= 0.5f || pending.isEmpty()) return
        val work = pending.toList()
        pending.clear()
        // Read inside a draw: run after it.
        main.post { work.forEach { it() } }
    }
}

/** Opacity (and an arrival blur) read from the surface's reach as it animates, in the layer: every frame gets its own value. */
private fun Modifier.reachFade(
    reach: () -> Float,
    range: ClosedFloatingPointRange<Float>,
    showsAbove: Boolean,
    blurs: Boolean = false,
    minimum: Float = 0f,
): Modifier = graphicsLayer {
    val t = ((reach() - range.start) / max(range.endInclusive - range.start, 1f)).coerceIn(0f, 1f)
    val shown = if (showsAbove) t else 1 - t
    // No blur on content all but gone or all but whole: it costs a full pass and shows nothing.
    val radius = if (blurs && shown > 0.04f && shown < 0.98f) (1 - shown) * ComposerExpansion.revealBlur else 0f
    renderEffect = if (radius > 0f) BlurEffect(radius * density, radius * density, TileMode.Decal) else null
    alpha = max(shown, minimum)
}

/** Unbouncy, and quicker to close than to open; springs retarget smoothly when toggled mid-flight. */
internal fun voiceSurfaceAnimation(expanding: Boolean): MotionSpring =
    if (expanding) MotionSpring.fromResponse(0.36, 0.9) else MotionSpring.fromResponse(0.26, 1.0)

/**
 * The composer capsule's surface (iOS `voiceCallSurface(_:panel:…)`). During a call with
 * captions open it grows up out of the bar into one surface holding the transcript, drawn above
 * the capsule's frame so the layout around it never changes; it measures as [content] (the bar)
 * alone. With no call ([store] `null`) the same surface grows for a pending request's [panel]
 * (D-31 B) or an [expansion] (the model list): one shape, never a second surface.
 *
 * Only the reach shows: unrevealed room above the bar is clipped from both drawing and touches
 * ([TopInsetRect]), so it passes touches through to the transcript beneath. Unbouncy spring,
 * quicker closing, retargeting on rapid toggles; Reduce Motion cross-fades. The transcript stays
 * mounted a second after closing. Everything above an open transcript dismisses it.
 *
 * @param panel stands on the bar at its own height; [panelExtent] is the room above the bar it's
 *   laid out in, held steady while the surface grows or folds, and [panelReveal] is how far up
 *   the surface and the panel show, which only draws, so nothing is laid out again mid-fold.
 * @param panelShown the panel is up: the surface is the full surface.
 * @param controlsTakeTouches the composer is open for typing: its controls take the touches.
 * @param panelHidden something over the composer (the Living Orb's card) hides the panel from
 *   TalkBack too.
 */
@Composable
fun VoiceCallSurface(
    store: VoiceStoreOf?,
    modifier: Modifier = Modifier,
    panel: (@Composable () -> Unit)? = null,
    panelExtent: () -> Float = { 0f },
    panelReveal: () -> Float = { 0f },
    panelShown: Boolean = false,
    expansion: ComposerExpansion? = null,
    @Suppress("UNUSED_PARAMETER") controlsTakeTouches: Boolean = false,
    panelHidden: Boolean = false,
    content: @Composable () -> Unit,
) {
    val panelExtentValue = { if (store == null) panelExtent() else 0f }
    val panelRevealValue = { if (store == null) panelReveal() else 0f }
    val panelIsShown = store == null && panelShown
    val activeExpansion = if (store == null) expansion else null
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark
    val hosted = LocalComposerGlassHostedByCallTray.current
    val merged = LocalComposerGlassMergedInCallTray.current
    val fontScale = LocalDensity.current.fontScale
    val isExpanded = store.watch { it.showsCaptions } == true
    // @ScaledMetric(relativeTo: .body) 176, capped at 300.
    val height = min(176f * fontScale, 300f)
    var isLingering by remember { mutableStateOf(false) }
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            isLingering = true
        } else if (isLingering) {
            // Kept through the collapse, so toggling again mid-flight retargets the same transcript.
            delay(1_000)
            isLingering = false
        }
    }
    val callExtentTarget = if (isExpanded || (store != null && reduceMotion && isLingering)) height else 0f
    val callExtent by animateFloatAsState(
        callExtentTarget,
        if (reduceMotion) snap() else voiceSurfaceAnimation(isExpanded).spec(),
        label = "voiceSurfaceExtent",
    )

    // The expansion's reach, animated here unless the opener holds it.
    val localReach = remember { ComposerExpansionReach() }
    var lastExpansionReach by remember { mutableStateOf<ComposerExpansionReach?>(null) }
    if (activeExpansion?.reach != null && lastExpansionReach !== activeExpansion.reach) lastExpansionReach = activeExpansion.reach
    val reachBox = activeExpansion?.reach ?: lastExpansionReach ?: localReach
    val expansionOpen = activeExpansion?.isOpen == true
    val expansionTarget = if (activeExpansion != null && (expansionOpen || (reduceMotion && activeExpansion.isShown))) activeExpansion.extent else 0f
    val motion = remember { ExpansionMotion() }
    val currentExpansion by rememberUpdatedState(activeExpansion)
    LaunchedEffect(reachBox, expansionTarget, activeExpansion?.settle, activeExpansion?.isTracking) {
        val old = motion.previousTarget
        motion.previousTarget = expansionTarget
        val target = expansionTarget
        val reach = reachBox.animatable
        val exp = currentExpansion
        // Opening and closing ride the expansion's spring; a reach that only follows the bar
        // changes with it, unless the spring is still running: then it retargets the same
        // spring, which a plain change would cut short with a jump.
        val toggled = (old == 0f) != (target == 0f)
        // Under a finger it follows, even down to the bar.
        if (exp != null && exp.isOpen && exp.isTracking) return@LaunchedEffect reach.snapTo(target)
        if (exp?.ridesKeyboard == true) {
            // Android: the keyboard's motion can't be shared; the reach follows on a spring of its length.
            if (reduceMotion) reach.snapTo(target) else reach.animateTo(target, MotionSpring(0.25).spec())
            return@LaunchedEffect
        }
        if (exp != null && !toggled && exp.isOpen) {
            // Anything else that moves an open surface (a rest chosen, a finger let go, the bar
            // moving under it) springs there, never jumps; a settle carries the finger's speed.
            var velocity = 0f
            if (exp.settle != motion.settle) {
                motion.settle = exp.settle
                val distance = target - reach.value
                velocity = if (abs(distance) > 1) (exp.settleVelocity / distance).coerceIn(-12f, 12f) * distance else 0f
            }
            if (reduceMotion) return@LaunchedEffect reach.snapTo(target)
            reach.animateTo(target, ComposerExpansion.detentSpring.spec(), initialVelocity = velocity)
            return@LaunchedEffect
        }
        val animation = if (exp == null) null else ComposerExpansion.animation(opening = target > 0, reduceMotion = reduceMotion)
        val now = System.nanoTime() / 1e9
        if (animation != null && (toggled || now < motion.motionEnds)) {
            if (toggled) motion.motionEnds = now + ComposerExpansion.settle
            reach.animateTo(target, animation.spec())
        } else {
            reach.snapTo(target)
        }
    }
    LaunchedEffect(activeExpansion == null) {
        // Gone (a reset, or mid-fold): the surface goes with it.
        if (activeExpansion != null) return@LaunchedEffect
        if (reachBox.value == 0f && System.nanoTime() / 1e9 >= motion.motionEnds) return@LaunchedEffect
        motion.motionEnds = Double.NEGATIVE_INFINITY
        reachBox.animatable.snapTo(0f)
    }
    val expansionReach = reachBox.value
    val expanding = expansionReach > 0 || expansionOpen
    val extent = { if (store == null) max(panelExtentValue(), expansionReach) else callExtent }
    val surfaceExpanded = if (store == null) panelIsShown || expansionOpen else isExpanded
    // The room above the bar the surface doesn't reach yet (or any more).
    val unrevealed = { if (store == null && !expanding) max(0f, extent() - panelRevealValue()) else 0f }
    val reducedReach by animateFloatAsState(if (expansionOpen) 1_000f else 0f, Motion.reducedFadeSpec(), label = "reducedReach")
    val dim by animateFloatAsState(
        if (expansionOpen) (if (isDark) ComposerExpansion.dimDark else ComposerExpansion.dimLight) else 0f,
        tween(250, easing = Motion.EaseInOut),
        label = "expansionDim",
    )
    val surfaceColor = VoiceCallSurfaceDefaults.color
    val cornerRadius = CallTrayComposerMetrics.cornerRadius
    val showsExpansion = activeExpansion?.isShown == true || (activeExpansion != null && expansionReach > 0)
    val glassFullAlpha by animateFloatAsState(if (surfaceExpanded) 1f else 0f, Motion.reducedFadeSpec(), label = "surfaceFull")

    Layout(
        modifier = modifier,
        content = {
            if (store != null && isExpanded) {
                // Everything above the surface dismisses it.
                Box(Modifier.layoutId(SurfaceSlot.backdrop)) { VoiceCaptionsDismissBackdrop(store) }
            }
            if (activeExpansion != null && store == null) {
                // Everything outside the expansion, above and below the bar, dims a little and dismisses it.
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.backdrop)
                        .wrapContentSize(unbounded = true)
                        .requiredSize(4_000.dp)
                        .background(Color.Black.copy(alpha = dim))
                        .then(if (expansionOpen) Modifier.clickable(interactionSource = null, indication = null) { activeExpansion.onDismiss() } else Modifier)
                        .excludedFromDrawerPan(expansionOpen)
                        .semantics { hideFromAccessibility() },
                )
            }
            // The surface: one shape that grows and shrinks; under Reduce Motion the bar's and the
            // full surface's cross-fade instead.
            Box(Modifier.layoutId(SurfaceSlot.glass)) {
                if (!(hosted && merged)) {
                    if (reduceMotion) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = 1f - glassFullAlpha }
                                .callSurface({ TopInsetRect(extent(), cornerRadius) }, surfaceColor),
                        )
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = glassFullAlpha }.callSurface(RoundedCornerShape(cornerRadius.dp), surfaceColor))
                    } else {
                        Box(Modifier.fillMaxSize().callSurface({ TopInsetRect(unrevealed(), cornerRadius) }, surfaceColor))
                    }
                }
            }
            // The surface reveals the transcript from the bar up as it grows.
            Box(
                Modifier
                    .layoutId(SurfaceSlot.transcript)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .then(if (isExpanded) Modifier else Modifier.blocksTouches()),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (store != null && (isExpanded || isLingering)) {
                    VoiceCallTranscript(
                        store = store,
                        isExpanded = isExpanded,
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).height(height.dp),
                    )
                }
            }
            Box(
                Modifier
                    .layoutId(SurfaceSlot.content)
                    // Under an expansion the bar's row fades with the surface's reach, so it only
                    // shows once the surface has nearly settled back onto it.
                    .reachFade(reach = { if (reduceMotion) reducedReach else reachBox.value }, range = ComposerExpansion.barFade, showsAbove = false)
                    .then(if (expansionOpen) Modifier.clearAndSetSemantics {} else Modifier),
            ) { content() }
            if (panel != null && store == null) {
                // A request's panel sits on the bar, its position fixed there; only the clip (the
                // surface's reach) grows and folds, so the panel never moves under it. Laid out
                // even before the surface reveals any of it, so its height is known to grow to.
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.panel)
                        .graphicsLayer {
                            shape = BottomRevealShape(min(panelRevealValue(), extent()), 28f)
                            clip = true
                        }
                        .then(if (panelIsShown) Modifier else Modifier.blocksTouches())
                        .then(if (panelHidden) Modifier.clearAndSetSemantics {} else Modifier),
                ) { panel() }
            }
            if (activeExpansion != null && showsExpansion) {
                // The surface's own shape clips it, revealing it from the bar up.
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.expansion)
                        .clip(RoundedCornerShape(28.dp))
                        .drawWithContent {
                            // Records the reach as each frame of its animation draws.
                            val drawn = activeExpansion.drawn
                            if (drawn != null) {
                                drawn.reach = reachBox.value
                                drawn.grew(reachBox.value)
                            }
                            drawContent()
                        }
                        .then(if (!expansionOpen) Modifier.clearAndSetSemantics {} else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .wrapContentSize(Alignment.TopCenter, unbounded = true)
                            .reachFade(
                                reach = { if (reduceMotion) reducedReach else reachBox.value },
                                range = ComposerExpansion.contentFade,
                                showsAbove = true,
                                blurs = !reduceMotion,
                                // A fully faded view keeps a trace under a finger, so the drag on its grabber lasts.
                                minimum = if (activeExpansion.isTracking) 0.02f else 0f,
                            ),
                    ) { activeExpansion.content() }
                }
            }
        },
        measurePolicy = surfaceMeasurePolicy(extent = extent, fullExtent = activeExpansion?.fullExtent ?: 0f, panelExtent = panelExtentValue, transcriptHeight = height),
    )
}

private enum class SurfaceSlot { backdrop, glass, transcript, content, panel, expansion }

private class ExpansionMotion {
    var previousTarget = 0f
    var settle = 0
    var motionEnds = Double.NEGATIVE_INFINITY
}

/** Laid out as the bar alone: what grows above it never moves the session's insets. */
private fun surfaceMeasurePolicy(extent: () -> Float, fullExtent: Float, panelExtent: () -> Float, transcriptHeight: Float) = MeasurePolicy { measurables, constraints ->
    val bar = measurables.first { it.layoutId == SurfaceSlot.content }.measure(constraints.copy(minHeight = 0))
    val width = bar.width
    // A self-animating panel reports its current height during measurement. Read the
    // surface extent afterwards so its outline does not trail that panel by one frame.
    val panel = measurables.firstOrNull { it.layoutId == SurfaceSlot.panel }
        ?.measure(Constraints(minWidth = width, maxWidth = width))
    val extentPx = extent().dp.roundToPx()
    val placeables = measurables.filter { it.layoutId != SurfaceSlot.content }.map { measurable ->
        val c = when (measurable.layoutId) {
            SurfaceSlot.glass -> Constraints.fixed(width, bar.height + extentPx)
            SurfaceSlot.transcript -> Constraints.fixed(width, extentPx)
            // At its own height (it caps itself): [panelExtent] is the room the session keeps for it.
            SurfaceSlot.panel -> Constraints(minWidth = width, maxWidth = width)
            SurfaceSlot.expansion -> Constraints.fixed(width, bar.height + extentPx)
            else -> Constraints()
        }
        measurable.layoutId to (if (measurable.layoutId == SurfaceSlot.panel) panel!! else measurable.measure(c))
    }
    val expansionHeight = fullExtent.dp.roundToPx() + bar.height
    layout(width, bar.height) {
        for ((id, placeable) in placeables) {
            when (id) {
                SurfaceSlot.backdrop -> placeable.place((width - placeable.width) / 2, (bar.height - placeable.height) / 2)
                SurfaceSlot.glass -> placeable.place(0, -extentPx)
                SurfaceSlot.transcript -> placeable.place(0, -extentPx)
                else -> Unit
            }
        }
        bar.place(0, 0)
        for ((id, placeable) in placeables) {
            when (id) {
                // No height of its own: it stands up from the bar's top edge.
                SurfaceSlot.panel -> placeable.place(0, -placeable.height)
                SurfaceSlot.expansion -> placeable.place(0, -extentPx)
                else -> Unit
            }
        }
        @Suppress("UNUSED_EXPRESSION") expansionHeight
        @Suppress("UNUSED_EXPRESSION") transcriptHeight
        @Suppress("UNUSED_EXPRESSION") panelExtent
    }
}

/**
 * A rounded rectangle whose top edge sits [inset] dp below its frame's: the surface growing or
 * folding over a panel without anything being laid out. Used to clip, it also passes touches in
 * the unrevealed room through.
 */
class TopInsetRect(private val inset: Float, private val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val top = min(inset * density.density, size.height)
        val radius = min(cornerRadius * density.density, min(size.width, size.height - top) / 2).coerceAtLeast(0f)
        return Outline.Rounded(RoundRect(0f, top, size.width, size.height, CornerRadius(radius)))
    }

    override fun equals(other: Any?) = other is TopInsetRect && other.inset == inset && other.cornerRadius == cornerRadius
    override fun hashCode() = inset.hashCode() * 31 + cornerRadius.hashCode()
}

/** The bottom [height] dp of a frame, its top corners rounded: a panel revealed from the bar up. */
private class BottomRevealShape(private val height: Float, private val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = min(height * density.density, size.height)
        val top = size.height - h
        val r = min(cornerRadius * density.density, h / 2).coerceAtLeast(0f)
        // Below the fold's rounded edge the sides stay open: a panel's control reaching past its
        // sides onto the surface's corner arc (the request panel's ×) isn't cut in half.
        val path = Path().apply {
            addRoundRect(RoundRect(0f, top, size.width, size.height, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
            if (h > r) addRect(Rect(-size.width, top + r, size.width * 2, size.height))
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is BottomRevealShape && other.height == height && other.cornerRadius == cornerRadius
    override fun hashCode() = height.hashCode() * 31 + cornerRadius.hashCode()
}

/**
 * What sits above the call bar keeps its place but hides while the call's transcript covers
 * it, so it neither shows through the surface nor stays reachable by TalkBack or touch.
 */
fun Modifier.hiddenUnderVoiceTranscript(isHidden: Boolean): Modifier = this then HiddenElement(isHidden, HiddenKind.voiceTranscript)

/**
 * What sits above the composer (the pills, the background-work capsule, a draft's projects)
 * hides while a panel grown out of the composer (the model list, the level picker) stands over
 * it: gone as the surface grows over it, and back only once the surface has shrunk below it.
 */
fun Modifier.hiddenUnderComposerPanel(isHidden: Boolean): Modifier = this then HiddenElement(isHidden, HiddenKind.composerPanel)

/**
 * The composer itself, while the Living Orb's card stands over the screen's foot (iOS
 * `hiddenUnderFloatingCall`): it fades on the composer panel's curves and stops taking touches.
 */
fun Modifier.hiddenUnderFloatingCall(isHidden: Boolean): Modifier = this then HiddenElement(isHidden, HiddenKind.floatingCall)

@Suppress("EnumEntryName")
private enum class HiddenKind { voiceTranscript, composerPanel, floatingCall }

private data class HiddenElement(val isHidden: Boolean, val kind: HiddenKind) : ModifierNodeElement<HiddenNode>() {
    override fun create() = HiddenNode(isHidden, kind)
    override fun update(node: HiddenNode) = node.update(isHidden, kind)
    override fun InspectorInfo.inspectableProperties() {
        name = when (kind) {
            HiddenKind.voiceTranscript -> "hiddenUnderVoiceTranscript"
            HiddenKind.composerPanel -> "hiddenUnderComposerPanel"
            HiddenKind.floatingCall -> "hiddenUnderFloatingCall"
        }
        properties["isHidden"] = isHidden
    }
}

private class HiddenNode(var isHidden: Boolean, var kind: HiddenKind) :
    Modifier.Node(), LayoutModifierNode, SemanticsModifierNode, PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private val opacity = Animatable(if (isHidden) 0f else 1f)

    fun update(hidden: Boolean, newKind: HiddenKind) {
        kind = newKind
        if (hidden == isHidden) return
        isHidden = hidden
        invalidateSemantics()
        val spec = spec(hidden)
        coroutineScope.launch { opacity.animateTo(if (hidden) 0f else 1f, spec) }
    }

    private fun spec(hiding: Boolean): AnimationSpec<Float> {
        val context = currentValueOf(LocalContext)
        val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reduceMotion) return Motion.reducedFadeSpec()
        return when (kind) {
            // Opening, the surface reaches the pill row within a few frames, so what's there fades
            // out about as fast; closing, the surface passes back below it 0.15 s into its fold.
            HiddenKind.composerPanel, HiddenKind.floatingCall -> if (hiding) tween(140, easing = Motion.EaseOut) else tween(200, delayMillis = 150, easing = Motion.EaseInOut)
            HiddenKind.voiceTranscript -> voiceSurfaceAnimation(expanding = hiding).spec()
        }
    }

    override fun androidx.compose.ui.layout.MeasureScope.measure(measurable: androidx.compose.ui.layout.Measurable, constraints: Constraints): androidx.compose.ui.layout.MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { alpha = opacity.value }
        }
    }

    override val shouldClearDescendantSemantics: Boolean get() = isHidden

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (isHidden) hideFromAccessibility()
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (isHidden && pass == PointerEventPass.Initial) pointerEvent.changes.forEach { it.consume() }
    }

    override fun onCancelPointerInput() = Unit
}

/**
 * The surface above the bar: the call's captions, with its duration in the top trailing corner.
 * Fades and rises in as the surface grows, and out as it shrinks; under Reduce Motion it only
 * fades. TalkBack's dismiss action closes it.
 *
 * @param animation the spec its fade follows for a given shown state (the tray's springs); the
 *   surface's own springs by default.
 */
@Composable
fun VoiceCallTranscript(
    store: VoiceStoreOf,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    animation: ((Boolean) -> FiniteAnimationSpec<Float>)? = null,
) {
    val reduceMotion = rememberReduceMotion()
    var isMounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isMounted = true }
    val isShown = isExpanded && isMounted
    val spec: FiniteAnimationSpec<Float> = if (reduceMotion) Motion.reducedFadeSpec() else animation?.invoke(isShown) ?: voiceSurfaceAnimation(isShown).spec()
    val shown by animateFloatAsState(if (isShown) 1f else 0f, spec, label = "voiceTranscriptShown")
    val connectedAt by store.observe { it.connectedAt }
    var durationSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .graphicsLayer {
                alpha = shown
                translationY = if (reduceMotion) 0f else (1 - shown) * 10.dp.toPx()
            }
            .semantics {
                dismiss { store.send(VoiceFeature.Action.CaptionsDismissed); true }
            },
    ) {
        VoiceCaptions(
            store = store,
            isVisible = isExpanded,
            modifier = Modifier
                .fillMaxSize()
                // Lines scrolled under the duration fade out around it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (connectedAt != null && durationSize.width > 0) {
                        val fadeLeading = 40.dp.toPx()
                        val fadeBottom = 10.dp.toPx()
                        val w = durationInsetTrailing.dp.toPx() + durationSize.width + 6.dp.toPx() + fadeLeading
                        val h = durationInsetTop.dp.toPx() + durationSize.height + 2.dp.toPx() + fadeBottom
                        val left = size.width - w
                        val horizontal = android.graphics.LinearGradient(left, 0f, left + fadeLeading, 0f, 0, android.graphics.Color.BLACK, android.graphics.Shader.TileMode.CLAMP)
                        val vertical = android.graphics.LinearGradient(0f, h - fadeBottom, 0f, h, android.graphics.Color.BLACK, 0, android.graphics.Shader.TileMode.CLAMP)
                        val combined = android.graphics.ComposeShader(horizontal, vertical, android.graphics.BlendMode.DST_IN)
                        drawRect(ShaderBrush(combined), topLeft = Offset(left, 0f), size = Size(w, h), blendMode = BlendMode.DstOut)
                    }
                },
        )
        connectedAt?.let { since ->
            CallDuration(
                since = since,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = durationInsetTop.dp, end = durationInsetTrailing.dp)
                    .onSizeChanged { durationSize = it },
            )
        }
    }
}

private const val durationInsetTop = 10f
private const val durationInsetTrailing = 18f

/** How long the call has been connected, as “2:31”. */
@Composable
private fun CallDuration(since: Instant, modifier: Modifier = Modifier) {
    val date = remember { dependency(DateClient) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(since) {
        while (true) {
            val now = date.now()
            val millis = Duration.between(since, now).toMillis()
            elapsed = max(0L, millis / 1000)
            // Next whole second from `since`.
            delay(1_000 - Math.floorMod(millis, 1_000L))
        }
    }
    val text = formatCallDuration(elapsed)
    val spoken = spokenCallDuration(elapsed)
    Text(
        text,
        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
        color = OpenCodeTheme.colors.secondaryText,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Call duration"
            stateDescription = spoken
        },
    )
}

/** `Duration.formatted(.time(pattern: .minuteSecond))`, or `.hourMinuteSecond` from an hour. */
internal fun formatCallDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (seconds >= 3600) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(seconds / 60, s)
}

/** `Duration.formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))`: “2 minutes, 31 seconds”. */
internal fun spokenCallDuration(seconds: Long): String {
    val parts = mutableListOf<String>()
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun unit(n: Long, name: String) = "$n $name${if (n == 1L) "" else "s"}"
    if (h > 0) parts += unit(h, "hour")
    if (m > 0) parts += unit(m, "minute")
    if (s > 0 || parts.isEmpty()) parts += unit(s, "second")
    return parts.joinToString(", ")
}

/** Lays out [wide] when it fits the width, else [narrow] (SwiftUI `ViewThatFits(in: .horizontal)`). */
@Composable
private fun ViewThatFitsHorizontally(modifier: Modifier = Modifier, wide: @Composable () -> Unit, narrow: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val wideMeasurables = subcompose("wide", wide)
        val intrinsic = wideMeasurables.sumOf { it.maxIntrinsicWidth(constraints.maxHeight.coerceAtMost(Constraints.Infinity - 1)) }
        val fits = constraints.maxWidth == Constraints.Infinity || intrinsic <= constraints.maxWidth
        val chosen = if (fits) wideMeasurables else subcompose("narrow", narrow)
        val placeables = chosen.map { it.measure(constraints.copy(minHeight = 0)) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), h.coerceAtLeast(constraints.minHeight)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * Shown above the composer when the connection was lost and couldn't be restored. Rejoin
 * starts a new call that remembers this one.
 */
@Composable
fun VoiceCallEnded(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .fillMaxWidth()
            .callSurface(shape, VoiceCallSurfaceDefaults.color)
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        val title = @Composable { Text("Call Ended", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)) }
        val actions = @Composable {
            OpenCodeSecondaryButton("Close", onClick = { store.send(VoiceFeature.Action.EndPressed) })
            OpenCodePrimaryButton("Rejoin", onClick = { store.send(VoiceFeature.Action.RejoinTapped) })
        }
        ViewThatFitsHorizontally(
            wide = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    title()
                    Box(Modifier.weight(1f))
                    actions()
                }
            },
            narrow = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    title()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
                }
            },
        )
    }
}

/**
 * Shown above the composer when the call couldn't connect. With Server Voice, the provider that
 * couldn't start it, one sentence and one fix ([VoiceProviderFailureCard]); otherwise the error as
 * it came, with Check API Key (or Open Settings when the microphone was denied) and Try Again.
 */
@Composable
fun VoiceCallFailure(store: VoiceStoreOf, message: String, modifier: Modifier = Modifier) {
    val diagnosis by store.observe { it.failureDiagnosis }
    val brand by store.observe { it.callPlace?.brand }
    val current = diagnosis
    if (current != null) {
        VoiceProviderFailureCard(
            diagnosis = current,
            brand = brand,
            onFix = { store.send(VoiceFeature.Action.FailureFixTapped(current.fix)) },
            onClose = { store.send(VoiceFeature.Action.EndPressed) },
            modifier = modifier,
        )
    } else {
        VoiceCallFailurePlainCard(store, message, modifier)
    }
}

@Composable
private fun VoiceCallFailurePlainCard(store: VoiceStoreOf, message: String, modifier: Modifier) {
    val isMicrophoneDenied = message == VoiceAudioError.microphoneDenied.message
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .callSurface(shape, VoiceCallSurfaceDefaults.color)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon(SFSymbol.ExclamationmarkTriangleFill, null, tint = VoiceCallSurfaceDefaults.orange, size = 18.dp)
                Text(
                    "Couldn’t Connect Voice",
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = VoiceCallSurfaceDefaults.orange,
                )
            }
            VoiceFailureCloseButton({ store.send(VoiceFeature.Action.EndPressed) })
        }
        SelectionContainer {
            Text(message, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        }
        val actions = @Composable {
            if (isMicrophoneDenied) {
                OpenCodeSecondaryButton("Open Settings", onClick = { store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.openSettings)) })
            } else {
                OpenCodeSecondaryButton("Check API Key", onClick = { store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.setUpOnIPhone)) })
            }
            OpenCodePrimaryButton("Try Again", onClick = { store.send(VoiceFeature.Action.Start) })
        }
        ViewThatFitsHorizontally(
            wide = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() } },
            narrow = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() } },
        )
    }
}
