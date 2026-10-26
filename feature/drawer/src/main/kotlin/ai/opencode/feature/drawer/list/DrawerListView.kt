package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.store.StoreOf
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.icons.resolved
import ai.opencode.feature.drawer.isPinned
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import ai.opencode.feature.drawer.motion.DrawerShortcutInputs
import ai.opencode.feature.drawer.motion.DrawerShortcutMotion
import ai.opencode.feature.drawer.motion.DrawerTimeReveal
import ai.opencode.feature.drawer.motion.DrawerTimestamp
import ai.opencode.feature.drawer.motion.drawerPanList
import ai.opencode.feature.drawer.pinnedSessions
import ai.opencode.feature.drawer.recentSessions
import ai.opencode.feature.drawer.style.DrawerEmptyPinDropZone
import ai.opencode.feature.drawer.style.DrawerHeaderScrollState
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.visibleSessions
import ai.opencode.server.model.OpenCodeProject
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Port of iOS `DrawerListController.swift` (+ its SwiftUI host `DrawerListView`): the drawer's
 * session list as a `LazyColumn` keyed by [DrawerListItem.key]. The title, search, New Session
 * and Settings lie around it in `HomeDrawer`.
 *
 * Home's state reaches the rows through one model build ([DrawerListModel.build]) in a derived
 * state; unchanged rows keep their instances, so only rows whose values changed recompose (iOS
 * reconfigures only those cells). iOS flushes observation on the layout pass so a pin drop's store
 * change is laid out before `DrawerPinDrag` reads the rows' new places; here the list reads Home's
 * state through its own snapshot value, which [DrawerPinDragHost.flushNow] sets at once, so the
 * drop recomposes and lays out in the frame that commits it.
 *
 * The time reveal: rows read the drawer's live [DrawerTimeReveal] while a drag moves them; it
 * lands on zero at release, so the list springs the rows back from where they were on iOS's
 * spring (0.4 s, bounce 0.2; Reduce Motion: the times fade over 0.2 s).
 */

/** What the list does that only `HomeDrawer` can (iOS `DrawerListActions`). */
class DrawerListActions(
    val select: (sessionID: String) -> Unit,
    /** The row's hold menu (iOS `rowMenu(_:isPinned:)`). */
    val menu: (DrawerListRow) -> List<DrawerRowMenuItem>,
    val openCatchUp: () -> Unit,
    val openWhatsNew: () -> Unit,
    val hideWhatsNew: () -> Unit,
)

@Stable
internal class DrawerListReveal(private val timeReveal: DrawerTimeReveal) : DrawerListRevealValues {
    /** Where the rows rest while no drag moves them: the last drag's place, springing back to zero. */
    var heldOffset: Float by mutableFloatStateOf(0f)
    var heldProgress: Float by mutableFloatStateOf(0f)

    private val isLive: Boolean get() = timeReveal.offset != 0f || timeReveal.progress != 0f
    override val offset: Float get() = if (isLive) timeReveal.offset else heldOffset
    override val progress: Float get() = if (isLive) timeReveal.progress else heldProgress
}

/** The last model built, so unchanged rows keep their instances. */
private class DrawerListModelHolder {
    var model = DrawerListModel()
}

/**
 * The drawer's session list.
 *
 * @param inputs the list's view-local values (selection, environment).
 * @param isDrawerVisible the drawer shows (iOS `isDrawerVisible`): the shortcut rows animate only then.
 * @param catchUpFocusRequests bumped when leaving Catch Up hands TalkBack back to its row.
 * @param contentPadding room under the title and above New Session and Settings.
 * @param panRegions the drawer pan's regions: a rightward drag on the list reveals the rows' times.
 */
@Composable
fun DrawerListView(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    pinDrag: DrawerPinDrag,
    timeReveal: DrawerTimeReveal,
    headerScroll: DrawerHeaderScrollState,
    shortcutMotion: DrawerShortcutMotion,
    inputs: DrawerListInputs,
    isDrawerVisible: Boolean,
    catchUpFocusRequests: Int,
    actions: DrawerListActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    panRegions: DrawerPanRegions? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    remember { DrawerListPerfProbe.mountStarted(); true }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val frameDuration = rememberFrameDurationSeconds()
    val currentActions by rememberUpdatedState(actions)
    val currentInputs = rememberUpdatedState(inputs)

    // Home's state as the list reads it; `flushNow` sets it at once for a drop.
    val homeState = remember(store) { mutableStateOf(store.state, referentialEqualityPolicy()) }
    LaunchedEffect(store) { store.states.collect { homeState.value = it } }
    val liftedID = remember { mutableStateOf<String?>(null) }
    val holder = remember { DrawerListModelHolder() }
    // The rows' times follow the device's 24-hour setting (iOS `.shortened` follows the user's).
    val timeContext = LocalContext.current
    val hour24 = remember(timeContext) { android.text.format.DateFormat.is24HourFormat(timeContext) }

    // Catch Up and What’s New stay in the list while they fold away.
    val catchUpMounted = shortcutMotion.isCatchUpMounted(fallback = holder.model.shortcuts.showsCatchUp)
    val whatsNewMounted = shortcutMotion.isWhatsNewMounted(fallback = holder.model.shortcuts.showsWhatsNew)
    val mounted = rememberUpdatedState(catchUpMounted to whatsNewMounted)

    val model by remember {
        derivedStateOf {
            val next = DrawerListModel.build(
                state = homeState.value,
                inputs = currentInputs.value,
                liftedID = liftedID.value,
                catchUpMounted = mounted.value.first,
                whatsNewMounted = mounted.value.second,
                now = Instant.now(),
                timestamp = { date, now -> DrawerTimestamp.text(date, now, hour24 = hour24) },
                project = { session, projects -> OpenCodeProject.resolved(session, projects) },
            )
            stabilized(holder.model, next).also { holder.model = it }
        }
    }
    val isSettingsShown by remember { derivedStateOf { homeState.value.isSettingsShown } }

    // The shortcut rows' spring follows what the list shows.
    val shortcutInputs = DrawerShortcutInputs(
        showsCatchUp = model.shortcuts.showsCatchUp,
        catchUpCount = model.shortcuts.catchUpCount,
        catchUpFinish = model.shortcuts.catchUpFinish,
        canMarkAllRead = model.shortcuts.canMarkAllRead,
        showsWhatsNew = model.shortcuts.showsWhatsNew,
        searchIsEmpty = model.search.isEmpty(),
        serverID = model.serverID,
        isSettingsShown = isSettingsShown,
        isDrawerVisible = isDrawerVisible,
        reducedMotion = model.reducedMotion,
    )
    val lastShortcutInputs = remember { arrayOfNulls<DrawerShortcutInputs>(1) }
    SideEffect {
        if (shortcutInputs != lastShortcutInputs[0]) {
            lastShortcutInputs[0] = shortcutInputs
            shortcutMotion.sync(shortcutInputs)
        }
    }

    // The time reveal, sprung back from where the rows were.
    val reveal = remember(timeReveal) { DrawerListReveal(timeReveal) }
    LaunchedEffect(reveal, inputs.reducedMotion, frameDuration) {
        snapshotFlow { timeReveal.offset to timeReveal.progress }.collectLatest { (offset, progress) ->
            if (offset != 0f || progress != 0f) {
                reveal.heldOffset = offset
                reveal.heldProgress = progress
                return@collectLatest
            }
            if (reveal.heldOffset <= 0.5f && reveal.heldProgress <= 0.01f) {
                reveal.heldOffset = 0f
                reveal.heldProgress = 0f
                return@collectLatest
            }
            springBack(reveal, inputs.reducedMotion, frameDuration)
        }
    }

    // The header's edge follows how far rows have scrolled beneath the title.
    LaunchedEffect(listState, headerScroll) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, px) ->
            val fade = max(DrawerTunables.fade.value, 1f)
            val scrolled = if (index == 0) min(px / density.density, fade) else fade
            headerScroll.update(((scrolled * 2).roundToInt() / 2f).dp)
        }
    }

    // The drawer doesn't make room for search's keyboard; a drag on the list puts it away.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) keyboard?.hide() }
    }

    // The pin drag reads the rows' places from the list.
    val listCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val host = remember(listState, scope) {
        object : DrawerPinDragHost {
            override fun visibleItems(): List<DrawerPinDragItem> {
                val info = listState.layoutInfo
                val start = info.viewportStartOffset
                return info.visibleItemsInfo.mapNotNull { item ->
                    val key = pinDragKey(item.key) ?: return@mapNotNull null
                    DrawerPinDragItem(key, (item.offset - start) / density.density, item.size / density.density)
                }
            }

            override val width: Float get() = (listCoordinates[0]?.size?.width ?: 0) / density.density

            override val viewportTop: Float get() = -listState.layoutInfo.viewportStartOffset / density.density

            override fun flushNow() {
                homeState.value = store.state
            }

            override fun preserveScroll() {
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }

            override val scope: CoroutineScope get() = scope
            override val frameDurationSeconds: Double get() = frameDuration
        }
    }
    DisposableEffect(pinDrag, host) {
        pinDrag.attach(host)
        onDispose { pinDrag.detach(host) }
    }

    fun beginPinDrag(id: String, fingerY: Float) {
        val state = store.state
        val unpinnedOrder = state.visibleSessions
            .filter { it.id == id || !state.isPinned(it.id) }
            .sortedByDescending { it.updatedAt }
            .map { it.id }
        pinDrag.beginDrag(
            sessionID = id,
            isPinnedSection = state.isPinned(id),
            pinnedIDs = state.pinnedSessions.map { it.id },
            recentIDs = state.recentSessions.map { it.id },
            unpinnedRecentsIndex = unpinnedOrder.indexOf(id).coerceAtLeast(0),
            showsShortcuts = state.search.isEmpty() && (mounted.value.first || mounted.value.second),
            reducedMotion = currentInputs.value.reducedMotion,
            fingerY = fingerY,
            onZoneCrossed = { store.send(HomeFeature.Action.DrawerPinZoneCrossed(it)) },
            onSlotChanged = { store.send(HomeFeature.Action.DrawerPinSlotChanged) },
            onDropReleased = { sessionID, index -> store.send(HomeFeature.Action.DrawerRowDropReleased(sessionID, index)) },
            onDrop = { sessionID, index ->
                store.send(HomeFeature.Action.DrawerRowDropped(sessionID, index))
                host.flushNow()
            },
            onSetLifted = { liftedID.value = it },
        )
    }

    // Leaving Catch Up: TalkBack lands on the Catch Up row, or on the highlighted session.
    val catchUpFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    val answeredFocusRequests = remember { intArrayOf(catchUpFocusRequests) }
    val context = LocalContext.current
    LaunchedEffect(catchUpFocusRequests) {
        if (catchUpFocusRequests == answeredFocusRequests[0]) return@LaunchedEffect
        answeredFocusRequests[0] = catchUpFocusRequests
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        if (accessibility?.isTouchExplorationEnabled != true) return@LaunchedEffect
        // Once the drawer is back in the accessibility tree (after this update).
        withFrameNanos {}
        val target = if (DrawerListItem.CatchUp in model.items) catchUpFocus else selectedFocus
        runCatching { target.requestFocus() }
    }

    // Another server's Home arriving in place fades the list in.
    val listAlpha = remember { Animatable(1f) }
    val shownServer = remember { arrayOf(model.serverID) }
    LaunchedEffect(model.serverID) {
        if (shownServer[0] == model.serverID) return@LaunchedEffect
        shownServer[0] = model.serverID
        if (currentInputs.value.reducedMotion) return@LaunchedEffect
        listAlpha.snapTo(0f)
        listAlpha.animateTo(1f, tween(250, easing = Motion.EaseInOut))
    }

    if (DrawerListPerfProbe.isEnabled) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.count { it.key is String && (it.key as String).startsWith("session-") } }
                .collect { DrawerListPerfProbe.frame(it, scope) }
        }
    }

    // A list resting at its top stays there when a row arrives above the first one (Catch Up
    // or Pinned coming back), as iOS's collection view keeps its zero offset; LazyColumn would
    // otherwise keep the old first row in place and leave the new one scrolled under the title.
    val restsAtTop = remember { booleanArrayOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { restsAtTop[0] = it }
    }
    val firstKey = model.items.firstOrNull()?.key
    val shownFirstKey = remember { arrayOfNulls<String>(1) }
    if (shownFirstKey[0] != firstKey) {
        if (shownFirstKey[0] != null && restsAtTop[0]) listState.requestScrollToItem(0, 0)
        shownFirstKey[0] = firstKey
    }

    Box(modifier) {
        // Beneath the rows: they move out of its way, and the lifted row (drawn in the list)
        // stays above it, as iOS's system platter floats above the zone.
        DrawerPinDropZoneOverlay(pinDrag)
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { listCoordinates[0] = it }
                .then(if (panRegions != null) Modifier.drawerPanList(panRegions) else Modifier)
                .graphicsLayer { alpha = listAlpha.value }
                .testTag("drawer-list"),
        ) {
            val current = model
            items(current.items, key = { it.key }, contentType = { contentType(it) }) { item ->
                when (item) {
                    DrawerListItem.CatchUp, DrawerListItem.WhatsNew -> DrawerListShortcutRow(
                        kind = if (item == DrawerListItem.CatchUp) DrawerListShortcutKind.CatchUp else DrawerListShortcutKind.WhatsNew,
                        motion = shortcutMotion,
                        shortcuts = current.shortcuts,
                        reducedMotion = current.reducedMotion,
                        openCatchUp = { currentActions.openCatchUp() },
                        markAllRead = { store.send(HomeFeature.Action.CatchUp(ai.opencode.feature.drawer.catchup.CatchUpFeature.Action.MarkAllReadTapped)) },
                        openWhatsNew = { currentActions.openWhatsNew() },
                        hideWhatsNew = { currentActions.hideWhatsNew() },
                        focusRequester = if (item == DrawerListItem.CatchUp) catchUpFocus else null,
                    )
                    DrawerListItem.DeletionError -> DrawerListDeletionErrorRow(current.deletionError.orEmpty())
                    is DrawerListItem.Header -> DrawerListHeaderCell(item.kind, pinDrag)
                    DrawerListItem.Empty -> DrawerListEmptyRow(current.search)
                    is DrawerListItem.Session -> {
                        val row = current.rows[item.id] ?: return@items
                        val rowActions = remember(row.session.id, row.unread, row.isPinnedSection) {
                            rowActions(row, store, holder, pinDrag, liftedID, { currentActions }, ::beginPinDrag)
                        }
                        DrawerListSessionCell(
                            row = row,
                            actions = rowActions,
                            reveal = reveal,
                            pinDrag = pinDrag,
                            listCoordinates = { listCoordinates[0] },
                            focusRequester = if (row.selected) selectedFocus else null,
                        )
                    }
                }
            }
        }
    }
}

/** The empty-pins drop zone at the list's top while a drag opens it (iOS's hosted `DrawerEmptyPinDropZone`). */
@Composable
private fun DrawerPinDropZoneOverlay(pinDrag: DrawerPinDrag) {
    val zone = pinDrag.dropZone ?: return
    DrawerEmptyPinDropZone(
        openProgress = zone.openProgress,
        hoverProgress = zone.hoverProgress,
        headerHeight = zone.headerHeight.dp,
        reducedMotion = zone.reducedMotion,
        modifier = Modifier
            .offset { IntOffset(0, (zone.top * density).roundToInt()) }
            .fillMaxWidth()
            .height(zone.height.dp)
            .clipToBounds(),
    )
}

private fun rowActions(
    row: DrawerListRow,
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    holder: DrawerListModelHolder,
    pinDrag: DrawerPinDrag,
    liftedID: androidx.compose.runtime.MutableState<String?>,
    actions: () -> DrawerListActions,
    beginPinDrag: (String, Float) -> Unit,
): DrawerListRowActions {
    val id = row.session.id
    val unread = row.unread
    return DrawerListRowActions(
        select = { actions().select(id) },
        pressed = { store.send(HomeFeature.Action.DrawerRowPressed(id)) },
        menu = { holder.model.rows[id]?.let { actions().menu(it) }.orEmpty() },
        beginDrag = { y -> beginPinDrag(id, y) },
        moveDrag = { y -> pinDrag.updateDrag(y) },
        endDrag = { x, y, cancelled ->
            if (cancelled) {
                pinDrag.cancelDrag(deferLiftedClear = true)
            } else {
                pinDrag.endDrag(x, y)
            }
            // A drag that never began (no list to move) leaves no lift behind (iOS
            // `dragInteraction(_:session:didEndWith:)` clears it whatever happened).
            if (pinDrag.draggingID == null) liftedID.value = null
        },
        dismantled = { if (pinDrag.activeSessionID == id) pinDrag.cancelDrag() },
        lifted = { liftedID.value = it },
        togglePin = { store.send(HomeFeature.Action.TogglePin(id)) },
        toggleRead = { store.send(if (unread) HomeFeature.Action.MarkRead(id) else HomeFeature.Action.MarkUnread(id)) },
        toggleMute = { store.send(HomeFeature.Action.ToggleMute(id)) },
        delete = { store.send(HomeFeature.Action.DeleteSessionTapped(id)) },
        movePinUp = { store.send(HomeFeature.Action.MovePinUp(id)) },
        movePinDown = { store.send(HomeFeature.Action.MovePinDown(id)) },
    )
}

/** iOS `DrawerTimeReveal`'s release: springs the rows back from where they are. */
private suspend fun springBack(reveal: DrawerListReveal, reducedMotion: Boolean, frameDuration: Double) {
    val startOffset = reveal.heldOffset
    val startProgress = reveal.heldProgress
    val spring = if (reducedMotion) {
        SpringSolver.of(duration = 0.2, bounce = 0.0, value = 1.0, target = 0.0, positionEpsilon = 0.002, velocityEpsilon = 0.02)
    } else {
        SpringSolver.of(duration = 0.4, bounce = 0.2, value = startOffset.toDouble(), target = 0.0, positionEpsilon = 0.1, velocityEpsilon = 1.0)
    }
    motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
        val step = spring.step(tick.dt)
        if (step.isSettled) {
            reveal.heldOffset = 0f
            reveal.heldProgress = 0f
            return@motionFrameLoop MotionFrameAction.Stop
        }
        val value = max(step.value, 0.0).toFloat()
        if (reducedMotion) {
            reveal.heldOffset = 0f
            reveal.heldProgress = startProgress * value
        } else {
            val fraction = if (startOffset > 0) min(value / startOffset, 1f) else 0f
            reveal.heldOffset = step.value.toFloat()
            reveal.heldProgress = startProgress * fraction
        }
        MotionFrameAction.Continue
    }
}

/** Keeps unchanged rows' instances, so their cells skip recomposition. */
private fun stabilized(previous: DrawerListModel, next: DrawerListModel): DrawerListModel {
    if (previous.rows.isEmpty()) return next
    val rows = LinkedHashMap<String, DrawerListRow>(next.rows.size)
    for ((id, row) in next.rows) rows[id] = previous.rows[id]?.takeIf { it == row } ?: row
    return next.copy(rows = rows)
}

private fun contentType(item: DrawerListItem): Int = when (item) {
    DrawerListItem.CatchUp, DrawerListItem.WhatsNew -> 0
    DrawerListItem.DeletionError -> 1
    is DrawerListItem.Header -> 2
    is DrawerListItem.Session -> 3
    DrawerListItem.Empty -> 4
}

/** A `LazyColumn` key back to the pin drag's item (headers and rows only). */
private fun pinDragKey(key: Any): DrawerPinDrag.ItemKey? {
    val text = key as? String ?: return null
    return when {
        text.startsWith("session-") -> DrawerPinDrag.ItemKey.Row(text.removePrefix("session-"))
        text == DrawerListItem.Header(DrawerSectionHeaderKind.Pinned).key -> DrawerPinDrag.ItemKey.PinnedHeader
        text == DrawerListItem.Header(DrawerSectionHeaderKind.Recents).key -> DrawerPinDrag.ItemKey.RecentsHeader
        else -> null
    }
}
