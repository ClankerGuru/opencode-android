package ai.opencode.feature.drawer.list

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates

/*
 * Port of iOS `DrawerRowInteraction.swift`: a drawer row's touches. A tap opens the session; a
 * finger resting on the row for [DRAWER_ROW_PRESS_DELAY_MS], or a tap, tells Home first (`pressed`,
 * so its transcript warms), but never a finger that scrolls the list or lands on a moving one
 * (iOS `Coordinator.pressDelay`); a hold opens the menu
 * over the lifted row; moving the held finger lifts the row to pin, unpin or reorder it
 * ([DrawerPinDrag]), as iOS's context menu and drag interactions share one view.
 *
 * UIKit's `UIContextMenuInteraction` + `UIDragInteraction` become one pointer handler:
 * - a tap lands on touch-up and only if the drawer's pan never chose the touch (the pan consumes
 *   it in the `Initial` pass, iOS's `shouldRequireFailureOf: DirectionalPanGestureRecognizer`);
 * - the hold (the platform long-press timeout) plays `Haptic.tap` (a menu opening) and shows the
 *   row's menu; the row shows lifted on a raised surface (iOS `DrawerHoldStyle.inPlace`);
 * - moving the held finger past the touch slop closes the menu and starts the drag (iOS's drag
 *   growing out of the menu's lift), which the finger then owns: the list never scrolls under it;
 * - lifting the finger without moving leaves the menu up, as iOS does.
 * iOS's frozen-bitmap lift (`liftView`, `cover`, `LiftTouchObserver`'s late release) exists only
 * because UIKit lifts a snapshot; Compose draws the live row raised, so none of that is needed.
 * Not ported: "Open in New Window" (iPad Multiple Windows; one task on Android), tapping the
 * lifted preview to open the row (the menu is a popup beside the row), the lift trace.
 * iOS has no swipe actions on drawer rows (a sideways drag reveals the rows' times), so neither
 * does Android.
 */

/** One entry of a row's menu (iOS `UIAction`). */
data class DrawerRowMenuItem(
    val title: String,
    val symbol: SFSymbol,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/** What a row's touches do, as closures: the row never holds a store. */
class DrawerRowInteraction(
    val sessionID: String,
    /** Session rows lift by a long-press drag; other rows only open the menu. */
    val reorderable: Boolean,
    val isPinnedSection: Boolean = false,
    val select: () -> Unit,
    /**
     * A finger rested on the row ([DRAWER_ROW_PRESS_DELAY_MS]) or tapped it, ahead of the tap's
     * selection; never a finger that scrolls the list.
     */
    val pressed: () -> Unit,
    val menu: () -> List<DrawerRowMenuItem> = { emptyList() },
    /**
     * Android: the hold chooses the row for selection mode ([DrawerSelection]) in place of the
     * menu; a drag growing out of the hold takes it back.
     */
    val held: (() -> Unit)? = null,
    /** The list is scrolling or flinging (iOS `Coordinator.isMoving`). */
    val isListMoving: () -> Boolean = { false },
    /** The held finger moved: the drag begins at [fingerInList] (dp, the list's coordinates). */
    val beginDrag: ((fingerInList: Offset) -> Unit)? = null,
    /** The finger moved while dragging (dp, the list's coordinates). */
    val moveDrag: (fingerInList: Offset, uptimeMillis: Long) -> Unit = { _, _ -> },
    /** The finger lifted ([cancelled] when the stream was taken away). */
    val endDrag: (fingerInList: Offset, cancelled: Boolean) -> Unit = { _, _ -> },
    val lifted: (String?) -> Unit = {},
)

/**
 * How long a finger rests on a row before it's a press (the session's transcript starts loading),
 * unless the list starts scrolling first (iOS `Coordinator.pressDelay`).
 */
const val DRAWER_ROW_PRESS_DELAY_MS = 80L

/** How a finger's first moments on a row ended. */
private enum class DrawerRowTouch { Tapped, Taken, Rested }

/** A row's hold: its menu on screen, or a drag under way (iOS `Coordinator.isMenuVisible`/`isDragActive`). */
@Stable
class DrawerRowHoldState {
    var isMenuVisible: Boolean by mutableStateOf(false)
    var isDragActive: Boolean by mutableStateOf(false)
        internal set

    /** The row reads lifted: its menu is up or it's being dragged. */
    val isHeld: Boolean get() = isMenuVisible || isDragActive

    fun dismissMenu() {
        isMenuVisible = false
    }
}

/**
 * The row's touches (iOS `DrawerRowInteraction.install(on:coordinator:)`). [list] and [row]
 * convert the finger into the list's coordinates for the drag; [density] is read from the
 * pointer scope.
 */
fun Modifier.drawerRowInteraction(
    interaction: DrawerRowInteraction,
    hold: DrawerRowHoldState,
    list: () -> LayoutCoordinates?,
    row: () -> LayoutCoordinates?,
): Modifier = composed {
    val current by rememberUpdatedState(interaction)
    val haptics = androidx.compose.runtime.remember { dependency(HapticsClient) }
    pointerInput(hold) {
        fun toList(position: Offset): Offset {
            val listCoordinates = list()
            val rowCoordinates = row()
            val inList = if (listCoordinates != null && rowCoordinates != null && listCoordinates.isAttached && rowCoordinates.isAttached) {
                listCoordinates.localPositionOf(rowCoordinates, position)
            } else {
                position
            }
            return Offset(inList.x / density, inList.y / density)
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointer = down.id
            // A new touch on the row: an earlier hold's menu never lingers.
            if (hold.isMenuVisible) hold.dismissMenu()
            val slop = viewConfiguration.touchSlop

            // Waits up to [timeoutMillis] for the finger to lift (a tap), or for a drag someone
            // else (the list, the drawer) takes; otherwise the finger rested.
            suspend fun AwaitPointerEventScope.awaitTouch(timeoutMillis: Long): DrawerRowTouch {
                var outcome = DrawerRowTouch.Rested
                withTimeoutOrNull(timeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer }
                        if (change == null || change.isConsumed) {
                            outcome = DrawerRowTouch.Taken
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            outcome = DrawerRowTouch.Tapped
                            break
                        }
                        if ((change.position - down.position).getDistance() > slop) {
                            outcome = DrawerRowTouch.Taken
                            break
                        }
                    }
                }
                return outcome
            }

            // A finger landing on a moving list stops it, and isn't choosing a row: no press.
            val landedOnMovingList = current.isListMoving()
            var touch = awaitTouch(DRAWER_ROW_PRESS_DELAY_MS)
            val pressDue = touch == DrawerRowTouch.Tapped || touch == DrawerRowTouch.Rested
            if (pressDue && !landedOnMovingList && !current.isListMoving()) current.pressed()

            // Before the hold: a tap, or a drag someone else takes.
            if (touch == DrawerRowTouch.Rested) {
                touch = awaitTouch(viewConfiguration.longPressTimeoutMillis - DRAWER_ROW_PRESS_DELAY_MS)
            }
            if (touch == DrawerRowTouch.Tapped) {
                current.select()
                return@awaitEachGesture
            }
            if (touch == DrawerRowTouch.Taken) return@awaitEachGesture

            // The hold: the menu over the lifted row, or the row chosen.
            haptics.play(Haptic.tap)
            val held = current.held
            if (held != null) held() else hold.isMenuVisible = true
            var start: Offset? = null
            var dragging = false
            var last: PointerInputChange? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointer }
                if (change == null) {
                    if (dragging) {
                        hold.isDragActive = false
                        current.endDrag(last?.let { toList(it.position) } ?: Offset.Zero, true)
                    }
                    return@awaitEachGesture
                }
                last = change
                if (dragging) {
                    change.consume()
                    if (!change.pressed) {
                        hold.isDragActive = false
                        current.endDrag(toList(change.position), false)
                        return@awaitEachGesture
                    }
                    current.moveDrag(toList(change.position), change.uptimeMillis)
                    continue
                }
                // The hold owns the finger now: neither the list nor the drawer takes it.
                change.consume()
                if (!change.pressed) return@awaitEachGesture // The menu stays up.
                val origin = start ?: change.position.also { start = it }
                val begin = current.beginDrag
                if (current.reorderable && begin != null && (change.position - origin).getDistance() > slop) {
                    hold.dismissMenu()
                    held?.invoke()
                    hold.isDragActive = true
                    dragging = true
                    current.lifted(current.sessionID)
                    begin(toList(change.position))
                }
            }
        }
    }
}

/** The row's menu (iOS `rowMenu`'s `UIMenu`), anchored on the row while [hold] shows it. */
@Composable
fun DrawerRowMenu(hold: DrawerRowHoldState, items: () -> List<DrawerRowMenuItem>) {
    DropdownMenu(expanded = hold.isMenuVisible, onDismissRequest = { hold.dismissMenu() }) {
        if (hold.isMenuVisible) {
            for (item in items()) {
                val tint = if (item.destructive) OpenCodeTheme.colors.destructive else OpenCodeTheme.colors.text
                DropdownMenuItem(
                    text = { Text(item.title, color = tint) },
                    leadingIcon = { OpenCodeIcon(item.symbol, contentDescription = null, tint = tint) },
                    onClick = {
                        hold.dismissMenu()
                        item.action()
                    },
                )
            }
        }
    }
}
