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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates

/*
 * Port of iOS `DrawerRowInteraction.swift`: a drawer row's touches. A tap opens the session; a
 * finger coming down tells Home first (`pressed`, so its transcript warms); a hold opens the menu
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
    /** A finger came down on the row, ahead of any tap. */
    val pressed: () -> Unit,
    val menu: () -> List<DrawerRowMenuItem>,
    /** The held finger moved: the drag begins at [fingerInList] (dp, the list's coordinates). */
    val beginDrag: ((fingerInList: Offset) -> Unit)? = null,
    /** The finger moved while dragging (dp, the list's coordinates). */
    val moveDrag: (fingerInList: Offset, uptimeMillis: Long) -> Unit = { _, _ -> },
    /** The finger lifted ([cancelled] when the stream was taken away). */
    val endDrag: (fingerInList: Offset, cancelled: Boolean) -> Unit = { _, _ -> },
    val lifted: (String?) -> Unit = {},
)

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
            current.pressed()
            val slop = viewConfiguration.touchSlop

            // Before the hold: a tap, or a drag someone else (the list, the drawer) takes.
            var tapped = false
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        tapped = true
                        change.consume()
                        break
                    }
                    if ((change.position - down.position).getDistance() > slop) break
                }
                Unit
            } == null
            if (tapped) {
                current.select()
                return@awaitEachGesture
            }
            if (!held) return@awaitEachGesture

            // The hold: the menu over the lifted row.
            haptics.play(Haptic.tap)
            hold.isMenuVisible = true
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
