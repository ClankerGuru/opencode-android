package ai.opencode.feature.transcript.list

import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptRowIdentityPlan
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The transcript list's engine: a plain object (iOS `TranscriptListViewController` minus UIKit),
 * owned by the list for one session. It turns configurations into row snapshots with stable
 * identities, feeds [TranscriptScrollBehavior] from LazyColumn layouts and the reader's
 * gestures, and executes its commands with one frame loop that drives every transcript motion:
 * the follower ([BottomSpring]), Jump to latest ([JumpGlide]), row growth ([RowGrowth]) and row
 * entrances. Geometry is in dp, like iOS points.
 */
@Stable
class TranscriptListController(
    val state: LazyListState,
    private val scope: CoroutineScope,
) {
    // Wiring the composable refreshes every composition.
    var send: (TranscriptAction) -> Unit = {}
    var setShowsJumpButton: (Boolean) -> Unit = {}
    var density: Float = 1f
    var reduceMotion: Boolean = false
    /** Whether the reader can watch the list move now (on screen, app resumed). */
    var isShowingToReader: Boolean = true

    var configuration: TranscriptConfiguration = TranscriptConfiguration()
        private set
    var snapshot: TranscriptListSnapshot by mutableStateOf(TranscriptListSnapshot.empty)
        private set
    val behavior = TranscriptScrollBehavior()
    var disclosure = TranscriptDisclosureState()
        private set

    private var entryStableIDs: Map<String, String> = emptyMap()
    private var queuedStableIDs: Map<String, String> = emptyMap()
    private var previousEntries: List<TranscriptEntry> = emptyList()
    private var previousQueued: List<OpenCodeQueuedInput> = emptyList()
    private val sessionLinkIDsByStableID = HashMap<String, Set<String>>()

    /** Advanced every motion frame; growing rows and entrances read it so only they relayout/redraw. */
    var frameTick by mutableIntStateOf(0)
        private set

    // Layout knowledge.
    private val measuredHeights = HashMap<String, Float>()
    private var lastLayout: LazyListLayoutInfo? = null
    var hasLaidOutContent = false
        private set
    private var firstFrameSent = false
    private var arrivalsGlideAfter = Double.POSITIVE_INFINITY
    private var catchUpLandsUntil = 0.0
    private var lastVisibleHeight = 0f
    private var lastContentWidth = 0f

    // Gestures.
    var isUserGestureActive = false
        private set
    private var contentArrivedDuringDrag = false

    // Motion.
    private var bottomSpring: BottomSpring? = null
    private var jumpGlide: JumpGlide? = null
    private var pendingCut = false
    private var motionJob: Job? = null
    private var now = transcriptNow()
    private val growths = HashMap<String, RowGrowth>()
    private val disclosureSprings = HashMap<String, DisclosureHeight>()
    private val settledHeights = HashMap<String, Float>()
    private val enteringItems = HashSet<String>()
    private val pendingEntrances = HashMap<String, Pair<Double, RowEntranceStyle>>()
    private var disclosureEntryID: String? = null
    private var disclosureToggledAt = 0.0
    private val ride = GrowthRide()

    // New messages.
    private var newMessagesDecision: Pair<String, Boolean>? = null
    private var fittingNewMessages: Int? = null
    private var landedNewMessages: Int? = null
    private var passedNewMessages: Int? = null
    private var pendingNewMessagesLanding: Int? = null

    /** Agent-sent messages glow once on arrival (iOS `TranscriptAgentMessageReveal`). */
    var agentReveal = ai.opencode.feature.transcript.user.TranscriptAgentMessageReveal()
        private set

    /** A run ended in the last update: every reply's line hold settles (iOS `textReveal.settleAll`). */
    var runEnded = false
        private set

    /** When the empty row started loading, so its mark keeps its fade across rehosting. */
    var emptyLoadingSince: Double? = null
        private set

    init {
        disclosure.animateToggle = ::toggleDisclosure
    }

    // MARK: Configuration

    /**
     * iOS `update(configuration:…)` + `applySnapshot`. Called during composition so the same frame
     * draws the new rows; anything with effects waits for [flushEffects].
     */
    fun update(configuration: TranscriptConfiguration) {
        val old = this.configuration
        if (configuration == old && snapshot !== TranscriptListSnapshot.empty) return
        val draftBecameSession = old.isDraft && old.sessionID == null && !configuration.isDraft && configuration.sessionID != null
        val sessionChanged = (configuration.sessionID != old.sessionID && !draftBecameSession) ||
            configuration.historyGeneration != old.historyGeneration
        if (sessionChanged) {
            resetForSessionChange()
            pendingEffects += { execute(behavior.handle(TranscriptScrollBehavior.Event.SessionChanged)) }
        }
        val wasBrowsing = !sessionChanged && behavior.mode == TranscriptScrollBehavior.Mode.Browsing
        this.configuration = configuration
        runEnded = old.isRunning && !configuration.isRunning
        applySnapshot(configuration, if (sessionChanged) null else old)

        val earlierWindowAdvanced = !sessionChanged && !configuration.isLoadingEarlier && configuration.hasEarlierItems &&
            old.entries.firstOrNull()?.id != configuration.entries.firstOrNull()?.id
        if ((old.isLoadingEarlier && !configuration.isLoadingEarlier) || earlierWindowAdvanced) {
            pendingEffects += { execute(behavior.handle(TranscriptScrollBehavior.Event.EarlierHistoryRequestCompleted)) }
        }
        // A following reader's send arrives under its flight from the composer. A browsing reader's
        // send takes them back to the end the way Jump to latest does, and the message arrives there.
        if (configuration.sendGeneration != old.sendGeneration && !sessionChanged) {
            pendingEffects += {
                val returnsToEnd = wasBrowsing || behavior.mode == TranscriptScrollBehavior.Mode.Browsing
                // A send during a jump's glide arrives under that glide: a flight on top would be a second motion.
                val flies = !returnsToEnd && bottomSpring?.kind != SpringKind.Jump
                if (returnsToEnd) jumpToLatest(TranscriptScrollBehavior.Event.MessageSent, fromBrowsing = wasBrowsing)
                scheduleSendLanding(flies)
            }
        } else if (sessionChanged && sendLanding?.let { configuration.sendGeneration != it.lastSendGeneration && it.isAwaitingSend() } == true) {
            // A draft's first send can arrive with the session it made, in a new list: it still lands.
            pendingEffects += { scheduleSendLanding(flies = true) }
        }
        sendLanding?.lastSendGeneration = configuration.sendGeneration
        val mark = configuration.newMessages
        if (mark != null && mark.generation != old.newMessages?.generation && landedNewMessages != mark.generation) {
            pendingNewMessagesLanding = mark.generation
        }
        if (configuration.isLoading && emptyLoadingSince == null && configuration.entries.isEmpty()) emptyLoadingSince = transcriptNow()
        if (!configuration.isLoading) emptyLoadingSince = if (configuration.entries.isEmpty()) emptyLoadingSince else null
    }

    /** The session's send flights (iOS `scrollEdgeBridge.sendTransition`); null: sends arrive as rows. */
    var sendLanding: ai.opencode.feature.transcript.user.SendLanding? = null

    /**
     * iOS `scheduleSendLanding` / `landSentMessage`'s list half: hands the send to [sendLanding]
     * with its row, its words and whether it flies. A send flying into its bubble enters with the
     * flight, not on its own; the time stamp a first message of the day brings settles in as it lands.
     */
    private fun scheduleSendLanding(flies: Boolean) {
        val landing = sendLanding ?: return
        val itemID = configuration.sentItemID
        // An idle send lands on its bubble; a steering send on its dim row.
        val stableID = itemID?.let { id ->
            TranscriptEntry.entryID(containing = id, entries = configuration.entries)?.let { entryStableIDs[it] ?: it }
                ?: queuedStableIDs[id]
        }
        val row = stableID?.let { snapshot.rows[it] }
        val rowText = when (row) {
            is MessageRow.Entry -> ai.opencode.feature.transcript.user.TranscriptUserMessage.from(row.entry)?.displayText
            is MessageRow.Queued -> ai.opencode.feature.transcript.user.TranscriptUserMessage.from(row.input)?.displayText
            null -> null
        }
        val text = rowText?.takeIf { it.isNotEmpty() } ?: itemID?.let { configuration.queuedPillTexts[it] }?.takeIf { it.isNotEmpty() }
        val isQueued = itemID != null && configuration.queuedPillTexts.containsKey(itemID)
        val flying = landing.send(
            ai.opencode.feature.transcript.user.SendLanding.Send(
                itemID = itemID,
                hasRow = stableID != null,
                flies = flies,
                isQueued = isQueued,
                text = text,
            ),
        )
        if (!flying || stableID == null) return
        // The flight is its entrance; the stamp above it settles in over the flight's last stretch.
        pendingEntrances.remove(TranscriptItemID.Message(stableID).key)
        val promptID = (row as? MessageRow.Entry)?.entry?.userMessageID ?: return
        val stampID = ai.opencode.feature.transcript.rows.TranscriptTimeStamps.PREFIX + promptID
        val stampKey = TranscriptItemID.Message(entryStableIDs[stampID] ?: stampID).key
        pendingEntrances[stampKey]?.let { (start, _) -> pendingEntrances[stampKey] = start to RowEntranceStyle.WithFlight }
    }

    private fun resetForSessionChange() {
        stopBottomSpring()
        growths.clear()
        disclosureSprings.clear()
        settledHeights.clear()
        enteringItems.clear()
        pendingEntrances.clear()
        measuredHeights.clear()
        ride.reset()
        entryStableIDs = emptyMap()
        queuedStableIDs = emptyMap()
        previousEntries = emptyList()
        previousQueued = emptyList()
        sessionLinkIDsByStableID.clear()
        hasLaidOutContent = false
        firstFrameSent = false
        arrivalsGlideAfter = Double.POSITIVE_INFINITY
        isUserGestureActive = false
        newMessagesDecision = null
        fittingNewMessages = null
        landedNewMessages = null
        passedNewMessages = null
        pendingNewMessagesLanding = null
        emptyLoadingSince = null
        agentReveal = ai.opencode.feature.transcript.user.TranscriptAgentMessageReveal()
        disclosure = TranscriptDisclosureState().also { it.animateToggle = ::toggleDisclosure }
    }

    private val pendingEffects = mutableListOf<() -> Unit>()

    /** Runs what [update] decided outside composition (sends, scrolls). Call from `SideEffect`. */
    fun flushEffects() {
        if (pendingEffects.isEmpty()) return
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        effects.forEach { it() }
    }

    private fun applySnapshot(configuration: TranscriptConfiguration, previous: TranscriptConfiguration?) {
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = previousEntries,
            previousQueued = previousQueued,
            previousEntryStableIDs = entryStableIDs,
            previousQueuedStableIDs = queuedStableIDs,
            entries = configuration.entries,
            queued = configuration.queuedInputs,
        )
        entryStableIDs = plan.entryStableIDs
        queuedStableIDs = plan.queuedStableIDs
        previousEntries = configuration.entries
        previousQueued = configuration.queuedInputs
        val old = snapshot
        val next = TranscriptListSnapshot.build(
            configuration = configuration,
            entryStableIDs = entryStableIDs,
            queuedStableIDs = queuedStableIDs,
            supersededQueuedIDs = plan.supersededQueuedIDs,
            sessionLinkIDsByStableID = sessionLinkIDsByStableID,
            drawsNewDivider = { TranscriptListSnapshot.drawsNewMessagesDivider(it, fittingNewMessages, newMessagesDecision) },
        )
        // Rows new at the end grow in and fade in (iOS `enteringItems`, `RowEntrance`); more than
        // four is a refresh or a catch-up, which lands.
        if (previous != null && hasLaidOutContent) {
            val oldKeys = old.items.map { it.key }.toSet()
            val lastKept = next.items.indexOfLast { it.key in oldKeys }
            val tailInserted = next.items.drop(lastKept + 1).filter { it.key !in oldKeys }
            if (tailInserted.size > RowEntrance.MAXIMUM_ROWS) {
                landArrivals()
            } else {
                val t = transcriptNow()
                for (item in tailInserted) {
                    // An agent-sent message glows as it arrives in front of the reader, once.
                    when (val row = next.rows[(item as? TranscriptItemID.Message)?.stableID]) {
                        is MessageRow.Entry -> ai.opencode.feature.transcript.user.TranscriptUserMessage.from(row.entry)
                        is MessageRow.Queued -> ai.opencode.feature.transcript.user.TranscriptUserMessage.from(row.input)
                        null -> null
                    }?.takeIf { it.isFromAgent }?.let { agentReveal.arrive(it.id) }
                    enteringItems += item.key
                    pendingEntrances[item.key] = t to RowEntranceStyle.Arrival
                }
                if (tailInserted.isNotEmpty()) pendingEffects += { startMotion() }
            }
            if (behavior.mode == TranscriptScrollBehavior.Mode.Following && isUserGestureActive && tailInserted.isNotEmpty()) {
                contentArrivedDuringDrag = true
            }
        }
        snapshot = next
    }

    /** Arrivals land rather than glide for the opening settle's length (iOS `landArrivals`). */
    private fun landArrivals() {
        finishGrowths()
        val kind = bottomSpring?.kind
        if (kind != null && kind != SpringKind.Keyboard && kind != SpringKind.Jump) stopBottomSpring()
        catchUpLandsUntil = transcriptNow() + BottomSpring.OPENING_SETTLE
        arrivalsGlideAfter = max(arrivalsGlideAfter, catchUpLandsUntil)
    }

    // MARK: Viewport

    /**
     * iOS `imageViewportIsSettling`: a session still opening (no content laid out, or its
     * arrivals still landing) holds image previews back so their reads and redraws don't change
     * rows while the list lands.
     */
    val imagesSettling: Boolean
        get() = !hasLaidOutContent || transcriptNow() < arrivalsGlideAfter

    private fun heightOf(item: LazyListItemInfo): Float = item.size / density

    /** iOS `viewport` from LazyColumn's layout: measured heights where known, the average elsewhere. */
    fun viewport(info: LazyListLayoutInfo = state.layoutInfo): TranscriptScrollBehavior.Viewport {
        val visible = info.visibleItemsInfo
        val visibleHeight = (info.viewportEndOffset - info.afterContentPadding) / density
        val keys = snapshot.items
        val average = if (measuredHeights.isEmpty()) 80f else measuredHeights.values.average().toFloat()
        val canRequest = configuration.hasEarlierItems && !configuration.isLoadingEarlier && configuration.earlierHistoryError == null
        if (visible.isEmpty() || keys.isEmpty()) {
            return TranscriptScrollBehavior.Viewport(0f, 0f, visibleHeight, 0f, canRequest, isUserGestureActive)
        }
        val first = visible.first()
        var before = 0f
        for (i in 0 until min(first.index, keys.size)) before += measuredHeights[keys[i].key] ?: average
        val offsetY = before - first.offset / density
        val last = visible.last()
        val contentHeight = if (last.index == keys.lastIndex) {
            // Exact at the end: the last row's bottom against the visible bottom.
            offsetY + visibleHeight + ((last.offset + last.size) / density - visibleHeight)
        } else {
            var total = before - first.offset / density + (last.offset + last.size) / density
            for (i in last.index + 1 until keys.size) total += measuredHeights[keys[i].key] ?: average
            total
        }
        return TranscriptScrollBehavior.Viewport(
            offsetY = offsetY,
            contentHeight = max(contentHeight, 0f),
            visibleHeight = visibleHeight,
            trailingSpace = 0f,
            canRequestEarlierHistory = canRequest,
            isUserScrolling = isUserGestureActive,
        )
    }

    private fun distanceFromBottom(viewport: TranscriptScrollBehavior.Viewport = viewport()): Float =
        TranscriptScrollBehavior.distanceFromBottom(viewport)

    // MARK: Layout

    /** Called after every LazyColumn layout (iOS `viewDidLayoutSubviews` + `reportLayoutChanged`). */
    fun onLayout(info: LazyListLayoutInfo) {
        lastLayout = info
        val keys = snapshot.items
        for (item in info.visibleItemsInfo) {
            val key = item.key as? String ?: continue
            measuredHeights[key] = heightOf(item)
        }
        if (keys.isEmpty() || info.visibleItemsInfo.isEmpty()) return
        val visibleHeight = (info.viewportEndOffset - info.afterContentPadding) / density
        val width = info.viewportSize.width / density
        if (!hasLaidOutContent) {
            // A session opens on its bottom; the self-sizing that follows its first frame lands.
            hasLaidOutContent = true
            lastVisibleHeight = visibleHeight
            lastContentWidth = width
            landOnLastItem()
            arrivalsGlideAfter = transcriptNow() + BottomSpring.OPENING_SETTLE
            if (!firstFrameSent && keys.any { it is TranscriptItemID.Message || it is TranscriptItemID.Empty }) {
                firstFrameSent = true
                send(TranscriptAction.FirstContentFrameRendered)
            }
            return
        }
        val geometryChanged = abs(visibleHeight - lastVisibleHeight) > 0.5f || abs(width - lastContentWidth) > 0.5f
        if (abs(width - lastContentWidth) > 0.5f) finishGrowths()
        lastVisibleHeight = visibleHeight
        lastContentWidth = width
        val viewport = viewport(info)
        if (isUserGestureActive) {
            handleBehavior(TranscriptScrollBehavior.Event.UserScrolled(viewport))
            return
        }
        if (pendingCut) {
            pendingCut = false
            beginCutGlide(viewport)
            return
        }
        if (jumpGlide != null) return
        pendingNewMessagesLanding?.let { generation ->
            if (landOnNewMessages(generation, info)) return
        }
        checkNewMessagesPassed(info)
        // Rows growing at the end are ridden by the frame loop.
        if (growths.isNotEmpty() || bottomSpring != null) return
        if (behavior.mode == TranscriptScrollBehavior.Mode.Following && distanceFromBottom(viewport) > 0.5f) {
            followEnd(viewport, viewportChanged = geometryChanged)
        } else if (behavior.mode == TranscriptScrollBehavior.Mode.Browsing) {
            handleBehavior(TranscriptScrollBehavior.Event.LayoutChanged(viewport))
        }
    }

    /** A following reader whose end moved: glide to it, or land (iOS `scrollToBottom(animated: false)`). */
    private fun followEnd(viewport: TranscriptScrollBehavior.Viewport, viewportChanged: Boolean) {
        val distance = distanceFromBottom(viewport)
        if (distance <= 0.5f) return
        val t = transcriptNow()
        val lastVisible = lastLayout?.visibleItemsInfo?.lastOrNull()?.index == snapshot.items.lastIndex
        val glides = !reduceMotion && hasLaidOutContent && t >= arrivalsGlideAfter && !isUserGestureActive && isShowingToReader
        // Not yet measured rows at the end come into view as the glide scrolls (LazyColumn clamps at the end).
        if (glides && (lastVisible || distance <= viewport.visibleHeight) && distance <= viewport.visibleHeight * BottomSpring.ARRIVAL_REACH) {
            springToBottom(if (viewportChanged) SpringKind.Keyboard else SpringKind.Arrival)
        } else {
            landOnLastItem()
        }
    }

    /** iOS `landOnLastItem` + `settleAtEnd`: the end exactly, now; later layouts settle what self-sizing moves. */
    fun landOnLastItem() {
        val keys = snapshot.items
        if (keys.isEmpty()) return
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (last != null && last.index == keys.lastIndex) {
            val bottom = info.viewportEndOffset - info.afterContentPadding
            val delta = (last.offset + last.size - bottom).toFloat()
            if (delta > 0.5f) state.dispatchRawDelta(delta)
        } else {
            state.requestScrollToItem(keys.lastIndex, Int.MAX_VALUE / 4)
        }
    }

    // MARK: Gestures (iOS `scrollViewWillBeginDragging`, `settleUserScroll`)

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && !isUserGestureActive) dragBegan()
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (isUserGestureActive) settleUserScroll()
            return Velocity.Zero
        }
    }

    private fun dragBegan() {
        stopBottomSpring()
        finishGrowths()
        isUserGestureActive = true
        contentArrivedDuringDrag = false
        handleBehavior(TranscriptScrollBehavior.Event.DragBegan(viewport()))
    }

    private fun settleUserScroll() {
        isUserGestureActive = false
        handleBehavior(TranscriptScrollBehavior.Event.UserSettled(viewport(), contentArrivedDuringDrag))
        lastLayout?.let { checkNewMessagesPassed(it) }
    }

    // MARK: Behavior

    private fun handleBehavior(event: TranscriptScrollBehavior.Event) {
        val previousMode = behavior.mode
        execute(behavior.handle(event))
        if (previousMode != behavior.mode) {
            send(TranscriptAction.TranscriptBrowsingChanged(behavior.mode == TranscriptScrollBehavior.Mode.Browsing))
        }
    }

    private fun execute(commands: List<TranscriptScrollBehavior.Command>) {
        for (command in commands) {
            when (command) {
                is TranscriptScrollBehavior.Command.AdjustOffset -> if (command.by != 0f) state.dispatchRawDelta(command.by * density)
                is TranscriptScrollBehavior.Command.ScrollToBottom -> scrollToBottom(command.animated && !reduceMotion)
                // Space held under the finger: LazyColumn never clamps a held reader, so nothing to hold.
                is TranscriptScrollBehavior.Command.SetTrailingSpace -> Unit
                TranscriptScrollBehavior.Command.RequestEarlierHistory -> send(TranscriptAction.ShowEarlierTapped)
                is TranscriptScrollBehavior.Command.ShowJumpButton -> setShowsJumpButton(command.shows)
            }
        }
    }

    private fun scrollToBottom(animated: Boolean) {
        if (!hasLaidOutContent) return
        if (animated) {
            glideToBottom()
            return
        }
        if (isUserGestureActive || bottomSpring != null) return
        if (growths.isNotEmpty() && ride.deficit > 0.5f) return
        followEnd(viewport(), viewportChanged = false)
    }

    /** Jump to latest, tapped or by sending while browsing (iOS `jumpToLatest`). */
    fun jumpToLatest(event: TranscriptScrollBehavior.Event = TranscriptScrollBehavior.Event.JumpToLatestTapped, fromBrowsing: Boolean = false) {
        val previousMode = if (fromBrowsing) TranscriptScrollBehavior.Mode.Browsing else behavior.mode
        if (fromBrowsing && behavior.mode != TranscriptScrollBehavior.Mode.Browsing) behavior.handle(TranscriptScrollBehavior.Event.UserLeftBottom)
        execute(behavior.handle(event))
        if (previousMode != behavior.mode) {
            send(TranscriptAction.TranscriptBrowsingChanged(behavior.mode == TranscriptScrollBehavior.Mode.Browsing))
        }
    }

    /**
     * Jump to latest always glides in [JumpGlide.DURATION]: within [JumpGlide.REACH] screens all
     * the way from rest; farther, cut to [JumpGlide.LEAD] screens above the end, already moving.
     */
    private fun glideToBottom() {
        stopBottomSpring()
        finishGrowths()
        val viewport = viewport()
        val distance = distanceFromBottom(viewport)
        if (distance <= 1f) return
        if (reduceMotion) {
            landOnLastItem()
            return
        }
        val lastVisible = lastLayout?.visibleItemsInfo?.lastOrNull()?.index == snapshot.items.lastIndex
        if (distance > viewport.visibleHeight * JumpGlide.REACH || !lastVisible) {
            // Cut: put the end in view, then step back to the lead and glide from there.
            pendingCut = true
            state.requestScrollToItem(snapshot.items.lastIndex, Int.MAX_VALUE / 4)
            return
        }
        jumpGlide = JumpGlide(distance, JumpGlide.Curve.FromRest)
        bottomSpring = BottomSpring(SpringKind.Jump, 0f, distance, transcriptNow())
        startMotion()
    }

    private fun beginCutGlide(viewport: TranscriptScrollBehavior.Viewport) {
        val lead = viewport.visibleHeight * JumpGlide.LEAD
        val distance = distanceFromBottom(viewport)
        val back = lead - distance
        if (back > 0) state.dispatchRawDelta(-back * density)
        jumpGlide = JumpGlide(lead, JumpGlide.Curve.FastStart)
        bottomSpring = BottomSpring(SpringKind.Jump, 0f, lead, transcriptNow())
        startMotion()
    }

    /** A critically damped spring toward the bottom, retargeted every frame (iOS `springToBottom`). */
    private fun springToBottom(kind: SpringKind) {
        val t = transcriptNow()
        val spring = bottomSpring
        if (spring != null) {
            if (spring.kind != SpringKind.Jump && (kind == SpringKind.Keyboard || kind == SpringKind.Settle)) {
                spring.kind = kind
                spring.omega = BottomSpring.JUMP_OMEGA
            }
            return
        }
        // Offsets are relative: 0 is now, the target is the distance to the end.
        bottomSpring = BottomSpring(kind, 0f, distanceFromBottom(), t)
        startMotion()
    }

    private fun stopBottomSpring() {
        bottomSpring = null
        jumpGlide = null
    }

    // MARK: Motion loop

    private fun startMotion() {
        if (motionJob?.isActive == true) return
        motionJob = scope.launch {
            motionFrameLoop { tick ->
                now = transcriptNow()
                if (stepMotion(tick.dt)) MotionFrameAction.Continue else MotionFrameAction.Stop
            }
        }
    }

    /** One frame of every transcript motion. Returns whether anything is still moving. */
    private fun stepMotion(dt: Double): Boolean {
        var moved = false
        val following = behavior.mode == TranscriptScrollBehavior.Mode.Following && !isUserGestureActive
        // Row growth, ridden at the end while following (iOS `stepGrowths` + `GrowthRide`).
        if (growths.isNotEmpty() || disclosureSprings.isNotEmpty()) {
            var grown = 0f
            val finished = mutableListOf<String>()
            for ((key, growth) in growths) {
                val before = growth.height
                growth.advance(dt, now)
                grown += growth.height - before
                if (growth.isFinished) finished += key
            }
            for (key in finished) {
                growths.remove(key)?.let { settledHeights[key] = it.target }
            }
            val doneDisclosures = disclosureSprings.filterValues { it.step(dt) }.keys
            for (key in doneDisclosures) disclosureSprings.remove(key)?.let { settledHeights[key] = it.target }
            if (following && bottomSpring?.kind != SpringKind.Jump) {
                val viewport = viewport()
                val distance = distanceFromBottom(viewport)
                val endBefore = viewport.offsetY + distance
                val step = (BottomSpring.ARRIVAL_SPEED * dt).toFloat()
                val next = ride.offset(viewport.offsetY, endBefore, endBefore + max(0f, grown), step)
                val delta = next - viewport.offsetY
                if (delta > 0.01f) state.dispatchRawDelta(delta * density)
            }
            frameTick++
            moved = true
            if (growths.isEmpty()) ride.reset()
        }
        // Entrances fade on the same clock.
        if (pendingEntrances.isNotEmpty()) {
            pendingEntrances.entries.removeAll { now - it.value.first > RowEntrance.WINDOW + RowEntrance.SETTLING_DURATION }
            if (pendingEntrances.isNotEmpty()) {
                frameTick++
                moved = true
            }
        }
        val spring = bottomSpring
        if (spring != null) {
            if (behavior.mode != TranscriptScrollBehavior.Mode.Following || isUserGestureActive) {
                stopBottomSpring()
            } else {
                stepBottomSpring(spring, dt)
                moved = moved || bottomSpring != null
            }
        }
        return moved || bottomSpring != null || growths.isNotEmpty() || disclosureSprings.isNotEmpty()
    }

    private fun stepBottomSpring(spring: BottomSpring, dt: Double) {
        val viewport = viewport()
        val remaining = distanceFromBottom(viewport)
        val pixel = 1.0 / density
        if (spring.kind == SpringKind.Jump) {
            val glide = jumpGlide
            val share = glide?.step(dt, remaining, pixel.toFloat(), expired = now > spring.deadline)
            if (glide == null || share == null) {
                stopBottomSpring()
                landOnLastItem()
                lastLayout?.let { checkNewMessagesPassed(it) }
                return
            }
            state.dispatchRawDelta(remaining * share * density)
            return
        }
        // In relative space: current 0, target = remaining.
        when (val result = spring.step(dt, 0f, remaining, pixel, now)) {
            is BottomSpring.Step.Move -> {
                state.dispatchRawDelta(result.y * density)
                // Next frame measures from where this one lands.
                spring.target = remaining - result.y
            }
            BottomSpring.Step.Settled -> {
                stopBottomSpring()
                if (remaining > 0) state.dispatchRawDelta(remaining * density)
            }
            is BottomSpring.Step.ShrunkEnd -> stopBottomSpring()
        }
    }

    private fun finishGrowths() {
        for ((key, growth) in growths) settledHeights[key] = growth.target
        growths.clear()
        for ((key, spring) in disclosureSprings) settledHeights[key] = spring.target
        disclosureSprings.clear()
        enteringItems.clear()
        ride.reset()
        frameTick++
    }

    // MARK: Row growth (called from a row's measure pass)

    /**
     * The height a message row lays out at this frame (iOS `fittedHeight(for:measured:frame:)`).
     * While the reader follows at rest, rows ease to a new measured height ([RowGrowth]); a
     * disclosure grows on its own spring; everything else takes its height at once.
     */
    fun fittedHeight(key: String, measured: Float, width: Float): Float {
        // Reading the tick subscribes this row's layout to motion frames: a growth that starts in
        // this pass must relayout the row on the next frame. Children's measurements stay cached.
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val growth = growths[key]
        if (growth != null) {
            if (!growth.continues(width)) {
                growths.remove(key)
                settledHeights[key] = measured
                return measured
            }
            if (abs(growth.target - measured) > 0.25f) growth.retarget(measured)
            return growth.height
        }
        val disclosureSpring = disclosureSprings[key]
        if (disclosureSpring != null) {
            if (abs(disclosureSpring.target - measured) > 0.25f) disclosureSpring.retarget(measured)
            return disclosureSpring.height
        }
        val settled = settledHeights[key]
        val entering = enteringItems.remove(key)
        if (settled == null && !entering) {
            settledHeights[key] = measured
            return measured
        }
        val from = settled ?: (1f / density)
        if (abs(from - measured) <= 0.5f) {
            settledHeights[key] = measured
            return measured
        }
        if (disclosureEntryID == key && now - disclosureToggledAt < 1.0) {
            disclosureEntryID = null
            disclosureSprings[key] = DisclosureHeight(from, measured, opening = measured > from)
            startMotion()
            return from
        }
        if (!allowsGrowth(measured - from)) {
            settledHeights[key] = measured
            return measured
        }
        growths[key] = RowGrowth(from, measured, now, width).also { it.changedRow = settled != null }
        startMotion()
        return from
    }

    private fun allowsGrowth(delta: Float): Boolean {
        val t = transcriptNow()
        val visible = lastVisibleHeight
        return !reduceMotion && hasLaidOutContent && isShowingToReader &&
            behavior.mode == TranscriptScrollBehavior.Mode.Following && !isUserGestureActive &&
            t >= arrivalsGlideAfter && t >= catchUpLandsUntil && jumpGlide == null &&
            abs(delta) <= visible
    }

    /** Whether [key] lays out shorter or taller than its content this frame (growth or disclosure): it clips. */
    fun isClipping(key: String): Boolean {
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        return growths.containsKey(key) || disclosureSprings.containsKey(key)
    }

    /** The soft edge's height while [key] grows, in dp (0 when not growing). */
    fun softEdge(key: String): Float {
        // Always subscribes: a growth starting in the measure pass must redraw this row's edge.
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val growth = growths[key] ?: return 0f
        return min(TranscriptTunables.growthSoftEdge, max(0f, growth.target - growth.height))
    }

    /** Entrance progress for [key]: null once settled (iOS `RowEntrance.add`). */
    fun entrance(key: String): Pair<Float, RowEntranceStyle>? {
        val (start, style) = pendingEntrances[key] ?: return null
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val duration = when (style) {
            RowEntranceStyle.Arrival -> RowEntrance.DURATION
            RowEntranceStyle.Settling -> RowEntrance.SETTLING_DURATION
            RowEntranceStyle.WithFlight -> RowEntrance.WITH_FLIGHT_DURATION
        }
        val progress = ((transcriptNow() - start) / duration).toFloat()
        if (progress >= 1f) {
            pendingEntrances.remove(key)
            return null
        }
        return progress.coerceIn(0f, 1f) to style
    }

    // MARK: Disclosure (iOS `toggleDisclosure`)

    private fun toggleDisclosure(entryID: String, opening: Boolean, crossFades: Boolean, toggle: () -> Unit) {
        // The tapped row never moves; its edge grows on the disclosure spring; the follower never chases it.
        stopBottomSpring()
        finishGrowths()
        if (!reduceMotion) {
            disclosureEntryID = entryStableIDs[entryID]?.let { "m:$it" } ?: "m:$entryID"
            disclosureToggledAt = transcriptNow()
        }
        toggle()
        scope.launch {
            // After the toggle's layout: like coming to rest, they follow only if the end is in view.
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            handleBehavior(TranscriptScrollBehavior.Event.DisclosureToggled(viewport()))
        }
    }

    // MARK: New messages (iOS `+NewMessages`)

    /** Lands the reader at the New divider when what arrived doesn't fit; else it stays at the end. Returns whether it moved. */
    private fun landOnNewMessages(generation: Int, info: LazyListLayoutInfo): Boolean {
        val mark = configuration.newMessages
        if (mark == null || mark.generation != generation) {
            pendingNewMessagesLanding = null
            return false
        }
        if (!isShowingToReader || behavior.mode != TranscriptScrollBehavior.Mode.Following || isUserGestureActive) return false
        pendingNewMessagesLanding = null
        landedNewMessages = generation
        if (!mark.showsDivider) return false
        val dividerIndex = snapshot.items.indexOf(TranscriptItemID.NewDivider)
        if (dividerIndex < 0) return false
        val divider = info.visibleItemsInfo.firstOrNull { it.index == dividerIndex }
        val fits = divider != null && divider.offset >= 0 && info.visibleItemsInfo.lastOrNull()?.index == snapshot.items.lastIndex
        if (fits) {
            // What's new fits below it: no divider, as in a chat app.
            fittingNewMessages = generation
            newMessagesDecision = mark.entryID to false
            send(TranscriptAction.NewMessagesFit(generation))
            snapshot = snapshot.copy(items = snapshot.items - TranscriptItemID.NewDivider)
            return false
        }
        newMessagesDecision = mark.entryID to true
        execute(behavior.handle(TranscriptScrollBehavior.Event.RevealBegan))
        state.requestScrollToItem(dividerIndex, 0)
        handleBehavior(TranscriptScrollBehavior.Event.RevealEnded(atEnd = false))
        AppLog.app.debug("transcript new divider landed generation=$generation")
        return true
    }

    private fun checkNewMessagesPassed(info: LazyListLayoutInfo) {
        val mark = configuration.newMessages ?: return
        if (passedNewMessages == mark.generation || !isShowingToReader || behavior.mode != TranscriptScrollBehavior.Mode.Following) return
        val dividerIndex = snapshot.items.indexOf(TranscriptItemID.NewDivider)
        if (dividerIndex < 0) return
        val divider = info.visibleItemsInfo.firstOrNull { it.index == dividerIndex }
        val firstIndex = info.visibleItemsInfo.firstOrNull()?.index ?: return
        val passed = if (divider != null) divider.offset + divider.size <= 0 else firstIndex > dividerIndex
        if (!passed) return
        passedNewMessages = mark.generation
        send(TranscriptAction.NewMessagesPassed(mark.generation))
    }

    // MARK: Session links

    /** A row showing session IDs (iOS `sessionLinksAppeared`): asked about once per screen. */
    fun sessionLinksAppeared(ids: List<String>, stableID: String) {
        val known = sessionLinkIDsByStableID[stableID] ?: emptySet()
        if (ids.all { it in known && configuration.sessionLinks[it] != null }) return
        sessionLinkIDsByStableID[stableID] = known + ids
        send(TranscriptAction.SessionLinksAppeared(ids))
    }
}

/** A disclosure's row height on its critically damped spring (open 0.3 s, close 0.24 s). */
private class DisclosureHeight(var height: Float, var target: Float, opening: Boolean) {
    private var velocity = 0.0
    private val omega = 2 * Math.PI / if (opening) TranscriptTunables.disclosureOpen else TranscriptTunables.disclosureClose

    fun retarget(target: Float) {
        this.target = target
    }

    /** Returns true when settled. */
    fun step(dt: Double): Boolean {
        val (value, v) = ai.opencode.core.motion.SpringSolver.evaluate(
            from = height.toDouble(), to = target.toDouble(), initialVelocity = velocity, omega = omega, zeta = 1.0, elapsed = dt,
        )
        height = value.toFloat()
        velocity = v
        if (abs(height - target) < 0.25f && abs(velocity) < 15) {
            height = target
            return true
        }
        return false
    }
}
