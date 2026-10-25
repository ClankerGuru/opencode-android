package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.rememberScope
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionHeaderStyle
import ai.opencode.feature.session.header.titleBreathing
import ai.opencode.feature.session.seams.ReplyCopyGlyph
import ai.opencode.feature.session.seams.ReplyCopyGlyphView
import ai.opencode.feature.session.seams.SessionScrollEdgeBridge
import ai.opencode.feature.session.seams.TranscriptEntry
import ai.opencode.feature.session.seams.TranscriptImageCache
import ai.opencode.feature.session.seams.TranscriptMetrics
import ai.opencode.feature.session.seams.endsInReply
import ai.opencode.feature.transcript.TranscriptView
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.time.Instant
import kotlin.math.roundToLong

// Port of iOS `Session/SessionTranscriptView.swift`: the transcript host below the header and
// above the composer, and the session-owned pieces the transcript draws (the title, the Working
// line, cached attachment images).
//
// iOS hosts its UIKit `TranscriptListView` here with a `Configuration` projected from the store
// (`ProjectionCache`: entries, time stamps, New divider placement). Android embeds
// `:feature:transcript`'s public entry point, `TranscriptView(store.scope(...))`; the projection
// (TranscriptEntry.build, TranscriptTimeStamps, NewMessagesPlacement) moves with the list when that
// area lands. What stays session-owned is ported here: the Working line's activity and timing.

/**
 * Owns everything below the header and above the composer. The list runs full-bleed under both
 * (its insets come from [top] and [scrollEdgeBridge]'s composer height), so the header backdrop
 * fades content rather than the screen clipping it.
 *
 * @param store the session screen's store.
 * @param scrollEdgeBridge geometry shared with the chrome, read per frame.
 * @param top where content rests below the header, the edge, and the backdrop's reveal distance.
 */
@Suppress("UNUSED_PARAMETER") // [top] reaches the list once `TranscriptView` takes its insets.
@Composable
fun SessionTranscriptView(
    store: StoreOf<State, Action>,
    scrollEdgeBridge: SessionScrollEdgeBridge,
    top: SessionHeaderStyle.Resolved.TranscriptTop,
    modifier: Modifier = Modifier,
) {
    val transcript = store.rememberScope({ it.transcript }, Action::Transcript)
    TranscriptView(transcript, modifier.fillMaxSize())

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

/** iOS `SessionTranscriptView`'s statics and the Working line's projection. */
object SessionTranscriptView {
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
        val replyEnds = !failed && TranscriptEntry.endsInReply(visibleItems)
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
        val cache = dependency(TranscriptImageCache)
        val key = "attachment:$id"
        cache.image(key)?.let { return it }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val image = bitmap.asImageBitmap()
        cache.insert(image, key)
        return image
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
        val style = TranscriptMetrics.metaFont(FontWeight.Medium)
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
            .padding(top = TranscriptMetrics.flowHalf.dp, bottom = TranscriptMetrics.turnHalf.dp),
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

/** The copy glyph's centre from the text edge (its 44 dp slot starts 13 dp out). */
private const val COPY_CENTRE = 22f + ReplyCopyGlyph.leading

@Composable
private fun WorkingLine(activity: WorkingActivity, pose: WorkingHandoffPose, offersMove: Boolean, onMove: () -> Unit) {
    Box {
        ReplyCopyGlyphView(
            Modifier
                .offset(x = ReplyCopyGlyph.leading.dp)
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
                .offset(x = (COPY_CENTRE * (1 - scale)).dp)
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
                    style = TranscriptMetrics.metaFont().copy(fontSize = 17.sp),
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}
