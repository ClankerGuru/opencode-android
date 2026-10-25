package ai.opencode.feature.drawer.switcher

import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.ui.MarkdownBlocksView
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.LocalDrawerPanExclusion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.statusSnapshot
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Port of iOS `OpenCode/Drawer/SessionSwitcher.swift`: the switcher's tunables, its pure
// geometry (`SessionSwitcherPose`, `SessionSwitcherLayout`, `SessionSwitcherLanding`,
// `SessionSwitcherPrune`), the cards' model, the cold card page, and the one composable Home
// mounts ([SessionSwitcherOverlay], iOS `SessionSwitcherModifier` + `SessionSwitcherActive` +
// `SessionSwitcherHost`). The finger, the cards and the motion clock are
// `SessionSwitcherController`'s.

/**
 * iOS `SessionSwitcherTunables`, at their defaults (Android has no Tuning Panel, so the
 * `looks` presets are n/a). Lengths are iOS points as dp.
 */
object SessionSwitcherTunables {
    /** Swipe settle. */
    val swipeSpring = MotionSpring(0.34, 0.0)
    /** Stack zoom. */
    val zoomSpring = MotionSpring(0.42, 0.0)
    /** Stack scroll settle. */
    val deckSpring = MotionSpring(0.4, 0.0)
    /** Switch commit fraction (of the width). */
    const val threshold = 0.26f
    /** Velocity projection (seconds). */
    const val projection = 0.18f
    /** Bottom gesture height. */
    val gestureHeight: Dp = 72.dp
    /** Swipe card shrink. */
    const val swipeShrink = 0.08f
    /** Swipe shrink travel (fraction of the row stride). */
    const val swipeShrinkTravel = 0.2f
    /** Swipe card gap. */
    val rowGap: Dp = 18.dp
    /** Stack front scale. */
    const val deckScale = 0.6f
    /** Stack centre height (fraction of the height). */
    const val deckCenter = 0.49f
    /** Stack gap to newer. */
    val deckGap: Dp = 20.dp
    /** Stack peek. */
    val peek: Dp = 74.dp
    /** Stack peek falloff. */
    const val falloff = 0.55f
    /** Stack depth scale. */
    const val depthScale = 0.05f
    /** Stack depth dim. */
    const val depthDim = 0.2f
    /** Card corner radius. */
    val cornerRadius: Dp = 55.dp
    /** Card shadow. */
    const val shadow = 0.28f
}

/**
 * Where sessions sit (iOS `SessionSwitcherPose`). One row, newest first, read two ways: side by
 * side at full size for the bottom-edge swipe, and as a stack (front card centred, older ones
 * tucked behind it to the left, newer ones off to the right) for the swipe up. Every pose is a
 * pure function of one progress value, so each card takes one straight path and a grab
 * mid-flight resumes exactly. Coordinates are px.
 */
@Immutable
data class SessionSwitcherPose(
    val center: Offset,
    val scale: Float,
    /** 0 at full screen, 1 as a card: corner radius, border and shadow. */
    val lift: Float = 0f,
    val dim: Float = 0f,
) {
    fun frame(size: Size): Rect = Rect(
        left = center.x - size.width * scale / 2,
        top = center.y - size.height * scale / 2,
        right = center.x + size.width * scale / 2,
        bottom = center.y + size.height * scale / 2,
    )

    companion object {
        fun lerp(a: SessionSwitcherPose, b: SessionSwitcherPose, t: Float) = SessionSwitcherPose(
            center = Offset(a.center.x + (b.center.x - a.center.x) * t, a.center.y + (b.center.y - a.center.y) * t),
            scale = a.scale + (b.scale - a.scale) * t,
            lift = a.lift + (b.lift - a.lift) * t,
            dim = a.dim + (b.dim - a.dim) * t,
        )
    }
}

/** iOS `SessionSwitcherLayout`. [size] and every length are px. */
@Immutable
data class SessionSwitcherLayout(
    val size: Size,
    val deckScale: Float = 0.6f,
    val deckCenter: Float = 0.49f,
    val deckGap: Float = 20f,
    val peek: Float = 74f,
    val falloff: Float = 0.55f,
    val depthScale: Float = 0.05f,
    val depthDim: Float = 0.2f,
    val rowGap: Float = 18f,
    val swipeShrink: Float = 0.08f,
    val swipeShrinkTravel: Float = 0.2f,
) {
    /** Distance between neighbours in the full-size row. */
    val rowStride: Float get() = size.width + rowGap

    /** How far the front card travels per card when the stack scrolls. */
    val deckStride: Float get() = size.width * deckScale + deckGap

    /** Finger travel for the swipe up: the front card's bottom edge follows it 1:1. */
    val zoomReach: Float get() = max(1f, size.height - deck(0f).frame(size).bottom)

    private val full: SessionSwitcherPose get() = SessionSwitcherPose(Offset(size.width / 2, size.height / 2), 1f)

    /**
     * How deep a card behind the front one sits: 0 at the front, 1 for the next, then a
     * geometric series so a long tail piles up instead of spreading.
     */
    private fun depth(position: Float): Float {
        if (position <= 0) return 0f
        val r = min(max(falloff, 0.01f), 0.99f)
        return (1 - r.pow(position)) / (1 - r)
    }

    /** A card [position] places from the stack's focus: positive is older (behind, to the left), negative is newer (on top, to the right). */
    fun deck(position: Float): SessionSwitcherPose {
        val a = depth(position)
        val x = if (position <= 0) size.width / 2 - position * deckStride else size.width / 2 - peek * a
        return SessionSwitcherPose(
            center = Offset(x, size.height * deckCenter),
            scale = deckScale * max(0.2f, 1 - depthScale * a),
            lift = 1f,
            dim = min(0.6f, depthDim * a),
        )
    }

    /** Full screen around [target]: older cards wait exactly behind it, a little smaller so none shows at its edges, and newer ones off to the right. */
    fun full(index: Int, target: Int): SessionSwitcherPose {
        var pose = full
        if (index > target) {
            pose = pose.copy(scale = deck((index - target).toFloat()).scale / deckScale)
        } else if (index < target) {
            pose = pose.copy(center = pose.center.copy(x = pose.center.x + (target - index) * rowStride))
        }
        return pose
    }

    /** Between full screen around [target] (0) and the stack at [focus] (1). */
    fun zoom(index: Int, target: Int, focus: Float, progress: Float): SessionSwitcherPose =
        SessionSwitcherPose.lerp(full(index, target), deck(index - focus), progress)

    /** How much the row has shrunk at a swipe [offset]: 0 at either rest, 1 once the finger has travelled `swipeShrinkTravel` of the way. */
    fun swipeLift(offset: Float): Float {
        val t = min(1f, abs(offset) / rowStride)
        val x = min(1f, min(t, 1 - t) / max(0.01f, swipeShrinkTravel))
        return 1 - (1 - x) * (1 - x)
    }

    /** The current card follows the finger 1:1; its neighbour rides beside it. A positive offset (finger moving right) reveals the older neighbour. */
    fun swipe(offset: Float): Pair<SessionSwitcherPose, SessionSwitcherPose> {
        val lift = swipeLift(offset)
        val scale = 1 - swipeShrink * lift
        val current = full.copy(center = full.center.copy(x = full.center.x + offset), scale = scale, lift = lift)
        val neighbour = current.copy(
            center = current.center.copy(x = current.center.x - (if (offset >= 0) 1 else -1) * (size.width * scale + rowGap)),
        )
        return current to neighbour
    }

    companion object {
        /** iOS `current(size:)`: the tunables at [density] (px per dp). */
        fun current(size: Size, density: Float): SessionSwitcherLayout {
            val t = SessionSwitcherTunables
            return SessionSwitcherLayout(
                size = size, deckScale = t.deckScale, deckCenter = t.deckCenter, deckGap = t.deckGap.value * density,
                peek = t.peek.value * density, falloff = t.falloff, depthScale = t.depthScale, depthDim = t.depthDim,
                rowGap = t.rowGap.value * density, swipeShrink = t.swipeShrink, swipeShrinkTravel = t.swipeShrinkTravel,
            )
        }

        /** iOS-style resistance past an end: approaches [limit] but never reaches it. */
        fun rubberBand(overshoot: Float, limit: Float): Float {
            val c = 0.55f
            val x = abs(overshoot)
            return (if (overshoot < 0) -1 else 1) * (x * limit * c) / (limit + c * x)
        }
    }
}

/**
 * A card that reached full screen waits for the live screen to show its session (iOS
 * `SessionSwitcherLanding`). Home ignores a session deleted meanwhile, so the wait ends when
 * the session leaves the list or the deadline passes. Times in seconds on one monotonic clock.
 */
data class SessionSwitcherLanding(val id: String, val deadline: Double) {
    enum class Outcome { waiting, arrived, abandoned }

    fun outcome(selectedID: String?, listed: Boolean, now: Double): Outcome {
        if (selectedID == id) return Outcome.arrived
        return if (!listed || now >= deadline) Outcome.abandoned else Outcome.waiting
    }

    companion object {
        const val TIMEOUT = 1.5

        operator fun invoke(id: String, now: Double) = SessionSwitcherLanding(id, now + TIMEOUT)
    }
}

/**
 * The row once sessions are deleted under it (iOS `SessionSwitcherPrune`): which cards stay and
 * where they move. The unlisted current screen (a draft, null) always stays.
 */
class SessionSwitcherPrune(ids: List<String?>, listed: Set<String>) {
    val removed: List<Int> = ids.indices.filter { i -> ids[i]?.let { it !in listed } ?: false }
    val remaining: Int = ids.size - removed.size

    val isEmpty: Boolean get() = removed.isEmpty()

    fun index(index: Int): Int? = if (index in removed) null else index - removed.count { it < index }

    /** The stack keeps its card in front, or the next older one if it was deleted. */
    fun focus(focus: Float): Float {
        val rounded = Math.round(focus)
        val shifted = focus - removed.count { it < rounded }
        return min(max(0f, shifted), max(0, remaining - 1).toFloat())
    }
}

/** One session as the switcher shows it (iOS `SessionSwitcherCard`). */
@Immutable
data class SessionSwitcherCard(
    val id: String,
    val title: String,
    val project: String,
    val indicator: HomeFeature.State.Indicator,
    val server: OpenCodeServer,
    val directory: String,
) {
    val status: String
        get() = when (indicator) {
            HomeFeature.State.Indicator.attention -> "Needs your input"
            HomeFeature.State.Indicator.running -> "Working"
            HomeFeature.State.Indicator.unread -> "Unread"
            HomeFeature.State.Indicator.none -> ""
        }
}

/**
 * What the controller reads from Home (iOS `SessionSwitcherHost`'s stored properties).
 *
 * @property transcript the last few transcript items of a session, read only when its card is drawn.
 */
class SessionSwitcherHost(
    val cards: List<SessionSwitcherCard>,
    val selectedID: String?,
    val serverID: String,
    val isPresented: Boolean,
    val available: Boolean,
    val reducedMotion: Boolean,
    val transcript: (String) -> List<OpenCodeTranscriptItem>,
    val send: (SessionSwitcherFeature.Action) -> Unit,
) {
    companion object {
        val inert = SessionSwitcherHost(emptyList(), null, "", isPresented = false, available = false, reducedMotion = false, transcript = { emptyList() }, send = {})
    }
}

/**
 * The session switcher (Experimental › Session Switcher), mounted by Home around the session
 * screen (iOS `.modifier(SessionSwitcherModifier(...))` on Home's content). Swiping along the
 * bottom 72 dp (clear of the 28 dp edges) slides to the neighbouring session; swiping up gathers
 * the sessions into a stack to pick from. Off, it adds nothing but reading the flag, and the
 * content keeps its place in the tree either way (turning it on never rebuilds the session).
 *
 * Mount it inside the container's `LocalDrawerPanExclusion` provider (inside the card), so its
 * bottom strip is kept from the drawer's pan as iOS makes the drawer's pan wait for it.
 *
 * @param drawerOpen the drawer is open or moving (the container's live state, iOS `drawerOpen`).
 * @param usesSidebar a wide window lists sessions in its sidebar; the switcher stays off there.
 * @param content the live session screen.
 */
@Composable
fun SessionSwitcherOverlay(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    drawerOpen: Boolean,
    usesSidebar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val enabledStored by remember { shared(ExperimentalFeature.sessionSwitcher.key).flow }.collectAsState()
    val enabled = ExperimentalFeature.sessionSwitcher.isActive(enabledStored)
    val scope = rememberCoroutineScope()
    val controller = remember { SessionSwitcherController(scope) }
    val liveLayer = rememberGraphicsLayer()
    val parentExclusion = LocalDrawerPanExclusion.current
    val exclusion = remember(parentExclusion) { SessionSwitcherExclusion(parentExclusion) }
    controller.liveLayer = liveLayer
    controller.exclusion = exclusion
    val colors = OpenCodeTheme.colors
    val backdropColor = if (colors.isDark) Color(0xFF1A1A1A) else Color(0xFFDBDBDB)

    Box(
        modifier
            .onGloballyPositioned { controller.rootCoordinates = it }
            .drawBehind {
                // iOS `backdrop`: under the live screen, white 0.1 dark / 0.86 light.
                if (!controller.backdropHidden) drawRect(backdropColor, alpha = controller.backdropAlpha)
            }
            .sessionSwitcherGestures(controller),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (controller.liveHidden) 0f else 1f }
                .drawWithContent {
                    liveLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(liveLayer)
                }
                .then(
                    if (enabled) {
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Show Sessions") {
                                    store.send(HomeFeature.Action.SessionSwitcher(SessionSwitcherFeature.Action.PresentationChanged(true)))
                                    true
                                },
                            )
                            if (controller.liveHidden) hideFromAccessibility()
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            CompositionLocalProvider(LocalDrawerPanExclusion provides exclusion) { content() }
        }
        if (enabled) {
            SessionSwitcherActive(store, controller, drawerOpen, usesSidebar)
        } else {
            androidx.compose.runtime.SideEffect { controller.disable() }
        }
    }
}

/** iOS `SessionSwitcherActive` + `SessionSwitcherHost`: reads Home and hands the controller its model. */
@Composable
private fun SessionSwitcherActive(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    controller: SessionSwitcherController,
    drawerOpen: Boolean,
    usesSidebar: Boolean,
) {
    val reducedMotion = rememberReduceMotion()
    val cards by store.observe { switcherCards(it) }
    val selectedID by store.observe { it.sessionDetail?.session?.id }
    val serverID by store.observe { it.server.persistenceID }
    val isPresented by store.observe { it.sessionSwitcher.isPresented }
    val homeAllows by store.observe {
        !it.isDrawerOpen && it.catchUp == null && it.subagentStack.isEmpty() && !it.isSettingsShown &&
            it.sessionDetail?.browser?.isOpen != true
    }
    val available = !usesSidebar && !drawerOpen && homeAllows
    controller.frameDuration = rememberFrameDurationSeconds()
    val host = SessionSwitcherHost(
        cards = cards,
        selectedID = selectedID,
        serverID = serverID,
        isPresented = isPresented,
        available = available,
        reducedMotion = reducedMotion,
        transcript = { id ->
            // Read when a card is drawn (idle, outside composition), so streaming never recomposes Home.
            val home = store.state
            val items = if (home.sessionDetail?.session?.id == id) home.sessionDetail.items.orEmpty() else home.transcriptCache[id].orEmpty()
            items.takeLast(12)
        },
        send = { store.send(HomeFeature.Action.SessionSwitcher(it)) },
    )
    androidx.compose.runtime.SideEffect { controller.update(host) }
    SessionSwitcherStage(controller)
}

/** iOS `SessionSwitcherActive.cards`: listed root sessions, newest first (ties by ID), at most 24. */
internal fun switcherCards(home: HomeFeature.State): List<SessionSwitcherCard> {
    val status = home.statusSnapshot()
    return home.sessions.asSequence()
        .filter { it.parentID == null }
        .sortedWith { a, b -> if (a.updatedAt == b.updatedAt) a.id.compareTo(b.id) else b.updatedAt.compareTo(a.updatedAt) }
        .take(24)
        .map { session ->
            SessionSwitcherCard(
                id = session.id,
                title = session.title,
                project = listProject(session, home.projects)?.name.orEmpty(),
                indicator = status.indicator(session),
                server = home.server,
                directory = session.directory,
            )
        }
        .toList()
}

/** iOS `SessionListContext.project(for:in:)`: the session's project, unless it has none (`global`). */
private fun listProject(session: OpenCodeSession, projects: List<OpenCodeProject>): OpenCodeProject? {
    if (session.projectID == "global") return null
    return projects.firstOrNull { it.id == session.projectID }
}

/**
 * A cold card (iOS `SessionSwitcherCachedPage`): drawn once from the existing transcript cache,
 * never a live session screen and never any I/O. It keeps the session screen's layout (header,
 * transcript, composer) so a card that becomes the live screen lands without a layout jump.
 * Replies render as the transcript's Markdown ([MarkdownBlocksView]), as history.
 */
@Composable
internal fun SessionSwitcherCachedPage(card: SessionSwitcherCard, items: List<OpenCodeTranscriptItem>, topInset: Dp, bottomInset: Dp) {
    val colors = OpenCodeTheme.colors
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = max(topInset.value, 20f).dp + 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                OpenCodeIcon(SFSymbol.Line3Horizontal, contentDescription = null, tint = colors.text)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .height(44.dp)
                        .background(fill, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(card.title, style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (card.project.isNotEmpty()) {
                        Text(card.project, style = OpenCodeTextStyles.caption2, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.size(44.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                OpenCodeIcon(SFSymbol.Ellipsis, contentDescription = null, tint = colors.text)
            }
        }
        // iOS `ViewThatFits`: the transcript from the top when it fits, else its end at the bottom under a top fade.
        TranscriptFit(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                for (item in items) {
                    when (item) {
                        is OpenCodeTranscriptItem.UserText -> Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f).widthIn(min = 48.dp))
                            Text(
                                item.text,
                                style = OpenCodeTextStyles.body,
                                color = colors.text,
                                modifier = Modifier
                                    .background(fill, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                        is OpenCodeTranscriptItem.AssistantMarkdown -> {
                            val blocks = remember(item.text) { OpenCodeMarkdownBlock.blocks(item.text) }
                            MarkdownBlocksView(blocks, rowID = item.id, revealItem = null)
                        }
                        is OpenCodeTranscriptItem.Info -> Text(item.text, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                        is OpenCodeTranscriptItem.Notice -> Text(item.text, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                        else -> Unit
                    }
                }
            }
        }
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = max(bottomInset.value, 16f).dp)
                .fillMaxWidth()
                .height(52.dp)
                .background(fill, RoundedCornerShape(50))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpenCodeIcon(SFSymbol.Plus, contentDescription = null, tint = colors.text)
            Text("Message", style = OpenCodeTextStyles.body, color = colors.tertiaryText, modifier = Modifier.weight(1f))
            OpenCodeIcon(SFSymbol.Mic, contentDescription = null, tint = colors.text)
        }
    }
}

/** Places [content] at the top when it fits, else bottom-aligned with an 8 % top fade (iOS `ViewThatFits` + mask). */
@Composable
private fun TranscriptFit(modifier: Modifier, content: @Composable () -> Unit) {
    var overflows = false
    Layout(
        content = content,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflows) {
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, startY = 0f, endY = size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val height = constraints.maxHeight
        overflows = placeable.height > height
        layout(constraints.maxWidth, height) {
            placeable.place(0, if (overflows) height - placeable.height else 0)
        }
    }
}
