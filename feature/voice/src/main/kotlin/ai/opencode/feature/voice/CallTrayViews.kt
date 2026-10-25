package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.Motion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wraps the composer capsule so when `ExperimentalFeature.callTray` is enabled, starting a call
 * dips and lifts the composer while the call tray splits out beneath it (iOS
 * `View.callTrayHost`). [content] (the composer) stays in one structural branch, so pushing
 * or popping a subagent never recreates the composer or its text field.
 *
 * Session hosts the composer capsule in it:
 * ```kotlin
 * CallTrayHost(controller = callTray, store = voiceStore,
 *     isEnabled = usesCallTray && !isCovered, isFocused = messageFieldFocused,
 *     reducedMotion = rememberReduceMotion()) { ComposerCapsule(…) }
 * ```
 * It measures as the composer plus the tray's reserve ([CallTrayTunables.height] +
 * [CallTrayTunables.gap]) while the tray is expanded, animated once on open and once on close
 * with the tray's spring ([callTrayReserveAnimation]); the tray, the lifting call button and
 * the transcript draw outside that frame.
 *
 * @param controller the session's [CallTrayController] (one per session screen), or `null`.
 * @param store the call, or `null` without one.
 * @param isEnabled the Call Tray is on (`experimental(ExperimentalFeature.callTray)`) and the
 *   session isn't covered.
 * @param isFocused the message field holds the keyboard.
 * @param allowsMergedComposerGlass the tray may draw the composer's surface itself
 *   ([LocalComposerGlassMergedInCallTray]).
 * @param isRequestDocked a pending request's panel holds the composer: captions don't toggle.
 * @param onExpandTranscript runs as the transcript opens (Session closes the attachment menu).
 */
@Composable
fun CallTrayHost(
    controller: CallTrayController?,
    store: VoiceStoreOf?,
    isEnabled: Boolean,
    isFocused: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    allowsMergedComposerGlass: Boolean = true,
    isRequestDocked: Boolean = false,
    onExpandTranscript: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isSceneActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val effectiveReducedMotion = reducedMotion || !isSceneActive
    val activeController = if (isEnabled) controller else null
    val scope = rememberCoroutineScope()
    DisposableEffect(controller, scope) {
        controller?.attach(scope)
        controller?.dock?.attach(scope)
        onDispose { controller?.detach(scope) }
    }

    val voicePhase = store.watch { it.phase }
    val liveShowsCaptions = store.watch { it.showsCaptions } == true
    val liveStore = if (store != null && voicePhase?.showsCard == false) store else null
    var lastStore by remember { mutableStateOf<VoiceStoreOf?>(null) }
    val activeStore = liveStore ?: lastStore
    val isCallLive = voicePhase != null && !voicePhase.isTerminal && !voicePhase.showsCard
    val showsCaptions = !isRequestDocked && liveStore != null && voicePhase?.isTerminal == false && liveShowsCaptions

    var composerFrame by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current.density
    val width = max(1f, if (composerFrame.width > 0) composerFrame.width else 350f)
    val showsSurface = activeController?.showsSurface == true
    val mergesComposerGlass = showsSurface && allowsMergedComposerGlass && !reducedMotion
    val reservesSpace = activeController?.isTrayExpanded == true && (CallTrayTunables.below != CallTrayBelowMode.home || isFocused)
    val reserve = CallTrayTunables.height + CallTrayTunables.gap
    val reserveSpec: FiniteAnimationSpec<Dp> = if (activeController?.trayExpansionAnimates == true) {
        CallTrayTunables.reserveAnimation(opening = reservesSpace, reducedMotion = reducedMotion) ?: snap()
    } else {
        snap()
    }
    val reservePadding by animateDpAsState(if (reservesSpace) reserve.dp else 0.dp, reserveSpec, label = "callTrayReserve")
    val showsTranscriptSurface = activeController != null && activeStore != null &&
        (showsCaptions || activeController.transcriptLingering)

    fun sync(target: CallTrayController, phase: VoiceFeature.State.Phase?) {
        if (liveStore != null && voicePhase?.isTerminal == false) lastStore = liveStore
        target.reconcile(voicePhase = phase, reducedMotion = effectiveReducedMotion, isSceneActive = isSceneActive)
        if (target.phase == CallTrayController.Phase.idle) lastStore = null
    }

    // onChange(of: isEnabled)
    LaunchedEffect(isEnabled) {
        val target = activeController ?: return@LaunchedEffect
        if (composerFrame.width > 0 && composerFrame.height > 0) target.noteComposer(composerFrame, isFocused)
        sync(target, voicePhase)
    }
    // onChange(of: isFocused)
    LaunchedEffect(isFocused) {
        val target = activeController ?: return@LaunchedEffect
        val frame = if (composerFrame.width > 0 && composerFrame.height > 0) composerFrame else target.baseComposerFrame
        target.noteComposer(frame, isFocused)
    }
    // onChange(of: voicePhase, initial: true)
    val previousPhase = remember { PhaseBox() }
    LaunchedEffect(voicePhase) {
        val oldPhase = previousPhase.phase
        previousPhase.phase = voicePhase
        val target = activeController ?: return@LaunchedEffect
        val effective = voicePhase ?: if (oldPhase == null && target.holdsCallButton && target.phase != CallTrayController.Phase.ending) {
            VoiceFeature.State.Phase.Connecting
        } else {
            null
        }
        sync(target, effective)
    }
    // onChange(of: tapGeneration)
    val tapGeneration = controller?.tapGeneration
    LaunchedEffect(tapGeneration) {
        val target = activeController ?: return@LaunchedEffect
        if (tapGeneration == null || tapGeneration == 0) return@LaunchedEffect
        val effective = voicePhase ?: if (target.holdsCallButton && target.phase != CallTrayController.Phase.ending) VoiceFeature.State.Phase.Connecting else null
        sync(target, effective)
    }
    // onChange(of: scenePhase)
    LaunchedEffect(isSceneActive) {
        val target = activeController ?: return@LaunchedEffect
        target.reconcile(voicePhase = voicePhase, reducedMotion = reducedMotion || !isSceneActive, isSceneActive = isSceneActive)
        if (target.phase == CallTrayController.Phase.idle && !isCallLive) lastStore = null
    }
    // onChange(of: showsCaptions)
    val firstCaptions = remember { BooleanBox() }
    LaunchedEffect(showsCaptions) {
        if (!firstCaptions.seen) {
            firstCaptions.seen = true
            return@LaunchedEffect
        }
        activeController?.setTranscript(expanded = showsCaptions, reducedMotion = reducedMotion, onExpand = onExpandTranscript)
    }
    // onChange(of: controller.phase)
    val controllerPhase = controller?.phase
    LaunchedEffect(controllerPhase) {
        if (controllerPhase == CallTrayController.Phase.idle && !isCallLive) lastStore = null
    }

    val isDark = OpenCodeTheme.colors.isDark
    androidx.compose.runtime.CompositionLocalProvider(
        LocalComposerGlassHostedByCallTray provides (controller != null),
        LocalComposerGlassMergedInCallTray provides mergesComposerGlass,
    ) {
        Layout(
            modifier = modifier,
            content = {
                // Everything above the surface dismisses the captions.
                if (activeController != null && showsCaptions) {
                    Box(Modifier.layoutId(HostSlot.dismiss)) { VoiceCaptionsDismissBackdrop(liveStore) }
                }
                if (showsSurface) {
                    Box(Modifier.layoutId(HostSlot.backdrop)) {
                        CallTrayGlassBackdrop(
                            controller = activeController,
                            width = width,
                            isFocused = isFocused,
                            reducedMotion = reducedMotion,
                            mergesComposerGlass = mergesComposerGlass,
                            composerHeight = composerFrame.height,
                            reserve = reservePadding.value,
                        )
                    }
                }
                Box(
                    Modifier
                        .layoutId(HostSlot.content)
                        .callTrayDip(activeController, reducedMotion)
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInWindow() / density
                            val frame = Rect(position.x, position.y, position.x + coordinates.size.width / density, position.y + coordinates.size.height / density)
                            if (frame.width <= 0 || frame.height <= 0) return@onGloballyPositioned
                            composerFrame = frame
                            activeController?.noteComposer(frame, isFocused)
                        },
                ) { content() }
                if (showsSurface) {
                    Box(Modifier.layoutId(HostSlot.stage)) {
                        CallTrayStageOverlay(
                            controller = activeController,
                            store = activeStore,
                            width = width,
                            isFocused = isFocused,
                            canToggleCaptions = !isRequestDocked,
                            reducedMotion = reducedMotion,
                            isDark = isDark,
                        )
                    }
                }
                if (showsTranscriptSurface) {
                    Box(Modifier.layoutId(HostSlot.transcript)) {
                        CallTrayTranscriptOverlay(
                            controller = activeController,
                            store = activeStore,
                            width = width,
                            isFocused = isFocused,
                            showsCaptions = showsCaptions,
                            reducedMotion = reducedMotion,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val contentPlaceable = measurables.first { it.layoutId == HostSlot.content }.measure(constraints.copy(minHeight = 0))
            val reservePx = reservePadding.roundToPx()
            val layoutWidth = contentPlaceable.width
            val layoutHeight = (contentPlaceable.height + reservePx).coerceIn(constraints.minHeight, constraints.maxHeight)
            val loose = Constraints()
            val stageHeight = (CallTrayComposerMetrics.barHeight + CallTrayTunables.height + CallTrayTunables.gap).dp.roundToPx()
            val fixed = Constraints.fixed(layoutWidth, stageHeight)
            val backdrop = measurables.firstOrNull { it.layoutId == HostSlot.backdrop }?.measure(fixed)
            val stage = measurables.firstOrNull { it.layoutId == HostSlot.stage }?.measure(fixed)
            val transcript = measurables.firstOrNull { it.layoutId == HostSlot.transcript }?.measure(Constraints.fixedWidth(layoutWidth))
            val dismiss = measurables.firstOrNull { it.layoutId == HostSlot.dismiss }?.measure(loose)
            layout(layoutWidth, layoutHeight) {
                dismiss?.place((layoutWidth - dismiss.width) / 2, (layoutHeight - dismiss.height) / 2)
                // The stage's bottom (`y = stageHeight`) is the composer's resting bottom.
                backdrop?.place(0, layoutHeight - stageHeight)
                contentPlaceable.place(0, 0)
                stage?.place(0, layoutHeight - stageHeight)
                // The transcript stands above the composer, its bottom on the composer's top.
                transcript?.place(0, -transcript.height)
            }
        }
    }
}

private enum class HostSlot { dismiss, backdrop, content, stage, transcript }

private class PhaseBox {
    var phase: VoiceFeature.State.Phase? = null
}

private class BooleanBox {
    var seen = false
}

/**
 * The spec the composer area (pills, the background-work capsule, a draft's projects, Jump to
 * Latest, its top edge) animates with when [CallTrayController.isTrayExpanded] flips (iOS
 * `callTrayReserveAnimation`): the tray's open/close spring, or `null` (no animation) when the
 * tray is off or covered, under Reduce Motion, or when the flip snaps.
 */
fun <T> callTrayReserveAnimation(controller: CallTrayController?, isEnabled: Boolean, reducedMotion: Boolean): FiniteAnimationSpec<T>? {
    if (!isEnabled || controller == null || !controller.trayExpansionAnimates) return null
    return CallTrayTunables.reserveAnimation(opening = controller.isTrayExpanded, reducedMotion = reducedMotion)
}

/** The composer's anticipation dip while the call button lifts (iOS `CallTrayDipModifier`); read per frame in the layer, so nothing recomposes. */
fun Modifier.callTrayDip(controller: CallTrayController?, reducedMotion: Boolean): Modifier =
    if (controller == null || reducedMotion) this
    else graphicsLayer {
        translationY = if (controller.showsSurface) controller.composerDip(reducedMotion) * density else 0f
    }

/**
 * The composer's dictation (record) button scales, blurs and fades out as a call starts and
 * back as the tray closes (iOS `CallTrayMicModifier`, [CallTrayController.composerMicLook]).
 * Composer folds it away for accessibility once [CallTrayController.isMicFolded].
 */
fun Modifier.callTrayMic(controller: CallTrayController?, reducedMotion: Boolean): Modifier = graphicsLayer {
    val look = controller?.composerMicLook(reducedMotion) ?: CallTrayController.PartLook.resting
    val blur = if (!reducedMotion && look.blur > 0.05f && look.opacity > 0.02f && look.opacity < 0.98f) look.blur else 0f
    scaleX = look.scale
    scaleY = look.scale
    alpha = look.opacity
    renderEffect = if (blur > 0f) BlurEffect(blur * density, blur * density, TileMode.Decal) else null
}

/**
 * The composer's surface (when the tray hosts it) and the tray's surface, drawn behind the
 * stage. iOS splits and merges them as Liquid Glass droplets in a `GlassEffectContainer`;
 * Android draws two Material surfaces: the tray's grows out of the call button's place and
 * across, with no droplet merge.
 */
@Composable
private fun CallTrayGlassBackdrop(
    controller: CallTrayController,
    width: Float,
    isFocused: Boolean,
    reducedMotion: Boolean,
    mergesComposerGlass: Boolean,
    composerHeight: Float,
    reserve: Float,
) {
    val g = controller.geometry(width = width, isFocused = isFocused, reducedMotion = reducedMotion)
    val surfaceColor = VoiceCallSurfaceDefaults.color
    val density = LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        if (mergesComposerGlass && composerHeight > 0) {
            // The composer's own surface, riding its lift and dip.
            Box(
                Modifier
                    .offset { IntOffset(0, ((g.stageHeight - composerHeight - reserve + g.composerDip) * density).roundToInt()) }
                    .size(width.dp, composerHeight.dp)
                    .callSurface(RoundedCornerShape(CallTrayComposerMetrics.cornerRadius.dp), surfaceColor),
            )
        }
        if (g.showsGlassSurface && g.trayOpacity > 0.001f && g.surf.width >= 1 && g.surf.height >= 1 && !controller.dock.hidesSessionTray) {
            val shape = RoundedCornerShape(g.radius.dp)
            Box(
                Modifier
                    .offset { IntOffset((g.surf.left * density).roundToInt(), (g.surf.top * density).roundToInt()) }
                    .size(max(1f, g.surf.width).dp, max(1f, g.surf.height).dp)
                    .graphicsLayer { alpha = g.trayOpacity }
                    .callSurface(shape, surfaceColor)
                    // A tap energizes it: a brief brighten (iOS `.brightness(0.12 × energize)`).
                    .drawWithContent {
                        drawContent()
                        if (g.energize > 0.001f) drawRect(Color.White.copy(alpha = 0.12f * g.energize))
                    }
                    .clip(shape),
            )
        }
    }
}

@Composable
private fun CallTrayTranscriptOverlay(
    controller: CallTrayController,
    store: VoiceStoreOf,
    width: Float,
    isFocused: Boolean,
    showsCaptions: Boolean,
    reducedMotion: Boolean,
) {
    val g = controller.geometry(width = width, isFocused = isFocused, reducedMotion = reducedMotion)
    if (g.transcriptHeight <= 0.5f) return
    val shape = RoundedCornerShape(CallTrayTunables.cornerRadius.dp)
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxWidth()
            .height(g.transcriptHeight.dp)
            .graphicsLayer {
                alpha = g.transcriptOpacity
                translationY = (-g.transcriptGap + g.composerDip) * density
            }
            .callSurface(shape, VoiceCallSurfaceDefaults.color)
            .clip(shape)
            .then(if (showsCaptions) Modifier else Modifier.blocksTouches()),
        contentAlignment = Alignment.BottomCenter,
    ) {
        VoiceCallTranscript(
            store = store,
            isExpanded = showsCaptions,
            modifier = Modifier
                .wrapContentSize(Alignment.BottomCenter, unbounded = true)
                .requiredSize(width.dp, CallTrayTunables.transcriptHeight.dp),
        )
    }
}

@Composable
private fun CallTrayStageOverlay(
    controller: CallTrayController,
    store: VoiceStoreOf?,
    width: Float,
    isFocused: Boolean,
    canToggleCaptions: Boolean,
    reducedMotion: Boolean,
    isDark: Boolean,
) {
    val g = controller.geometry(width = width, isFocused = isFocused, reducedMotion = reducedMotion)
    val isInteractive = g.ca > 0.6f && controller.phase == CallTrayController.Phase.live
    val showsBar = g.showsGlassSurface && g.trayOpacity > 0.001f && g.surf.width >= 1 && g.surf.height >= 1
    val shape = RoundedCornerShape(g.radius.dp)
    val density = LocalDensity.current.density
    // Docked in the drawer, or shrinking away there after a hang-up.
    val isAway = controller.dock.hidesSessionTray
    Box(Modifier.fillMaxSize()) {
        CallTraySessionAnchor(dock = controller.dock, frame = g.trayRect)
        // While the drawer is open the tray is out there ([CallTrayDockOverlay]). It stays
        // mounted, so its scope picks up on the frame it comes back.
        val barShown = showsBar && !isAway
        Box(
            Modifier
                .offset { IntOffset((g.surf.left * density).roundToInt(), (g.surf.top * density).roundToInt()) }
                .size(max(1f, g.surf.width).dp, max(1f, g.surf.height).dp)
                .graphicsLayer { alpha = if (barShown) g.trayOpacity else 0f }
                .clip(shape)
                .then(if (barShown) Modifier else Modifier.clearAndSetSemantics {}),
            contentAlignment = Alignment.CenterEnd,
        ) {
            CallTrayBar(
                store = store,
                fullScopeWidth = width,
                visibleWidth = g.trayRect.width,
                trayHeight = CallTrayTunables.height,
                parts = g.parts,
                scanPending = controller.scanPending,
                canToggleCaptions = canToggleCaptions,
                reducedMotion = g.reducedMotion,
                isInteractive = barShown && isInteractive,
                scopeRenderer = controller.scopeRenderer,
                scopePaused = isAway,
                scopeSurfaceWidth = g.surf.width,
                scopeSurfaceHeight = g.surf.height,
                scopeCornerRadius = g.radius,
                modifier = Modifier.wrapContentSize(Alignment.CenterEnd, unbounded = true),
            )
        }
        if (g.seed.opacity > 0.001f) {
            CallTraySeed(g, isDark, Modifier.graphicsLayer { alpha = if (isAway) 0f else 1f })
        }
    }
}

private const val hangupAngle = 135f

/**
 * The handset swap's opacity, kept just inside 0…1 so neither glyph's layer is dropped or
 * flattened mid-spin: the layers stay put for the whole swap.
 */
private fun glyphMix(t: Float): Float = min(max(t, 0.001f), 0.999f)

private const val ghostCount = 2

/** The call button lifting, spinning into End and blending into its red (iOS `seedView`). */
@Composable
private fun CallTraySeed(g: CallTrayController.Geometry, isDark: Boolean, modifier: Modifier) {
    val sd = g.seed
    val look = CallTrayController.buttonLook(CallTrayTunables.callButton, CallTrayTunables.endButton, sd.dissolve, sd.callness, isDark)
    val trail = CallTrayTunables.spinTrail * CallTrayController.smoothstep(3f, 25f, sd.spread)
    val shadowFactor = g.lifted * look.discAlpha
    val outerBlur = if (!g.reducedMotion && sd.blur > 0.05f) sd.blur else 0f
    val glyphBlur = if (!g.reducedMotion && sd.iconBlur > 0.05f) sd.iconBlur else 0f
    val density = LocalDensity.current.density
    val diameter = max(1f, sd.diameter)
    Box(
        modifier
            .offset { IntOffset(((sd.x - diameter / 2) * density).roundToInt(), ((sd.y - diameter / 2) * density).roundToInt()) }
            .size(diameter.dp)
            .graphicsLayer {
                alpha = sd.opacity
                shape = CircleShape
                shadowElevation = CallTrayTunables.liftShadow * shadowFactor * density
                ambientShadowColor = Color.Black.copy(alpha = (0.32f * shadowFactor).coerceIn(0f, 1f))
                spotShadowColor = Color.Black.copy(alpha = (0.32f * shadowFactor).coerceIn(0f, 1f))
                renderEffect = if (outerBlur > 0f) BlurEffect(outerBlur * density, outerBlur * density, TileMode.Decal) else null
            }
            .background(look.disc.copy(alpha = look.discAlpha.coerceIn(0f, 1f)), CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                renderEffect = if (glyphBlur > 0f) BlurEffect(glyphBlur * density, glyphBlur * density, TileMode.Decal) else null
            },
            contentAlignment = Alignment.Center,
        ) {
            for (index in 1..ghostCount) {
                val f = index.toFloat() / (ghostCount + 1)
                val angleOffset = -sd.dir * sd.spread * f
                val ghostOpacity = if (trail > 0.01f) trail * 0.35f * (1 - f * 0.5f) else 0f
                if (ghostOpacity > 0f) {
                    SeedGlyph(SFSymbol.PhoneDownFill, look.glyph, sd.angle - hangupAngle + angleOffset, sd.drift, ghostOpacity)
                }
            }
            // The composer's own handset until mid-spin, then End's: same turn, so it never jumps.
            SeedGlyph(SFSymbol.PhoneFill, look.glyph, sd.angle, sd.drift, glyphMix(1 - sd.phoneGlyph))
            SeedGlyph(SFSymbol.PhoneDownFill, look.glyph, sd.angle - hangupAngle, sd.drift, glyphMix(sd.phoneGlyph))
        }
    }
}

@Composable
private fun SeedGlyph(symbol: SFSymbol, tint: Color, degrees: Float, drift: Float, opacity: Float) {
    OpenCodeIcon(
        symbol,
        contentDescription = null,
        tint = tint,
        size = 20.dp,
        modifier = Modifier.graphicsLayer {
            rotationZ = degrees
            translationX = drift * density
            alpha = opacity
        },
    )
}

/**
 * The content inside the Call Tray: the phosphor dot scope on the left (reaching from the
 * leading rounded cap to Mute) and Mute + End on the right.
 *
 * @param scopePaused drawn elsewhere for now (the other of the session's tray and the docked
 *   one): its scope stops drawing, keeping its last frame for the hand-back.
 * @param scopeSurfaceWidth the surface's current bounds, not the fully unfolded tray's bounds
 *   (`null` uses the bar's). Controls keep their trailing slots while the scope follows the
 *   moving cap.
 */
@Composable
fun CallTrayBar(
    store: VoiceStoreOf?,
    fullScopeWidth: Float,
    visibleWidth: Float,
    trayHeight: Float,
    parts: List<CallTrayController.PartLook>,
    scanPending: Boolean,
    canToggleCaptions: Boolean,
    reducedMotion: Boolean,
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
    scopeRenderer: DotScopeRenderer? = null,
    scopePaused: Boolean = false,
    scopeSurfaceWidth: Float? = null,
    scopeSurfaceHeight: Float? = null,
    scopeCornerRadius: Float = CallTrayTunables.cornerRadius,
) {
    val isMuted = store.watch { it.isMuted } == true
    val showsPoorConnection = store.watch { it.showsPoorConnection } == true
    val scopeStage = store.watch { it.scopeStage } ?: DotScopeSimulation.Stage.ending
    val isTerminal = store.watch { it.phase.isTerminal } ?: true
    val endPart = parts.getOrNull(0) ?: CallTrayController.PartLook.resting
    val mutePart = parts.getOrNull(1) ?: CallTrayController.PartLook.resting
    val scopePart = parts.getOrNull(2) ?: CallTrayController.PartLook.resting
    val isDark = OpenCodeTheme.colors.isDark
    val endLook = CallTrayController.buttonLook(CallTrayTunables.callButton, CallTrayTunables.endButton, dissolve = 1f, callness = 1f, isDark = isDark)
    val bareMute = CallTrayTunables.muteStyle == CallTrayMuteStyle.icon && !isMuted
    // The composer mic's size: iOS caps it at accessibility-medium Dynamic Type; dp icons don't scale.
    val layout = CallTrayBarLayout.current(CallTrayBarLayout.micGlyphWidth(CallTrayComposerMetrics.micScale))
    val scopeWidth = scopeSurfaceWidth ?: visibleWidth
    val scopeHeight = scopeSurfaceHeight ?: trayHeight
    val canAct = isInteractive && !isTerminal
    val label = OpenCodeTheme.colors.text
    val background = MaterialTheme.colorScheme.background
    val secondary = OpenCodeTheme.colors.secondaryText
    val tertiaryFill = VoiceCallSurfaceDefaults.tertiaryFill

    Box(modifier.size(visibleWidth.dp, trayHeight.dp)) {
        CallTrayScopeSurface(
            mode = DotScopeMode.Tray(
                scopeStage,
                leadingInset = CallTrayTunables.scopeLeadingInset,
                trailingInset = layout.scopeTrailingInset,
                visibleWidth = scopeWidth,
                fade = CallTrayTunables.scopeFade,
                scanReach = CallTrayTunables.scanReach,
                cornerRadius = scopeCornerRadius,
            ),
            isHidden = scanPending || scopePaused || scopePart.opacity <= 0.01f,
            renderer = scopeRenderer,
            width = scopeWidth.dp,
            height = scopeHeight.dp,
            canvasWidth = fullScopeWidth.dp,
            cornerRadius = scopeCornerRadius.dp,
            opacity = scopePart.opacity,
            showsPoorConnection = showsPoorConnection,
            reducedMotion = reducedMotion,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = CallTrayComposerMetrics.innerHorizontal.dp, end = CallTrayBarLayout.trailingPadding.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceCallCenterButton(
                store = store,
                canToggleCaptions = canToggleCaptions,
                animation = Motion.easeOut(Motion.fadeStandard, reduceMotion = reducedMotion),
                enabled = canAct,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(layout.buttonSpacing.dp), verticalAlignment = Alignment.CenterVertically) {
                // Mute: the composer mic's exact glyph unmuted; only an actual mute swaps it to `mic.slash`.
                CallTraySlot(
                    label = if (isMuted) "Unmute" else "Mute",
                    enabled = canAct && scopeStage == DotScopeSimulation.Stage.live,
                    onClick = { store?.send(VoiceFeature.Action.MuteTapped) },
                ) {
                    Box(
                        Modifier
                            .size(CallTrayBarLayout.disc.dp)
                            .callTrayPart(mutePart, reducedMotion)
                            .background(if (isMuted) label else if (bareMute) Color.Transparent else tertiaryFill, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = if (isMuted) background else if (bareMute) secondary else label
                        if (reducedMotion) {
                            OpenCodeIcon(if (isMuted) SFSymbol.MicSlash else SFSymbol.Mic, null, tint = tint, size = CallTrayBarLayout.micIconSize(CallTrayComposerMetrics.micScale).dp)
                        } else {
                            Crossfade(isMuted, animationSpec = Motion.easeInOut(Motion.fadeStandard), label = "muteGlyph") { muted ->
                                OpenCodeIcon(if (muted) SFSymbol.MicSlash else SFSymbol.Mic, null, tint = tint, size = CallTrayBarLayout.micIconSize(CallTrayComposerMetrics.micScale).dp)
                            }
                        }
                    }
                }
                CallTraySlot(label = "End Call", enabled = canAct, onClick = { store?.send(VoiceFeature.Action.EndPressed) }) {
                    Box(
                        Modifier
                            .size(CallTrayBarLayout.disc.dp)
                            .callTrayPart(endPart, reducedMotion)
                            .background(endLook.disc.copy(alpha = endLook.discAlpha.coerceIn(0f, 1f)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(SFSymbol.PhoneDownFill, null, tint = endLook.glyph, size = 20.dp)
                    }
                }
            }
        }
    }
}

/** A [CallTrayBarLayout.slot] (the 48 dp touch target) holding one of the tray's buttons. */
@Composable
private fun CallTraySlot(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .then(
                if (enabled) Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = null, onClick = onClick)
                else Modifier,
            )
            .openCodeTapTarget()
            .size(CallTrayBarLayout.slot.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** End, Mute and the scope fully in, as the tray rests (End drawn by the bar rather than by the seed). */
object CallTrayBarDefaults {
    val restingParts: List<CallTrayController.PartLook> = List(3) { CallTrayController.PartLook.resting }
}

/**
 * Centre status and caption-toggle target shared by [VoiceCallComposer] and [CallTrayBar]: the
 * needs-input hand, the weak-connection note or the paused glyph over the scope, and a tap
 * that shows or hides the call's transcript. TalkBack: “Voice call”, its status as the state.
 */
@Composable
fun RowScope.VoiceCallCenterButton(
    store: VoiceStoreOf?,
    canToggleCaptions: Boolean,
    animation: FiniteAnimationSpec<Float>,
    enabled: Boolean = true,
) {
    val showsCaptionsState = store.watch { it.showsCaptions } == true
    val showsCaptions = canToggleCaptions && showsCaptionsState
    val needsInput = store.watch { it.targetNeedsInput } == true
    val poor = store.watch { it.showsPoorConnection } == true
    val interrupted = store.watch { it.isAudioInterrupted } == true
    val status = store.watch { it.statusText } ?: ""
    val toggle = {
        if (canToggleCaptions && store != null) {
            store.send(if (store.state.showsCaptions) VoiceFeature.Action.CaptionsDismissed else VoiceFeature.Action.CaptionsTapped)
        }
    }
    val hint = if (canToggleCaptions) (if (showsCaptions) "Hide the call transcript" else "Show the call transcript") else null
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .then(if (enabled) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle) else Modifier)
            .clearAndSetSemantics {
                contentDescription = "Voice call"
                stateDescription = status
                role = Role.Button
                if (enabled) onClick(label = hint) { toggle(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            needsInput -> OpenCodeIcon(SFSymbol.HandRaisedFill, null, tint = VoiceCallSurfaceDefaults.orange)
            else -> {
                // Speech may not be getting through: say so, over the scope.
                PoorConnectionBadgeVisibility(poor, animation)
                if (!poor && interrupted) {
                    // Another app or call has the audio; the detail is in captions.
                    OpenCodeIcon(SFSymbol.PauseFill, null, tint = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun PoorConnectionBadgeVisibility(visible: Boolean, animation: FiniteAnimationSpec<Float>) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animation) + scaleIn(animation, initialScale = 0.9f),
        exit = fadeOut(animation) + scaleOut(animation, targetScale = 0.9f),
    ) { PoorConnectionBadge() }
}

/** Invisible backdrop that dismisses the call's captions on tap (4,000 dp square, centred on its host). */
@Composable
fun VoiceCaptionsDismissBackdrop(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    Box(
        modifier
            .wrapContentSize(unbounded = true)
            .requiredSize(4_000.dp)
            .clickable(interactionSource = null, indication = null) { store.send(VoiceFeature.Action.CaptionsDismissed) }
            .semantics { hideFromAccessibility() },
    )
}

/**
 * Shown on the call bar while microphone audio is backing up on the way to the voice model,
 * so a call that can't hear you doesn't look fine. Quiet by design: a small amber glyph and
 * secondary text over the dimmed scope. Hidden from TalkBack (the bar's status says it).
 */
@Composable
fun PoorConnectionBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(SFSymbol.WifiExclamationmark, null, tint = VoiceCallSurfaceDefaults.orange, size = 16.dp)
        Text(
            "Weak connection",
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
