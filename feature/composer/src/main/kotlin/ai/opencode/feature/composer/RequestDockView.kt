package ai.opencode.feature.composer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** The panel's measures (iOS `RequestDockView` statics). */
object RequestDockMetrics {
    /** What a squeezed part still shows, at least: two lines of a question. */
    val questionFloor: Dp = 44.dp
    /** About one choice, or three lines of a command. */
    val scrollFloor: Dp = 64.dp
    /** One edge for the caption, the question, the marks, and the bands. */
    val edge: Dp = 20.dp
    /** The composer's glass, which the panel shares. */
    val cornerRadius: Dp = 28.dp
    /** × is a circle centred on the glass's top-trailing corner arc, so its edge runs concentric with the glass's corner. */
    val closeSize: Dp = 34.dp
    /** The header's row is centred on that arc too. */
    val headerTop: Dp = cornerRadius - 22.dp
    /** How far a request's content rises out of the bar as it arrives. */
    val rise: Dp = 28.dp
    /** The blur a request's content sharpens in from and blurs away to (iOS `.blur(radius: 8)`). */
    val revealBlur: Dp = 8.dp
    /** Caps scaled down with small text, never up (OC-471): larger text scrolls inside them. */
    val questionCap: Dp = 220.dp
    val rowsCap: Dp = 300.dp
    val commandCap: Dp = 300.dp
    /** The actions stop growing at the first accessibility size (iOS `...accessibility1`). */
    const val actionsMaxFontScale = 1.65f
}

/**
 * The panel's content motion (iOS `RequestPanelReveal.content`): it rises and sharpens in as
 * the glass grows, and sinks and fades away ahead of the glass folding over it. Springs, so a
 * request arriving or leaving mid-flight retargets from where it is. The glass itself is the
 * session's ([RequestPanelReveal.glass]).
 */
object RequestPanelReveal {
    fun <T> content(showing: Boolean, reducedMotion: Boolean): FiniteAnimationSpec<T> = when {
        reducedMotion -> Motion.easeInOut(Motion.reducedFade)
        showing -> MotionSpring.fromResponse(0.42, 0.86).spec()
        else -> MotionSpring.fromResponse(0.22, 1.0).spec()
    }

    /** Unbouncy, and quicker to fold than to grow; folding waits a beat for the content to fade. */
    fun <T> glass(opening: Boolean): FiniteAnimationSpec<T> =
        if (opening) MotionSpring.fromResponse(0.42, 1.0).spec()
        else MotionSpring.fromResponse(0.32, 1.0).animation(reduceMotion = false, delaySeconds = 0.08)
}

/**
 * What the dock reads from the session beyond its own state (iOS reads these from
 * `StoreOf<SessionFeature>`).
 */
data class RequestDockContext(
    val currentSessionID: String? = null,
    /** A draft that just became this session keeps what's shown (iOS `draftBecameSessionID`). */
    val draftBecameSessionID: String? = null,
    val familySessions: List<OpenCodeSession> = emptyList(),
    /** The session's items followed by its family transcript cache, to find the tool call that asks. */
    val transcripts: List<List<OpenCodeTranscriptItem>> = emptyList(),
    /** The live screen, never an inert Catch Up preview. */
    val isVisible: Boolean = true,
    /** Something is being dictated (`hasLiveDictation || dictationHold != nil`): a pick never auto-advances then. */
    val isDictating: Boolean = false,
)

/**
 * A pending request's panel, grown up out of the composer on its surface (D-31 B; iOS
 * `RequestDockView`). The composer's own row below it is your answer: typing or dictating
 * there answers, and its Send sends ([RequestBar]). The panel holds the header (with × for
 * questions), the question, which scrolls on its own, and the choices, which stay in reach;
 * or a permission's action, a one- or two-line summary that expands to the whole command or
 * diff, and Allow Once beside Always Allow (R-177, [RequestTunables]). Deny sits in the bar
 * beside “Tell it what to do instead”, so a reason costs no step.
 *
 * Only the content: the session draws the surface, grows it (animating its size), and caps
 * it with [requestPanelCap].
 *
 * @param isKeyboardUp the bar has the keyboard: the question and choices take less height.
 */
@Composable
fun RequestDockView(
    dock: RequestDockFeature.State<*>,
    context: RequestDockContext,
    isKeyboardUp: Boolean,
    send: (RequestDockFeature.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReduceMotion()
    val live = remember(dock, context.transcripts) { liveShown(dock, context) }
    var lastShown by remember { mutableStateOf<Shown?>(null) }
    var arrivedID by remember { mutableStateOf<String?>(null) }
    var previousSessionID by remember { mutableStateOf(context.currentSessionID) }
    val state = remember { DockViewState() }
    val pickScope = rememberCoroutineScope()
    val latestDock by rememberUpdatedState(dock)
    val latestDictating by rememberUpdatedState(context.isDictating)

    // A new session: what's shown is the new one's, without a reveal.
    if (previousSessionID != context.currentSessionID) {
        val old = previousSessionID
        SideEffect {
            previousSessionID = context.currentSessionID
            if (!(old == null && context.currentSessionID != null && context.currentSessionID == context.draftBecameSessionID)) {
                lastShown = live
                arrivedID = live?.id
            }
        }
    }
    SideEffect { if (live != null && live != lastShown) lastShown = live }
    // Mounted hidden for its first frame, so it rises in; cleared when it leaves, so the same request docking again rises again.
    LaunchedEffect(live?.id) { arrivedID = live?.id }
    LaunchedEffect(live == null) {
        if (live != null || lastShown == null) return@LaunchedEffect
        delay(500)
        lastShown = null
    }
    // Each request starts folded, with nothing sent.
    val focus = remember { FocusRequester() }
    val androidContext = LocalContext.current
    val accessibility = remember(androidContext) { androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    val screenReaderOn = { accessibility?.isTouchExplorationEnabled == true }
    LaunchedEffect(dock.dockRequestID) {
        state.sentAction = null
        state.expandedPermissionID = null
        state.scopeShownForID = null
        if (dock.dockRequestID == null || !context.isVisible || !screenReaderOn()) return@LaunchedEffect
        delay(400)
        runCatching { focus.requestFocus() }
    }
    val idle = dock.respondingRequestIDs.isEmpty()
    LaunchedEffect(idle) {
        val id = dock.dockRequestID
        if (idle && id != null && id in dock.failedReplyIDs) state.sentAction = null
    }

    val shown = live ?: lastShown ?: return
    key(shown.id) {
        val showing = live != null && arrivedID == shown.id
        val alpha by animateFloatAsState(if (showing) 1f else 0f, RequestPanelReveal.content(showing, reducedMotion), label = "requestAlpha")
        val riseTarget = when {
            reducedMotion || showing -> 0f
            live == null -> RequestDockMetrics.rise.value / 2
            else -> RequestDockMetrics.rise.value
        }
        val rise by animateFloatAsState(riseTarget, RequestPanelReveal.content(showing, reducedMotion), label = "requestRise")
        // Arriving it sharpens in as the surface grows; leaving it blurs away in place just
        // ahead of the surface folding over it (one motion). Reduce Motion only fades.
        val blurTarget = if (reducedMotion || showing) 0f else RequestDockMetrics.revealBlur.value
        val blur by animateFloatAsState(blurTarget, RequestPanelReveal.content(showing, reducedMotion), label = "requestBlur")
        val leaving = live == null
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = RequestDockMetrics.edge)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = rise.dp.toPx()
                    val radius = blur.dp.toPx()
                    renderEffect = if (radius > 0.01f) BlurEffect(radius, radius, TileMode.Decal) else null
                }
                .testTag(if (leaving) "request-dock-leaving" else "request-dock")
                .semantics {
                    if (leaving) hideFromAccessibility()
                    // TalkBack's escape: dismiss at once, as × does (Deny, for a permission).
                    customActions = listOf(CustomAccessibilityAction("Dismiss") { send(RequestDockFeature.Action.DismissTapped); true })
                },
        ) {
            val scope = DockScope(
                dock, context, isKeyboardUp, send, state, focus, reducedMotion,
                enabled = !leaving, pickScope = pickScope, latest = { latestDock to latestDictating },
                screenReaderOn = screenReaderOn,
            )
            scope.Content(shown)
        }
    }
}

/** What the panel shows: the docked request, its answers and page. */
private data class Shown(
    val id: String,
    val permission: OpenCodePermissionRequest? = null,
    val form: OpenCodeFormRequest? = null,
    val answers: Map<String, OpenCodeFormValue> = emptyMap(),
    val page: Int = 0,
    val call: OpenCodeToolCall? = null,
)

private fun liveShown(dock: RequestDockFeature.State<*>, context: RequestDockContext): Shown? {
    if (!dock.isAnswering) return null
    val id = dock.dockRequestID ?: return null
    dock.permissionRequests.firstOrNull { it.id == id }?.let {
        return Shown(id, permission = it, call = RequestDockAnswer.toolCall(it, context.transcripts))
    }
    dock.formRequests.firstOrNull { it.id == id }?.let {
        return Shown(id, form = it, answers = dock.formAnswers[id] ?: emptyMap(), page = dock.questionPageIndices[id] ?: 0)
    }
    return null
}

private enum class DockAction { Once, Always }

/** A question page as it was drawn: what [DockScope.LeavingPage] keeps on screen as it leaves. */
private data class PageSnapshot(
    val key: String,
    val field: OpenCodeFormField?,
    val answers: Map<String, OpenCodeFormValue>,
    val autoAdvance: Int?,
)

/** Measured and drawn at its own height, but takes none in its parent: an overlay over what follows. */
private fun Modifier.overlayWithoutHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, 0) { placeable.place(0, 0) }
}

/** The view's own per-request state (iOS `@State`). */
private class DockViewState {
    /** The action last sent, so only its button shows progress. */
    var sentAction by mutableStateOf<DockAction?>(null)
    /** The permission whose full command or diff is shown (R-177: folded by default). */
    var expandedPermissionID by mutableStateOf<String?>(null)
    /** The permission whose Always Allow scope is showing, to confirm. */
    var scopeShownForID by mutableStateOf<String?>(null)
}

private class DockScope(
    val dock: RequestDockFeature.State<*>,
    val context: RequestDockContext,
    val isKeyboardUp: Boolean,
    val send: (RequestDockFeature.Action) -> Unit,
    val state: DockViewState,
    val focus: FocusRequester,
    val reducedMotion: Boolean,
    /** Leaving content takes no touches. */
    val enabled: Boolean,
    /** Where a pick's auto-advance waits, and the dock as it is when it wakes. */
    val pickScope: CoroutineScope,
    val latest: () -> Pair<RequestDockFeature.State<*>, Boolean>,
    /** TalkBack is on (iOS `UIAccessibility.isVoiceOverRunning`). */
    val screenReaderOn: () -> Boolean,
) {
    fun responding(id: String) = id in dock.respondingRequestIDs

    @Composable
    fun Content(shown: Shown) {
        val permission = shown.permission
        val form = shown.form
        when {
            permission != null -> PermissionPanel(permission, shown.call)
            form != null -> if (form.fitsDock) FormPanel(form, shown.answers, shown.page) else SheetPanel(form)
        }
    }

    // Header

    @Composable
    fun Header(
        caption: AnnotatedString,
        spoken: String,
        closes: Boolean,
        progress: Pair<Int, Int>? = null,
        onBack: (() -> Unit)? = null,
        busy: Boolean = false,
    ) {
        val colors = OpenCodeTheme.colors
        val idle = dock.respondingRequestIDs.isEmpty()
        // Top-aligned, each item centred in its own 48 dp, so × stays on the corner arc however tall larger text makes the row.
        Row(
            Modifier.fillMaxWidth().padding(top = RequestDockMetrics.headerTop).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (onBack != null) {
                Box(
                    Modifier
                        .outset(start = 14.dp, end = 8.dp)
                        .size(48.dp)
                        // The panel's `.disabled(responding)` covers Back too.
                        .clickable(enabled = enabled && !busy, onClickLabel = "Back", role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = "Back" }
                        .testTag("request-back"),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.ChevronLeft, contentDescription = null, tint = colors.text)
                }
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    caption,
                    style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusable()
                        .semantics {
                            contentDescription = "Needs your input, $spoken"
                            heading()
                        },
                )
            }
            if (progress != null && progress.second > 1) {
                val (page, count) = progress
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    RollingNumber(
                        text = "${page + 1} of $count",
                        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                        color = colors.secondaryText,
                        alignment = RollingNumberAlignment.End,
                        accessibilityLabel = "Question ${page + 1} of $count",
                    )
                }
            }
            if (dock.reviewRequestIDs.size > 1) QueueMenu()
            if (closes) {
                // Its centre on the corner arc's: `cornerRadius` in from the edge.
                Box(
                    Modifier
                        .outset(end = RequestDockMetrics.edge - (RequestDockMetrics.cornerRadius - 22.dp))
                        .size(48.dp)
                        .clickable(enabled = enabled && idle && !busy, onClickLabel = "Dismiss", role = Role.Button) {
                            send(RequestDockFeature.Action.DismissTapped)
                        }
                        .semantics { contentDescription = "Dismiss" }
                        .testTag("request-dismiss"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(RequestDockMetrics.closeSize)
                            .clip(OpenCodeShape.circle)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = 16.dp, tint = colors.secondaryText)
                    }
                }
            }
        }
    }

    /** A subagent's request names it; a global or MCP form names its server when present. */
    fun captionText(caption: String, sessionID: String, mcpServer: String? = null): String =
        RequestDockAnswer.caption(caption, sessionID, mcpServer, context.currentSessionID, context.familySessions)

    @Composable
    fun QueueMenu() {
        val colors = OpenCodeTheme.colors
        var open by remember { mutableStateOf(false) }
        val count = dock.reviewRequestIDs.size
        Box {
            Row(
                Modifier
                    .widthIn(min = 48.dp)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled && dock.respondingRequestIDs.isEmpty(), role = Role.Button) { open = true }
                    .semantics {
                        contentDescription = "Pending requests"
                        stateDescription = "$count"
                    }
                    .testTag("request-queue"),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(SFSymbol.TrayFull, contentDescription = null, size = 18.dp, tint = colors.secondaryText)
                Text("$count", style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (form in dock.formRequests) {
                    val title = if (form.isQuestion) form.fields.firstOrNull()?.title?.ifEmpty { null } ?: "Question" else form.title
                    DropdownMenuItem(text = { Text(title) }, onClick = {
                        open = false
                        send(RequestDockFeature.Action.ReviewRequestSelected(form.id))
                    })
                }
                for (permission in dock.permissionRequests) {
                    DropdownMenuItem(text = { Text(OpenCodeToolCall.displayName(permission.action)) }, onClick = {
                        open = false
                        send(RequestDockFeature.Action.ReviewRequestSelected(permission.id))
                    })
                }
            }
        }
    }

    // Questions and small forms

    @Composable
    fun FormPanel(form: OpenCodeFormRequest, answers: Map<String, OpenCodeFormValue>, storedPage: Int) {
        val fields = form.activeFields(answers)
        val pageCount = if (form.isQuestion) fields.size else 1
        val page = min(storedPage, max(pageCount - 1, 0))
        val field = fields.getOrNull(page)
        val caption = captionText(
            if (form.isQuestion) field?.title?.ifEmpty { null } ?: "Question" else form.title,
            form.sessionID,
            form.mcpServer,
        )
        val pageKey = "${form.id}/$page"
        val firstPage = remember { pageKey }
        val autoAdvance = if (form.isQuestion && page < pageCount - 1) page + 1 else null
        // The page on screen until the last composition, so a new page can slide the old one out.
        var lastPage by remember { mutableStateOf<PageSnapshot?>(null) }
        val leavingPage = remember(pageKey) { lastPage?.takeIf { it.key != pageKey } }
        val current = PageSnapshot(pageKey, field, answers, autoAdvance)
        SideEffect { lastPage = current }
        // iOS posts `.screenChanged` with each page: TalkBack starts again at the caption.
        LaunchedEffect(pageKey) {
            if (pageKey != firstPage && enabled && screenReaderOn()) runCatching { focus.requestFocus() }
        }
        RequestPanelFallback(Modifier.padding(bottom = 6.dp)) {
            Header(
                AnnotatedString(caption), caption, closes = true,
                progress = page to pageCount,
                onBack = if (page > 0) ({ changePage(form.id, page - 1) }) else null,
                busy = responding(form.id),
            )
            // The old page fades out sliding the other way, drawn over the new one (it takes no
            // room in the stack, so the panel measures the new page alone).
            if (leavingPage != null) {
                key("leaving/${leavingPage.key}/$pageKey") { LeavingPage(form, leavingPage) }
            }
            // Each page measures afresh and slides in from the side it comes from.
            key(pageKey) {
                val entrance = rememberEntrance(animate = pageKey != firstPage, slide = true)
                if (field != null) {
                    FieldView(field, form, answers, autoAdvance, entrance)
                }
            }
            ErrorLines(form.id, isForm = true, inline = field?.let { inlineValidation(it, answers) })
        }
    }

    fun changePage(formID: String, page: Int) {
        send(RequestDockFeature.Action.QuestionPageChanged(formID, page))
    }

    /**
     * iOS `pageTransition`'s removal (`.opacity` + `.offset(x: -24)`; opacity only under Reduce
     * Motion): the page just left, inert and hidden from TalkBack, overlaid where the new page
     * starts until it has faded.
     */
    @Composable
    fun LeavingPage(form: OpenCodeFormRequest, snapshot: PageSnapshot) {
        val field = snapshot.field ?: return
        val progress = remember { Animatable(0f) }
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            progress.animateTo(1f, MotionSpring(0.3).animation(reducedMotion))
            done = true
        }
        if (done) return
        val offset = if (reducedMotion) 0.dp else (-24).dp
        Column(
            Modifier
                .fillMaxWidth()
                .overlayWithoutHeight()
                .graphicsLayer {
                    alpha = 1f - progress.value
                    translationX = progress.value * offset.toPx()
                }
                .clearAndSetSemantics {},
        ) {
            FieldView(field, form, snapshot.answers, snapshot.autoAdvance, Modifier, interactive = false)
        }
    }

    @Composable
    fun FieldView(
        field: OpenCodeFormField,
        form: OpenCodeFormRequest,
        answers: Map<String, OpenCodeFormValue>,
        autoAdvance: Int?,
        entrance: Modifier,
        interactive: Boolean = true,
    ) {
        val colors = OpenCodeTheme.colors
        val options = RequestDockAnswer.options(field)
        val multiple = field.type == OpenCodeFormFieldType.Multiselect
        val fontScale = LocalDensity.current.fontScale
        val scale = min(fontScale, 1f)
        val formEnabled = interactive && enabled && !responding(form.id)
        // A long question scrolls on its own; the choices stay in reach.
        FadingScroll(
            cap = RequestDockMetrics.questionCap * scale * (if (isKeyboardUp) 0.5f else 1f),
            floor = RequestDockMetrics.questionFloor,
            priority = RequestPanelFit.Priority.question,
            identifier = "request-question",
            modifier = entrance,
        ) {
            Text(
                RequestDockAnswer.prompt(field, form),
                style = OpenCodeTextStyles.body,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (options.isEmpty()) 10.dp else 4.dp)
                    .padding(horizontal = RequestDockMetrics.edge)
                    .semantics { heading() },
            )
        }
        if (options.isNotEmpty()) {
            FadingScroll(
                cap = RequestDockMetrics.rowsCap * scale * (if (isKeyboardUp) 0.5f else 1f),
                floor = RequestDockMetrics.scrollFloor,
                priority = RequestPanelFit.Priority.choices,
                identifier = "request-options",
                modifier = entrance,
            ) {
                Column(Modifier.padding(horizontal = RequestDockMetrics.edge)) {
                    options.forEachIndexed { index, option ->
                        if (index > 0) RequestOptionRowDivider()
                        RequestOptionRow(
                            title = option.label,
                            description = option.description,
                            selected = RequestDockAnswer.isSelected(option.value, answers[field.key]),
                            multiple = multiple,
                            enabled = formEnabled,
                            modifier = Modifier.testTag("request-option-$index"),
                        ) { pick(option.value, field, form, autoAdvance) }
                    }
                }
            }
            if (multiple) {
                Text(
                    "Choose one or more",
                    style = OpenCodeTextStyles.footnote,
                    color = colors.secondaryText,
                    modifier = entrance.padding(bottom = 4.dp),
                )
            }
        }
    }

    /** A typed answer's problem, shown under the panel as you type. */
    fun inlineValidation(field: OpenCodeFormField, answers: Map<String, OpenCodeFormValue>): String? {
        if (!RequestDockAnswer.hasAnswer(answers[field.key])) return null
        return field.validationError(answers[field.key])
    }

    fun pick(value: String, field: OpenCodeFormField, form: OpenCodeFormRequest, autoAdvance: Int?) {
        val current = dock.formAnswers[form.id]?.get(field.key)
        val next = RequestDockAnswer.pickedValue(value, field, current)
        if (next != current) dependency(HapticsClient).play(Haptic.selection)
        send(RequestDockFeature.Action.FormValueChanged(form.id, field.key, next))
        // A single choice on an earlier page moves on, as the desktop's does.
        if (field.type == OpenCodeFormFieldType.Multiselect || autoAdvance == null) return
        pickScope.launch {
            delay(if (reducedMotion) 120 else 280)
            val (latestDock, dictating) = latest()
            // Only if the pick still stands and nothing is being dictated.
            if (latestDock.dockRequestID != form.id || (latestDock.questionPageIndices[form.id] ?: 0) != autoAdvance - 1) return@launch
            if (form.id in latestDock.respondingRequestIDs || dictating) return@launch
            if (!RequestDockAnswer.isSelected(value, latestDock.formAnswers[form.id]?.get(field.key))) return@launch
            changePage(form.id, autoAdvance)
        }
    }

    /** A form too large for the panel opens the request sheet. */
    @Composable
    fun SheetPanel(form: OpenCodeFormRequest) {
        val colors = OpenCodeTheme.colors
        val caption = captionText("", form.sessionID, form.mcpServer)
        Column(Modifier.fillMaxWidth()) {
            Header(AnnotatedString(caption.ifEmpty { "Form" }), spoken = caption.ifEmpty { form.title }, closes = true, busy = responding(form.id))
            Text(form.title, style = OpenCodeTextStyles.body, color = colors.text, modifier = Modifier.fillMaxWidth())
            ErrorLines(form.id, isForm = true)
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.CenterEnd) {
                RequestMonochromeButton(
                    onClick = { send(RequestDockFeature.Action.SheetTapped(form.id)) },
                    enabled = enabled && !responding(form.id),
                    modifier = Modifier.testTag("request-review"),
                ) { Text("Review", maxLines = 1) }
            }
        }
    }

    // Permissions

    @Composable
    fun PermissionPanel(permission: OpenCodePermissionRequest, call: OpenCodeToolCall?) {
        val colors = OpenCodeTheme.colors
        val name = OpenCodeToolCall.displayName(permission.action)
        // What it does, in the tool call's own words; an edit names its file.
        val description = call?.input?.obj?.get("description")?.string?.trim()?.ifEmpty { null }
            ?: permission.filePreview?.let { lastPathComponent(it.first) }?.ifEmpty { null }
        val owner = captionText(name, permission.sessionID)
        val caption = buildAnnotatedString {
            append(owner)
            if (description != null) {
                append(" · ")
                withStyle(SpanStyle(color = colors.text)) { append(description) }
            }
        }
        val spoken = description?.let { "$owner, $it" } ?: owner
        val command = call?.input?.obj?.get("command")?.string?.ifEmpty { null }
        val expanded = state.expandedPermissionID == permission.id
        val fontScale = LocalDensity.current.fontScale
        RequestPanelFallback(
            footer = {
                // Allow stays in reach: past the floors the rest scrolls above it. The actions stop growing at the first accessibility size.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, RequestDockMetrics.actionsMaxFontScale))) {
                    Box(Modifier.padding(vertical = 10.dp)) { PermissionButtons(permission) }
                }
            },
        ) {
            Header(caption, spoken, closes = false)
            SummaryRow(permission, command, expanded)
            if (expanded) {
                val entrance = rememberEntrance(animate = true, slide = false)
                FadingScroll(
                    cap = RequestDockMetrics.commandCap * min(fontScale, 1f) * (if (isKeyboardUp) 0.6f else 1f),
                    floor = RequestDockMetrics.scrollFloor,
                    priority = RequestPanelFit.Priority.command,
                    identifier = "request-command",
                    modifier = entrance,
                ) {
                    PermissionPreview(permission, command)
                }
            }
            ErrorLines(permission.id, isForm = false)
        }
    }

    /** What's asked, in a line or two; tapping it shows the whole command or diff. */
    @Composable
    fun SummaryRow(permission: OpenCodePermissionRequest, command: String?, expanded: Boolean) {
        val colors = OpenCodeTheme.colors
        val summary = remember(permission, command) { PermissionSummary(permission, command) }
        val turn by animateFloatAsState(if (expanded) 180f else 0f, MotionSpring(0.3).animation(reducedMotion), label = "summaryChevron")
        val textStyle = if (summary.isCode) RequestTranscriptMetrics.toolFont else OpenCodeTextStyles.subheadline
        val firstLine = with(LocalDensity.current) { if (textStyle.lineHeight.isSp) textStyle.lineHeight.toDp() else 0.dp }
        // Centred in its 48 dp while one line; the chevron stays on the first line's baseline as it wraps.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button) {
                    state.expandedPermissionID = if (expanded) null else permission.id
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = summary.text
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    onClick(label = if (expanded) "Hide the details" else "Show the full ${if (summary.isCode) "command" else "details"}") {
                        state.expandedPermissionID = if (expanded) null else permission.id
                        true
                    }
                }
                .padding(vertical = 6.dp)
                .testTag("request-summary"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    summary.text,
                    style = textStyle,
                    color = colors.text,
                    maxLines = if (expanded) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.heightIn(min = firstLine), contentAlignment = Alignment.Center) {
                    // `.caption.weight(.semibold)`.
                    OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = 12.dp, tint = colors.secondaryText, modifier = Modifier.rotate(turn))
                }
            }
        }
    }

    @Composable
    fun PermissionButtons(permission: OpenCodePermissionRequest) {
        val busy = responding(permission.id)
        val actionsEnabled = enabled && !busy
        val save = permission.save?.takeIf { it.isNotEmpty() }
        // Side by side, one size, while the titles fit; stacked at larger sizes.
        SideBySideOrStacked(spacing = 8.dp) {
            RequestPermissionButton(
                primary = false,
                enabled = actionsEnabled,
                onClick = {
                    state.sentAction = DockAction.Once
                    send(RequestDockFeature.Action.PermissionReplyTapped(permission.id, OpenCodePermissionReply.Once))
                },
                modifier = Modifier.testTag("request-allow-once"),
            ) { ButtonLabel("Allow Once", sending = busy && state.sentAction == DockAction.Once) }
            if (save != null) {
                val scope = save.joinToString(", ")
                Box {
                    // A tap shows what it covers; the popover's Always Allow gives it.
                    RequestPermissionButton(
                        primary = true,
                        enabled = actionsEnabled,
                        onClick = { state.scopeShownForID = permission.id },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Always Allow"
                                stateDescription = scope
                                // iOS hint “Shows what won’t ask again”.
                                onClick(label = "Show what won’t ask again") {
                                    if (actionsEnabled) state.scopeShownForID = permission.id
                                    actionsEnabled
                                }
                            }
                            .testTag("request-always"),
                    ) { ButtonLabel("Always Allow", sending = busy && state.sentAction == DockAction.Always) }
                    if (state.scopeShownForID == permission.id) {
                        Popup(
                            popupPositionProvider = AbovePositionProvider,
                            onDismissRequest = { state.scopeShownForID = null },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Surface(shape = OpenCodeShape.card, tonalElevation = 3.dp, shadowElevation = 8.dp) {
                                PermissionScopeConfirm(save, confirm = {
                                    state.scopeShownForID = null
                                    state.sentAction = DockAction.Always
                                    send(RequestDockFeature.Action.PermissionReplyTapped(permission.id, OpenCodePermissionReply.Always))
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ButtonLabel(title: String, sending: Boolean) {
        // With the button's own padding, a 48 dp target.
        Box(Modifier.fillMaxWidth().heightIn(min = RequestTunables.labelHeight), contentAlignment = Alignment.Center) {
            Text(
                title,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = if (sending) 0f else 1f },
            )
            if (sending) {
                CircularProgressIndicator(
                    Modifier.size(18.dp).semantics { contentDescription = "Sending" },
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            }
        }
    }

    // Shared

    @Composable
    fun ErrorLines(id: String, isForm: Boolean, inline: String? = null) {
        val error = dock.requestErrors[id]
        if (error != null) {
            if (id in dock.failedReplyIDs) {
                RequestErrorLine(
                    if (isForm) "Couldn’t send answer" else "Couldn’t send reply",
                    detail = error,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else if (error != inline) {
                RequestErrorLine(error, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        if (inline != null) RequestErrorLine(inline, modifier = Modifier.padding(vertical = 6.dp))
    }

    @Composable
    fun rememberEntrance(animate: Boolean, slide: Boolean): Modifier {
        val progress = remember { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (progress.value < 1f) progress.animateTo(1f, MotionSpring(0.3).animation(reducedMotion))
        }
        val offset = if (slide && !reducedMotion) 24.dp else 0.dp
        return Modifier.graphicsLayer {
            alpha = progress.value
            translationX = (1f - progress.value) * offset.toPx()
        }
    }
}

/** A choice: its mark on the title's baseline, the title 12 dp after it (iOS `RequestOptionRow`). */
@Composable
fun RequestOptionRow(
    title: String,
    description: String?,
    selected: Boolean,
    multiple: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = if (multiple) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionMark(selected, multiple)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = OpenCodeTextStyles.body, color = colors.text)
            description?.ifEmpty { null }?.let {
                Text(it, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
            }
        }
    }
}

/** Hairlines start at the titles (iOS `RequestOptionRow.divider`). */
@Composable
fun RequestOptionRowDivider() {
    HorizontalDivider(Modifier.padding(start = SelectionMarkWidth + 12.dp), color = OpenCodeTheme.colors.border)
}

/** [SelectionMark]'s width. */
val SelectionMarkWidth: Dp = 22.dp

/**
 * The empty or filled mark beside a choice: a circle for one answer, a rounded square for
 * several. Filled is the cue beyond colour.
 */
@Composable
fun SelectionMark(selected: Boolean, multiple: Boolean) {
    val colors = OpenCodeTheme.colors
    val reducedMotion = rememberReduceMotion()
    val symbol = when {
        selected && multiple -> SFSymbol.CheckmarkSquareFill
        selected -> SFSymbol.CheckmarkCircleFill
        multiple -> SFSymbol.Square
        else -> SFSymbol.Circle
    }
    // Sits on the title's first line (body, 22 sp line).
    Box(Modifier.width(SelectionMarkWidth).heightIn(min = 22.dp), contentAlignment = Alignment.Center) {
        if (reducedMotion) {
            OpenCodeIcon(symbol, contentDescription = null, size = SelectionMarkWidth, tint = if (selected) colors.text else colors.tertiaryText)
        } else {
            Crossfade(symbol, animationSpec = Motion.easeOut(Motion.fadeFast), label = "selectionMark") {
                OpenCodeIcon(it, contentDescription = null, size = SelectionMarkWidth, tint = if (selected) colors.text else colors.tertiaryText)
            }
        }
    }
}

/** “Couldn’t send answer”, with the server's reason beneath (iOS `RequestErrorLine`). */
@Composable
fun RequestErrorLine(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = OpenCodeTheme.colors
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, contentDescription = null, size = 16.dp, tint = colors.destructive)
            Text(title, style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium), color = colors.destructive)
        }
        if (detail != null) {
            Text(detail, style = OpenCodeTextStyles.caption1, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The monochrome prominent button the request surfaces use (iOS `.openCodePrimaryButton()`
 * tinted `label` over `systemBackground`): the theme's Send button colours.
 */
@Composable
internal fun RequestMonochromeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = OpenCodeTheme.colors
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ai.opencode.core.ui.OpenCodeButtonDefaults.height),
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = colors.sendButtonBackground,
            contentColor = colors.sendButtonForeground,
        ),
        contentPadding = ai.opencode.core.ui.OpenCodeButtonDefaults.contentPadding,
        content = content,
    )
}

/** iOS `ViewThatFits(in: .horizontal) { HStack; VStack }` for equal-width actions. */
@Composable
private fun SideBySideOrStacked(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val count = measurables.size
        if (count == 0) return@Layout layout(0, 0) {}
        val slot = if (count > 1) (width - gap * (count - 1)) / count else width
        val fits = count == 1 || measurables.all { it.minIntrinsicWidth(Constraints.Infinity) <= slot }
        if (fits) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(slot).copy(maxHeight = constraints.maxHeight)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable -> placeable.place(index * (slot + gap), (height - placeable.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { it.place(0, y); y += it.height + gap }
            }
        }
    }
}

/** iOS negative padding: reaches past its slot by [start] / [end] without taking their room. */
private fun Modifier.outset(start: Dp = 0.dp, end: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = max(placeable.width - start.roundToPx() - end.roundToPx(), 0)
    layout(width, placeable.height) { placeable.place(-start.roundToPx(), 0) }
}

/** Places the Always Allow scope popover above its button (below when there's no room), inside the window. */
private object AbovePositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 8
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, max(margin, windowSize.width - popupContentSize.width - margin))
        val above = anchorBounds.top - popupContentSize.height - margin
        val y = if (above >= margin) above else min(anchorBounds.bottom + margin, max(margin, windowSize.height - popupContentSize.height - margin))
        return IntOffset(x, y)
    }
}
