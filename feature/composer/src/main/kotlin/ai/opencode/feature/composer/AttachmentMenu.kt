package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.transitionBlur
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.plusmenu.PlusMenuPlacement
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val open = MotionSpring(0.2, 0.14)
    val close = MotionSpring(0.2, 0.0)
    const val MENU_BLUR = 3.5f
    const val MENU_SCALE = 0.96f
    /** Each row's reveal starts this much (× open) after the one before. */
    const val ROW_STAGGER = 0.02f
    /** A row's reveal spans this much of the open (× open). */
    const val ROW_SPAN = 0.65f
    const val HOLD_DURATION = 0.18
    val peekOpen = MotionSpring(0.28, 0.14)
    const val PEEK_CLOSE = 0.16
    const val PEEK_STAGGER = 0.02
    /** iOS's peek backdrop blur (pt). Not drawn on Android, which dims the band with a scrim. */
    const val PEEK_BLUR = 10f
    const val PEEK_DIM = 0f
    const val PEEK_FEATHER = 56f
    const val ALL_PHOTOS_PADDING = 18f
    const val VIDEO_BADGE_INSET = 6f
    const val VIDEO_BADGE_RADIUS = 6f
    const val VIDEO_BADGE_OPACITY = 0.58
    /** Rim inset around the photo grid inside the photo sheet. */
    const val GRID_INSET = 0f
    const val SELECTION_DIM = 0.18
    /** The unpicked photos' wash once the limit is picked. */
    const val LIMIT_DIM = 0.5
    const val SELECTION_BADGE_SIZE = 24f
    const val SELECTION_BADGE_INSET = 6f
    /** How far the menu's foot sits below the composer's (iOS overlaps the keys in its own window). */
    const val MENU_DROP = 80f
    /** The menu's width, × the screen's. */
    const val MENU_WIDTH = 0.63f
    const val MENU_RADIUS = 28f
    const val MENU_ROW_HEIGHT = 56f
    /** The menu's leading edge in from the composer's. */
    const val MENU_INSET = 0f

    // iOS declares these in `PlusMenuGlassSurface.swift` (an extension of `PlusMenuTunables`).
    // Measured from ChatGPT's iOS + menu at 60 fps: its glass shrinks to a +-sized disc on a
    // critically damped ~0.3 s curve, the disc leaves within two frames at 250 ms, and its +
    // fades in from inside the disc between 200 and 300 ms; these run at two thirds of that.
    val collapse = MotionSpring(0.2, 0.0)
    /** When the collapsed glass leaves the +, in seconds after close. */
    const val COLLAPSE_GLASS_OUT = 0.2
    /** The collapsing glass's size where it leaves, as a fraction of the + frame (1 leaves a +-sized disc, which reads as a pop). */
    const val COLLAPSE_END = 0.25f
    const val COLLAPSE_FADE = 0.08
    /** The scale the + pops back from as the glass arrives (1 is a fade only). */
    const val PLUS_POP = 0.8
    const val PLUS_POP_DELAY = 0.13
    val plusPopSpring = MotionSpring(0.22, 0.45)
}

/**
 * Media kinds the inline picker can narrow to (iOS `InlinePhotoFilter`; `PHPickerFilter.images`,
 * `.videos`, `.screenshots` map to [InlinePhotoLibraryClient]'s `MediaStore` queries).
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
    /** New Plus Menu: taller rows with the icon on a circle. */
    largeMenu: Boolean = false,
    /** New Plus Menu: the menu's fixed width; otherwise the rows size to their content. */
    menuWidth: Dp? = null,
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
            .then(if (menuWidth != null) Modifier.width(max(menuWidth.value, AttachmentMenuMetrics.minimumWidth.value).dp) else Modifier.width(IntrinsicSize.Max))
            .widthIn(min = AttachmentMenuMetrics.minimumWidth)
            .padding(AttachmentMenuMetrics.padding),
    ) {
        items.forEachIndexed { index, item ->
            val label = @Composable { lit: Boolean ->
                AttachmentMenuRowLabel(
                    item,
                    iconWidth = if (largeMenu) iconWidth * 1.3f else iconWidth,
                    inset = iconInset,
                    height = if (largeMenu) rowHeight * (PlusMenuTunables.MENU_ROW_HEIGHT / 48f) else rowHeight,
                    isLit = lit,
                    circularIcon = largeMenu,
                )
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
                    // Android divergence: a ripple answers a tap (iOS lights the row while pressed);
                    // the press-and-slide highlight stays.
                    Box(
                        reveal
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AttachmentMenuRowHighlightRadius))
                            .clickable(role = Role.Button) {
                                if (item == AttachmentMenuItem.Paste) onPaste() else onChoose(item)
                            }
                            .semantics { testTag = "attachment-menu-${item.name.lowercase()}" },
                    ) { label(highlighted == item) }
                }
            }
        }
    }
}

private val AttachmentMenuRowHighlightRadius: Dp = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.padding

/** The system menus' highlight: a quiet fill concentric with the panel, for a press or a finger sliding over the row from +. */
@Composable
private fun AttachmentMenuRowLabel(item: AttachmentMenuItem, iconWidth: Dp, inset: Dp, height: Dp, isLit: Boolean, circularIcon: Boolean = false) {
    val lit by animateFloatAsState(
        if (isLit) 1f else 0f,
        MotionSpring(if (isLit) 0.08 else 0.2).spec(),
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
        Box(
            Modifier
                .width(iconWidth)
                .then(
                    if (circularIcon) {
                        Modifier.height(iconWidth).background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.CircleShape)
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
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
    val stagger = if (totalCount > 1) PlusMenuTunables.ROW_STAGGER else 0f
    val delay = bottomIndex * stagger
    val span = max(PlusMenuTunables.ROW_SPAN - max(totalCount - 1, 0) * stagger, 0.25f)
    val eased = PickerDissolve.smoothstep(delay, delay + span, clamped)
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
        .then(if (radius > 0f) Modifier.transitionBlur(radius.dp) else Modifier)
}

// endregion

// region Menu and photo card

/**
 * The attachment menu and the photo card: one floating surface drawn over the session screen,
 * always above the composer, so the + (a × while it's open) stays visible and the composer and
 * transcript never move (iOS `AttachmentMenuOverlay`). Closed, nothing is drawn. Opening grows
 * the surface up out of a seed just above the + into the menu; Photos grows it on into the
 * card, which frames the inline photo picker; Back shrinks it onto the menu and closing shrinks
 * it into the seed. Liquid Glass maps to a tonal Material surface. Under Reduce Motion the menu
 * and the card cross-fade in place.
 *
 * The picker: iOS frames the inline system `PhotosPicker`; Android has no inline system picker
 * with a Screenshots filter, so the card frames [InlinePhotoPicker] (a `MediaStore` grid read
 * through [PhotoLibraryClient] / [InlinePhotoLibraryClient]) bound to [selection], which the
 * session mirrors onto attachments as iOS's `syncPickedPhotos` does. It mounts as soon as the
 * menu opens (parked out of sight, unreachable by touch and TalkBack) so the card shows a
 * loaded grid, stands at its final frame on the card while the surface resizes around it
 * (clipped to the surface's inside), and dissolves in and out under a cover of the card's
 * colour, as iOS's picker does. [photoGrid], when given, replaces the built-in grid.
 *
 * Put this in a full-screen box over the session; it positions itself from [anchors]. System
 * Back (iOS's escape action) closes it; the session pairs it with [AttachmentMenuDismissArea].
 *
 * @param onPhaseChange the overlay's own Back, drag-dismiss and outside dismissals.
 * @param identity the session screen's identity, so lingering exit layers never bleed into
 *   another session or draft.
 * @param selection the picker's continuous selection (iOS `@Bindable var selection`); without
 *   one the card keeps a local selection nothing reads.
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
    /** Replaces the card's built-in grid, below its controls. */
    photoGrid: (@Composable (filter: InlinePhotoFilter, modifier: Modifier) -> Unit)? = null,
    /** New Plus Menu: Photos opens the plus-menu sheet instead of this card's picker, which is never mounted. */
    usesSystemPicker: Boolean = true,
    /** New Plus Menu: the menu grows out of the + itself and stands over the composer and the keyboard's top. */
    overComposer: Boolean = false,
    /** The photo sheet has taken the menu's place: the menu's surface gives way and its rows blur out. */
    handedOff: Boolean = false,
    identity: Any = 0,
    selection: InlinePhotoSelection<Uri>? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The docked keyboard's top in the overlay's space (iOS `DockedKeyboard.frameInScreen`).
    val imeBottom = WindowInsets.ime.getBottom(density)
    var filter by remember { mutableStateOf(InlinePhotoFilter.All) }
    var linger by remember { mutableStateOf(AttachmentMenuLinger()) }
    var rowsSize by remember { mutableStateOf(Size.Zero) }
    val rowFrames = remember { mutableStateMapOf<AttachmentMenuItem, Rect>() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    /** A drag that carries the menu also carries a row under the finger; its release must not count as a tap. */
    var isDragging by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(Size.Zero) }
    var rootHeight by remember { mutableFloatStateOf(0f) }
    var previousPhase by remember { mutableStateOf(phase) }
    val library = remember { InlinePhotoLibrary() }
    val localSelection = remember { InlinePhotoSelection() }
    val pickerSelection = selection ?: localSelection

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
    // One linger task (iOS `LingerTaskID`): the rows go after 400 ms, the picker and the glass 150 ms later.
    LaunchedEffect(identity, phase) {
        val lingersAfterRows = { (phase != AttachmentMenuPhase.Photos && linger.keepsPickerDrawn(identity)) ||
            (phase == AttachmentMenuPhase.Closed && linger.glassLingers(identity)) }
        if (!(phase != AttachmentMenuPhase.Menu && linger.rowsLinger(identity)) && !lingersAfterRows()) return@LaunchedEffect
        delay(400)
        if (phase != AttachmentMenuPhase.Menu) linger = linger.syncIdentity(identity, phase).copy(rowsLinger = false)
        if (!lingersAfterRows()) return@LaunchedEffect
        delay(150)
        if (phase != AttachmentMenuPhase.Photos) linger = linger.syncIdentity(identity, phase).copy(keepsPickerDrawn = false)
        if (phase == AttachmentMenuPhase.Closed) linger = linger.syncIdentity(identity, phase).copy(glassLingers = false)
    }

    val keyboardTop = if (overComposer && imeBottom > 0 && overlaySize.height > 0f) {
        val top = rootHeight - imeBottom - origin.y
        if (top < overlaySize.height) top else null
    } else null
    val frames = with(density) {
        attachmentMenuFrames(
            anchors = anchors,
            origin = origin,
            overlaySize = overlaySize,
            rowsSize = rowsSize,
            overComposer = overComposer,
            px = { it.toPx() },
            itemCount = items.size,
            fontScale = min(fontScale, 2f),
            keyboardTop = keyboardTop,
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
            // Attachments and the keyboard move the composer; what stands on it follows.
            from == phase -> MotionSpring(0.3).spec()
            phase == AttachmentMenuPhase.Closed -> AttachmentMenuMotion.close(reduceMotion, overComposer)
            from == AttachmentMenuPhase.Closed -> AttachmentMenuMotion.open(reduceMotion, overComposer)
            // Back: the picker dissolves away, then the card shrinks back onto the menu.
            phase == AttachmentMenuPhase.Menu -> AttachmentMenuMotion.morph(reduceMotion, if (reduceMotion) 0.0 else PickerDissolve.BACK_DELAY)
            else -> AttachmentMenuMotion.morph(reduceMotion)
        }
        if (surface.value == Rect.Zero || reduceMotion) surface.snapTo(target) else surface.animateTo(target, spec)
    }
    val isOpen = phase != AttachmentMenuPhase.Closed
    // The seed is clear; the surface gathers as it grows.
    val surfaceAlpha by animateFloatAsState(
        if (isOpen) 1f else 0f,
        if (isOpen) MotionSpring(0.16).spec() else MotionSpring(0.22).animation(false, delaySeconds = 0.1),
        label = "attachment-surface-alpha",
    )
    // The rows arrive once the surface has mostly grown, and leave first.
    val rowsAlpha by animateFloatAsState(
        if (phase == AttachmentMenuPhase.Closed) 0f else 1f,
        if (phase == AttachmentMenuPhase.Closed) MotionSpring(0.1).spec() else MotionSpring(0.22).animation(false, delaySeconds = if (reduceMotion) 0.0 else 0.08),
        label = "attachment-rows-alpha",
    )
    val glassShown = isOpen || linger.glassLingers(identity)
    val showsRows = phase == AttachmentMenuPhase.Menu || (linger.rowsLinger(identity) && !reduceMotion)
    val keepsPickerDrawn = linger.keepsPickerDrawn(identity)
    /** The picker mounts (parked) as soon as the menu opens, so it has loaded by the time it's chosen. */
    val mountsPicker = usesSystemPicker && when (phase) {
        AttachmentMenuPhase.Photos, AttachmentMenuPhase.Menu -> true
        AttachmentMenuPhase.Closed -> keepsPickerDrawn
    }
    val showsCardControls = phase == AttachmentMenuPhase.Photos || keepsPickerDrawn

    fun dismiss() = onPhaseChange(AttachmentMenuPhase.Closed)
    fun back() = onPhaseChange(AttachmentMenuPhase.Menu)

    // iOS's escape action (`accessibilityAction(.escape)`): Back closes the menu or the card.
    BackHandler(enabled = isOpen && !handedOff && !overComposer) { dismiss() }

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
                // Released after the row under the finger saw the up: it must not count as a tap.
                scope.launch { isDragging = false }
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

    /** The glass shape's current rect: seed, menu or card, carried by a drag while open (iOS `surfaceRect`). */
    fun surfaceRect(): Rect = surface.value.translate(Offset(0f, if (isOpen) dragOffset else 0f))

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                overlaySize = it.size.toSize()
                rootHeight = it.findRootCoordinates().size.height.toFloat()
            },
    ) {
        // Measures the rows where nothing can see or reach them, at the menu's resting frame.
        AttachmentMenuRows(
            items = items,
            buttonCenterX = buttonCenterX,
            largeMenu = overComposer,
            menuWidth = if (overComposer) with(density) { frames.menu.width.toDp() } else null,
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
                    modifier = Modifier.alpha(menuAlpha * (1 - handoff)).then(if (handedOff) Modifier else dragModifier),
                    isModal = true,
                    hidden = handedOff,
                ) {
                    MenuRowsSlot(frames, items, buttonCenterX, press, isDragging, onChoose, onPaste, rowsAlpha = 1f, cardFade = 1f, handoff = handoff)
                }
            }
            if (cardAlpha > 0f) {
                AttachmentMenuPanel(
                    rect = frames.card,
                    radius = AttachmentMenuMetrics.cornerRadius,
                    modifier = Modifier.alpha(cardAlpha).then(dragModifier),
                    hidden = true,
                ) {}
            }
        } else if (glassShown || isOpen) {
            AttachmentMenuPanel(
                rect = surfaceRect(),
                radius = if (isOpen) AttachmentMenuMetrics.cornerRadius else AttachmentMenuMetrics.seedSize / 2,
                modifier = Modifier
                    .alpha(surfaceAlpha * (1 - handoff))
                    .then(if (isOpen && !handedOff) dragModifier else Modifier),
                isModal = showsRows && phase == AttachmentMenuPhase.Menu,
                hidden = phase != AttachmentMenuPhase.Menu || handedOff,
            ) {
                if (showsRows) {
                    MenuRowsSlot(
                        frames, items, buttonCenterX, press, isDragging, onChoose, onPaste,
                        rowsAlpha = rowsAlpha,
                        // They fade out in place before the picker shows, and back in once it has gone.
                        cardFade = morphFade(cardProgress, clear = PickerDissolve.SHOWS, shown = 0.05f),
                        handoff = handoff,
                    )
                }
            }
        }

        if (mountsPicker) {
            val visible = phase == AttachmentMenuPhase.Photos
            // With Reduce Motion the card stays put and the picker simply fades.
            val reducedAlpha by animateFloatAsState(
                if (!reduceMotion || visible) 1f else 0f,
                if (reduceMotion) MotionSpring(0.2).spec() else snap(),
                label = "inline-picker-fade",
            )
            val window: () -> Rect = {
                if (reduceMotion) frames.card.translate(Offset(0f, if (visible) dragOffset else 0f)) else surfaceRect()
            }
            InlinePickerLayer(
                frames = frames,
                window = window,
                // Never translucent but under Reduce Motion: drawn or not, a cover above it does the dissolve.
                opacity = { if (reduceMotion) reducedAlpha else PickerDissolve.pickerOpacity(cardProgress) },
                dragOffset = { if (visible) dragOffset else 0f },
                visible = visible,
            ) {
                if (photoGrid != null) {
                    photoGrid(filter, Modifier.fillMaxSize())
                } else {
                    InlinePhotoPicker(
                        library = library,
                        selection = pickerSelection,
                        filter = filter,
                        includesVideos = includesVideos,
                        isActive = visible,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!reduceMotion) {
                // The card's colour over the picker, fading out to dissolve it in and back in to dissolve it away.
                PickerCover(
                    frames = frames,
                    window = ::surfaceRect,
                    opacity = { PickerDissolve.coverOpacity(cardProgress) },
                    dragOffset = { if (phase == AttachmentMenuPhase.Photos) dragOffset else 0f },
                )
            }
        }

        // Mounted only on the card or while it folds away.
        if (showsCardControls) {
            CardControls(
                card = { if (reduceMotion) frames.card.translate(Offset(0f, dragOffset)) else surfaceRect() },
                fade = { morphFade(cardProgress, clear = 0.55f, shown = 1f) },
                interactive = phase == AttachmentMenuPhase.Photos,
                reduceMotion = reduceMotion,
                filter = filter,
                onFilter = { filter = it },
                includesVideos = includesVideos,
                onBack = ::back,
            )
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
    /** The picker's place inside the card, below its controls. */
    val grid: Rect,
)

internal fun attachmentMenuFrames(
    anchors: AttachmentMenuAnchors,
    origin: Offset,
    overlaySize: Size,
    rowsSize: Size,
    overComposer: Boolean,
    px: (Dp) -> Float,
    itemCount: Int = 0,
    fontScale: Float = 1f,
    /** The docked keyboard's top in the overlay's space, if it covers part of it. */
    keyboardTop: Float? = null,
): AttachmentMenuFrames {
    val button = anchors.button.translate(-origin)
    val composer = anchors.composer.translate(-origin)
    val inset = px(AttachmentMenuMetrics.cardInset)
    val header = px(AttachmentMenuMetrics.cardHeaderHeight)
    // Everything rests on this line, a little above the composer.
    val base = composer.top - px(AttachmentMenuMetrics.composerGap)
    val seedSize = px(AttachmentMenuMetrics.seedSize)
    // Row membership can change while the measuring copy is hidden (Location): its old measured
    // height must never reserve a blank row on the next open, so the height comes from the rows.
    val rowHeight = px(48.dp) * fontScale * (if (overComposer) PlusMenuTunables.MENU_ROW_HEIGHT / 48f else 1f)
    val rows = Size(
        if (overComposer) overlaySize.width * PlusMenuTunables.MENU_WIDTH
        else if (rowsSize == Size.Zero) px(AttachmentMenuMetrics.minimumWidth) else rowsSize.width,
        rowHeight * itemCount + px(AttachmentMenuMetrics.padding) * 2,
    )
    val screenHeight = overlaySize.height
    val seed: Rect
    val menu: Rect
    if (overComposer) {
        // Its foot below the composer's, short of the screen's bottom edge.
        val foot = PlusMenuPlacement.foot(
            composer = composer,
            screenBottom = overlaySize.height,
            keyboardTop = keyboardTop,
            drop = px(PlusMenuTunables.MENU_DROP.dp),
            floor = px(AttachmentMenuMetrics.overComposerFloor),
        )
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

/** The card's colour: what the cover fades in and out as (iOS `SystemPasteControl.glass`, the panel's opaque colour). */
@Composable
private fun panelColor(): androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer

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
        color = panelColor(),
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
                .then(if (handoffBlur > 0f) Modifier.transitionBlur(handoffBlur.dp) else Modifier),
            contentAlignment = Alignment.BottomStart,
        ) {
            AttachmentMenuRows(
                items = items,
                buttonCenterX = buttonCenterX,
                highlighted = press.highlighted,
                onChoose = { item ->
                    if (!isDragging) {
                        press.rowTapped()
                        onChoose(item)
                    }
                },
                onPaste = onPaste,
            )
        }
    }
}

/** A rounded rect in the overlay's coordinates, read at draw time (iOS `GlassClip`'s mask). */
private class WindowShape(private val window: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (window.isEmpty || window.width <= 0f || window.height <= 0f) Outline.Rectangle(Rect.Zero)
        else Outline.Rounded(RoundRect(window, CornerRadius(radius)))
}

/**
 * Clips a layer to the inside of the surface as it resizes, on the surface's own spring, so
 * nothing shows past it (iOS `GlassClip`). Read in the layer block, so the surface's frames
 * redraw only this layer.
 */
private fun Modifier.glassClip(window: () -> Rect): Modifier = graphicsLayer {
    val inset = AttachmentMenuMetrics.cardInset.toPx()
    val inside = window().deflate(inset)
    clip = true
    shape = WindowShape(if (inside.width > 0f && inside.height > 0f) inside else Rect.Zero, PickerDissolve.gridRadius.toPx())
}

/**
 * The picker at its final frame on the card (iOS `picker(_:)`): parked out of sight while it
 * isn't drawn, and while it shows but isn't the card's (folding away), it takes no touches and
 * is hidden from TalkBack, so the menu's rows stay reachable.
 */
@Composable
private fun InlinePickerLayer(
    frames: AttachmentMenuFrames,
    window: () -> Rect,
    opacity: () -> Float,
    dragOffset: () -> Float,
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val grid = frames.grid
    Box(Modifier.fillMaxSize().glassClip(window)) {
        Box(
            Modifier
                .offset {
                    // Waiting behind the menu, it parks below the screen; it parks and unparks unseen.
                    val parked = if (!visible && opacity() == 0f) grid.bottom + grid.height + 200.dp.toPx() else 0f
                    IntOffset(grid.left.roundToInt(), (grid.top + dragOffset() + parked).roundToInt())
                }
                .requiredSize(with(density) { grid.width.toDp() }, with(density) { grid.height.toDp() })
                .graphicsLayer { alpha = opacity() }
                .clip(RoundedCornerShape(PickerDissolve.gridRadius))
                .background(OpenCodeTheme.colors.canvas)
                .then(
                    if (visible) Modifier.semantics { testTag = "inline-photo-picker" }
                    else Modifier
                        .clearAndSetSemantics {}
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                ),
        ) { content() }
    }
}

/** The card's colour over the picker (iOS `pickerCover(_:)`). */
@Composable
private fun PickerCover(frames: AttachmentMenuFrames, window: () -> Rect, opacity: () -> Float, dragOffset: () -> Float) {
    val density = LocalDensity.current
    val grid = frames.grid
    val color = panelColor()
    Box(Modifier.fillMaxSize().glassClip(window).clearAndSetSemantics {}) {
        Box(
            Modifier
                .offset { IntOffset(grid.left.roundToInt(), (grid.top + dragOffset()).roundToInt()) }
                .requiredSize(with(density) { grid.width.toDp() }, with(density) { grid.height.toDp() })
                .graphicsLayer { alpha = opacity() }
                .background(color, RoundedCornerShape(PickerDissolve.gridRadius)),
        )
    }
}

/**
 * Back and the filter along the card's top edge (iOS `cardControls(_:)`). They ride the
 * surface's edge, show only near the card, and arrive once it has mostly grown; on the way back
 * they fade with the morph. Only the card's take touches.
 */
@Composable
private fun CardControls(
    card: () -> Rect,
    fade: () -> Float,
    interactive: Boolean,
    reduceMotion: Boolean,
    filter: InlinePhotoFilter,
    onFilter: (InlinePhotoFilter) -> Unit,
    includesVideos: Boolean,
    onBack: () -> Unit,
) {
    val inset = AttachmentMenuMetrics.cardInset
    val header = AttachmentMenuMetrics.cardHeaderHeight
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, MotionSpring(0.2).animation(false, delaySeconds = if (reduceMotion) 0.0 else 0.14)) }
    val density = LocalDensity.current
    val headerPx = with(density) { header.toPx() }
    val insetPx = with(density) { inset.toPx() }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val rect = card()
                val width = max(rect.width, headerPx + insetPx * 2).roundToInt()
                val height = (headerPx + insetPx * 2).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((rect.center.x - width / 2f).roundToInt(), rect.top.roundToInt())
                }
            }
            .graphicsLayer { alpha = fade() * arrival.value }
            .then(if (interactive) Modifier.semantics { isTraversalGroup = true } else Modifier.clearAndSetSemantics {})
            .padding(inset),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(header)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .then(
                        if (interactive) {
                            Modifier
                                .clickable(onClickLabel = "Return to attachment options", role = Role.Button, onClick = onBack)
                                .openCodeTapTarget(header)
                        } else Modifier,
                    )
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.ChevronLeft, contentDescription = null, tint = OpenCodeTheme.colors.text)
            }
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            var expanded by remember { mutableStateOf(false) }
            if (!interactive && expanded) expanded = false
            Box {
                Row(
                    Modifier
                        .heightIn(min = header)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .then(
                            if (interactive) {
                                Modifier
                                    .clickable(role = Role.DropdownList) { expanded = true }
                                    .openCodeTapTarget(header)
                            } else Modifier,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Show"
                            stateDescription = filter.title
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        filter.title,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    OpenCodeIcon(SFSymbol.ChevronUpChevronDown, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText)
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
    }
}

/**
 * The picker's cross-dissolve with the menu (iOS `PickerDissolve`). The picker stands at its
 * final frame on the card while the surface resizes around it ([glassClip]), and its content
 * cross-fades with the menu's rows. It is either drawn or not ([pickerOpacity]), under a cover
 * of the card's colour that fades instead ([coverOpacity]): the cover gathers over the fading
 * rows, the picker switches on beneath it once they're gone, and the cover clears to reveal it.
 * The dissolve runs on its own clock: toward the card a little behind the surface's spring; on
 * Back quickly, before the surface shrinks.
 */
internal object PickerDissolve {
    /** Where the rows have faded out and the picker shows under its cover. */
    const val SHOWS = 0.38f
    val gridRadius: Dp = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.cardInset
    /** Back waits this long for the picker to dissolve before the surface shrinks. */
    const val BACK_DELAY = 0.08

    /** Toward the card, a little behind the surface's spring; back to the menu, quickly, ahead of it. */
    fun <T> animation(toCard: Boolean): FiniteAnimationSpec<T> =
        if (toCard) Motion.easeInOut(0.42) else Motion.easeOut(0.2)

    /** Exactly drawn or not: never translucent. */
    fun pickerOpacity(progress: Float): Float = if (progress < SHOWS) 0f else 1f

    /** Gathers over the rows as they fade, then clears to reveal the picker. */
    fun coverOpacity(progress: Float): Float =
        if (progress < SHOWS) smoothstep(0.16f, SHOWS, progress) else 1 - smoothstep(SHOWS + 0.04f, 0.96f, progress)

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
