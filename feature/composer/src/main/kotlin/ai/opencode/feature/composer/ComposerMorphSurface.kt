package ai.opencode.feature.composer

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.excludedFromDrawerPan
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `ComposerMorphSurface`: the morph on screen, the backdrop, the glass and its
 * content. Always mounted (it draws nothing closed), so opening grows it from the source instead
 * of arriving open. Everything is placed from the morph's progress, so opening and closing in
 * any order only retargets its springs.
 *
 * - **Backdrop**: everything above the sheet dims (dark 0.3 / light 0.12, ×1.6 under Reduce
 *   Transparency) as it grows, and a tap closes it. The blur (iOS `ProgressiveBlur`, 18 by
 *   default) is published on [ComposerMorph.backdrop] for the host to apply to its content with
 *   [composerMorphBackdrop], since Compose can't blur a sibling.
 * - **Glass**: one M3 tonal surface (Liquid Glass → `surfaceContainerHigh`) on one rect from the
 *   source to the rest, the width leading, its corners interpolating, drawn down (or, with
 *   [stretchesUpward], its top stretched) by the morph's drag, cross-fading with the source over
 *   the first tenth ([ComposerMorphGeometry.glassFade]).
 * - **Content**: laid out once where it rests and revealed by the glass's shape, arriving out of
 *   a real blur (14) and leaving into one (10) on its own clock ([ComposerMorph.content]).
 * - **Outgoing**: what it grows out of, drawn in the glass at the source's frame, dissolving
 *   into a blur (10) and growing 12 % as the content arrives; folding back with
 *   [outgoingReturns], it returns as the content leaves.
 * - **Unified + menu** ([menuRows]): the one surface is also the + menu, grown out of [seed] on
 *   the menu's springs; its rows dissolve into the sheet and back (`MorphMenuPlacement`).
 * - **Foot**: controls riding the glass's bottom edge outside its mask (`MorphFootPlacement`).
 * - **Keyboard**: with [contentHasKeyboard], the rest stands on the IME, read from
 *   `WindowInsets.ime` every frame so it rides the IME's own animation; switching on or off
 *   without the IME moving rides iOS's keyboard spring.
 *
 * Under Reduce Motion it doesn't travel; it cross-fades in place. Frames are root px; radii dp.
 * [target] receives the surface's frame and the keyboard's top (or null), root px.
 */
@Composable
fun ComposerMorphSurface(
    morph: ComposerMorph,
    /** Where it folds back into, root px: the source as it is now. */
    source: Rect,
    sourceRadiusDp: Double,
    target: (screen: Rect, keyboardTop: Float?) -> Rect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where it grows from: the source as it was when it opened (it may move beneath it meanwhile). */
    openedFrom: Rect? = null,
    /** Where it folds back into when that's known as it closes, rather than the source as it moves. */
    landsOn: Rect? = null,
    /** A field in the content has the keyboard: while open, the sheet rises above it. */
    contentHasKeyboard: Boolean = false,
    /** The photo sheet stretches its top toward the expanded detent, its bottom anchored. */
    stretchesUpward: Boolean = false,
    /** Backdrop blur (dp) and dim overrides (iOS `backdropBlur`, `backdropDim`). */
    backdropBlur: Double? = null,
    backdropDim: Double? = null,
    /** Controls along the sheet's foot, over its content but outside its mask; they arrive and leave with the content. */
    foot: (@Composable () -> Unit)? = null,
    /** What it grows out of, dissolving as the content arrives (ignored with [menuRows]). */
    outgoing: (@Composable () -> Unit)? = null,
    outgoingReturns: Boolean = false,
    outgoingAlignedToBottom: Boolean = false,
    /** The + seed (root px) when this surface also owns the + menu phase. */
    seed: Rect = Rect.Zero,
    seedRadiusDp: Double = AttachmentMenuMetrics.seedSize.value / 2.0,
    menuShown: Boolean = false,
    /** The menu's drag (dp, rubber-banded by the host). */
    menuDragOffset: Float = 0f,
    onMenuDrag: ((Float) -> Unit)? = null,
    /** Translation and predicted end translation (dp); (0, 0) when the drag was cancelled. */
    onMenuDragEnded: ((Float, Float) -> Unit)? = null,
    onMenuDismiss: (() -> Unit)? = null,
    menuRows: (@Composable () -> Unit)? = null,
    /** The photo sheet returns to a small seed or the menu, not a replacement surface: opaque throughout its fold. */
    continuousGlass: Boolean = false,
    /** Dismissing slides it down and fades it out while the composer rises back from behind it. */
    dismissesDown: Boolean = false,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val reduceTransparency = rememberReduceTransparency()
    val density = LocalDensity.current
    val px = density.density
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var screen by remember { mutableStateOf(Rect.Zero) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    // The morph's phase is read here, not by whoever hosts it, so only the surface redraws.
    val shown = morph.isShown
    val progress = morph.progress.value
    val bottomProgress = morph.bottomProgress.value
    val sheetActive = morph.isOpening || morph.foldPending
    val unified = ComposerMorphGeometry.unifiedMenuState(menuShown, morph, dismissesDown)
    val usesDownwardDismiss = unified.usesDownwardDismiss
    val menuProgress by animateFloatAsState(
        unified.menuProgress,
        if (unified.menuProgress > 0.5f) AttachmentMenuMotion.open(reduceMotion, plusMenuSheet = true)
        else AttachmentMenuMotion.close(reduceMotion, plusMenuSheet = true),
        label = "morph-menu-progress",
    )
    val keyboardUp = contentHasKeyboard && morph.isOpen
    // The keyboard carries the sheet up and down on its own curve.
    val keyboardBlend by animateFloatAsState(
        if (keyboardUp) 1f else 0f,
        if (reduceMotion) snap() else ComposerMorphTunables.keyboard(),
        label = "morph-keyboard",
    )
    // Until its fold starts it is still open, so it keeps growing from where it opened.
    val from = if (sheetActive) openedFrom ?: source else landsOn ?: source
    val outgoingShown = sheetActive || outgoingReturns
    val menuInteractive = unified.menuInteractive
    val currentOnMenuDrag by rememberUpdatedState(onMenuDrag)
    val currentOnMenuDragEnded by rememberUpdatedState(onMenuDragEnded)
    val currentMenuInteractive by rememberUpdatedState(menuInteractive)

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { screen = it.boundsInRoot() },
    ) {
        val anyShown = shown || menuShown || menuProgress > 0.001f
        if (!anyShown || screen == Rect.Zero) {
            SideEffect { morph.backdrop.blurPx = 0f }
            return@Box
        }
        val keyboardTop = if (imeBottom > 0) screen.bottom - imeBottom else null
        val restFree = target(screen, null)
        val rest = if (keyboardBlend <= 0f || keyboardTop == null) restFree else lerpRect(restFree, target(screen, keyboardTop), keyboardBlend)
        val origin = screen.topLeft
        val targetInSpace = rest.translate(-origin)
        val seedInSpace = if (seed == Rect.Zero) Rect.Zero else seed.translate(-origin)
        val topRadius = (ComposerMorphTunables.topRadius * px).toFloat()
        val geometry = ComposerMorphGeometry(
            source = if (usesDownwardDismiss) {
                ComposerMorphGeometry.downwardDismissSource(targetInSpace, (ComposerMorphTunables.dismissDrop * px).toFloat())
            } else {
                from.translate(-origin)
            },
            sourceRadius = if (usesDownwardDismiss) topRadius else (sourceRadiusDp * px).toFloat(),
            target = targetInSpace,
            topRadius = topRadius,
            bottomRadius = (ComposerMorphTunables.bottomRadius * px).toFloat(),
        )
        val minHeight = 120 * px
        val dimBase = backdropDim ?: if (dark) ComposerMorphTunables.dimDark else ComposerMorphTunables.dimLight
        val dim = (dimBase * if (reduceTransparency) 1.6 else 1.0).toFloat()
        val blurDp = if (reduceTransparency) 0.0 else backdropBlur ?: ComposerMorphTunables.backdropBlur
        SideEffect {
            morph.backdrop.sheetTop = rest.top
            morph.backdrop.blurPx = if (shown) (blurDp * px).toFloat() else 0f
        }

        // The backdrop: everything above the sheet dims, growing with the morph.
        val strength = progress.coerceIn(0f, 1f)
        if (shown || strength > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim * strength))
                    .then(
                        if (morph.isOpen) {
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                        } else {
                            Modifier
                        },
                    )
                    .excludedFromDrawerPan(morph.isOpen)
                    .clearAndSetSemantics {},
            )
        }

        // MorphBody: the glass and its content, placed from one progress.
        val hasUnifiedMenu = menuRows != null
        val placed = if (!usesDownwardDismiss && hasUnifiedMenu) {
            val (menuRect, menuRadius) = ComposerMorphGeometry.menuSource(
                progress = menuProgress,
                seed = seedInSpace,
                seedRadius = (seedRadiusDp * px).toFloat(),
                menu = geometry.source,
                menuRadius = geometry.sourceRadius,
                dragOffset = if (morph.isOpening) 0f else menuDragOffset * px,
                reduceMotion = reduceMotion,
            )
            geometry.copy(source = menuRect, sourceRadius = menuRadius)
        } else {
            geometry
        }
        // Under Reduce Motion it stands where it rests and only fades.
        val reducedAtTarget = morph.isOpening || usesDownwardDismiss || !hasUnifiedMenu
        val travel = if (!shown) 0f else if (reduceMotion) (if (reducedAtTarget) 1f else 0f) else progress
        val bottomTravel = if (!shown) 0f else if (reduceMotion) (if (reducedAtTarget) 1f else 0f) else bottomProgress
        val frame = placed.frame(travel, bottomTravel, 0f)
        // A finger's drag moves the glass and its content together (or stretches the photo sheet's top).
        val pull = if (shown && !reduceMotion) morph.drag * px else 0f
        val (stretchedRect, downward) = ComposerMorphGeometry.stretched(frame.rect, pull, stretchesUpward, minHeight)
        val (stretchedTarget, _) = ComposerMorphGeometry.stretched(placed.target, pull, stretchesUpward, minHeight)
        val armed = morph.phase == ComposerMorph.Phase.Armed
        val fade = ComposerMorphGeometry.glassFade(
            progress = progress,
            menuProgress = menuProgress,
            shown = shown,
            armed = armed,
            continuousGlass = continuousGlass,
            dismissesDown = usesDownwardDismiss,
            reduceMotion = reduceMotion,
        )
        val glassShape = MorphShape(frame.top, frame.bottom)
        val contentY = min(stretchedTarget.top, stretchedRect.top)

        PlacedBox(stretchedRect.translate(0f, downward), Modifier.graphicsLayer { alpha = fade }.background(MaterialTheme.colorScheme.surfaceContainerHigh, glassShape))

        if (menuRows != null) {
            // MorphMenuPlacement: the one set of rows inside the unified glass, never rebuilt during the morph.
            // Drawn under the content (iOS draws them over it): Compose can't let a sibling's touches
            // fall through (`allowsHitTesting(false)`), and the rows only dissolve while the content
            // arrives transparent, so the order doesn't show.
            val menuTravel = if (shown && !reduceMotion) progress else 0f
            val menuBottomTravel = if (shown && !reduceMotion) bottomProgress else 0f
            val menuPlaced = if (usesDownwardDismiss) {
                geometry
            } else {
                val (menuRect, menuRadius) = ComposerMorphGeometry.menuSource(
                    progress = menuProgress,
                    seed = seedInSpace,
                    seedRadius = (seedRadiusDp * px).toFloat(),
                    menu = geometry.source,
                    menuRadius = geometry.sourceRadius,
                    dragOffset = if (sheetActive) 0f else menuDragOffset * px,
                    reduceMotion = reduceMotion,
                )
                geometry.copy(source = menuRect, sourceRadius = menuRadius)
            }
            val menuFrame = menuPlaced.frame(menuTravel, menuBottomTravel, 0f)
            val (menuRectNow, menuDownward) = ComposerMorphGeometry.stretched(menuFrame.rect, pull, stretchesUpward, minHeight)
            val clamped = menuTravel.coerceIn(0f, 1f)
            val growth = if (shown && !reduceMotion) (1 + ComposerMorphTunables.outgoingGrowth * clamped).toFloat() else 1f
            val restingMenu = geometry.source.translate(0f, if (sheetActive) 0f else menuDragOffset * px)
            val menuShape = MorphShape(menuFrame.top, menuFrame.bottom)
            PlacedBox(
                menuRectNow.translate(0f, menuDownward),
                Modifier
                    .graphicsLayer {
                        clip = true
                        shape = menuShape
                    }
                    .semantics {
                        if (menuInteractive) {
                            testTag = "attachment-menu"
                            // iOS's escape action: TalkBack's dismiss.
                            dismiss { onMenuDismiss?.invoke(); true }
                        } else {
                            hideFromAccessibility()
                        }
                    }
                    .then(if (!menuInteractive) Modifier else Modifier.pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        var total = 0f
                        var ended = false
                        detectVerticalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                total = 0f
                                ended = false
                            },
                            onVerticalDrag = { change, dy ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                total += dy
                                if (currentMenuInteractive && kotlin.math.abs(total) >= 10 * px) {
                                    change.consume()
                                    currentOnMenuDrag?.invoke(total / px)
                                }
                            },
                            onDragEnd = {
                                ended = true
                                if (currentMenuInteractive) {
                                    val velocity = tracker.calculateVelocity().y / px
                                    val translation = total / px
                                    currentOnMenuDragEnded?.invoke(translation, translation + velocity * PREDICT_SECONDS)
                                }
                            },
                            onDragCancel = { if (!ended) currentOnMenuDragEnded?.invoke(0f, 0f) },
                        )
                    }),
            ) {
                val outerHeight = menuRectNow.height
                PlacedBox(
                    // Top-leading in the sheet, bottom-leading as it shrinks into the seed.
                    Rect(Offset(0f, if (shown) 0f else outerHeight - restingMenu.height), restingMenu.size),
                    Modifier
                        .graphicsLayer {
                            scaleX = growth
                            scaleY = growth
                            transformOrigin = TransformOrigin(0f, 0f)
                            alpha = ComposerMorphGeometry.glassFade(
                                progress = progress, menuProgress = menuProgress, shown = shown, armed = armed,
                                continuousGlass = continuousGlass, dismissesDown = usesDownwardDismiss, reduceMotion = reduceMotion,
                            )
                        }
                        .morphDissolve(
                            reveal = { if (shown) (if (reduceMotion) progress else morph.content.value) else 0f },
                            shown = if (shown) outgoingShown else true,
                            fade = 1f,
                            scales = false,
                            blurPx = if (reduceMotion) 0f else (ComposerMorphTunables.outgoingBlur * px).toFloat(),
                        ),
                ) { menuRows() }
            }
        }

        if (shown) {
            val contentFade: Float? = if (usesDownwardDismiss) fade else if (reduceMotion) progress.coerceIn(0f, 1f) else null
            val revealBlur = if (morph.isOpening) ComposerMorphTunables.contentBlur else ComposerMorphTunables.exitBlur
            val maskOffset = Offset(stretchedRect.left - stretchedTarget.left, stretchedRect.top - contentY)
            PlacedBox(
                Rect(Offset(stretchedTarget.left, contentY + downward), stretchedTarget.size),
                Modifier
                    // The glass's shape, where it is now, in the content's own frame.
                    .graphicsLayer {
                        clip = true
                        shape = MorphShape(frame.top, frame.bottom, maskOffset, stretchedRect.size)
                    }
                    .morphReveal({ morph.content.value }, contentFade, (revealBlur * px).toFloat())
                    .semantics { if (!morph.isOpening) hideFromAccessibility() },
            ) {
                content()
                // Taps reach the content only once it has mostly grown (iOS `allowsHitTesting(progress > 0.5)`).
                if (progress <= 0.5f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .clearAndSetSemantics {},
                    )
                }
            }
        }

        if (shown && outgoing != null && !hasUnifiedMenu) {
            val src = placed.source
            val clamped = travel.coerceIn(0f, 1f)
            // It rides the glass's top-leading corner (or bottom edge folding into the composer), growing a little with it.
            val growth = if (outgoingAlignedToBottom) 1f else (1 + ComposerMorphTunables.outgoingGrowth * clamped).toFloat()
            // As the real composer fades back in over the last stretch of the fold, the stand-in fades out with it.
            val outgoingFade = if (outgoingAlignedToBottom && !morph.isOpening && !reduceMotion) ((progress - 0.04f) / 0.24f).coerceIn(0f, 1f) else fade
            val innerSize = if (outgoingAlignedToBottom) stretchedRect.size else src.size
            PlacedBox(
                stretchedRect.translate(0f, downward),
                Modifier
                    .graphicsLayer {
                        clip = true
                        shape = glassShape
                    }
                    .clearAndSetSemantics {},
            ) {
                PlacedBox(
                    Rect(Offset.Zero, innerSize),
                    Modifier
                        .graphicsLayer {
                            scaleX = growth
                            scaleY = growth
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .morphDissolve(
                            reveal = { morph.content.value },
                            shown = outgoingShown && !reduceMotion,
                            fade = outgoingFade,
                            scales = outgoingAlignedToBottom,
                            blurPx = (ComposerMorphTunables.outgoingBlur * px).toFloat(),
                        ),
                    contentAlignment = if (outgoingAlignedToBottom) Alignment.BottomCenter else Alignment.TopStart,
                ) { outgoing() }
            }
        }

        if (foot != null && shown) {
            // MorphFootPlacement: along the morphing glass's bottom edge, riding up with a fold.
            val footTravel = if (reduceMotion) 1f else progress
            val footFrame = geometry.frame(footTravel, if (reduceMotion) 1f else bottomProgress, 0f)
            val footPull = if (reduceMotion) 0f else morph.drag * px
            val (footRect, footDownward) = ComposerMorphGeometry.stretched(footFrame.rect, footPull, stretchesUpward, minHeight)
            val footBlur = if (reduceMotion) 0f else ((if (morph.isOpen) ComposerMorphTunables.contentBlur else ComposerMorphTunables.exitBlur) * px).toFloat()
            PlacedBox(
                footRect.translate(0f, footDownward),
                Modifier
                    .morphReveal({ morph.content.value }, null, footBlur)
                    .semantics { if (!morph.isOpen) hideFromAccessibility() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box {
                    foot()
                    // iOS `allowsHitTesting(morph.isOpen)`: folding, its controls take no taps.
                    if (!morph.isOpen) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

/** UIKit's normal deceleration (0.998 per ms): a throw's predicted travel is velocity × 0.499 s (SwiftUI `predictedEndTranslation`). */
internal const val PREDICT_SECONDS = 0.499f

/**
 * iOS `MorphReveal`: the content arriving out of a blur and leaving into one on its own clock
 * ([reveal]); under Reduce Motion (or a downward dismissal) [fade], the glass's own, instead.
 * No blur on content all but gone or all but whole: it costs a pass and shows nothing.
 */
internal fun Modifier.morphReveal(reveal: () -> Float, fade: Float?, blurPx: Float): Modifier = graphicsLayer {
    val showing = fade ?: reveal().coerceIn(0f, 1f)
    val radius = if (fade != null || showing < 0.02f || showing > 0.98f) 0f else (1 - showing) * blurPx
    renderEffect = if (radius > 0.25f) androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal) else null
    alpha = showing
}

/**
 * iOS `MorphDissolve`: what the morph grows out of, leaving into a blur as the content arrives
 * and returning as it leaves (`1 − reveal`, on the content's clock, so the two add up to a
 * whole); [scales] shrinks it 4 % toward its foot as it goes.
 */
internal fun Modifier.morphDissolve(reveal: () -> Float, shown: Boolean, fade: Float, scales: Boolean, blurPx: Float): Modifier = graphicsLayer {
    val r = reveal()
    val clamped = r.coerceIn(0f, 1f)
    val showing = if (shown) 1 - clamped else 0f
    val radius = if (showing < 0.02f || showing > 0.98f) 0f else (1 - showing) * blurPx
    val scale = if (scales) 0.96f + 0.04f * (1 - clamped) else 1f
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 1f)
    renderEffect = if (radius > 0.25f) androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal) else null
    alpha = ComposerMorphGeometry.outgoingOpacity(r, shown, fade)
}

/** iOS `UnevenRoundedRectangle` with equal top and equal bottom corners, optionally at [offset] / [size] inside the layer (a mask). */
private class MorphShape(
    private val top: Float,
    private val bottom: Float,
    private val offset: Offset = Offset.Zero,
    private val maskSize: Size? = null,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = maskSize ?: size
        val rect = Rect(offset, s)
        val limit = min(s.width, s.height) / 2
        val t = CornerRadius(min(top, limit))
        val b = CornerRadius(min(bottom, limit))
        return Outline.Generic(Path().apply { addRoundRect(RoundRect(rect, t, t, b, b)) })
    }
}

/**
 * A box at [rect] (px, in its parent's space) with exactly that size, whatever its content
 * measures, without reading the rect in a layout lambda (the surface recomposes per frame anyway).
 */
@Composable
private fun PlacedBox(
    rect: Rect,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit = {},
) {
    val width = max(rect.width, 1f).roundToInt()
    val height = max(rect.height, 1f).roundToInt()
    Layout(
        content = { Box(modifier, contentAlignment = contentAlignment) { content() } },
        modifier = Modifier,
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
        }
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
