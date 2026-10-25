package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptRowContext
import ai.opencode.feature.transcript.rows.NewMessagesDivider
import ai.opencode.feature.transcript.rows.LocalTranscriptRowSlots
import ai.opencode.feature.transcript.rows.TranscriptRowSlots
import ai.opencode.feature.transcript.links.LinkPreviewReplyEnd
import ai.opencode.feature.transcript.markdown.ui.LocalTranscriptTextReveal
import ai.opencode.feature.transcript.markdown.ui.TranscriptTextReveal
import ai.opencode.feature.transcript.user.TranscriptMessageRow
import ai.opencode.feature.transcript.user.UserMessageRun
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Slots for rows whose views live above this module (iOS reads them directly from one module):
 * the Working line (`Session/SessionTranscriptView.swift` `WorkingLineRow` with its activity orb).
 * Null draws a plain fallback.
 */
class TranscriptSlots(
    val workingLine: (@Composable () -> Unit)? = null,
)

/**
 * The transcript list (iOS `TranscriptListView` + `TranscriptListViewController`): a LazyColumn
 * with stable keys and content types, opening at the bottom and following it while the reader
 * is there, browsing otherwise. [contentPadding] is the header above and the composer's own
 * obstruction below (iOS `contentInset`); the keyboard is the session's `imePadding`.
 * [jumpRequest] increments when the reader taps Jump to latest; [onShowsJumpButtonChanged]
 * reports the behavior's button visibility.
 */
@Composable
fun TranscriptList(
    configuration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    jumpRequest: Int = 0,
    onShowsJumpButtonChanged: (Boolean) -> Unit = {},
    slots: TranscriptSlots = TranscriptSlots(),
    layoutTuning: TranscriptLayoutTuning = LocalTranscriptLayoutTuning.current,
    onScrolledUnderHeader: (Float) -> Unit = {},
) {
    // A new session is a new list (iOS resets the controller; the draft becoming its session keeps it).
    key(configuration.sessionID ?: "draft", configuration.historyGeneration) {
        TranscriptListContent(configuration, send, modifier, contentPadding, jumpRequest, onShowsJumpButtonChanged, slots, layoutTuning, onScrolledUnderHeader)
    }
}

@Composable
private fun TranscriptListContent(
    configuration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    jumpRequest: Int,
    onShowsJumpButtonChanged: (Boolean) -> Unit,
    slots: TranscriptSlots,
    layoutTuning: TranscriptLayoutTuning,
    onScrolledUnderHeader: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initialIndex = remember { (configuration.entries.size + configuration.queuedInputs.size + 2).coerceAtLeast(0) }
    val listState = remember { LazyListState(firstVisibleItemIndex = initialIndex) }
    val controller = remember { TranscriptListController(listState, scope) }
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val currentSend by rememberUpdatedState(send)
    val currentShowsJump by rememberUpdatedState(onShowsJumpButtonChanged)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    controller.send = { currentSend(it) }
    controller.setShowsJumpButton = { currentShowsJump(it) }
    controller.density = density.density
    controller.reduceMotion = reduceMotion
    controller.isShowingToReader = resumed.isAtLeast(Lifecycle.State.RESUMED)
    // Applied in composition so this frame draws the new rows with their stable keys.
    controller.update(configuration)
    SideEffect { controller.flushEffects() }

    LaunchedEffect(controller) {
        snapshotFlow { listState.layoutInfo }.collect { controller.onLayout(it) }
    }
    // iOS `reportHeaderGeometry`: how far content sits above its resting top (dp), for the
    // session header's backdrop reveal. Past the first row it's simply "far".
    val currentScrolledUnderHeader by rememberUpdatedState(onScrolledUnderHeader)
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE
            else listState.firstVisibleItemScrollOffset / density.density
        }.collect { currentScrolledUnderHeader(it) }
    }
    var lastJump by remember { androidx.compose.runtime.mutableIntStateOf(jumpRequest) }
    LaunchedEffect(jumpRequest) {
        if (jumpRequest != lastJump) {
            lastJump = jumpRequest
            controller.jumpToLatest()
        }
    }

    val snapshot = controller.snapshot
    val rowContext = remember(controller.disclosure, configuration.server, configuration.sessionID, configuration.directory) {
        TranscriptRowContext(
            server = configuration.server,
            sessionID = configuration.sessionID,
            directory = configuration.directory,
            disclosure = controller.disclosure,
            send = { action ->
                currentSend(action)
            },
        )
    }
    val gutter = layoutTuning.contentGutter.dp

    // One per list, so a row LazyColumn recreates never fades its text again (iOS controller-owned).
    val textReveal = remember(controller.disclosure) { TranscriptTextReveal() }
    if (controller.runEnded) textReveal.settleAll()
    val rowSlots = remember { TranscriptRowSlots(replyEnd = { key, entryID, texts, actions -> LinkPreviewReplyEnd(texts, key, entryID, actions = actions) }) }
    CompositionLocalProvider(
        LocalTranscriptRowContext provides rowContext,
        LocalTranscriptLayoutTuning provides layoutTuning,
        LocalTranscriptTextReveal provides textReveal,
        LocalTranscriptRowSlots provides rowSlots,
    ) {
        Box(modifier.nestedScroll(controller.nestedScrollConnection)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("transcript-list"),
                contentPadding = contentPadding,
            ) {
                items(snapshot.items, key = { it.key }, contentType = { it.contentType }) { item ->
                    // Centred at the readable width on a wider window (iOS `readableColumn`).
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth()
                        .widthIn(max = TranscriptMetrics.readableColumnWidth)
                        .fillMaxWidth()
                        .padding(horizontal = gutter)
                    when (item) {
                        TranscriptItemID.History -> TranscriptHistoryStatusView(
                            isLoading = configuration.isLoadingEarlier,
                            error = configuration.earlierHistoryError,
                            retry = { currentSend(TranscriptAction.ShowEarlierTapped) },
                            modifier = rowModifier,
                        )
                        TranscriptItemID.Empty -> TranscriptEmptyView(
                            isLoading = configuration.isLoading,
                            showsEmptyState = configuration.showsEmptyState,
                            loadingSince = controller.emptyLoadingSince,
                            pulsePaused = false,
                            modifier = rowModifier.fillParentMaxHeight(),
                        )
                        TranscriptItemID.NewDivider -> NewMessagesDivider(rowModifier)
                        TranscriptItemID.Working -> GrowingRow(controller, item.key, rowModifier) {
                            val working = slots.workingLine
                            if (working != null) {
                                working()
                            } else {
                                WorkingLineFallback(Modifier.padding(vertical = TranscriptMetrics.flowHalf))
                            }
                        }
                        is TranscriptItemID.Message -> {
                            val row = snapshot.rows[item.stableID] ?: return@items
                            val props = snapshot.props[item.stableID] ?: RowProps()
                            val run = snapshot.runs[item.stableID] ?: UserMessageRun.alone
                            GrowingRow(controller, item.key, rowModifier) {
                                when (row) {
                                    is MessageRow.Entry -> TranscriptMessageRow(
                                        rowID = item.stableID,
                                        entry = row.entry,
                                        queued = null,
                                        failure = row.failure,
                                        deliveryNote = row.note,
                                        props = props,
                                        run = run,
                                        revealArrival = row.entry.userMessageID?.let(controller.agentReveal::arrival),
                                    )
                                    is MessageRow.Queued -> if (row.input.isCompaction) {
                                        TranscriptQueuedInputView(row.input, Modifier.padding(vertical = TranscriptMetrics.sectionHalf))
                                    } else {
                                        TranscriptMessageRow(
                                            rowID = item.stableID,
                                            entry = null,
                                            queued = row.input,
                                            failure = null,
                                            deliveryNote = row.note,
                                            props = props,
                                            run = run,
                                            revealArrival = controller.agentReveal.arrival(row.input.id),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A message or Working row as the list hosts it: laid out at its measured height, but reported at
 * the height the controller grows it to (iOS `fittedHeight` + `TopAnchoredRow`), clipped
 * top-anchored behind a soft edge while it grows, fading in when it arrives.
 */
@Composable
private fun GrowingRow(controller: TranscriptListController, key: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .rowEntrance { controller.entrance(key) }
            // Outside the layout below, so the clip and soft edge sit at the reported (growing) height.
            .graphicsLayer {
                clip = controller.isClipping(key)
                compositingStrategy = if (controller.softEdge(key) > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                val edge = controller.softEdge(key) * density
                if (edge > 0.5f) {
                    // The soft edge only ever brightens what it covers (iOS growth mask).
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startY = size.height - edge,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - edge),
                        blendMode = BlendMode.DstOut,
                    )
                }
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val measured = placeable.height / density
                val height = controller.fittedHeight(key, measured, placeable.width / density)
                val px = (height * density).roundToInt().coerceAtLeast(0)
                layout(placeable.width, px) { placeable.place(0, 0) }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}

/** Until the session supplies its Working line (activity orb + title), a quiet label. */
@Composable
private fun WorkingLineFallback(modifier: Modifier = Modifier) {
    Text("Working", modifier, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
}
