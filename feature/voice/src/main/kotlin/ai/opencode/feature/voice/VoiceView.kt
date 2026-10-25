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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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
 *
 * @param onOpenSettings opens the app's system settings (the microphone permission), for the
 *   failure card's Open Settings; the host owns the side effect.
 */
@Composable
fun VoiceView(store: VoiceStoreOf, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    // Once per call: the call keeps running while the drawer swaps sessions.
    LaunchedEffect(store) { store.send(VoiceFeature.Action.StartIfNeeded) }
    val phase by store.observe { it.phase }
    when (val current = phase) {
        is VoiceFeature.State.Phase.Failed -> VoiceCallFailure(
            store = store,
            message = current.message,
            onOpenSettings = onOpenSettings,
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
 */
@Composable
fun VoiceCallSurface(
    store: VoiceStoreOf?,
    modifier: Modifier = Modifier,
    panel: (@Composable () -> Unit)? = null,
    panelExtent: Float = 0f,
    panelReveal: Float = 0f,
    panelShown: Boolean = false,
    expansion: ComposerExpansion? = null,
    @Suppress("UNUSED_PARAMETER") controlsTakeTouches: Boolean = false,
    content: @Composable () -> Unit,
) {
    val panelExtentValue = if (store == null) panelExtent else 0f
    val panelRevealValue = if (store == null) panelReveal else 0f
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
    val extent = if (store == null) max(panelExtentValue, expansionReach) else callExtent
    val surfaceExpanded = if (store == null) panelIsShown || expansionOpen else isExpanded
    // The room above the bar the surface doesn't reach yet (or any more).
    val unrevealed = if (store == null && !expanding) max(0f, extent - panelRevealValue) else 0f
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
                                .callSurface(TopInsetRect(extent, cornerRadius), surfaceColor),
                        )
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = glassFullAlpha }.callSurface(RoundedCornerShape(cornerRadius.dp), surfaceColor))
                    } else {
                        Box(Modifier.fillMaxSize().callSurface(TopInsetRect(unrevealed, cornerRadius), surfaceColor))
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
            if (panel != null && store == null && panelRevealValue > 0) {
                // A request's panel sits on the bar, its position fixed there; only the clip (the
                // surface's reach) grows and folds, so the panel never moves under it.
                val reveal = min(panelRevealValue, extent)
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.panel)
                        .clip(BottomRevealShape(reveal, 28f))
                        .then(if (panelIsShown) Modifier else Modifier.blocksTouches()),
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
private fun surfaceMeasurePolicy(extent: Float, fullExtent: Float, panelExtent: Float, transcriptHeight: Float) = MeasurePolicy { measurables, constraints ->
    val bar = measurables.first { it.layoutId == SurfaceSlot.content }.measure(constraints.copy(minHeight = 0))
    val width = bar.width
    val extentPx = extent.dp.roundToPx()
    val placeables = measurables.filter { it.layoutId != SurfaceSlot.content }.map { measurable ->
        val c = when (measurable.layoutId) {
            SurfaceSlot.glass -> Constraints.fixed(width, bar.height + extentPx)
            SurfaceSlot.transcript -> Constraints.fixed(width, extentPx)
            SurfaceSlot.panel -> Constraints(minWidth = width, maxWidth = width, maxHeight = max(0, panelExtent.dp.roundToPx()))
            SurfaceSlot.expansion -> Constraints.fixed(width, bar.height + extentPx)
            else -> Constraints()
        }
        measurable.layoutId to measurable.measure(c)
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

private enum class HiddenKind { voiceTranscript, composerPanel }

private data class HiddenElement(val isHidden: Boolean, val kind: HiddenKind) : ModifierNodeElement<HiddenNode>() {
    override fun create() = HiddenNode(isHidden, kind)
    override fun update(node: HiddenNode) = node.update(isHidden, kind)
    override fun InspectorInfo.inspectableProperties() {
        name = if (kind == HiddenKind.voiceTranscript) "hiddenUnderVoiceTranscript" else "hiddenUnderComposerPanel"
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
            HiddenKind.composerPanel -> if (hiding) tween(140, easing = Motion.EaseOut) else tween(200, delayMillis = 150, easing = Motion.EaseInOut)
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

/**
 * Which words of each caption line have appeared, and when. New words queue and appear at
 * least [spacing] apart, so a burst of streamed text still flows in word by word. Immutable:
 * each change returns the next reveal. Times are seconds on the monotonic clock.
 */
data class CaptionReveal(val lines: List<Line> = emptyList(), val lastReveal: Double = Double.NEGATIVE_INFINITY) {
    data class Line(
        val id: String,
        val isUser: Boolean,
        val words: List<String>,
        /** Reveal times of the leading words; the rest are still queued. */
        val revealedAt: List<Double>,
        /** Words the pacer may reveal; a live line holds back its unfinished last word. */
        val revealable: Int,
    ) {
        val text: String get() = words.joinToString(" ")
    }

    /**
     * Matches lines by ID (or, when the live reply settles under a new ID, by position and
     * speaker) and keeps the reveal of every unchanged leading word. [revealed] shows
     * everything at once, for opening onto a call in progress.
     */
    fun sync(targets: List<CaptionTarget>, revealed: Boolean = false): CaptionReveal {
        val ids = targets.map { it.id }.toSet()
        val next = targets.mapIndexed { index, target ->
            val words = target.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hasUnfinishedWord = target.isLive && !(target.text.lastOrNull()?.isWhitespace() ?: true)
            val revealable = if (hasUnfinishedWord) max(0, words.size - 1) else words.size
            var previous = lines.firstOrNull { it.id == target.id }
            if (previous == null && index in lines.indices && lines[index].isUser == target.isUser && lines[index].id !in ids) {
                previous = lines[index]
            }
            val revealedAt = when {
                revealed -> List(revealable) { Double.NEGATIVE_INFINITY }
                previous != null -> {
                    val unchanged = previous.words.zip(words).takeWhile { (a, b) -> a == b }.size
                    previous.revealedAt.take(min(unchanged, revealable))
                }
                else -> emptyList()
            }
            Line(id = target.id, isUser = target.isUser, words = words, revealedAt = revealedAt, revealable = revealable)
        }
        return copy(lines = next)
    }

    val hasQueued: Boolean get() = lines.any { it.revealedAt.size < it.revealable }

    /** Seconds until the next queued word may appear, or `null` when none is queued. */
    fun nextRevealDelay(now: Double): Double? = if (hasQueued) max(0.0, lastReveal + spacing - now) else null

    fun revealNext(now: Double): CaptionReveal {
        val index = lines.indexOfFirst { it.revealedAt.size < it.revealable }
        if (index < 0) return this
        val updated = lines.toMutableList()
        updated[index] = updated[index].copy(revealedAt = updated[index].revealedAt + now)
        return copy(lines = updated, lastReveal = now)
    }

    companion object {
        const val spacing = 0.045
    }
}

/** A critically damped follow: when the column jumps by `delta`, it is drawn offset back by that much, and the offset decays exponentially to zero. */
data class CaptionGlide(val value: Float = 0f, val start: Double = 0.0) {
    fun offset(now: Double): Float = value * exp(-max(0.0, now - start) / timeConstant).toFloat()

    fun push(delta: Float, now: Double): CaptionGlide = CaptionGlide(value = offset(now) + delta, start = now)

    /** When the offset falls under a third of a dp. */
    val settlesAt: Double get() = if (abs(value) < 0.3f) start else start + timeConstant * ln(abs(value) / 0.3)

    companion object {
        const val timeConstant = 0.16
    }
}

/** Each word fades in where it lies, out of a light blur (iOS `CaptionWordRenderer`). */
object CaptionWordRenderer {
    const val duration = 0.42

    /** Eased progress of a word revealed [at] by [now] (0 not yet, 1 whole). */
    fun eased(at: Double, now: Double): Float {
        val progress = (now - at) / duration
        if (progress >= 1) return 1f
        if (progress <= 0) return 0f
        return (1 - (1 - progress).pow(3)).toFloat()
    }

    /** The word's blur radius (dp) at [eased]; none under Reduce Motion. */
    fun blur(eased: Float, reduceMotion: Boolean): Float = if (reduceMotion) 0f else (1 - eased) * 3
}

/** Monotonic seconds, the clock captions use (and Compose's frame time). */
private fun captionNow(): Double = System.nanoTime() / 1e9

/**
 * Recent lines of the call, newest at the bottom. The column stays anchored to the bottom and
 * glides when it grows; it follows only while the reader is already at the bottom. Hidden while
 * the surface closes, it shows new words at once and draws nothing frame by frame.
 */
@Composable
private fun VoiceCaptions(store: VoiceStoreOf, isVisible: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val targets by store.observe { it.captionTargets }
    val isAudioInterrupted by store.observe { it.isAudioInterrupted }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Opening onto a call in progress shows what's there from the first frame.
    var reveal by remember { mutableStateOf(CaptionReveal().sync(store.state.captionTargets, revealed = true)) }
    var glide by remember { mutableStateOf(CaptionGlide()) }
    var now by remember { mutableDoubleStateOf(captionNow()) }
    var columnHeight by remember { mutableStateOf<Int?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var isFollowing by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val queueChanged = remember { Channel<Unit>(Channel.CONFLATED) }
    val currentVisible by rememberUpdatedState(isVisible)

    // When the newest word has finished appearing and the glide has settled.
    val animationEnd = if (reduceMotion) reveal.lastReveal + CaptionWordRenderer.duration
    else max(reveal.lastReveal + CaptionWordRenderer.duration, glide.settlesAt)
    val isActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(animationEnd, isVisible, isActive) {
        // Paused when settled, hidden, or in the background; a paused timeline's time is stale,
        // so settled drawing reads the real time.
        now = captionNow()
        if (!animationEnd.isFinite() || !isVisible || !isActive) return@LaunchedEffect
        while (now < animationEnd) withFrameNanos { now = it / 1e9 }
        now = captionNow()
    }

    // Reveals queued words for the call's lifetime, waking when lines change: one long-lived
    // pacer, never keyed on a flag that toggles within one update.
    LaunchedEffect(Unit) {
        queueChanged.trySend(Unit)
        for (signal in queueChanged) {
            while (true) {
                val delayFor = reveal.nextRevealDelay(captionNow()) ?: break
                if (delayFor > 0) delay((delayFor * 1000).roundToInt().toLong().coerceAtLeast(1))
                reveal = reveal.revealNext(captionNow())
            }
        }
    }
    val firstTargets = remember { BooleanFlag() }
    LaunchedEffect(targets) {
        if (!firstTargets.set) {
            firstTargets.set = true
            return@LaunchedEffect
        }
        reveal = reveal.sync(targets, revealed = !currentVisible)
        queueChanged.trySend(Unit)
    }
    val firstVisible = remember { BooleanFlag() }
    LaunchedEffect(isVisible) {
        if (!firstVisible.set) {
            firstVisible.set = true
            return@LaunchedEffect
        }
        if (isVisible) {
            // Opens on the latest lines.
            isFollowing = true
            scrollState.scrollTo(scrollState.maxValue)
        } else {
            reveal = reveal.sync(store.state.captionTargets, revealed = true)
        }
    }
    // Starts at the bottom; only the reader scrolls away from it, a growing column doesn't.
    LaunchedEffect(scrollState) {
        scrollState.scrollTo(scrollState.maxValue)
        var lastMax = scrollState.maxValue
        snapshotFlow { Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress) }.collect { (value, maxValue, scrolling) ->
            val fromBottom = with(density) { (maxValue - value).toDp().value }
            if (fromBottom <= 8f) isFollowing = true
            else if (scrolling && lastMax == maxValue) isFollowing = false
            lastMax = maxValue
        }
    }

    fun columnResized(height: Int) {
        // Empty (before the first sync, or after the call moved sessions): start over, so what
        // appears next shows at once instead of gliding.
        if (reveal.lines.none { it.revealedAt.isNotEmpty() }) {
            columnHeight = null
            glide = CaptionGlide()
            isFollowing = true
            return
        }
        val previous = columnHeight
        columnHeight = height
        if (previous == null || !isFollowing) return
        if (height > previous && !reduceMotion && isVisible) {
            glide = glide.push(with(density) { (height - previous).toDp().value }, captionNow())
        }
        if (height != previous) scope.launch { scrollState.scrollTo(scrollState.maxValue) }
    }

    // Pulling down past the top dismisses, like a sheet.
    val pullConnection = remember(store) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled / density.density > 56f) {
                        pulled = 0f
                        store.send(VoiceFeature.Action.CaptionsDismissed)
                    }
                } else if (available.y <= 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    val lines = reveal.lines.filter { it.revealedAt.isNotEmpty() }
    Box(
        modifier
            .padding(top = 6.dp)
            .onSizeChanged { viewportHeight = it.height }
            // Lines rise in under the top edge rather than look clipped, and fade out before they
            // reach the bar, with no line between them.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val top = 14.dp.toPx()
                val bottom = 16.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        (top / size.height).coerceIn(0f, 1f) to Color.Black,
                        (1 - bottom / size.height).coerceIn(0f, 1f) to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .clickable(interactionSource = null, indication = null) { store.send(VoiceFeature.Action.CaptionsDismissed) }
            .nestedScroll(pullConnection)
            .verticalScroll(scrollState),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = with(density) { viewportHeight.toDp() }),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { columnResized(it.height) }
                    .graphicsLayer { translationY = glide.offset(now) * density.density }
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (lines.isEmpty()) {
                    Text("Say something to your session.", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                }
                // A line takes space from its first word, so lines still queued behind another leave no gap.
                for (line in lines) {
                    androidx.compose.runtime.key(line.id) {
                        CaptionLine(line = line, now = { now }, reduceMotion = reduceMotion)
                    }
                }
                if (isAudioInterrupted) {
                    Text("Audio paused by another app or call", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
    }
}

private class BooleanFlag {
    var set = false
}

/**
 * Both speakers start at the leading edge; your lines are dimmer. No visible speaker labels:
 * TalkBack hears “You:” before yours. A line is laid out as its words appear (one view per
 * word, [CaptionWordFlow]), so an appended word never moves an earlier one.
 */
@Composable
private fun CaptionLine(line: CaptionReveal.Line, now: () -> Double, reduceMotion: Boolean) {
    val style = OpenCodeTextStyles.body
    val measurer = rememberTextMeasurer()
    val spaceWidth = remember(style, measurer) { measurer.measure(" ", style).size.width }
    val color = if (line.isUser) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.text
    val composedAt = captionNow()
    Layout(
        modifier = Modifier.clearAndSetSemantics { contentDescription = if (line.isUser) "You: ${line.text}" else line.text },
        content = {
            for (index in line.revealedAt.indices) {
                val at = line.revealedAt[index]
                // Only words still revealing redraw frame by frame.
                val settled = at + CaptionWordRenderer.duration <= composedAt
                Text(
                    line.words[index],
                    style = style,
                    color = color,
                    modifier = if (settled) Modifier else Modifier.graphicsLayer {
                        val eased = CaptionWordRenderer.eased(at, now())
                        alpha = eased
                        val radius = if (eased < 1f) CaptionWordRenderer.blur(eased, reduceMotion) else 0f
                        renderEffect = if (radius > 0.05f) BlurEffect(radius * density, radius * density, TileMode.Decal) else null
                    },
                )
            }
        },
        measurePolicy = captionWordFlow(spaceWidth),
    )
}

/**
 * Words wrapped greedily, one view each (iOS `CaptionWordFlow`). A paragraph of text avoids
 * leaving one word on its last line by pulling the word before it down, so appending a word
 * there would move the previous one; here a word never moves once placed.
 */
private fun captionWordFlow(spaceWidth: Int) = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    var x = 0
    var rowY = 0
    var rowHeight = 0
    val placed = measurables.map { measurable ->
        var placeable = measurable.measure(Constraints())
        if (placeable.width > width) {
            // A word wider than the line wraps within its own row.
            placeable = measurable.measure(Constraints(maxWidth = width))
        }
        if (x > 0 && x + spaceWidth + placeable.width > width) {
            rowY += rowHeight
            rowHeight = 0
            x = 0
        } else if (x > 0) {
            x += spaceWidth
        }
        val position = IntOffset(x, rowY)
        x += placeable.width
        rowHeight = max(rowHeight, placeable.height)
        placeable to position
    }
    val maxX = placed.maxOfOrNull { (p, pos) -> pos.x + p.width } ?: 0
    val maxY = placed.maxOfOrNull { (p, pos) -> pos.y + p.height } ?: 0
    layout(maxX.coerceIn(constraints.minWidth, constraints.maxWidth), maxY.coerceAtLeast(constraints.minHeight)) {
        for ((p, pos) in placed) p.place(pos.x, pos.y)
    }
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
 * Shown above the composer when the call couldn't connect: Check API Key (or Open Settings when
 * the microphone was denied) and Try Again.
 *
 * @param onOpenSettings opens the app's system settings; the host owns the side effect.
 */
@Composable
fun VoiceCallFailure(store: VoiceStoreOf, message: String, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
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
            Box(
                Modifier
                    .clickable(interactionSource = null, indication = null, role = Role.Button) { store.send(VoiceFeature.Action.EndPressed) }
                    .openCodeTapTarget()
                    .semantics { contentDescription = "Close"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.Xmark, null, tint = OpenCodeTheme.colors.secondaryText, size = 18.dp)
            }
        }
        SelectionContainer {
            Text(message, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        }
        val actions = @Composable {
            if (isMicrophoneDenied) {
                OpenCodeSecondaryButton("Open Settings", onClick = onOpenSettings)
            } else {
                OpenCodeSecondaryButton("Check API Key", onClick = { store.send(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.CheckAPIKey)) })
            }
            OpenCodePrimaryButton("Try Again", onClick = { store.send(VoiceFeature.Action.Start) })
        }
        ViewThatFitsHorizontally(
            wide = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() } },
            narrow = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() } },
        )
    }
}
