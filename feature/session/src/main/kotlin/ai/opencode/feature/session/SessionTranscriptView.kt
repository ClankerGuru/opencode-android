package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionHeaderStyle
import ai.opencode.feature.session.header.titleBreathing
import ai.opencode.feature.media.DecodedImageCache
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.LinkMenuAction
import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.feature.transcript.list.TranscriptList
import ai.opencode.feature.transcript.list.TranscriptSlots
import ai.opencode.feature.transcript.rows.AgentGlyphState
import ai.opencode.feature.transcript.rows.LocalTranscriptRowSlots
import ai.opencode.feature.transcript.rows.ReplyCopyGlyph
import ai.opencode.feature.transcript.rows.TranscriptFonts
import ai.opencode.feature.transcript.rows.TranscriptRowSlots
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong
import ai.opencode.feature.transcript.SinceYouLeft as TranscriptSinceYouLeft

// Port of iOS `Session/SessionTranscriptView.swift`: the transcript host below the header and
// above the composer, and the session-owned pieces the transcript draws (the title, the Working
// line, cached attachment images).
//
// iOS hosts its UIKit `TranscriptListView` with a `Configuration` projected from the store
// through a `ProjectionCache` (the visible window, entries, time stamps, the New divider's
// placement). Android projects the same `TranscriptConfiguration` here and hands it to
// `:feature:transcript`'s `TranscriptList`; the list's `TranscriptAction`s map to the
// `SessionFeature` actions of the same names. The Working line and the rows' Session-owned views
// (Since You Left, the activity orb, provider marks) go in through the list's slots.

/**
 * Owns everything below the header and above the composer. The list runs full-bleed under both
 * (its insets come from [top] and [scrollEdgeBridge]'s composer height), so the header backdrop
 * fades content rather than the screen clipping it.
 *
 * @param store the session screen's store.
 * @param scrollEdgeBridge geometry shared with the chrome, read per frame.
 * @param top where content rests below the header, the edge, and the backdrop's reveal distance.
 */
@Composable
fun SessionTranscriptView(
    store: StoreOf<State, Action>,
    scrollEdgeBridge: SessionScrollEdgeBridge,
    top: SessionHeaderStyle.Resolved.TranscriptTop,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = LocalSessionComposerHost.current
    val sinceYouLeftSetting by remember { shared(ExperimentalFeature.sinceYouLeft.key) }.flow
        .collectAsState(shared(ExperimentalFeature.sinceYouLeft.key).value)
    val imageLinkPreviewsSetting by remember { shared(ExperimentalFeature.imageLinkPreviews.key) }.flow
        .collectAsState(shared(ExperimentalFeature.imageLinkPreviews.key).value)
    val usesSinceYouLeft = ExperimentalFeature.sinceYouLeft.isActive(sinceYouLeftSetting)
    val hour24 = remember(context) { DateFormat.is24HourFormat(context) }
    val cache = remember { SessionTranscriptView.ProjectionCache() }

    // iOS projects in `body`: here once per state change, off the cache, so a keystroke that
    // changes nothing the list draws reuses every list and the configuration compares equal.
    fun project(state: State) = SessionTranscriptView.projection(
        state = state,
        cache = cache,
        scrollEdgeBridge = scrollEdgeBridge,
        usesSinceYouLeft = usesSinceYouLeft,
        hour24 = hour24,
    )
    val projection by remember(store, usesSinceYouLeft, hour24) {
        store.states.map(::project).distinctUntilChanged()
    }.collectAsState(remember(store, usesSinceYouLeft, hour24) { project(store.state) })
    val latestProjection by rememberUpdatedState(projection)
    val showsMoveToBackground by store.observe { it.showsMoveToBackground }

    val send: (TranscriptAction) -> Unit = remember(store, context, host) {
        { action -> SessionTranscriptView.handle(action, store, context, host) }
    }
    val slots = remember(store) {
        TranscriptSlots(
            workingLine = {
                val working = latestProjection.working
                if (working != null) {
                    WorkingLineRow(
                        activity = working,
                        showsMoveToBackground = showsMoveToBackground,
                        onMoveToBackground = { store.send(Action.MoveToBackgroundTapped) },
                    )
                }
            },
        )
    }
    val rowSlots = remember {
        TranscriptRowSlots(
            sinceYouLeft = { entryID, summary, onDismiss ->
                SinceYouLeftRow(entryID, SessionTranscriptView.sinceYouLeft(summary), onDismiss)
            },
            agentGlyph = { glyph, glyphModifier ->
                AgentActivityGlyph(
                    source = AgentActivitySource.Subagent(SessionTranscriptView.subagentActivity(glyph)),
                    modifier = glyphModifier,
                    size = glyph.size,
                )
            },
            providerMark = { providerID, markModifier -> ProviderIcon(providerID, markModifier) },
        )
    }
    // A new screen starts without the jump button (iOS `resetShowsJumpButton`).
    DisposableEffect(host) { onDispose { host?.showsJumpButton = false } }

    val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomObstruction = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    // Separate from the projection, so geometry and jumps never rebuild the configuration.
    CompositionLocalProvider(LocalTranscriptRowSlots provides rowSlots) {
        TranscriptList(
            configuration = projection.configuration,
            send = send,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = safeTop + top.contentTop.dp,
                bottom = bottomObstruction + scrollEdgeBridge.composerHeight.dp,
            ),
            jumpRequest = scrollEdgeBridge.jumpRequest,
            onShowsJumpButtonChanged = { host?.showsJumpButton = it },
            slots = slots,
            layoutTuning = TranscriptTunables.layoutSnapshot(
                imageLinkPreviews = ExperimentalFeature.imageLinkPreviews.isActive(imageLinkPreviewsSetting),
            ),
            onScrolledUnderHeader = { scrollEdgeBridge.scrolled(underHeader = it, revealDistance = top.revealDistance) },
        )
    }

    // Starting Up and the finale end on time, and a remount never replays them.
    val timedRunEvent by store.observe { SessionTranscriptView.timedRunEvent(it) }
    val navigationID by store.observe { it.navigationID }
    LaunchedEffect(timedRunEvent) {
        val event = timedRunEvent ?: return@LaunchedEffect
        val now = AgentActivityClock.now()
        val started = scrollEdgeBridge.workingRunTime(WorkingRun.token(navigationID, event.id), now)
        val remaining = event.duration - (now - started)
        if (remaining > 0) delay((remaining * 1000).roundToLong())
        store.send(Action.WorkingRunElapsed(event.id))
    }
}

/** iOS `SessionTranscriptView`'s statics: the projection, its cache, and the Working line's activity. */
object SessionTranscriptView {
    /** What the list draws, and the Working line beside it (iOS `Configuration.working`). */
    @Immutable
    data class Projection(val configuration: TranscriptConfiguration, val working: WorkingActivity?)

    /**
     * iOS `ProjectionCache`: rebuilds each stage of the projection only when its own inputs
     * change, and patches a streaming reply's last text in place instead of regrouping the
     * transcript. Lists handed out are never mutated afterwards (a new list replaces a patched
     * one), so equal-by-reference checks downstream stay sound.
     */
    class ProjectionCache {
        private data class WindowInputs(
            val items: List<OpenCodeTranscriptItem>,
            val visibleLimit: Int,
            val browsingWindowStartID: String?,
            val revertMessageID: String?,
        )

        private data class StructuralInputs(
            val visibleItems: List<OpenCodeTranscriptItem>,
            val allItems: List<OpenCodeTranscriptItem>,
            val showsWorking: Boolean,
            val failedFinale: Boolean,
            val models: List<OpenCodeModel>,
            val sinceYouLeft: TranscriptSinceYouLeft?,
        )

        private data class StampInputs(
            val allItems: List<OpenCodeTranscriptItem>,
            val sentNow: Boolean?,
            val nowSecond: Long,
            val zoneID: String,
            val localeID: String,
            val hour24: Boolean?,
        )

        private data class FinalInputs(
            val structuralGeneration: Int,
            val stamps: Map<String, String>,
            val newMessages: NewMessagesMark?,
        )

        private var windowInputs: WindowInputs? = null
        private var visibleItems: List<OpenCodeTranscriptItem> = emptyList()
        private var structuralInputs: StructuralInputs? = null
        private var structuralEntries: List<TranscriptEntry> = emptyList()
        private var structuralGeneration = 0
        private var stampInputs: StampInputs? = null
        private var stamps: Map<String, String> = emptyMap()
        private var finalInputs: FinalInputs? = null
        private var entries: List<TranscriptEntry> = emptyList()
        private var newMessages: NewMessagesPlacement? = null

        fun resolveVisibleItems(
            items: List<OpenCodeTranscriptItem>,
            visibleLimit: Int,
            browsingWindowStartID: String?,
            revertMessageID: String?,
            compute: () -> List<OpenCodeTranscriptItem>,
        ): List<OpenCodeTranscriptItem> {
            val inputs = WindowInputs(items, visibleLimit, browsingWindowStartID, revertMessageID)
            val prev = windowInputs
            // A streaming reply changes only its last item: patch it rather than re-window.
            if (prev != null &&
                prev.visibleLimit == visibleLimit &&
                prev.browsingWindowStartID == browsingWindowStartID &&
                prev.revertMessageID == revertMessageID &&
                revertMessageID == null &&
                prev.items.size == items.size &&
                items.isNotEmpty() &&
                visibleItems.isNotEmpty()
            ) {
                val lastIdx = items.size - 1
                if (prev.items[lastIdx] != items[lastIdx] &&
                    prev.items[lastIdx].id == items[lastIdx].id &&
                    visibleItems.last().id == items[lastIdx].id &&
                    prev.items.subList(0, lastIdx) == items.subList(0, lastIdx)
                ) {
                    windowInputs = inputs
                    visibleItems = visibleItems.dropLast(1) + items[lastIdx]
                    return visibleItems
                }
            }
            if (inputs != windowInputs) {
                windowInputs = inputs
                visibleItems = compute()
            }
            return visibleItems
        }

        fun resolveEntries(
            visibleItems: List<OpenCodeTranscriptItem>,
            allItems: List<OpenCodeTranscriptItem>,
            showsWorking: Boolean,
            failedFinale: Boolean,
            models: List<OpenCodeModel>,
            sinceYouLeft: TranscriptSinceYouLeft? = null,
            sentNow: Boolean?,
            mark: NewMessagesMark?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
            hour24: Boolean? = null,
        ): Pair<List<TranscriptEntry>, NewMessagesPlacement?> {
            val nowSecond = now.epochSecond
            val prevStructural = structuralInputs
            val prevStamp = stampInputs
            val prevFinal = finalInputs
            // While a reply streams, only its last text changes: replace that entry in place.
            if (showsWorking && !failedFinale &&
                prevStructural != null && prevStamp != null && prevFinal != null &&
                prevStructural.showsWorking == showsWorking &&
                prevStructural.failedFinale == failedFinale &&
                prevStructural.models == models &&
                prevStamp.sentNow == sentNow &&
                prevStamp.nowSecond == nowSecond &&
                prevStamp.zoneID == zone.id &&
                prevStamp.localeID == locale.toLanguageTag() &&
                prevStamp.hour24 == hour24 &&
                prevFinal.newMessages == mark &&
                visibleItems.isNotEmpty() &&
                visibleItems.size == prevStructural.visibleItems.size &&
                allItems.isNotEmpty() &&
                allItems.size == prevStructural.allItems.size &&
                structuralEntries.isNotEmpty() &&
                entries.isNotEmpty()
            ) {
                val vLast = visibleItems.size - 1
                val aLast = allItems.size - 1
                val newItem = visibleItems[vLast] as? OpenCodeTranscriptItem.AssistantMarkdown
                val oldItem = prevStructural.visibleItems[vLast] as? OpenCodeTranscriptItem.AssistantMarkdown
                val structuralLast = ((structuralEntries.last() as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.AssistantMarkdown)
                val entryLast = ((entries.last() as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.AssistantMarkdown)
                if (newItem != null && oldItem != null && newItem.id == oldItem.id &&
                    allItems[aLast].id == prevStructural.allItems[aLast].id &&
                    TranscriptEntry.promptID(allItems[aLast]) == null &&
                    structuralLast?.id == newItem.id &&
                    entryLast?.id == newItem.id &&
                    visibleItems.subList(0, vLast) == prevStructural.visibleItems.subList(0, vLast) &&
                    (visibleItems.size == allItems.size || allItems.subList(0, aLast) == prevStructural.allItems.subList(0, aLast))
                ) {
                    if (visibleItems[vLast] != prevStructural.visibleItems[vLast]) {
                        val updatedEntry = TranscriptEntry.Single(visibleItems[vLast])
                        structuralInputs = prevStructural.copy(visibleItems = visibleItems, allItems = allItems)
                        structuralEntries = structuralEntries.dropLast(1) + updatedEntry
                        structuralGeneration += 1
                        stampInputs = prevStamp.copy(allItems = allItems)
                        finalInputs = prevFinal.copy(structuralGeneration = structuralGeneration)
                        entries = entries.dropLast(1) + updatedEntry
                    }
                    return entries to newMessages
                }
            }
            val structural = StructuralInputs(visibleItems, allItems, showsWorking, failedFinale, models, sinceYouLeft)
            if (structural != structuralInputs) {
                structuralInputs = structural
                // While the Working line plays Failed, it is the failure: the run's error line
                // takes its place once the flash has played.
                val items = if (failedFinale) withoutTurnRunError(visibleItems) else visibleItems
                structuralEntries = TranscriptEntry.build(
                    items = items,
                    isRunning = showsWorking,
                    models = models,
                    sessionStartModels = TranscriptEntry.sessionStartModelIDs(allItems),
                    sinceYouLeft = sinceYouLeft,
                )
                structuralGeneration += 1
            }
            val stampKey = StampInputs(allItems, sentNow, nowSecond, zone.id, locale.toLanguageTag(), hour24)
            if (stampKey != stampInputs) {
                stampInputs = stampKey
                // Times read from every loaded message, so the window's first one is dated
                // against the messages before it. A new session's sends count as sent now until
                // their replies carry a time.
                stamps = TranscriptTimeStamps.stamps(allItems, sentNow, now, zone, locale, hour24)
            }
            val finalKey = FinalInputs(structuralGeneration, stamps, mark)
            if (finalKey != finalInputs) {
                finalInputs = finalKey
                val built = TranscriptTimeStamps.inserting(stamps, structuralEntries)
                entries = built
                newMessages = NewMessagesMark.resolve(mark, visibleItems, built)
            }
            return entries to newMessages
        }
    }

    /**
     * iOS `configuration(using:at:)`: everything the list draws, from the store. [now] is
     * `AgentActivityClock` seconds (the Working line's clock); time stamps read the wall clock.
     */
    fun projection(
        state: State,
        cache: ProjectionCache,
        scrollEdgeBridge: SessionScrollEdgeBridge,
        usesSinceYouLeft: Boolean,
        now: Double = AgentActivityClock.now(),
        hour24: Boolean? = null,
    ): Projection {
        val visibleItems = cache.resolveVisibleItems(
            items = state.items,
            visibleLimit = state.visibleLimit,
            browsingWindowStartID = state.browsingWindowStartID,
            revertMessageID = state.session?.revertMessageID,
            compute = { state.visibleItems },
        )
        val working = workingActivity(state, visibleItems, scrollEdgeBridge, now)
        // The finale holds the transcript as it was running, so Working leaves and the reply's
        // end arrives together, as they do without it.
        val showsWorking = working != null
        val isFailedFinale = working?.phase == WorkingActivity.Phase.failed
        val sentNow: Boolean? = if (state.isDraft || state.draftBecameSessionID != null) true else null
        val (entries, newMessages) = cache.resolveEntries(
            visibleItems = visibleItems,
            allItems = state.items,
            showsWorking = showsWorking,
            failedFinale = isFailedFinale,
            models = state.models,
            sinceYouLeft = if (usesSinceYouLeft && !state.isDraft) state.sinceYouLeft?.let(::transcriptSinceYouLeft) else null,
            sentNow = sentNow,
            mark = state.newMessages,
            hour24 = hour24,
        )
        val queuedRows = if (state.queuedInputs.isEmpty()) emptyList() else state.queuedInputRows
        val actionableErrorID = state.actionableRunErrorID
        val configuration = TranscriptConfiguration(
            server = state.server,
            sessionID = state.session?.id,
            directory = state.session?.directory,
            historyGeneration = state.historyGeneration,
            entries = entries,
            hasEarlierItems = state.hasEarlierItems,
            isLoadingEarlier = state.isLoadingEarlier,
            earlierHistoryError = state.earlierHistoryError,
            isLoading = state.isTranscriptLoading,
            showsEmptyState = state.showsEmptyState,
            isRunning = showsWorking,
            hasPendingRequests = state.hasPendingRequests,
            showsMoveToBackground = state.showsMoveToBackground,
            actionableRunErrorID = actionableErrorID,
            actionableRunErrorActions = if (actionableErrorID == null) emptyList() else state.actionableRunErrorActions,
            subagents = state.transcriptSubagents,
            sessionLinks = state.sessionLinks,
            isDraft = state.isDraft,
            // Steering messages land at the next step, so they sit in the transcript as dim
            // bubbles; queued ones wait in the pill.
            queuedInputs = if (queuedRows.isEmpty()) emptyList() else queuedRows.filter { !it.waitsInQueuedPill },
            failedSends = if (state.failedSends.isEmpty()) {
                emptyMap()
            } else {
                buildMap { for (failed in state.failedSends) putIfAbsent(failed.id, failed.reason) }
            },
            deliveryNotes = if (state.failedSends.isEmpty()) emptyMap() else state.deliveryNotes.mapValues { it.value.label },
            queuedPillTexts = if (queuedRows.isEmpty()) {
                emptyMap()
            } else {
                queuedRows.filter { it.waitsInQueuedPill }.distinctBy { it.id }
                    .associate { it.id to (TranscriptUserMessage.from(it)?.displayText ?: it.text) }
            },
            sentItemID = state.lastSentItemID,
            sendGeneration = state.sendGeneration,
            newMessages = newMessages,
        )
        return Projection(configuration, working)
    }

    /**
     * Maps what a row or the list asks for to the `SessionFeature` action of the same name (iOS
     * rows send them directly). Share, files and image previews are the screen's: Share opens
     * the system share sheet with the link; files and images end text focus first, as iOS does
     * before presenting their viewers.
     */
    fun handle(action: TranscriptAction, store: StoreOf<State, Action>, context: Context, host: SessionComposerHost?) {
        when (action) {
            is TranscriptAction.LinkMenu -> {
                store.send(Action.LinkMenu(action.action.mediaAction, action.url))
                if (action.action == LinkMenuAction.Share) {
                    val url = OpenCodePairingLink.shareableURL(action.url) ?: return
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            // The keyboard would stay up over the viewer. (The viewers belong to :feature:media.)
            is TranscriptAction.OpenFile, is TranscriptAction.PreviewImage -> host?.dismissComposerKeyboard()
            else -> sessionAction(action)?.let(store::send)
        }
    }

    /** The `SessionFeature` action a [TranscriptAction] stands for; null for the screen's own. */
    fun sessionAction(action: TranscriptAction): Action? = when (action) {
        TranscriptAction.FirstContentFrameRendered -> Action.FirstContentFrameRendered
        is TranscriptAction.TranscriptBrowsingChanged -> Action.TranscriptBrowsingChanged(action.isBrowsing)
        is TranscriptAction.NewMessagesPassed -> Action.NewMessagesPassed(action.generation)
        is TranscriptAction.NewMessagesFit -> Action.NewMessagesFit(action.generation)
        TranscriptAction.SinceYouLeftTapped -> Action.SinceYouLeftTapped
        TranscriptAction.ShowEarlierTapped -> Action.ShowEarlierTapped
        is TranscriptAction.CopyTextTapped -> Action.CopyTextTapped(action.text)
        is TranscriptAction.QuoteTextTapped -> Action.QuoteTextTapped(action.text)
        is TranscriptAction.LinkMenu -> Action.LinkMenu(action.action.mediaAction, action.url)
        is TranscriptAction.LinkTapped -> Action.LinkTapped(action.url)
        is TranscriptAction.SessionLinksAppeared -> Action.SessionLinksAppeared(action.ids)
        is TranscriptAction.SubagentTapped -> Action.SubagentTapped(action.sessionID)
        TranscriptAction.MoveToBackgroundTapped -> Action.MoveToBackgroundTapped
        is TranscriptAction.FailedSendRetryTapped -> Action.FailedSendRetryTapped(action.messageID)
        is TranscriptAction.FailedSendEditTapped -> Action.FailedSendEditTapped(action.messageID)
        is TranscriptAction.FailedSendDeleteTapped -> Action.FailedSendDeleteTapped(action.messageID)
        is TranscriptAction.RunErrorActionTapped -> Action.RunErrorActionTapped(action.action)
        is TranscriptAction.RevertToHereTapped -> Action.RevertToHereTapped(action.messageID)
        TranscriptAction.RedoTapped -> Action.RedoTapped
        is TranscriptAction.PendingInputSendNowTapped -> Action.PendingInputSendNowTapped(action.inputID)
        is TranscriptAction.PendingInputMoveToQueueTapped -> Action.PendingInputMoveToQueueTapped(action.inputID)
        is TranscriptAction.PendingInputCancelTapped -> Action.PendingInputCancelTapped(action.inputID)
        is TranscriptAction.OpenFile, is TranscriptAction.PreviewImage -> null
    }

    /** The transcript's copy of the Since You Left value (`:feature:transcript` draws its row). */
    fun transcriptSinceYouLeft(summary: SinceYouLeft) = TranscriptSinceYouLeft(
        sessionID = summary.sessionID,
        lastItemID = summary.lastItemID,
        text = summary.text,
        phase = if (summary.phase == SinceYouLeft.Phase.Generating) TranscriptSinceYouLeft.Phase.Generating else TranscriptSinceYouLeft.Phase.Done,
        isFallback = summary.isFallback,
    )

    /** Back to the session's value for [SinceYouLeftRow]. */
    fun sinceYouLeft(summary: TranscriptSinceYouLeft) = SinceYouLeft(
        sessionID = summary.sessionID,
        lastItemID = summary.lastItemID,
        text = summary.text,
        phase = if (summary.phase == TranscriptSinceYouLeft.Phase.Generating) SinceYouLeft.Phase.Generating else SinceYouLeft.Phase.Done,
        isFallback = summary.isFallback,
    )

    /** A transcript row's subagent glyph as the activity orb reads it. */
    fun subagentActivity(glyph: AgentGlyphState) = SubagentActivity(
        sessionID = glyph.sessionID,
        phase = when (glyph.phase) {
            AgentGlyphState.Phase.Spawning -> SubagentActivity.Phase.spawning
            AgentGlyphState.Phase.Running -> SubagentActivity.Phase.running
            AgentGlyphState.Phase.Done -> SubagentActivity.Phase.done
            AgentGlyphState.Phase.Failed -> SubagentActivity.Phase.failed
        },
        needsInput = glyph.needsInput,
    )

    /** A Working-line event that ends on time: Starting Up, or a finale. */
    @Immutable
    data class TimedRunEvent(val id: Int, val duration: Double)

    fun timedRunEvent(state: State): TimedRunEvent? {
        val run = state.workingRun
        run.finale?.let { return TimedRunEvent(it.id, it.duration) }
        if (!state.isRunning) return null
        return run.startID?.let { TimedRunEvent(it, WorkingActivity.STARTUP) }
    }

    /**
     * The Working line's orb, or null when the line isn't shown: while the session runs, and for
     * its finale's flash after. [now] is `AgentActivityClock` seconds.
     */
    fun workingActivity(
        state: State,
        visibleItems: List<OpenCodeTranscriptItem>,
        scrollEdgeBridge: SessionScrollEdgeBridge,
        now: Double,
    ): WorkingActivity? {
        val run = state.workingRun
        val key = state.workingOrbKey
        fun token(id: Int) = WorkingRun.token(state.navigationID, id)
        val running = if (state.isRunning) {
            WorkingActivity(
                phase = if (state.isInterrupting) WorkingActivity.Phase.stopping else WorkingActivity.Phase.running,
                startedAt = run.startID?.let { scrollEdgeBridge.workingRunTime(token(it), now) },
                needsInput = state.hasPendingRequests,
                key = key,
                retry = WorkingActivity.retry(visibleItems),
            )
        } else {
            null
        }
        // The flash plays on through events that trail the run's end.
        val finale = run.finale ?: return running
        val started = scrollEdgeBridge.workingRunTime(token(finale.id), now)
        if (now - started >= finale.duration) return running
        // Waiting on the settlement read: the line stays as it was.
        if (finale.kind == WorkingRun.Finale.Kind.pending) return running ?: WorkingActivity(phase = WorkingActivity.Phase.running, key = key)
        // A run's error can land a moment after its end.
        val live = WorkingRun.outcome(visibleItems)
        if (live == WorkingRun.Outcome.interrupted) return running
        // The line ends in the reply's copy button when the turn has reply text. A failed run's
        // line fades instead: its error line arrives in its place, with the copy button below it.
        val failed = finale.kind == WorkingRun.Finale.Kind.failed || live == WorkingRun.Outcome.failed
        val replyEnds = !failed && TranscriptEntry.build(visibleItems, isRunning = false).lastOrNull() is TranscriptEntry.ReplyEnd
        return WorkingActivity(
            phase = if (failed) WorkingActivity.Phase.failed else WorkingActivity.Phase.done,
            key = key,
            finaleStartedAt = started,
            handsOffToCopy = replyEnds,
            finaleDuration = finale.finaleDuration,
            handoffDuration = finale.handoffDuration,
        )
    }

    /** The last turn's items without its run error. */
    fun withoutTurnRunError(items: List<OpenCodeTranscriptItem>): List<OpenCodeTranscriptItem> {
        val last = items.indexOfLast {
            it is OpenCodeTranscriptItem.UserText || it is OpenCodeTranscriptItem.UserImages || it is OpenCodeTranscriptItem.UserFiles
        }
        val start = if (last >= 0) last + 1 else 0
        return items.subList(0, start) + items.subList(start, items.size).filter { it !is OpenCodeTranscriptItem.RunError }
    }
}

// MARK: - Shared pieces

/** What keeps a transcript's list: its visit and session (see `transcriptIdentity`). */
@Immutable
data class TranscriptIdentity(val visit: Int, val sessionID: String?)

/**
 * The transcript's identity: the session on screen, except that a draft's transcript carries on
 * into the session it became. Drafts (and that session) are told apart by their visit
 * (`navigationID`), so one draft's transcript never carries into the next.
 */
val State.transcriptIdentity: TranscriptIdentity
    get() {
        val continues = session != null && session.id == draftBecameSessionID
        if (session != null && !continues) return TranscriptIdentity(visit = 0, sessionID = session.id)
        return TranscriptIdentity(visit = navigationID, sessionID = null)
    }

/**
 * Decodes image bytes once and caches the result (iOS `CachedImage`); decoding in a list row
 * would otherwise run on every recomposition. Fills its bounds, cropping.
 */
@Composable
fun CachedImage(id: String, data: ByteArray, modifier: Modifier = Modifier) {
    val image = remember(id, data) { CachedImage.decoded(id, data) } ?: return
    Image(image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

object CachedImage {
    fun decoded(id: String, data: ByteArray): ImageBitmap? {
        val cache = dependency(DecodedImageCache)
        val key = "attachment:$id"
        cache.image(key)?.let { return it.asImageBitmap() }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        cache.insert(bitmap, key)
        return bitmap.asImageBitmap()
    }
}

/**
 * The header's title: a new title replaces the old one word-softly (the old drifts up and blurs
 * out, the new rises in), breathing while the session syncs or the server writes a title.
 *
 * @param isLastKnown the last refresh failed: the title is secondary until it syncs again.
 * @param animates false on the frame a session switched in place first shows: it swaps at once.
 */
@Composable
fun SyncingSessionTitle(
    title: String,
    isSyncing: Boolean,
    isLastKnown: Boolean,
    modifier: Modifier = Modifier,
    animates: Boolean = true,
) {
    val reducedMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val color = if (isLastKnown && !isSyncing) colors.secondaryText else colors.text
    AnimatedContent(
        targetState = title,
        modifier = modifier.titleBreathing(isSyncing),
        transitionSpec = {
            val spec = titleSmooth
            when {
                !animates -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                reducedMotion -> fadeIn(Motion.reducedFadeSpec()) togetherWith fadeOut(Motion.reducedFadeSpec())
                // `.blurReplace(.upUp)`: both move up.
                else -> (fadeIn(spec.spec()) + slideInVertically(spec.spec()) { it / 3 }) togetherWith
                    (fadeOut(spec.spec()) + slideOutVertically(spec.spec()) { -it / 3 })
            }
        },
        label = "syncingSessionTitle",
    ) { shown ->
        Text(
            shown,
            style = HeaderBlurTunables.titleFont.textStyle.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** SwiftUI `.smooth(duration: 0.45)`. */
private val titleSmooth = MotionSpring(0.45, 0.0)

/**
 * The session's run status: the status orb, then “Working” (or “Needs your input”) as still
 * text, since the orb carries the motion. While the server waits to retry, the text counts down
 * once a second.
 */
@Composable
fun WorkingIndicator(activity: WorkingActivity, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    // The subagent cards' orb, scaled with the body text it leads.
    val orbSize = (18 * fontScale).dp
    val spacing = (8 * fontScale).dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        AgentActivityGlyph(
            source = AgentActivitySource.Session(activity),
            size = orbSize,
            isPaused = activity.phase == WorkingActivity.Phase.stopping,
            modifier = Modifier.clearAndSetSemantics {},
        )
        val style = TranscriptFonts.metaFont(FontWeight.Medium)
        if (activity.retry != null && activity.phase == WorkingActivity.Phase.running && !activity.needsInput) {
            // The server's retry counts down once a second.
            var now by remember { mutableStateOf(Instant.now()) }
            LaunchedEffect(activity.retry) {
                while (true) {
                    now = Instant.now()
                    delay(1000)
                }
            }
            Text(activity.title(now), style = style.copy(fontFeatureSettings = "tnum"), color = OpenCodeTheme.colors.secondaryText)
        } else {
            Text(activity.title, style = style, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * The transcript's Working line. It lays out as the reply's end does (the copy glyph's slot
 * between the reply end's insets), so the two rows are one height and the handoff moves nothing:
 * after the Done or Failed flash the indicator blurs and shrinks into the copy glyph's centre
 * while the glyph sharpens in where the reply's copy button then takes over.
 *
 * While a shell or subagent has blocked the run for three seconds, the line's trailing end offers
 * Move to Background when [showsMoveToBackground] (`MoveToBackground.showsManualAction`, off).
 */
@Composable
fun WorkingLineRow(
    activity: WorkingActivity,
    modifier: Modifier = Modifier,
    showsMoveToBackground: Boolean = false,
    onMoveToBackground: () -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val offersMove = activity.finaleStartedAt == null && !activity.needsInput && showsMoveToBackground
    // One clock throughout, so the indicator keeps its identity (and its orb its pose) when the
    // finale starts it. It ticks only during the finale, while the app is active.
    var now by remember { mutableDoubleStateOf(AgentActivityClock.now()) }
    LaunchedEffect(activity.finaleStartedAt, active) {
        now = AgentActivityClock.now()
        if (activity.finaleStartedAt == null || !active) return@LaunchedEffect
        while (true) {
            withFrameNanos { now = AgentActivityClock.now() }
            if (activity.handoff(now) >= 1) break
        }
    }
    val pose = WorkingHandoffPose.of(activity.handoff(now), activity.handsOffToCopy, reduceMotion)
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = TranscriptMetrics.flowHalf, bottom = TranscriptMetrics.turnHalf),
    ) {
        WorkingLine(activity, pose, offersMove, onMoveToBackground)
        // An overlay, so offering it never changes the row's height.
        AnimatedVisibility(
            visible = offersMove,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (reduceMotion) fadeIn(Motion.reducedFadeSpec()) else fadeIn(moveSnappy.spec()) + slideInHorizontally(moveSnappy.spec()) { 8 },
            exit = if (reduceMotion) fadeOut(Motion.reducedFadeSpec()) else fadeOut(moveSnappy.spec()) + slideOutHorizontally(moveSnappy.spec()) { 8 },
        ) {
            MoveToBackgroundAction(activity, onMoveToBackground)
        }
    }
}

/** SwiftUI `.snappy(duration: 0.35)`. */
private val moveSnappy = MotionSpring(0.35, 0.15)

/** The copy glyph's centre from the text edge (its slot starts [ReplyCopyGlyph.leading] out). */
private val COPY_CENTRE = ReplyCopyGlyph.slot / 2 + ReplyCopyGlyph.leading

@Composable
private fun WorkingLine(activity: WorkingActivity, pose: WorkingHandoffPose, offersMove: Boolean, onMove: () -> Unit) {
    Box {
        ReplyCopyGlyph.Glyph(
            Modifier
                .offset(x = ReplyCopyGlyph.leading)
                .alpha(pose.copyOpacity.toFloat())
                .blur(pose.copyBlur.dp)
                .graphicsLayer {
                    scaleX = pose.copyScale.toFloat()
                    scaleY = pose.copyScale.toFloat()
                }
                .clearAndSetSemantics {},
        )
        // Shrinks about the copy glyph's centre, 9 dp in, where the orb sits.
        val scale = pose.indicatorScale.toFloat()
        WorkingIndicator(
            activity,
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = COPY_CENTRE * (1 - scale))
                .blur(pose.indicatorBlur.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = pose.indicatorOpacity.toFloat()
                }
                .semantics(mergeDescendants = true) {
                    if (offersMove) customActions = listOf(CustomAccessibilityAction("Move to Background") { onMove(); true })
                },
        )
    }
}

/** A quiet text action at the Used N rows' size. It gives way to its glyph alone before it would reach Working. */
@Composable
private fun MoveToBackgroundAction(activity: WorkingActivity, onMove: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints {
        // Working's footprint (orb slot + text), without a second animating orb.
        val reserved = (26 * fontScale).dp + (if (activity.needsInput) 140 else 70).dp * fontScale + 12.dp
        val showsTitle = maxWidth - reserved > 200.dp * fontScale
        Row(
            Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onMove)
                .semantics { contentDescription = "Move to Background" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            OpenCodeIcon(SFSymbol.TrayAndArrowDown, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
            if (showsTitle) {
                Text(
                    "Move to Background",
                    style = TranscriptFonts.metaFont().copy(fontSize = 17.sp),
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The transcript's copy of the link menu's actions, as `:feature:media`'s (the reducer's) type: the same cases. */
private val LinkMenuAction.mediaAction: ai.opencode.feature.media.LinkMenuAction
    get() = ai.opencode.feature.media.LinkMenuAction.valueOf(name)
