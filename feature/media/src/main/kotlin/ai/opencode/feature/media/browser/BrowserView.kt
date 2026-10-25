package ai.opencode.feature.media

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.OpenCodePairingLink
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** Port of iOS `BrowserMetrics`. */
object BrowserMetrics {
    /** The controls, like the session header's. */
    val controlSize = 44.dp
    /** The back button's row at the top, where the session header's sits. */
    val headerClearance = TranscriptMetrics.clustered + controlSize + 8.dp
    /** The bottom bar's height. */
    val barHeight = 52.dp
}

/**
 * What the reply capsule shows (the slice of iOS `SessionFeature.State.BrowserPeek` the page
 * reads): the reply's text once it lands, null while the agent works.
 */
data class BrowserPeek(val text: CharSequence?)

/**
 * The page pushed over the session (D-40, Push In): the web view edge to edge, a back button
 * where the drawer button sits, and a bar at the bottom (the page's back and forward, its title
 * and host, Share, Open in Browser, and Ask). Ask raises a composer over the bar; the question goes to the
 * session with the page attached, and a capsule shows the reply's progress. Going back from the
 * first page closes it: the back button, or the stack's back gesture (which steps back through
 * the page's history first: see [browserBackAction] and [BrowserWebViews.canGoBack]).
 * Port of iOS `BrowserPage`; the session passes its browser state, its peek and an action sink.
 *
 * @param workingIndicator the session's `WorkingIndicator`, shown in the capsule while the
 *   agent works on the question (`needsInput`: a request waiting on you).
 */
@Composable
fun BrowserPage(
    browser: BrowserFeature.State,
    peek: BrowserPeek?,
    hasPendingRequests: Boolean,
    onAction: (BrowserFeature.Action) -> Unit,
    workingIndicator: @Composable (needsInput: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var bottomHeight by remember { mutableStateOf(BrowserMetrics.barHeight) }
    // The visit as it was last open: closing clears the store at once, while the page still
    // slides off the stack showing it.
    var lastOpen by remember { mutableStateOf<BrowserFeature.State?>(null) }
    LaunchedEffect(browser) { if (browser.isOpen) lastOpen = browser }
    val shown = if (browser.isOpen) browser else lastOpen ?: browser

    fun close() {
        if (!browser.isOpen) return
        focusManager.clearFocus()
        onAction(BrowserFeature.Action.CloseTapped)
    }

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("browser"),
    ) {
        val pageID = shown.pageID
        val url = shown.openedURL ?: shown.url
        if (pageID != null && url != null) {
            // The back button above and the bar below; the page stays clear of them.
            BrowserWebViewHost(
                pageID = pageID,
                url = url,
                onEvent = onAction,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = statusBar + BrowserMetrics.headerClearance, bottom = navigationBar + bottomHeight + 8.dp),
            )
        }
        // Legibility behind the status bar and the back button, like the session header's wash.
        val surface = MaterialTheme.colorScheme.surface
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusBar + BrowserMetrics.headerClearance)
                .background(Brush.verticalGradient(0f to surface, 0.5f to surface.copy(alpha = 0.85f), 1f to surface.copy(alpha = 0f)))
                .clearAndSetSemantics {},
        )
        // Where the drawer button sits on the session: back to it.
        Surface(
            onClick = ::close,
            shape = CircleShape,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.statusBars))
                .padding(start = 12.dp, top = TranscriptMetrics.clustered)
                .size(BrowserMetrics.controlSize)
                .semantics { contentDescription = "Back to Session" }
                .testTag("browser-pop"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                OpenCodeIcon("chevron.backward", contentDescription = null)
            }
        }
        // The bar and Ask rise with the keyboard; the page stays put beneath.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp)
                .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = peek != null && !shown.isAsking,
                enter = appear(reduceMotion),
                exit = disappear(reduceMotion),
            ) {
                if (peek != null) ReplyCapsule(peek, hasPendingRequests, ::close, workingIndicator)
            }
            AnimatedVisibility(
                visible = shown.isAsking,
                enter = appear(reduceMotion),
                exit = disappear(reduceMotion),
            ) {
                AskComposer(shown, onAction, onSend = { focusManager.clearFocus() })
            }
            Bar(shown, onAction)
        }
    }
}

/**
 * What the stack's back gesture does on this page: Ask goes away first, then the page's own
 * history, then the page leaves (iOS: the escape action puts Ask away, the web view's back swipe
 * steps its history, and the stack's swipe pops).
 */
fun browserBackAction(browser: BrowserFeature.State): BrowserFeature.Action = when {
    browser.isAsking -> BrowserFeature.Action.AskDismissed
    browser.canGoBack -> BrowserFeature.Action.BackTapped
    else -> BrowserFeature.Action.CloseTapped
}

/**
 * The page's place in the session's stack: stable for one visit, and never a detail's identity
 * (iOS `BrowserPage.stackID`: the UUID's first eight bytes with bit 62 set).
 */
fun browserStackID(pageID: UUID): Long = pageID.mostSignificantBits or (1L shl 62)

private fun appear(reduceMotion: Boolean) =
    if (reduceMotion) fadeIn(tween(300)) else slideInVertically(tween(300)) { it } + fadeIn(tween(300))

private fun disappear(reduceMotion: Boolean) =
    if (reduceMotion) fadeOut(tween(300)) else slideOutVertically(tween(300)) { it } + fadeOut(tween(300))

@Composable
private fun Bar(browser: BrowserFeature.State, onAction: (BrowserFeature.Action) -> Unit) {
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = OpenCodeShape.capsule,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .weight(1f)
                .height(BrowserMetrics.barHeight),
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton("chevron.left", "Back", "browser-back", browser.canGoBack) { onAction(BrowserFeature.Action.BackTapped) }
                BarButton("chevron.right", "Forward", "browser-forward", browser.canGoForward) {
                    onAction(BrowserFeature.Action.ForwardTapped)
                }
                Address(browser, Modifier.weight(1f))
                val url = browser.url
                if (url != null) {
                    BarButton("square.and.arrow.up", "Share", "browser-share", enabled = true) {
                        dependency(LinkShareSheet).present(url)
                    }
                    BarButton("safari", "Open in Browser", "browser-safari", enabled = true) {
                        val opener = dependency(OpenURLClient)
                        scope.launch { opener.openInBrowser(url) }
                    }
                }
            }
        }
        Surface(
            onClick = { onAction(if (browser.isAsking) BrowserFeature.Action.AskDismissed else BrowserFeature.Action.AskTapped) },
            shape = CircleShape,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(BrowserMetrics.barHeight)
                .semantics { contentDescription = if (browser.isAsking) "Cancel" else "Ask About This Page" }
                .testTag("browser-ask"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                OpenCodeIcon(if (browser.isAsking) "xmark" else "text.bubble", contentDescription = null)
            }
        }
    }
}

@Composable
private fun BarButton(systemImage: String, label: String, tag: String, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier
            .size(BrowserMetrics.controlSize)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = null, onClick = action)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        OpenCodeIcon(systemImage, contentDescription = null, tint = if (enabled) color else color.copy(alpha = 0.3f))
    }
}

/** The page's title over its host; a tap scrolls to the top. */
@Composable
private fun Address(browser: BrowserFeature.State, modifier: Modifier) {
    Column(
        modifier
            .heightIn(min = BrowserMetrics.controlSize)
            .clickable { browser.pageID?.let { BrowserWebViews.shared.scrollToTop(it) } }
            .clearAndSetSemantics {
                contentDescription = browser.displayTitle
                stateDescription = browser.url ?: ""
                role = Role.Button
                onClick(label = "Scrolls to the top of the page") {
                    browser.pageID?.let { BrowserWebViews.shared.scrollToTop(it) }
                    true
                }
            }
            .testTag("browser-address"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Text(
            browser.displayTitle,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            if (browser.isLoading) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = secondary)
            } else if (browser.url?.startsWith("https:", ignoreCase = true) == true) {
                OpenCodeIcon("lock.fill", contentDescription = null, size = 11.dp, tint = secondary)
            }
            Text(browser.host, style = OpenCodeTextStyles.caption1, color = secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

@Composable
private fun AskComposer(browser: BrowserFeature.State, onAction: (BrowserFeature.Action) -> Unit, onSend: () -> Unit) {
    val focus = remember { FocusRequester() }
    val canSend = browser.askDraft.isNotBlank()
    fun send() {
        if (!canSend) return
        onSend()
        onAction(BrowserFeature.Action.AskSent)
    }
    Surface(
        shape = RoundedCornerShape(BrowserMetrics.barHeight / 2),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().heightIn(min = BrowserMetrics.barHeight),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = browser.askDraft,
                onValueChange = { text ->
                    // A multi-line field's Send key types a newline: that's Send.
                    if (text == browser.askDraft + "\n") send() else onAction(BrowserFeature.Action.AskDraftChanged(text))
                },
                placeholder = { Text("Ask about this page") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .testTag("browser-ask-field"),
            )
            val onSurface = MaterialTheme.colorScheme.onSurface
            Box(
                Modifier
                    .padding(end = 2.dp, bottom = 4.dp)
                    .clickable(enabled = canSend, role = Role.Button, onClick = ::send)
                    .openCodeTapTarget(BrowserMetrics.controlSize)
                    .semantics { contentDescription = "Send" }
                    .testTag("browser-ask-send"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(if (canSend) onSurface else onSurface.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon("arrow.up", contentDescription = null, tint = MaterialTheme.colorScheme.surface)
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * What the agent is doing with the question asked here; once its reply lands, a tap goes back
 * to the session to read it.
 */
@Composable
private fun ReplyCapsule(
    peek: BrowserPeek,
    hasPendingRequests: Boolean,
    close: () -> Unit,
    workingIndicator: @Composable (needsInput: Boolean) -> Unit,
) {
    // A request waiting on you (a permission, a question) is answered in the session.
    val needsInput = peek.text == null && hasPendingRequests
    val goesBack = peek.text != null || needsInput
    Surface(
        shape = OpenCodeShape.capsule,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .heightIn(min = BrowserMetrics.controlSize)
            .then(if (goesBack) Modifier.clickable(role = Role.Button, onClickLabel = "Goes back to the session", onClick = close) else Modifier)
            .clearAndSetSemantics {
                contentDescription = if (peek.text != null) "Reply ready" else if (needsInput) "Needs your input" else "Working"
                if (goesBack) {
                    role = Role.Button
                    onClick(label = "Goes back to the session") { close(); true }
                }
            }
            .testTag("browser-reply"),
    ) {
        Box(Modifier.padding(horizontal = 14.dp).heightIn(min = BrowserMetrics.controlSize), contentAlignment = Alignment.CenterStart) {
            if (peek.text == null) {
                workingIndicator(needsInput)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                    OpenCodeIcon("text.bubble", contentDescription = null, tint = secondary, size = 17.dp)
                    Text("Reply ready", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    Text("· Back to read it", style = OpenCodeTextStyles.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Hosts the page's [android.webkit.WebView] from [BrowserWebViews], which outlives this view,
 * so a rebuilt page keeps its history and scroll position. Also shows the hold menu for links
 * held in the page. Port of iOS `BrowserWebViewHost`.
 */
@Composable
fun BrowserWebViewHost(
    pageID: UUID,
    url: String,
    onEvent: (BrowserFeature.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    class Coordinator(var pageID: UUID) {
        /**
         * The page's web view was attached once; if it's discarded later (the page closing), it
         * isn't made again while the view slides away.
         */
        var attached = false
    }
    val coordinator = remember { Coordinator(pageID) }
    val isDark = OpenCodeTheme.colors.isDark
    var held by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    val pages = BrowserWebViews.shared

    fun attach(container: FrameLayout) {
        val webView = pages.webView(pageID, url, isDark)
        if (webView.parent !== container) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.removeAllViews()
            container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pages.setHostContext(pageID, container.context)
        pages.setEventHandler(pageID, onEvent)
        pages.setLinkHeldHandler(pageID) { link, x, y -> held = Triple(link, x, y) }
        pages.resume(pageID)
    }

    Box(modifier) {
        AndroidView(
            factory = { context -> FrameLayout(context).apply { clipChildren = true } },
            update = { container ->
                if (coordinator.pageID != pageID) {
                    pages.setEventHandler(coordinator.pageID, null)
                    pages.setLinkHeldHandler(coordinator.pageID, null)
                    coordinator.pageID = pageID
                    coordinator.attached = false
                }
                if (coordinator.attached && !pages.holds(pageID)) return@AndroidView
                attach(container)
                coordinator.attached = true
            },
            // Off screen, the page plays nothing and keeps nothing of the screen.
            onRelease = { container ->
                container.removeAllViews()
                pages.pauseMedia(coordinator.pageID)
                pages.setEventHandler(coordinator.pageID, null)
                pages.setLinkHeldHandler(coordinator.pageID, null)
                pages.setHostContext(coordinator.pageID, null)
            },
            modifier = Modifier.fillMaxSize(),
        )
        held?.let { (link, x, y) ->
            val previewURL = OpenCodePairingLink.shareableURL(link) ?: link
            Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(1.dp)) {
                LinkMenuPopup(
                    url = previewURL,
                    actions = pages.linkMenuActions(link),
                    expanded = true,
                    onAction = { action ->
                        held = null
                        pages.performLinkMenu(pageID, action, link)
                    },
                    onOpen = {
                        held = null
                        pages.commitLinkPreview(pageID, link)
                    },
                    onDismiss = { held = null },
                )
            }
        }
    }
}
