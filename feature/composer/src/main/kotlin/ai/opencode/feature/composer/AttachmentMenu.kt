package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the attachment menu is (iOS `AttachmentMenuPhase`). The menu and the photo card are one
 * floating surface ([AttachmentMenuOverlay]) above the composer.
 */
enum class AttachmentMenuPhase { Closed, Menu, Photos }

/**
 * Identity-scoped linger flags for [AttachmentMenuOverlay], so closing animations (rows fade,
 * picker dissolve, surface shrink) never bleed across session or draft switches
 * (iOS `AttachmentMenuLinger`).
 */
@Immutable
data class AttachmentMenuLinger(
    val identity: Any = 0,
    val keepsPickerDrawn: Boolean = false,
    val shownPhase: AttachmentMenuPhase = AttachmentMenuPhase.Closed,
    val glassLingers: Boolean = false,
    val rowsLinger: Boolean = false,
) {
    fun keepsPickerDrawn(currentIdentity: Any): Boolean = identity == currentIdentity && keepsPickerDrawn
    fun shownPhase(currentIdentity: Any): AttachmentMenuPhase = if (identity == currentIdentity) shownPhase else AttachmentMenuPhase.Closed
    fun glassLingers(currentIdentity: Any): Boolean = identity == currentIdentity && glassLingers
    fun rowsLinger(currentIdentity: Any): Boolean = identity == currentIdentity && rowsLinger

    fun syncIdentity(newIdentity: Any, phase: AttachmentMenuPhase): AttachmentMenuLinger {
        if (identity == newIdentity) return this
        return AttachmentMenuLinger(
            identity = newIdentity,
            shownPhase = phase,
            glassLingers = phase != AttachmentMenuPhase.Closed,
            rowsLinger = phase == AttachmentMenuPhase.Menu,
            keepsPickerDrawn = phase == AttachmentMenuPhase.Photos,
        )
    }

    fun phaseChanged(new: AttachmentMenuPhase, currentIdentity: Any): AttachmentMenuLinger {
        if (identity != currentIdentity) return syncIdentity(currentIdentity, new)
        return copy(
            shownPhase = new,
            glassLingers = glassLingers || new != AttachmentMenuPhase.Closed,
            rowsLinger = rowsLinger || new == AttachmentMenuPhase.Menu,
            keepsPickerDrawn = keepsPickerDrawn || new == AttachmentMenuPhase.Photos,
        )
    }
}

/**
 * The menu's curves (iOS `AttachmentMenuMotion`). iOS's `AnimationTimeScale` debug slow-down is
 * n/a: Android's animator duration scale applies system-wide.
 */
object AttachmentMenuMotion {
    /** The surface grows up out of + while the + turns into a ×. */
    fun <T> open(reduceMotion: Boolean, plusMenuSheet: Boolean = false): FiniteAnimationSpec<T> {
        if (reduceMotion) return Motion.easeInOut(0.2)
        return (if (plusMenuSheet) PlusMenuTunables.open else MotionSpring(0.4, 0.18)).spec()
    }

    /** Menu ↔ card, the same curve both ways so Back retraces Photos. */
    fun <T> morph(reduceMotion: Boolean, delaySeconds: Double = 0.0): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.easeInOut(0.22, delaySeconds) else MotionSpring(0.44, 0.06).animation(false, delaySeconds)

    fun <T> close(reduceMotion: Boolean, plusMenuSheet: Boolean = false): FiniteAnimationSpec<T> {
        if (reduceMotion) return Motion.easeInOut(0.2)
        return (if (plusMenuSheet) PlusMenuTunables.close else MotionSpring(0.34, 0.0)).spec()
    }
}

/** iOS `AttachmentMenuMetrics` (points → dp). */
object AttachmentMenuMetrics {
    /** The composer's, so the menu and the card read as the same surface. */
    val cornerRadius: Dp = 28.dp
    /** Between the panel's edge and its rows; the highlight is concentric. */
    val padding: Dp = 6.dp
    /** Between the composer's top edge and the menu's or card's bottom edge. */
    val composerGap: Dp = 10.dp
    val minimumWidth: Dp = 210.dp
    /** The surface the menu grows out of and shrinks back into, above the +. */
    val seedSize: Dp = 34.dp
    /** Between the card's edge and its controls and grid. */
    val cardInset: Dp = 8.dp
    val cardHeaderHeight: Dp = 44.dp
    /** The card stops short of the session header. */
    val cardTopClearance: Dp = 64.dp
    /** Over the composer, the menu's foot stays this far above the screen's bottom edge. */
    val overComposerFloor: Dp = 12.dp
}

/**
 * The New Plus Menu's values (OC-354), iOS `PlusMenuTunables` with the same names and defaults.
 * iOS backs them with live Tunables; here they are the baked defaults. Seconds and points.
 */
object PlusMenuTunables {
    const val FLIGHT_DURATION = 0.34
    val open = MotionSpring(0.34, 0.12)
    val close = MotionSpring(0.26, 0.0)
    const val MENU_BLUR = 10f
    const val MENU_SCALE = 0.92f
    const val HOLD_DURATION = 0.18
    val peekOpen = MotionSpring(0.28, 0.14)
    const val PEEK_CLOSE = 0.16
    const val PEEK_STAGGER = 0.02
    const val PEEK_BLUR = 10f
    const val PEEK_DIM = 0f
    const val PEEK_FEATHER = 56f
    const val ALL_PHOTOS_PADDING = 18f
    const val VIDEO_BADGE_INSET = 6f
    const val VIDEO_BADGE_RADIUS = 6f
    const val VIDEO_BADGE_OPACITY = 0.58
    /** Rim inset around the photo grid inside the photo sheet. */
    const val GRID_INSET = 6f
    /** How far the menu's foot sits below the composer's; by default it stands on the keyboard's top edge. */
    const val MENU_DROP = 0f
    /** The menu's leading edge in from the composer's. */
    const val MENU_INSET = 0f
}

/**
 * Media kinds the inline picker can narrow to (iOS `InlinePhotoFilter`). Android's system
 * photo picker has no Screenshots filter; a grid that reads `MediaStore` itself can apply it.
 */
enum class InlinePhotoFilter(val title: String, val systemImage: SFSymbol) {
    All("All Photos", SFSymbol.PhotoOnRectangle),
    Screenshots("Screenshots", SFSymbol.CameraViewfinder),
    Videos("Videos", SFSymbol.Video),
}

// region Rows

/**
 * The menu's rows (iOS `AttachmentMenuRows`). A hidden measuring copy reports where each row
 * rests ([onRowFrame], root coordinates), so choosing by a slide from + never depends on the
 * surface mid-flight.
 *
 * Paste is a plain row here: Android reads the clipboard without iOS's paste alert (it shows
 * its own toast), so no system paste control is needed; [onPaste] asks the session to read it.
 *
 * @param buttonCenterX where the + glyph's center sits from the panel's leading edge (dp); the
 *   rows' icons line up under it.
 */
@Composable
fun AttachmentMenuRows(
    items: List<AttachmentMenuItem>,
    buttonCenterX: Dp,
    modifier: Modifier = Modifier,
    highlighted: AttachmentMenuItem? = null,
    staggeredEntrance: Boolean = false,
    entranceProgress: Float = 1f,
    entranceBlur: Float = 0f,
    entranceScale: Float = 1f,
    onRowFrame: ((AttachmentMenuItem, Rect) -> Unit)? = null,
    onChoose: (AttachmentMenuItem) -> Unit = {},
    onPaste: () -> Unit = {},
) {
    val fontScale = min(LocalDensity.current.fontScale, 2f)
    val rowHeight = 48.dp * fontScale
    val iconWidth = 26.dp * fontScale
    val iconInset = max((buttonCenterX - AttachmentMenuMetrics.padding - iconWidth / 2).value, 10f).dp
    val count = items.size
    Column(
        modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = AttachmentMenuMetrics.minimumWidth)
            .padding(AttachmentMenuMetrics.padding),
    ) {
        items.forEachIndexed { index, item ->
            val label = @Composable { lit: Boolean ->
                AttachmentMenuRowLabel(item, iconWidth, iconInset, rowHeight, isLit = lit)
            }
            when {
                onRowFrame != null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { onRowFrame(item, Rect(it.positionInRoot(), it.size.toSize())) },
                ) { label(false) }
                else -> {
                    val reveal = if (staggeredEntrance) {
                        Modifier.menuRowEntranceReveal(entranceProgress, max(count - 1 - index, 0), count, entranceBlur, entranceScale)
                    } else Modifier
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    Box(
                        reveal
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AttachmentMenuRowHighlightRadius))
                            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                if (item == AttachmentMenuItem.Paste) onPaste() else onChoose(item)
                            }
                            .semantics { testTag = "attachment-menu-${item.name.lowercase()}" },
                    ) { label(pressed || highlighted == item) }
                }
            }
        }
    }
}

private val AttachmentMenuRowHighlightRadius: Dp = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.padding

/** The system menus' highlight: a quiet fill concentric with the panel, for a press or a finger sliding over the row from +. */
@Composable
private fun AttachmentMenuRowLabel(item: AttachmentMenuItem, iconWidth: Dp, inset: Dp, height: Dp, isLit: Boolean) {
    val lit by animateFloatAsState(
        if (isLit) 1f else 0f,
        tween(if (isLit) 80 else 200, easing = Motion.EaseInOut),
        label = "menu-row-highlight",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = lit), RoundedCornerShape(AttachmentMenuRowHighlightRadius))
            .padding(start = inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(iconWidth), contentAlignment = Alignment.Center) {
            OpenCodeIcon(item.systemImage, contentDescription = null, tint = OpenCodeTheme.colors.text)
        }
        Text(item.title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 1)
        Spacer(Modifier.width(24.dp))
    }
}

/**
 * Staggers the + menu's rows bottom-up out of the + seed (and back into it on close), each row
 * unblurring, scaling from its bottom leading corner, and lifting into its slot as one layer
 * (iOS `MenuRowEntranceReveal`).
 */
fun Modifier.menuRowEntranceReveal(progress: Float, bottomIndex: Int, totalCount: Int, blur: Float, scale: Float): Modifier {
    val clamped = progress.coerceIn(0f, 1f)
    val stagger = if (totalCount > 1) 0.11f else 0f
    val delay = bottomIndex * stagger
    val span = max(1 - max(totalCount - 1, 0) * stagger, 0.5f)
    val local = ((clamped - delay) / span).coerceIn(0f, 1f)
    val eased = local * local * (3 - 2 * local)
    val currentScale = scale + (1 - scale) * eased
    val lift = (1 - eased) * (5 + bottomIndex * 3)
    val radius = if (blur > 0 && eased > 0.01f && eased < 0.99f) (1 - eased) * blur else 0f
    return this
        .graphicsLayer {
            scaleX = currentScale
            scaleY = currentScale
            transformOrigin = TransformOrigin(0f, 1f)
            translationY = if (blur > 0) lift.dp.toPx() else 0f
            alpha = eased
        }
        .then(if (radius > 0f) Modifier.blur(radius.dp) else Modifier)
}

// endregion

// region Menu and photo card

/**
 * The attachment menu and the photo card: one floating surface drawn over the session screen,
 * always above the composer, so the + (a × while it's open) stays visible and the composer and
 * transcript never move (iOS `AttachmentMenuOverlay`). Closed, nothing is drawn. Opening grows
 * the surface up out of a seed just above the + into the menu; Photos grows it on into the
 * card, which frames [photoGrid]; Back shrinks it onto the menu and closing shrinks it into the
 * seed. Liquid Glass maps to a tonal Material surface. Under Reduce Motion the menu and the
 * card cross-fade in place.
 *
 * Android has no inline system photo picker to frame: [photoGrid] supplies the card's grid
 * (for example the New Plus Menu's `PhotoLibraryClient` grid). When the session has none, it
 * launches the system picker for Photos instead of opening the card ([ComposerPickers]).
 *
 * Put this in a full-screen box over the session; it positions itself from [anchors]. The
 * session pairs it with [AttachmentMenuDismissArea] and handles system Back (iOS's escape
 * action) by closing it.
 *
 * @param onPhaseChange the overlay's own Back, drag-dismiss and outside dismissals.
 * @param identity the session screen's identity, so lingering exit layers never bleed into
 *   another session or draft.
 */
@Composable
fun AttachmentMenuOverlay(
    phase: AttachmentMenuPhase,
    onPhaseChange: (AttachmentMenuPhase) -> Unit,
    anchors: AttachmentMenuAnchors,
    press: AttachmentMenuPress,
    items: List<AttachmentMenuItem>,
    /** Videos upload as files, so they appear only when the server takes uploads. */
    includesVideos: Boolean,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    /** The card's grid, below its controls; `null` keeps the card empty. */
    photoGrid: (@Composable (filter: InlinePhotoFilter, modifier: Modifier) -> Unit)? = null,
    /** New Plus Menu: Photos opens the plus-menu sheet instead of this card's grid. */
    usesSystemPicker: Boolean = true,
    /** New Plus Menu: the menu grows out of the + itself and stands over the composer and the keyboard's top. */
    overComposer: Boolean = false,
    /** The photo sheet has taken the menu's place: the menu's surface gives way and its rows blur out. */
    handedOff: Boolean = false,
    identity: Any = 0,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var filter by remember { mutableStateOf(InlinePhotoFilter.All) }
    var linger by remember { mutableStateOf(AttachmentMenuLinger()) }
    var rowsSize by remember { mutableStateOf(Size.Zero) }
    val rowFrames = remember { mutableStateMapOf<AttachmentMenuItem, Rect>() }
    var dragOffset by remember { mutableStateOf(0f) }
    /** A drag that carries the menu also carries a row under the finger; its release must not count as a tap. */
    var isDragging by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(Size.Zero) }
    var previousPhase by remember { mutableStateOf(phase) }

    // A new session or draft: nothing of the old one lingers.
    LaunchedEffect(identity) {
        if (linger.identity != identity) {
            linger = linger.syncIdentity(identity, phase)
            filter = InlinePhotoFilter.All
            dragOffset = 0f
            isDragging = false
        }
    }
    LaunchedEffect(phase, identity) { linger = linger.phaseChanged(phase, identity) }
    LaunchedEffect(identity, phase) {
        if (phase != AttachmentMenuPhase.Menu && linger.rowsLinger(identity)) {
            delay(400)
            if (phase != AttachmentMenuPhase.Menu) linger = linger.syncIdentity(identity, phase).copy(rowsLinger = false)
        }
    }
    LaunchedEffect(identity, phase) {
        if (phase != AttachmentMenuPhase.Photos && linger.keepsPickerDrawn(identity)) {
            delay(550)
            if (phase != AttachmentMenuPhase.Photos) linger = linger.syncIdentity(identity, phase).copy(keepsPickerDrawn = false)
        }
    }
    LaunchedEffect(identity, phase) {
        if (phase == AttachmentMenuPhase.Closed && linger.glassLingers(identity)) {
            delay(550)
            if (phase == AttachmentMenuPhase.Closed) linger = linger.syncIdentity(identity, phase).copy(glassLingers = false)
        }
    }

    val frames = with(density) {
        attachmentMenuFrames(
            anchors = anchors,
            origin = origin,
            overlaySize = overlaySize,
            rowsSize = rowsSize,
            overComposer = overComposer,
            px = { it.toPx() },
        )
    }

    // Publish what the + and the photo sheet lay out against.
    LaunchedEffect(frames.menu, rowFrames.toMap(), items, origin) {
        val menuOrigin = frames.menu.topLeft + origin
        val resting = rowFrames.filterKeys { it in items }.mapValues { it.value.translate(menuOrigin) }
        if (resting != anchors.rows) anchors.rows = resting
        anchors.seed = frames.seed.translate(origin)
        anchors.menu = frames.menu.translate(origin)
        val centerX = frames.button.center.x - frames.menu.left
        if (centerX > 0 && kotlin.math.abs(anchors.buttonCenterXInMenu - centerX) > 0.5f) anchors.buttonCenterXInMenu = centerX
    }

    val buttonCenterX = with(density) { (frames.button.center.x - frames.menu.left).toDp() }
    val cardProgress by animateFloatAsState(
        if (phase == AttachmentMenuPhase.Photos) 1f else 0f,
        if (reduceMotion) AttachmentMenuMotion.morph(true) else PickerDissolve.animation(toCard = phase == AttachmentMenuPhase.Photos),
        label = "attachment-card-progress",
    )
    val handoff by animateFloatAsState(if (handedOff) 1f else 0f, AttachmentMenuMotion.morph(reduceMotion), label = "attachment-handoff")

    // The surface: one rect that is the seed, the menu, or the card.
    val target = when (phase) {
        AttachmentMenuPhase.Closed -> frames.seed
        AttachmentMenuPhase.Menu -> frames.menu
        AttachmentMenuPhase.Photos -> frames.card
    }
    val surface = remember { Animatable(target, RectConverter) }
    LaunchedEffect(target, phase) {
        val from = previousPhase
        previousPhase = phase
        val spec: FiniteAnimationSpec<Rect> = when {
            from == phase -> if (reduceMotion) tween(0) else tween(300, easing = Motion.EaseInOut)
            phase == AttachmentMenuPhase.Closed -> AttachmentMenuMotion.close(reduceMotion, overComposer)
            from == AttachmentMenuPhase.Closed -> AttachmentMenuMotion.open(reduceMotion, overComposer)
            // Back: the grid dissolves away, then the card shrinks back onto the menu.
            phase == AttachmentMenuPhase.Menu -> AttachmentMenuMotion.morph(reduceMotion, if (reduceMotion) 0.0 else PickerDissolve.BACK_DELAY)
            else -> AttachmentMenuMotion.morph(reduceMotion)
        }
        if (surface.value == Rect.Zero || reduceMotion) surface.snapTo(target) else surface.animateTo(target, spec)
    }
    val isOpen = phase != AttachmentMenuPhase.Closed
    // The seed is clear; the surface gathers as it grows.
    val surfaceAlpha by animateFloatAsState(
        if (isOpen) 1f else 0f,
        if (isOpen) Motion.easeInOut(0.16) else Motion.easeInOut(0.22, delaySeconds = 0.1),
        label = "attachment-surface-alpha",
    )
    val rowsAlpha by animateFloatAsState(
        if (phase == AttachmentMenuPhase.Closed) 0f else 1f,
        if (phase == AttachmentMenuPhase.Closed) Motion.easeInOut(0.1) else Motion.easeInOut(0.22, delaySeconds = if (reduceMotion) 0.0 else 0.08),
        label = "attachment-rows-alpha",
    )
    val glassShown = isOpen || linger.glassLingers(identity)
    val showsRows = phase == AttachmentMenuPhase.Menu || (linger.rowsLinger(identity) && !reduceMotion)
    val showsCard = phase == AttachmentMenuPhase.Photos || linger.keepsPickerDrawn(identity)

    fun dismiss() = onPhaseChange(AttachmentMenuPhase.Closed)
    fun back() = onPhaseChange(AttachmentMenuPhase.Menu)

    val dragModifier = Modifier.pointerInput(isOpen) {
        if (!isOpen) return@pointerInput
        val tracker = VelocityTracker()
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = {
                isDragging = true
                total = 0f
                tracker.resetTracking()
            },
            onDragEnd = {
                isDragging = false
                val velocity = tracker.calculateVelocity().y
                if (AttachmentMenuOverlay.dragDismisses(total.toDp().value, (total + velocity * 0.5f).toDp().value)) {
                    dismiss()
                    scope.launch { animateDrag(dragOffset, AttachmentMenuMotion.close(reduceMotion, overComposer)) { dragOffset = it } }
                } else {
                    scope.launch { animateDrag(dragOffset, MotionSpring(0.35, 0.25).spec()) { dragOffset = it } }
                }
            },
            onDragCancel = {
                isDragging = false
                scope.launch { animateDrag(dragOffset, MotionSpring(0.35, 0.25).spec()) { dragOffset = it } }
            },
        ) { change, amount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            total += amount
            // Rubber-band upward; follow the finger downward.
            dragOffset = AttachmentMenuOverlay.rubberBand(total.toDp().value).dp.toPx()
            change.consume()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                overlaySize = it.size.toSize()
            },
    ) {
        // Measures the rows where nothing can see or reach them, at the menu's resting frame.
        AttachmentMenuRows(
            items = items,
            buttonCenterX = buttonCenterX,
            modifier = Modifier
                .offset { IntOffset(frames.menu.left.roundToInt(), frames.menu.top.roundToInt()) }
                .alpha(0f)
                .clearAndSetSemantics {}
                .onSizeChanged { rowsSize = it.toSize() },
            onRowFrame = { item, frame ->
                val menuOrigin = frames.menu.topLeft + origin
                rowFrames[item] = frame.translate(-menuOrigin)
            },
        )
        if (overComposer || anchors.composer == Rect.Zero) return@Box

        if (reduceMotion) {
            // The menu and the card each fade in and out in place.
            val menuAlpha by animateFloatAsState(if (phase == AttachmentMenuPhase.Menu) 1f else 0f, Motion.easeInOut(0.2), label = "menu-fade")
            val cardAlpha by animateFloatAsState(if (phase == AttachmentMenuPhase.Photos) 1f else 0f, Motion.easeInOut(0.22), label = "card-fade")
            if (menuAlpha > 0f) {
                AttachmentMenuPanel(
                    rect = frames.menu,
                    radius = AttachmentMenuMetrics.cornerRadius,
                    modifier = Modifier.alpha(menuAlpha * (1 - handoff)).then(dragModifier),
                    isModal = true,
                ) {
                    MenuRowsSlot(frames, items, buttonCenterX, press, isDragging, onChoose, onPaste, rowsAlpha = 1f, cardFade = 1f, handoff = handoff)
                }
            }
            if (cardAlpha > 0f) {
                AttachmentMenuPanel(rect = frames.card, radius = AttachmentMenuMetrics.cornerRadius, modifier = Modifier.alpha(cardAlpha).then(dragModifier)) {
                    CardContent(frames.card, filter, { filter = it }, includesVideos, photoGrid, controlsAlpha = 1f, dragOffset = 0f, onBack = ::back)
                }
            }
        } else if (glassShown || isOpen) {
            val rect = surface.value.translate(Offset(0f, if (isOpen) dragOffset else 0f))
            val radius = if (isOpen) AttachmentMenuMetrics.cornerRadius else AttachmentMenuMetrics.seedSize / 2
            AttachmentMenuPanel(
                rect = rect,
                radius = radius,
                modifier = Modifier
                    .alpha(surfaceAlpha * (1 - handoff))
                    .then(if (isOpen && !handedOff) dragModifier else Modifier),
                isModal = showsRows && phase == AttachmentMenuPhase.Menu,
                hidden = phase != AttachmentMenuPhase.Menu && phase != AttachmentMenuPhase.Photos || handedOff,
            ) {
                if (showsRows) {
                    MenuRowsSlot(
                        frames, items, buttonCenterX, press, isDragging, onChoose, onPaste,
                        rowsAlpha = rowsAlpha,
                        // They fade out in place before the grid shows, and back in once it has gone.
                        cardFade = morphFade(cardProgress, clear = PickerDissolve.SHOWS, shown = 0.05f),
                        handoff = handoff,
                    )
                }
                if (showsCard && usesSystemPicker) {
                    CardContent(
                        rect, filter, { filter = it }, includesVideos, photoGrid,
                        controlsAlpha = morphFade(cardProgress, clear = 0.55f, shown = 1f),
                        gridAlpha = PickerDissolve.gridOpacity(cardProgress),
                        dragOffset = 0f,
                        onBack = ::back,
                    )
                }
            }
        }
    }
}

/** iOS `AttachmentMenuOverlay`'s pure statics. */
object AttachmentMenuOverlay {
    /** Rubber-band upward; follow the finger downward (dp). */
    fun rubberBand(y: Float): Float = if (y > 0) y else -sqrt(-y) * 2

    /** A downward drag (dp) or flick that closes the menu or card. */
    fun dragDismisses(translation: Float, predicted: Float): Boolean = translation > 50 || predicted > 160
}

private suspend fun animateDrag(from: Float, spec: FiniteAnimationSpec<Float>, set: (Float) -> Unit) {
    val animatable = Animatable(from)
    animatable.animateTo(0f, spec) { set(value) }
}

/** The frames the overlay lays out, in its own coordinates (px). */
@Immutable
internal data class AttachmentMenuFrames(
    val button: Rect,
    val seed: Rect,
    val menu: Rect,
    val card: Rect,
    /** The grid's place inside the card, below its controls. */
    val grid: Rect,
)

internal fun attachmentMenuFrames(
    anchors: AttachmentMenuAnchors,
    origin: Offset,
    overlaySize: Size,
    rowsSize: Size,
    overComposer: Boolean,
    px: (Dp) -> Float,
): AttachmentMenuFrames {
    val button = anchors.button.translate(-origin)
    val composer = anchors.composer.translate(-origin)
    val inset = px(AttachmentMenuMetrics.cardInset)
    val header = px(AttachmentMenuMetrics.cardHeaderHeight)
    // Everything rests on this line, a little above the composer.
    val base = composer.top - px(AttachmentMenuMetrics.composerGap)
    val seedSize = px(AttachmentMenuMetrics.seedSize)
    val rows = if (rowsSize == Size.Zero) Size(px(AttachmentMenuMetrics.minimumWidth), px(160.dp)) else rowsSize
    val screenHeight = overlaySize.height
    val seed: Rect
    val menu: Rect
    if (overComposer) {
        // Its foot below the composer's, short of the screen's bottom edge.
        val floor = overlaySize.height - px(AttachmentMenuMetrics.overComposerFloor)
        val foot = min(composer.bottom + px(PlusMenuTunables.MENU_DROP.dp), floor)
        seed = Rect(Offset(button.center.x - seedSize / 2, button.center.y - seedSize / 2), Size(seedSize, seedSize))
        menu = Rect(
            Offset(composer.left + px(PlusMenuTunables.MENU_INSET.dp), foot - rows.height),
            Size(min(rows.width, composer.width), rows.height),
        )
    } else {
        seed = Rect(Offset(button.center.x - seedSize / 2, base - seedSize), Size(seedSize, seedSize))
        menu = Rect(Offset(composer.left, base - rows.height), Size(min(rows.width, composer.width), rows.height))
    }
    // About half the screen, short of the session header.
    val cardHeight = max(min(screenHeight * 0.5f, base - px(AttachmentMenuMetrics.cardTopClearance)), menu.height)
    val card = Rect(Offset(composer.left, base - cardHeight), Size(composer.width, cardHeight))
    val grid = Rect(
        Offset(card.left + inset, card.top + inset * 2 + header),
        Size(max(card.width - inset * 2, 0f), max(card.height - inset * 3 - header, 0f)),
    )
    return AttachmentMenuFrames(button, seed, menu, card, grid)
}

@Composable
private fun AttachmentMenuPanel(
    rect: Rect,
    radius: Dp,
    modifier: Modifier = Modifier,
    isModal: Boolean = false,
    hidden: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(radius)
    Surface(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .requiredSize(with(density) { rect.width.coerceAtLeast(0f).toDp() }, with(density) { rect.height.coerceAtLeast(0f).toDp() })
            .then(modifier)
            .then(
                if (hidden) Modifier.clearAndSetSemantics {}
                else Modifier.semantics {
                    isTraversalGroup = true
                    if (isModal) {
                        testTag = "attachment-menu"
                        traversalIndex = -1f
                    }
                },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) { content() }
    }
}

/**
 * The rows' place stays mounted while the surface morphs, so rows that return with Back arrive
 * where they rest rather than at the top of the shrinking card.
 */
@Composable
private fun MenuRowsSlot(
    frames: AttachmentMenuFrames,
    items: List<AttachmentMenuItem>,
    buttonCenterX: Dp,
    press: AttachmentMenuPress,
    isDragging: Boolean,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: () -> Unit,
    rowsAlpha: Float,
    cardFade: Float,
    handoff: Float,
) {
    val density = LocalDensity.current
    val handoffFade = morphFade(handoff, clear = 1f, shown = 0f)
    val handoffBlur = if (handoffFade > 0.02f && handoffFade < 0.98f) (1 - handoffFade) * 10f else 0f
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier
                .requiredSize(with(density) { frames.menu.width.toDp() }, with(density) { frames.menu.height.toDp() })
                .graphicsLayer { alpha = rowsAlpha * cardFade * handoffFade }
                .then(if (handoffBlur > 0f) Modifier.blur(handoffBlur.dp) else Modifier),
            contentAlignment = Alignment.BottomStart,
        ) {
            AttachmentMenuRows(
                items = items,
                buttonCenterX = buttonCenterX,
                highlighted = press.highlighted,
                onChoose = { item -> if (!isDragging) onChoose(item) },
                onPaste = onPaste,
            )
        }
    }
}

/** Back and the filter along the card's top edge, then the grid. */
@Composable
private fun CardContent(
    card: Rect,
    filter: InlinePhotoFilter,
    onFilter: (InlinePhotoFilter) -> Unit,
    includesVideos: Boolean,
    photoGrid: (@Composable (InlinePhotoFilter, Modifier) -> Unit)?,
    controlsAlpha: Float,
    gridAlpha: Float = 1f,
    dragOffset: Float,
    onBack: () -> Unit,
) {
    val inset = AttachmentMenuMetrics.cardInset
    val header = AttachmentMenuMetrics.cardHeaderHeight
    Column(Modifier.fillMaxSize().padding(inset).offset { IntOffset(0, dragOffset.roundToInt()) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(header)
                .graphicsLayer { alpha = controlsAlpha }
                .semantics { isTraversalGroup = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIconButton(SFSymbol.ChevronLeft, contentDescription = "Back", onClick = onBack, size = header)
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .heightIn(min = header)
                        .semantics {
                            contentDescription = "Show"
                            stateDescription = filter.title
                        }
                        .openCodeTapTarget(header),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            filter.title,
                            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = OpenCodeTheme.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OpenCodeIcon(SFSymbol.ChevronUpChevronDown, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText)
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (option in InlinePhotoFilter.entries.filter { includesVideos || it != InlinePhotoFilter.Videos }) {
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            leadingIcon = { OpenCodeIcon(option.systemImage, contentDescription = null) },
                            trailingIcon = if (option == filter) ({ OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null) }) else null,
                            onClick = {
                                expanded = false
                                onFilter(option)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(inset))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(PickerDissolve.gridRadius))
                .graphicsLayer { alpha = gridAlpha }
                .semantics { testTag = "inline-photo-picker" },
        ) {
            photoGrid?.invoke(filter, Modifier.fillMaxSize())
        }
    }
}

/**
 * The grid's dissolve with the menu (iOS `PickerDissolve`). iOS covers its out-of-process picker
 * with a glass-coloured layer because the picker can't fade; a Compose grid can, so the cover
 * folds into [gridOpacity] (shown once the rows have faded at [SHOWS], fully by 0.96).
 */
internal object PickerDissolve {
    /** Where the rows have faded out and the grid shows. */
    const val SHOWS = 0.38f
    val gridRadius: Dp = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.cardInset
    /** Back waits this long for the grid to dissolve before the surface shrinks. */
    const val BACK_DELAY = 0.08

    /** Toward the card, a little behind the surface's spring; back to the menu, quickly, ahead of it. */
    fun <T> animation(toCard: Boolean): FiniteAnimationSpec<T> =
        if (toCard) Motion.easeInOut(0.42) else Motion.easeOut(0.2)

    fun gridOpacity(progress: Float): Float =
        if (progress < SHOWS) 0f else smoothstep(SHOWS + 0.04f, 0.96f, progress)

    fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}

/**
 * Opacity by the morph's progress rather than by the clock, so content fades in and out at the
 * same point of the surface's travel both ways (iOS `MorphFade`).
 */
internal fun morphFade(progress: Float, clear: Float, shown: Float): Float {
    val t = ((progress - clear) / (shown - clear)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

private val RectConverter: TwoWayConverter<Rect, AnimationVector4D> = TwoWayConverter(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) },
)

/** Dismisses the menu on a tap or a downward drag (iOS `AttachmentMenuDismissArea`). */
@Composable
fun AttachmentMenuDismissArea(onDismiss: () -> Unit, modifier: Modifier = Modifier, extent: Dp? = null) {
    Box(
        modifier
            .then(if (extent != null) Modifier.size(extent) else Modifier.fillMaxSize())
            .clearAndSetSemantics {}
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .pointerInput(onDismiss) {
                val tracker = VelocityTracker()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        val predicted = total + tracker.calculateVelocity().y * 0.5f
                        if (total.toDp().value > 30 || predicted.toDp().value > 120) onDismiss()
                    },
                ) { change, amount ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    total += amount
                }
            },
    ) {}
}

// endregion
