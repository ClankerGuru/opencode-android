package ai.opencode.feature.transcript.list

import ai.opencode.core.diagnostics.MotionFrame
import ai.opencode.core.diagnostics.PerfTimeline
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
import kotlin.math.roundToInt
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
    var frameDuration: Double = 1.0 / 60.0
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
        private set(value) {
            if (value == field) return
            field = value
            if (perfRows == null) return
            if (value) PerfTimeline.rowsShown(configuration.sessionID, perfRows) else PerfTimeline.rowsHidden(perfRows)
        }

    /** iOS `perfRows`: this list's rows for `PerfTimeline` (null unless measuring); released when the list leaves composition. */
    val perfRows = PerfTimeline.makeRowsToken()

    /** A configuration was taken that `PerfTimeline` hasn't heard rendered. */
    private var perfRenderPending = false
    private var firstFrameSent = false
    private var arrivalsGlideAfter = Double.POSITIVE_INFINITY
    private var catchUpLandsUntil = 0.0
    private var lastVisibleHeight = 0f
    private var lastContentWidth = 0f

    // Gestures.
    var isUserGestureActive = false
        private set
    private var contentArrivedDuringDrag = false
    var trailingSpace by mutableStateOf(0f)
        private set
    private var heldSpaceDrain = HeldSpaceDrain()
    private var bottomObstruction: Float? = null
    private var keyboardInset = 0f
    private var keyboardMoved = false

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
    private val entranceStarts = HashMap<String, Double>()
    private var disclosureEntryID: String? = null
    private var disclosureToggledAt = 0.0
    private val ride = GrowthRide()
    // LazyColumn lays out after the motion tick. Consume growth only after that layout;
    // dispatching before it clamps against yesterday's end and silently loses the ride.
    private var pendingGrowthRide: Pair<Boolean, Float>? = null
    private val removedHeights = HashMap<String, Float>()
    private val runResizedItems = HashSet<String>()
    private val revisedRows = HashMap<String, Double>()
    private val imageGrowths = HashMap<String, ai.opencode.feature.media.AgentImageRowGrowth>()
    private var readingAnchor: Pair<String, Int>? = null
    private var promotionUntil = 0.0
    private var followsSentRow = false
    /**
     * The spring rows move on when an update moves them (null: they move at once): a promotion's
     * [ai.opencode.feature.transcript.user.PromotionMotion.spring], or the disclosure's closing
     * spring when a row leaves a transcript shorter than the screen (iOS `closeTopGap`: the rows
     * glide up into its place instead of jumping in one frame).
     */
    var placementSpring by mutableStateOf<ai.opencode.core.motion.MotionSpring?>(null)
        private set

    /**
     * Whether rows this update removes fade out where they were (iOS `fadeOut`: the few rows an
     * update takes off the screen, like the copy button as Working takes its place). A refresh,
     * catch-up or window swap replaces many at once; iOS reloads those, and here a bulk fade
     * left disappearing rows stuck half-drawn over the new ones. They go at once.
     */
    var fadesRemovals by mutableStateOf(true)
        private set
    private var placementUntil = 0.0

    // New messages.
    private var newMessagesDecision: Pair<String, Boolean>? = null
    private var fittingNewMessages: Int? = null
    private var landedNewMessages: Int? = null
    private var passedNewMessages: Int? = null
    private var pendingNewMessagesLanding: Int? = null
    private var revealGlide: JumpGlide? = null
    private var revealDeadline = 0.0
    private var revealsNewMessages = false
    /** The reveal slides to the end (what's new fit, so no divider): iOS `revealsToEnd`. */
    private var revealsToEnd = false

    /** Agent-sent messages glow once on arrival (iOS `TranscriptAgentMessageReveal`). */
    var agentReveal = ai.opencode.feature.transcript.user.TranscriptAgentMessageReveal()
        private set

    /** A run ended in the last update: every reply's line hold settles (iOS `textReveal.settleAll`). */
    var runEnded = false
        private set

    /** When the empty row started loading, so its mark keeps its fade across rehosting. */
    var emptyLoadingSince: Double? = null
        private set
    data class EmptyHandoff(val loading: Boolean, val showsEmpty: Boolean, val since: Double?, val start: Double, val duration: Double)
    var emptyHandoff by mutableStateOf<EmptyHandoff?>(null)
        private set

    fun handoffProgress(): Float {
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val handoff = emptyHandoff ?: return 1f
        val progress = ((transcriptNow() - handoff.start) / handoff.duration).toFloat().coerceIn(0f, 1f)
        return androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f).transform(progress)
    }

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
        if (perfRows != null) perfRenderPending = true
        // iOS `differsOnlyInSnapshotRevision`: the rows on screen already show it.
        if (snapshot !== TranscriptListSnapshot.empty && configuration.snapshotRevision != old.snapshotRevision &&
            configuration.copy(snapshotRevision = old.snapshotRevision) == old
        ) {
            this.configuration = configuration
            return
        }
        val draftBecameSession = old.isDraft && old.sessionID == null && !configuration.isDraft && configuration.sessionID != null
        val sessionChanged = (configuration.sessionID != old.sessionID && !draftBecameSession) ||
            configuration.historyGeneration != old.historyGeneration
        if (sessionChanged) {
            resetForSessionChange()
            pendingEffects += { execute(behavior.handle(TranscriptScrollBehavior.Event.SessionChanged)) }
        } else if (draftBecameSession && hasLaidOutContent && perfRows != null) {
            // The draft's first content frame was drawn before there was a session to tell.
            PerfTimeline.rowsShown(configuration.sessionID, perfRows)
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
            followsSentRow = !wasBrowsing
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
            revealsNewMessages = hasLaidOutContent && isShowingToReader && !reduceMotion && !isUserGestureActive
        }
        if (mark == null) {
            newMessagesDecision = null
            pendingNewMessagesLanding = null
            revealGlide = null
            revealsToEnd = false
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
        removedHeights.clear()
        runResizedItems.clear()
        revisedRows.clear()
        imageGrowths.values.forEach { it.settle() }
        imageGrowths.clear()
        readingAnchor = null
        pendingGrowthRide = null
        promotionUntil = 0.0
        followsSentRow = false
        disclosureSprings.clear()
        settledHeights.clear()
        enteringItems.clear()
        pendingEntrances.clear()
        entranceStarts.clear()
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
        revealGlide = null
        revealsToEnd = false
        trailingSpace = 0f
        heldSpaceDrain = HeldSpaceDrain()
        emptyLoadingSince = null
        emptyHandoff = null
        agentReveal = ai.opencode.feature.transcript.user.TranscriptAgentMessageReveal()
        disclosure = TranscriptDisclosureState().also { it.animateToggle = ::toggleDisclosure }
    }

    private val pendingEffects = mutableListOf<() -> Unit>()

    /** Runs what [update] decided outside composition (sends, scrolls). Call from `SideEffect`. */
    fun flushEffects() {
        if (perfRenderPending) reportRenderedSnapshot()
        if (pendingEffects.isEmpty()) return
        val effects = pendingEffects.toList()
        pendingEffects.clear()
        effects.forEach { it() }
    }

    private fun applySnapshot(configuration: TranscriptConfiguration, previous: TranscriptConfiguration?) {
        if (TranscriptMotionTrace.isActive) TranscriptMotionTrace.willChange()
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
        captureReadingAnchor()
        val next = TranscriptListSnapshot.build(
            configuration = configuration,
            entryStableIDs = entryStableIDs,
            queuedStableIDs = queuedStableIDs,
            supersededQueuedIDs = plan.supersededQueuedIDs,
            sessionLinkIDsByStableID = sessionLinkIDsByStableID,
            drawsNewDivider = { TranscriptListSnapshot.drawsNewMessagesDivider(it, fittingNewMessages, newMessagesDecision) },
        )
        if (previous != null && TranscriptItemID.Empty in old.items &&
            (TranscriptItemID.Empty !in next.items || previous.isLoading && !configuration.isLoading)
        ) {
            val time = transcriptNow()
            val hold = if (previous.isLoading) MarkLoadingTiming(emptyLoadingSince).handoffDelay(time) else 0.0
            if (hold != null) {
                emptyHandoff = EmptyHandoff(previous.isLoading, previous.showsEmptyState, emptyLoadingSince,
                    time + hold, if (previous.isLoading) MarkLoadingTunables.fadeOut else EmptyStateTunables.exitFade)
                pendingEffects += { startMotion() }
            }
        }
        // Rows new at the end grow in and fade in (iOS `enteringItems`, `RowEntrance`); more than
        // four is a refresh or a catch-up, which lands.
        if (previous != null && hasLaidOutContent) {
            val oldKeys = old.items.map { it.key }.toSet()
            val nextKeys = next.items.map { it.key }.toSet()
            val sent = previous.sendGeneration != configuration.sendGeneration
            val promoted = next.items.any { item ->
                item is TranscriptItemID.Message && old.rows[item.stableID] is MessageRow.Queued &&
                    next.rows[item.stableID] is MessageRow.Entry
            }
            if (promoted) {
                finishGrowths()
                promotionUntil = transcriptNow() + ai.opencode.feature.transcript.user.PromotionMotion.duration * 1.5
                if (!reduceMotion) animatePlacement(ai.opencode.feature.transcript.user.PromotionMotion.spring, promotionUntil)
            } else if (!reduceMotion && old.items.any { it.key !in nextKeys && it != TranscriptItemID.Empty } && fitsOnScreen()) {
                // iOS `closeTopGap`: a row leaving a transcript shorter than the screen.
                val spring = ai.opencode.feature.transcript.rows.DisclosureMotion.spring(opening = false)
                animatePlacement(spring, transcriptNow() + spring.duration * 1.5)
            }
            // Working and waiting messages don't fence off arrivals in the reply above them.
            val lastKept = next.items.indexOfLast {
                it.key in oldKeys && it is TranscriptItemID.Message && old.rows[it.stableID] is MessageRow.Entry
            }
            val tailInserted = next.items.drop(lastKept + 1).filter { it.key !in oldKeys }
            // Working handed off to the reply's copy button in its own row: the reply end that
            // replaces it is already drawn there, so it doesn't fade in (iOS `handoff`). Without
            // this the copy button blinked out and faded back in as Working left.
            val handoff = if (previous.workingHandsOffToCopy && !configuration.isRunning) {
                tailInserted.filter { (next.rows[(it as? TranscriptItemID.Message)?.stableID] as? MessageRow.Entry)?.entry is TranscriptEntry.ReplyEnd }.toSet()
            } else {
                emptySet()
            }
            val fades = old.items.count { it.key !in nextKeys } <= RowEntrance.MAXIMUM_ROWS &&
                next.items.count { it.key !in oldKeys } <= RowEntrance.MAXIMUM_ROWS
            if (fades != fadesRemovals) fadesRemovals = fades
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
                    // The flight owns a local send's arrival. Its destination must be measured
                    // at full height, not clipped to a pixel while the follower looks for it.
                    if (!sent && !promoted && !reduceMotion) {
                        enteringItems += item.key
                        if (item !in handoff) pendingEntrances[item.key] = t to RowEntranceStyle.Arrival
                    }
                }
                if (tailInserted.isNotEmpty()) pendingEffects += { startMotion() }
            }
            if (behavior.mode == TranscriptScrollBehavior.Mode.Following && isUserGestureActive && tailInserted.isNotEmpty()) {
                contentArrivedDuringDrag = true
            }
            for ((id, run) in next.runs) {
                if (old.rows[id] == next.rows[id] && old.runs[id] != run) runResizedItems += "m:$id"
            }
            // A removed row lends its height to the new neighbour, or the survivor above it.
            // Its content fades separately; the layout closes on the ordinary growth curve.
            if (!sent && !promoted && allowsGrowth(0f)) {
                for ((index, item) in old.items.withIndex()) {
                    if (item.key in nextKeys || item == TranscriptItemID.Empty || item == TranscriptItemID.History) continue
                    val visible = lastLayout?.visibleItemsInfo?.firstOrNull { it.key == item.key } ?: continue
                    val above = old.items.take(index).lastOrNull { it.key in nextKeys }
                    val position = above?.let(next.items::indexOf)?.plus(1) ?: 0
                    val below = next.items.getOrNull(position)?.takeIf { it.key in enteringItems }
                    val neighbour = below ?: above ?: continue
                    removedHeights[neighbour.key] = (removedHeights[neighbour.key] ?: 0f) + heightOf(visible)
                }
            }
            growths.keys.retainAll(nextKeys)
            settledHeights.keys.retainAll(nextKeys)
            enteringItems.retainAll(nextKeys)
            pendingEntrances.keys.retainAll(nextKeys)
            entranceStarts.keys.retainAll(nextKeys)
            imageGrowths.keys.filter { it !in next.rows }.forEach { imageGrowths.remove(it)?.settle() }
            // The opening settle is for initial self-sizing, not a live update or a send.
            if (!configuration.isLoading && !previous.isLoading && (tailInserted.isNotEmpty() || next.rows != old.rows)) {
                arrivalsGlideAfter = min(arrivalsGlideAfter, transcriptNow())
            }
        }
        if (TranscriptMotionTrace.isActive) {
            val oldIDs = old.items.mapTo(HashSet(), ::traceID)
            val nextIDs = next.items.map(::traceID)
            val nextSet = nextIDs.toHashSet()
            TranscriptMotionTrace.note(
                MotionFrame.Event(
                    name = "apply",
                    inserted = nextIDs.filter { it !in oldIDs }.sorted(),
                    removed = oldIDs.filter { it !in nextSet }.sorted(),
                    order = nextIDs,
                    isInitial = previous == null || old === TranscriptListSnapshot.empty,
                ),
            )
        }
        snapshot = next
    }

    /** iOS `traceID`: a message row by its stable ID, the others by name. Never message text. */
    private fun traceID(item: TranscriptItemID): String = when (item) {
        is TranscriptItemID.Message -> item.stableID
        else -> item.key
    }

    /**
     * iOS `motionProbe` (`-lab-motion-trace` and Motion Watch): the rows on screen as laid out
     * this frame and the motions running now; null before the list has laid anything out.
     */
    internal fun motionProbe(): TranscriptMotionTrace.Probe? {
        val info = lastLayout ?: return null
        val items = snapshot.items
        val before = info.beforeContentPadding
        val rows = info.visibleItemsInfo.mapNotNull { visible ->
            val item = items.getOrNull(visible.index)?.takeIf { it.key == visible.key } ?: return@mapNotNull null
            var kind = traceID(item)
            var key: String? = null
            if (item is TranscriptItemID.Message) {
                when (val row = snapshot.rows[item.stableID]) {
                    is MessageRow.Entry -> { kind = row.entry.layoutKind; key = row.entry.userMessageID }
                    is MessageRow.Queued -> { kind = "pending"; key = row.input.id }
                    null -> kind = "row"
                }
            }
            val animated = item.key in enteringItems || item.key in growths || item.key in disclosureSprings
            MotionFrame.Row(
                id = traceID(item),
                y = TranscriptMotionTrace.round(((visible.offset + before) / density).toDouble()),
                h = TranscriptMotionTrace.round((visible.size / density).toDouble()),
                alpha = 1.0,
                kind = kind,
                animated = animated,
                key = key,
            )
        }
        val motions = ArrayList<String>()
        bottomSpring?.kind?.let { motions += it.rawValue }
        if (jumpGlide != null && "jump" !in motions) motions += "jump"
        if (growths.isNotEmpty()) motions += "grow"
        if (disclosureSprings.isNotEmpty()) motions += "disclosure"
        val phase = when {
            !hasLaidOutContent -> "opening"
            transcriptNow() < arrivalsGlideAfter -> "settle"
            else -> "live"
        }
        val viewport = viewport(info)
        return TranscriptMotionTrace.Probe(
            sessionID = configuration.sessionID,
            rows = rows,
            motions = motions,
            phase = phase,
            reduceMotion = reduceMotion,
            browsing = behavior.mode == TranscriptScrollBehavior.Mode.Browsing,
            gesture = isUserGestureActive || state.isScrollInProgress,
            offset = TranscriptMotionTrace.round(viewport.offsetY.toDouble()),
            contentHeight = TranscriptMotionTrace.round(viewport.contentHeight.toDouble()),
            insetTop = TranscriptMotionTrace.round((before / density).toDouble()),
            insetBottom = TranscriptMotionTrace.round((info.afterContentPadding / density).toDouble()),
            height = TranscriptMotionTrace.round((info.viewportSize.height / density).toDouble()),
            width = TranscriptMotionTrace.round((info.viewportSize.width / density).toDouble()),
        )
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

    /** Every row is on screen: nothing to scroll in either direction. */
    private fun fitsOnScreen(): Boolean = !state.canScrollBackward && !state.canScrollForward &&
        lastLayout?.let { it.visibleItemsInfo.size == it.totalItemsCount } == true

    private fun animatePlacement(spring: ai.opencode.core.motion.MotionSpring, until: Double) {
        placementSpring = spring
        placementUntil = until
        pendingEffects += {
            scope.launch {
                kotlinx.coroutines.delay((ai.opencode.core.tuning.AnimationTimeScale.shared.wallSeconds(until - transcriptNow()) * 1000).toLong())
                if (placementUntil == until) placementSpring = null
            }
        }
    }

    fun imageGrowth(stableID: String): ai.opencode.feature.media.AgentImageRowGrowth =
        imageGrowths.getOrPut(stableID) { ai.opencode.feature.media.AgentImageRowGrowth() }

    fun imageContentRevised(stableID: String) {
        revisedRows["m:$stableID"] = transcriptNow()
        captureReadingAnchor()
    }

    /** Composer space may shrink under a held finger; the IME is already system-animated. */
    fun obstructionChanged(bottom: Float, ime: Float) {
        keyboardMoved = abs(ime - keyboardInset) > 0.5f
        val old = bottomObstruction
        if (old != null && hasLaidOutContent && isUserGestureActive && !keyboardMoved && bottom < old) {
            trailingSpace += old - bottom
            pendingEffects += { startMotion() }
        }
        bottomObstruction = bottom
        keyboardInset = ime
    }

    fun visibilityChanged() {
        if (isShowingToReader) lastLayout?.let(::onLayout) else finishGrowths()
    }

    private fun captureReadingAnchor() {
        if (readingAnchor != null || (behavior.mode != TranscriptScrollBehavior.Mode.Browsing && !isUserGestureActive)) return
        val visible = lastLayout?.visibleItemsInfo ?: return
        val entries = visible.filter { (it.key as? String)?.removePrefix("m:")?.let(snapshot.rows::get) is MessageRow.Entry }
        // Prefer the row below one only peeking out from under the header (44 pt).
        val row = entries.firstOrNull { it.offset + it.size > 44 * density } ?: entries.firstOrNull() ?: return
        readingAnchor = (row.key as String) to row.offset
    }

    private fun heightOf(item: LazyListItemInfo): Float = item.size / density

    /** iOS `viewport` from LazyColumn's layout: measured heights where known, the average elsewhere. */
    fun viewport(info: LazyListLayoutInfo = state.layoutInfo): TranscriptScrollBehavior.Viewport {
        val visible = info.visibleItemsInfo
        val visibleHeight = (info.viewportEndOffset - info.afterContentPadding) / density + trailingSpace
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
            trailingSpace = trailingSpace,
            canRequestEarlierHistory = canRequest,
            isUserScrolling = isUserGestureActive,
        )
    }

    private fun distanceFromBottom(viewport: TranscriptScrollBehavior.Viewport = viewport()): Float =
        TranscriptScrollBehavior.distanceFromBottom(viewport)

    // MARK: Layout

    /** Called after every LazyColumn layout (iOS `viewDidLayoutSubviews` + `reportLayoutChanged`). */
    fun onLayout(info: LazyListLayoutInfo) {
        layout(info)
        if (TranscriptMotionTrace.isActive) TranscriptMotionTrace.wake()
        if (perfRenderPending) reportRenderedSnapshot()
    }

    /**
     * iOS `reportRenderedSnapshot`: tells `PerfTimeline` the current configuration is on its way
     * to the screen (composed and its rows laid out). Composition and layout share a frame, so
     * from [flushEffects] (after composition) or [onLayout] the frame drawing it has its rows.
     * Until then it stays pending and is asked again after the next layout.
     */
    private fun reportRenderedSnapshot() {
        val perfRows = perfRows ?: return
        if (!perfRenderPending || !hasLaidOutContent) return
        perfRenderPending = false
        PerfTimeline.snapshotRendered(configuration.sessionID, configuration.snapshotRevision, perfRows)
    }

    private fun layout(info: LazyListLayoutInfo) {
        lastLayout = info
        val keys = snapshot.items
        for (item in info.visibleItemsInfo) {
            val key = item.key as? String ?: continue
            measuredHeights[key] = heightOf(item)
        }
        if (keys.isEmpty() || info.visibleItemsInfo.isEmpty()) return
        val visibleHeight = (info.viewportEndOffset - info.afterContentPadding) / density + trailingSpace
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
                if (TranscriptMotionTrace.isActive) TranscriptMotionTrace.note(MotionFrame.Event("first-frame"))
                send(TranscriptAction.FirstContentFrameRendered)
            }
            return
        }
        val geometryChanged = abs(visibleHeight - lastVisibleHeight) > 0.5f || abs(width - lastContentWidth) > 0.5f
        if (abs(width - lastContentWidth) > 0.5f) finishGrowths()
        lastVisibleHeight = visibleHeight
        lastContentWidth = width
        val viewport = viewport(info)
        readingAnchor?.let { (key, offset) ->
            readingAnchor = null
            val row = info.visibleItemsInfo.firstOrNull { it.key == key }
            if (row != null && abs(row.offset - offset) > 1) {
                state.dispatchRawDelta((row.offset - offset).toFloat())
                return
            }
        }
        pendingGrowthRide?.let { (wasAtEnd, step) ->
            pendingGrowthRide = null
            if (behavior.mode == TranscriptScrollBehavior.Mode.Following && !isUserGestureActive && bottomSpring == null) {
                val end = viewport.offsetY + distanceFromBottom(viewport)
                val before = if (wasAtEnd) viewport.offsetY else end
                val next = ride.offset(viewport.offsetY, before, end, step)
                if (next > viewport.offsetY) state.dispatchRawDelta((next - viewport.offsetY) * density)
                if (growths.isEmpty()) {
                    if (distanceFromBottom() > 0.5f) springToBottom(SpringKind.Arrival)
                    ride.reset()
                }
            }
        }
        if (isUserGestureActive) {
            handleBehavior(TranscriptScrollBehavior.Event.UserScrolled(viewport))
            return
        }
        if (pendingCut) {
            pendingCut = false
            beginCutGlide(viewport)
            return
        }
        if (jumpGlide != null || revealGlide != null) return
        pendingNewMessagesLanding?.let { generation ->
            if (landOnNewMessages(generation, info)) return
        }
        checkNewMessagesPassed(info)
        // A send is an explicit new destination, even if an earlier streaming row is
        // still growing. That growth's ride only owns an already-pinned end; it cannot
        // bridge the height of a new steering row below Working.
        if (followsSentRow && behavior.mode == TranscriptScrollBehavior.Mode.Following) {
            followsSentRow = false
            followEnd(viewport, viewportChanged = geometryChanged)
        }
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
            springToBottom(if (viewportChanged) {
                if (keyboardMoved) SpringKind.Keyboard else SpringKind.Viewport
            } else SpringKind.Arrival)
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
        revealGlide = null
        revealsToEnd = false
        readingAnchor = null
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

    /** TalkBack/focus scrolls enter LazyListState's scroll mutation without nested drag events. */
    fun scrollInProgressChanged(scrolling: Boolean) {
        if (scrolling && !isUserGestureActive) dragBegan()
        if (!scrolling && isUserGestureActive) settleUserScroll()
    }

    fun scrollToTop(): Boolean {
        stopBottomSpring()
        revealGlide = null
        revealsToEnd = false
        finishGrowths()
        handleBehavior(TranscriptScrollBehavior.Event.UserLeftBottom)
        scope.launch { state.animateScrollToItem(0) }
        return true
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
                is TranscriptScrollBehavior.Command.SetTrailingSpace -> {
                    if (command.space > trailingSpace) heldSpaceDrain = HeldSpaceDrain()
                    trailingSpace = command.space
                    if (trailingSpace > 0) startMotion()
                }
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
        revealGlide = null
        revealsToEnd = false
        isUserGestureActive = false
        readingAnchor = null
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
        if (TranscriptMotionTrace.isActive) TranscriptMotionTrace.wake()
        if (motionJob?.isActive == true) return
        motionJob = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                now = transcriptNow()
                if (stepMotion(tick.dt, min(tick.dt, tick.rawFrameDuration * tick.scale))) MotionFrameAction.Continue else MotionFrameAction.Stop
            }
        }
    }

    /** One frame of every transcript motion. Returns whether anything is still moving. */
    private fun stepMotion(dt: Double, followDt: Double): Boolean {
        var moved = false
        if (trailingSpace > 0) {
            val held = trailingSpace
            trailingSpace = if (reduceMotion) 0f else heldSpaceDrain.step(held, followDt, 1f / density)
            val drained = held - trailingSpace
            handleBehavior(TranscriptScrollBehavior.Event.ViewportRode(-drained))
            state.dispatchRawDelta(-drained * density)
            moved = trailingSpace > 0
        }
        revealGlide?.let { glide ->
            val remaining = newMessagesDistance()
            val share = remaining?.let { glide.step(dt, it, 1f / density, now > revealDeadline) }
            if (remaining == null || share == null) {
                if (remaining != null) state.dispatchRawDelta(remaining * density)
                revealGlide = null
                revealsToEnd = false
                handleBehavior(TranscriptScrollBehavior.Event.RevealEnded(distanceFromBottom() <= 1f))
            } else {
                state.dispatchRawDelta(remaining * share * density)
                moved = true
            }
        }
        emptyHandoff?.let {
            if (now >= it.start + it.duration) emptyHandoff = null
            frameTick++
            moved = moved || emptyHandoff != null
        }
        val following = behavior.mode == TranscriptScrollBehavior.Mode.Following && !isUserGestureActive
        // Row growth, ridden at the end while following (iOS `stepGrowths` + `GrowthRide`).
        if (growths.isNotEmpty() || disclosureSprings.isNotEmpty()) {
            val atEnd = distanceFromBottom() <= 1f || ride.deficit > 0.5f
            val finished = mutableListOf<String>()
            var grown = 0f
            for ((key, growth) in growths) {
                if (lastLayout?.visibleItemsInfo?.none { it.key == key } == true) {
                    finished += key
                    imageGrowths[key.removePrefix("m:")]?.settle()
                    continue
                }
                val before = growth.height
                growth.advance(dt, now)
                grown += growth.height - before
                if (growth.isFinished) finished += key
            }
            for (key in finished) {
                growths.remove(key)?.let { settledHeights[key] = it.target }
                imageGrowths[key.removePrefix("m:")]?.growthFinished()
            }
            for ((key, growth) in growths) imageGrowths[key.removePrefix("m:")]?.update(growth.target - growth.height)
            val doneDisclosures = disclosureSprings.filterValues { it.step(dt) }.keys
            for (key in doneDisclosures) disclosureSprings.remove(key)?.let { settledHeights[key] = it.target }
            if (following && bottomSpring == null) {
                // Ridden in the same frame as the growth (iOS `GrowthRide`): the position is
                // requested for this frame's measure, which lays the row out at its new height
                // and clamps to the new end. A plain scroll here would be refused at the old end
                // (nothing to scroll into yet), and one after the layout is a frame late: the end
                // would bob down and back on every chunk.
                val step = (BottomSpring.ARRIVAL_SPEED * dt).toFloat()
                val viewport = viewport()
                val end = viewport.offsetY + distanceFromBottom(viewport)
                val before = if (atEnd) viewport.offsetY else end
                val next = ride.offset(viewport.offsetY, before, end + max(0f, grown), step)
                val delta = ((next - viewport.offsetY) * density).roundToInt()
                if (delta > 0) state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset + delta)
            } else if (following && bottomSpring?.kind != SpringKind.Jump) {
                val step = (BottomSpring.ARRIVAL_SPEED * dt).toFloat()
                pendingGrowthRide = atEnd to step
            }
            frameTick++
            moved = true
            if (growths.isEmpty() && following && bottomSpring == null && distanceFromBottom() > 0.5f) {
                // The last subpixel height need not trigger another LazyColumn layout.
                // Hand the unconsumed ride directly to the follower instead of waiting for one.
                val carriesVelocity = ride.deficit > 0.5f
                springToBottom(SpringKind.Arrival)
                if (carriesVelocity) bottomSpring?.velocity = BottomSpring.ARRIVAL_SPEED
                ride.reset()
            }
        }
        // Entrances fade on the same clock.
        if (pendingEntrances.isNotEmpty()) {
            pendingEntrances.entries.removeAll { now - (entranceStarts[it.key] ?: it.value.first) > RowEntrance.WINDOW + RowEntrance.SETTLING_DURATION }
            entranceStarts.keys.retainAll(pendingEntrances.keys)
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
                stepBottomSpring(spring, if (spring.kind == SpringKind.Jump) dt else followDt)
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
        imageGrowths.values.forEach { it.settle() }
        pendingGrowthRide = null
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
        val imageGrowth = imageGrowths[key.removePrefix("m:")]
        val revised = revisedRows[key]?.let { transcriptNow() - it < 1 } == true
        val removed = removedHeights.remove(key) ?: 0f
        val runResized = runResizedItems.remove(key)
        val row = lastLayout?.visibleItemsInfo?.firstOrNull { it.key == key }
        val aboveAnchor = row != null && row.offset + row.size <= 44 * density
        if (runResized && removed <= 0.5f || revised && aboveAnchor) {
            growths.remove(key)
            revisedRows.remove(key)
            settledHeights[key] = measured
            imageGrowth?.settle()
            return measured
        }
        val growth = growths[key]
        if (growth != null) {
            if (!growth.continues(width)) {
                growths.remove(key)
                settledHeights[key] = measured
                imageGrowth?.settle()
                return measured
            }
            if (removed > 0.5f) {
                growth.absorb(removed, measured)
                imageGrowth?.beginGrowth()
            } else if (abs(growth.target - measured) > 0.25f) {
                growth.retarget(measured)
                imageGrowth?.beginGrowth()
            } else imageGrowth?.measuredUnchanged()
            return growth.height
        }
        val disclosureSpring = disclosureSprings[key]
        if (disclosureSpring != null) {
            if (abs(disclosureSpring.target - measured) > 0.25f) disclosureSpring.retarget(measured)
            return disclosureSpring.height
        }
        val settled = settledHeights[key]
        val entering = enteringItems.remove(key)
        if (settled == null && !entering && removed <= 0.5f) {
            settledHeights[key] = measured
            return measured
        }
        val from = (if (entering) 1f / density else settled ?: measured) + removed
        if (abs(from - measured) <= 0.5f) {
            settledHeights[key] = measured
            imageGrowth?.measuredUnchanged()
            return measured
        }
        if (disclosureEntryID == key && now - disclosureToggledAt < 1.0) {
            disclosureEntryID = null
            disclosureSprings[key] = DisclosureHeight(from, measured, opening = measured > from)
            startMotion()
            return from
        }
        val changedRowMayGrow = revised && !reduceMotion && hasLaidOutContent && isShowingToReader &&
            !isUserGestureActive && jumpGlide == null && transcriptNow() >= promotionUntil &&
            transcriptNow() >= catchUpLandsUntil && abs(measured - from) <= lastVisibleHeight
        if (!allowsGrowth(measured - from) && !changedRowMayGrow) {
            settledHeights[key] = measured
            imageGrowth?.settle()
            return measured
        }
        growths[key] = RowGrowth(from, measured, transcriptNow(), width,
            springDuration = if (revised) {
                val entry = (snapshot.rows[key.removePrefix("m:")] as? MessageRow.Entry)?.entry
                if (entry is TranscriptEntry.ReplyEnd) ai.opencode.feature.transcript.links.LinkPreviewMotion.grow else ai.opencode.feature.media.AgentImageTunables.grow
            } else null,
        ).also { it.changedRow = revised }
        revisedRows.remove(key)
        imageGrowth?.beginGrowth()
        startMotion()
        return from
    }

    private fun allowsGrowth(delta: Float): Boolean {
        val t = transcriptNow()
        val visible = lastVisibleHeight
        return !reduceMotion && hasLaidOutContent && isShowingToReader &&
            behavior.mode == TranscriptScrollBehavior.Mode.Following && !isUserGestureActive &&
            t >= arrivalsGlideAfter && t >= catchUpLandsUntil && jumpGlide == null &&
            t >= promotionUntil &&
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
        val (queued, style) = pendingEntrances[key] ?: return null
        @Suppress("UNUSED_VARIABLE") val tick = frameTick
        val time = transcriptNow()
        if (entranceStarts[key] == null && time - queued > RowEntrance.WINDOW) {
            pendingEntrances.remove(key)
            return null
        }
        val start = entranceStarts.getOrPut(key) { time }
        val duration = when (style) {
            RowEntranceStyle.Arrival -> RowEntrance.DURATION
            RowEntranceStyle.Settling -> RowEntrance.SETTLING_DURATION
            RowEntranceStyle.WithFlight -> RowEntrance.WITH_FLIGHT_DURATION
        }
        val progress = ((time - start) / duration).toFloat()
        if (progress >= 1f) {
            pendingEntrances.remove(key)
            entranceStarts.remove(key)
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
        if (!isShowingToReader || isUserGestureActive) return false
        pendingNewMessagesLanding = null
        landedNewMessages = generation
        if (!mark.showsDivider) {
            fittingNewMessages = generation
            send(TranscriptAction.NewMessagesFit(generation))
            return false
        }
        if (behavior.mode == TranscriptScrollBehavior.Mode.Browsing) {
            newMessagesDecision = mark.entryID to true
            return false
        }
        val dividerIndex = snapshot.items.indexOf(TranscriptItemID.NewDivider)
        if (dividerIndex < 0) return false
        val divider = info.visibleItemsInfo.firstOrNull { it.index == dividerIndex }
        val last = info.visibleItemsInfo.lastOrNull()
        val laidOut = divider != null && last?.index == snapshot.items.lastIndex
        val fits = if (laidOut) {
            (last.offset + last.size - divider.offset) / density <= viewport(info).visibleHeight
        } else {
            // iOS measures every row to the end before deciding. Laid out away from the end, the
            // divider fits when it sits no further down than the end can come up (estimated).
            dividerDistance()?.let { it >= distanceFromBottom() - 0.5f } ?: false
        }
        if (fits && !laidOut && revealsNewMessages && distanceFromBottom() > 1f) {
            // iOS `revealsToEnd`: it slides up to the end with no divider.
            fittingNewMessages = generation
            newMessagesDecision = mark.entryID to false
            send(TranscriptAction.NewMessagesFit(generation))
            snapshot = snapshot.copy(items = snapshot.items - TranscriptItemID.NewDivider)
            stopBottomSpring()
            finishGrowths()
            handleBehavior(TranscriptScrollBehavior.Event.RevealBegan)
            val distance = distanceFromBottom()
            val duration = NewMessagesReveal.duration(distance, viewport(info).visibleHeight)
            revealsToEnd = true
            revealGlide = JumpGlide(distance, JumpGlide.Curve.FromRest, duration)
            revealDeadline = transcriptNow() + duration + JumpGlide.MAXIMUM_EXTENSION + 0.5
            revealsNewMessages = false
            startMotion()
            return true
        }
        if (fits) {
            // What's new fits below it: no divider, as in a chat app.
            fittingNewMessages = generation
            newMessagesDecision = mark.entryID to false
            send(TranscriptAction.NewMessagesFit(generation))
            snapshot = snapshot.copy(items = snapshot.items - TranscriptItemID.NewDivider)
            return false
        }
        newMessagesDecision = mark.entryID to true
        stopBottomSpring()
        finishGrowths()
        handleBehavior(TranscriptScrollBehavior.Event.RevealBegan)
        val distance = newMessagesDistance()
        if (revealsNewMessages && distance != null && distance > 1f) {
            val duration = NewMessagesReveal.duration(distance, viewport(info).visibleHeight)
            revealGlide = JumpGlide(distance, JumpGlide.Curve.FromRest, duration)
            revealDeadline = transcriptNow() + duration + JumpGlide.MAXIMUM_EXTENSION + 0.5
            startMotion()
        } else {
            state.requestScrollToItem(dividerIndex, 0)
            handleBehavior(TranscriptScrollBehavior.Event.RevealEnded(atEnd = false))
        }
        revealsNewMessages = false
        AppLog.app.debug("transcript new divider landed generation=$generation")
        return true
    }

    private fun checkNewMessagesPassed(info: LazyListLayoutInfo) {
        val mark = configuration.newMessages ?: return
        if (passedNewMessages == mark.generation || revealGlide != null || pendingNewMessagesLanding != null || !isShowingToReader || behavior.mode != TranscriptScrollBehavior.Mode.Following) return
        val dividerIndex = snapshot.items.indexOf(TranscriptItemID.NewDivider)
        if (dividerIndex < 0) return
        val divider = info.visibleItemsInfo.firstOrNull { it.index == dividerIndex }
        val firstIndex = info.visibleItemsInfo.firstOrNull()?.index ?: return
        val passed = if (divider != null) divider.offset + divider.size <= 0 else firstIndex > dividerIndex
        if (!passed) return
        passedNewMessages = mark.generation
        send(TranscriptAction.NewMessagesPassed(mark.generation))
    }

    /** Exact once the divider enters view; measured row heights carry the glide until then. */
    private fun newMessagesDistance(): Float? {
        if (revealsToEnd) return distanceFromBottom()
        return dividerDistance()?.let { min(it, distanceFromBottom()) }
    }

    /** How far below the viewport's top the divider sits (dp, estimated until it's laid out). */
    private fun dividerDistance(): Float? {
        val index = snapshot.items.indexOf(TranscriptItemID.NewDivider)
        if (index < 0) return null
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo.firstOrNull { it.key == TranscriptItemID.NewDivider.key }
        if (visible != null) return visible.offset / density
        val first = info.visibleItemsInfo.firstOrNull() ?: return null
        val average = if (measuredHeights.isEmpty()) 80f else measuredHeights.values.average().toFloat()
        var distance = first.offset / density
        if (index >= first.index) {
            for (i in first.index until index) distance += measuredHeights[snapshot.items[i].key] ?: average
        } else {
            for (i in index until first.index) distance -= measuredHeights[snapshot.items[i].key] ?: average
        }
        return distance
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
