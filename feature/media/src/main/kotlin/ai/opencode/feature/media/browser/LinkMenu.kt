package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.ExperimentalFeatures
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.SessionLink
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What holding a web link in a reply offers (Markdown links, bare URLs, and link cards): Open in
 * Safari, Open in App, Copy Link, and Share…; a tap still opens it as before. With the In-App
 * Browser on, a public page also offers Open in App Browser first, since a tap tries the app
 * that claims the link before the browser. TalkBack gets the same as custom actions.
 * Port of iOS `LinkMenuAction`; the copy keeps iOS's “Open in Safari” (it opens the default
 * browser on Android).
 */
enum class LinkMenuAction(val rawValue: String) {
    OpenInAppBrowser("openInAppBrowser"),
    OpenInSafari("openInSafari"),
    OpenInApp("openInApp"),
    Copy("copy"),
    Share("share"),
    /** A session ID in a reply ([SessionLink]): TalkBack's open action. */
    OpenSession("openSession"),
    CopySessionID("copySessionID");

    val title: String
        get() = when (this) {
            OpenInAppBrowser -> "Open in App Browser"
            OpenInSafari -> "Open in Safari"
            OpenInApp -> "Open in App"
            Copy -> "Copy Link"
            Share -> "Share…"
            OpenSession -> "Open Session"
            CopySessionID -> "Copy ID"
        }

    val systemImage: String
        get() = when (this) {
            OpenInAppBrowser -> "globe"
            OpenInSafari -> "safari"
            OpenInApp -> "arrow.up.forward.app"
            Copy -> "doc.on.doc"
            Share -> "square.and.arrow.up"
            OpenSession -> "arrow.up.right.square"
            CopySessionID -> "doc.on.doc"
        }

    companion object {
        /** Only web links and valid pairing links get the menu; file links keep opening the viewer. */
        fun offers(url: String): Boolean {
            if (SessionLink.id(url) != null) return true
            if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
                return OpenCodePairingLink.shareableURL(url) != null
            }
            val scheme = url.substringBefore(':', "").lowercase()
            return scheme == "http" || scheme == "https"
        }

        /** What holding [url] offers, in order. */
        fun actions(url: String, experimental: ExperimentalFeatures): List<LinkMenuAction> {
            if (SessionLink.id(url) != null) return listOf(CopySessionID)
            if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
                // Never offer Open in Safari, Open in App, or Open in App Browser for a single-use pairing URL.
                return if (OpenCodePairingLink.shareableURL(url) != null) listOf(Copy, Share) else emptyList()
            }
            val publicWebPage = BrowserLinkPolicy.isPublicWebPage(url)
            val appBrowser = experimental(ai.opencode.core.dependencies.ExperimentalFeature.InAppBrowser) && publicWebPage
            return entries.filter { action ->
                when (action) {
                    OpenInAppBrowser -> appBrowser
                    OpenInApp -> publicWebPage
                    OpenInSafari, Copy, Share -> true
                    OpenSession, CopySessionID -> false
                }
            }
        }
    }
}

/**
 * A link tap asking the system whether an app claims it: one tap, from one session. Its answer
 * opens the fallback only while it is still the latest. Port of iOS `LinkProbe`.
 */
data class LinkProbe(
    val id: java.util.UUID,
    val url: String,
    val sessionID: String?,
)

/**
 * Hands a web link to the installed app that claims it (GitHub, X, YouTube…), for Open Links in
 * Apps. Opens nothing when no app claims it, so the caller falls back to the browser. Port of
 * iOS `UniversalLinkClient` (`.universalLinksOnly`): Android's `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`.
 */
class UniversalLinkClient(
    /** Whether an app took the link. */
    val open: suspend (String) -> Boolean,
) {
    companion object Key : DependencyKey<UniversalLinkClient> {
        override val liveValue: UniversalLinkClient by lazy {
            UniversalLinkClient { url ->
                // Lab runs stand in for an installed app: `all` claims every link, `none` none.
                when (labUniversalLinks()) {
                    "all" -> return@UniversalLinkClient true
                    "none" -> return@UniversalLinkClient false
                }
                withContext(Dispatchers.Main) {
                    val context = dependency(AppContext)
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
                    try {
                        context.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
            }
        }
        override val testValue = UniversalLinkClient {
            throw AssertionError("Unimplemented: UniversalLinkClient.open")
        }

        /** iOS `OPENCODE_LAB_UNIVERSAL_LINKS`, debuggable builds only. */
        private fun labUniversalLinks(): String? {
            val context = runCatching { dependency(AppContext) }.getOrNull() ?: return null
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return System.getenv("OPENCODE_LAB_UNIVERSAL_LINKS") ?: runCatching {
                @Suppress("PrivateApi")
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.opencode.lab_universal_links") as? String
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Opens a URL in the system (iOS `\.openURL` / `ActiveScene.open`): [open] hands it to whatever
 * claims it (an app or the browser); [openInBrowser] is “Open in Safari”: the default browser
 * as a Custom Tab. Both return whether something took it. Lives here because no lower module has
 * a URL opener yet; the session reducer reads it too.
 */
class OpenURLClient(
    val open: suspend (String) -> Boolean,
    val openInBrowser: suspend (String) -> Boolean = open,
) {
    companion object Key : DependencyKey<OpenURLClient> {
        override val liveValue: OpenURLClient by lazy {
            OpenURLClient(
                open = { url ->
                    withContext(Dispatchers.Main) {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            dependency(AppContext).startActivity(intent)
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    }
                },
                openInBrowser = { url ->
                    withContext(Dispatchers.Main) {
                        val context = dependency(AppContext)
                        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            tab.launchUrl(context, url.toUri())
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    }
                },
            )
        }
        override val testValue = OpenURLClient(open = { throw AssertionError("Unimplemented: OpenURLClient.open") })
    }
}

/**
 * Presents the share sheet for a link (iOS `LinkShareSheet`): `ACTION_SEND` through the system
 * chooser. Pairing links share only their shareable form, as on iOS.
 */
class LinkShareSheet(val present: (String) -> Unit) {
    companion object Key : DependencyKey<LinkShareSheet> {
        override val liveValue: LinkShareSheet by lazy {
            LinkShareSheet { url ->
                val shareURL = OpenCodePairingLink.shareableURL(url) ?: return@LinkShareSheet
                val context = dependency(AppContext)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareURL)
                val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(chooser)
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        override val testValue = LinkShareSheet {}
    }
}

/**
 * How a row's links run their menu: set by the transcript row ([LocalLinkMenu]), read by the
 * text overlay and the link cards. Port of iOS `LinkMenuHandler`.
 */
class LinkMenuHandler(val perform: (LinkMenuAction, String) -> Unit) {
    /** Read as the menu opens, so it follows the Experimental switches. */
    fun actions(url: String): List<LinkMenuAction> = LinkMenuAction.actions(url, dependency(ExperimentalFeatures))

    /** A session link also opens from TalkBack's actions, named for its session. */
    fun accessibilityActions(url: String): List<LinkMenuAction> =
        (if (SessionLink.id(url) != null) listOf(LinkMenuAction.OpenSession) else emptyList()) + actions(url)
}

/** iOS `EnvironmentValues.linkMenu`. */
val LocalLinkMenu = staticCompositionLocalOf<LinkMenuHandler?> { null }

/** Port of iOS `LinkMenuText`. */
object LinkMenuText {
    /** The text's web links, once each, in order. */
    fun webLinks(text: AnnotatedString): List<String> {
        val seen = HashSet<String>()
        return linkRanges(text).mapNotNull { (_, url) -> url.takeIf { LinkMenuAction.offers(it) && seen.add(it) } }
    }

    fun displayHost(url: String): String =
        OpenCodePairingLink.decode(url)?.server?.host
            ?: browserHost(url)?.let { if (it.startsWith("www.")) it.drop(4) else it }
            ?: url

    /** One link: the menu's own titles. Several: each names its host. */
    fun accessibilityName(action: LinkMenuAction, url: String, among: List<String>): String {
        val id = SessionLink.id(url)
        if (id != null) {
            val name = SessionLink.title(url) ?: SessionLink.shortID(id)
            return when (action) {
                LinkMenuAction.CopySessionID -> if (among.size > 1) "Copy ID of $name" else "Copy ID"
                else -> "Open session $name"
            }
        }
        if (among.size <= 1) return action.title.replace("…", "")
        val host = displayHost(url)
        return when (action) {
            LinkMenuAction.OpenInAppBrowser -> "Open $host in App Browser"
            LinkMenuAction.OpenInSafari -> "Open $host in Safari"
            LinkMenuAction.OpenInApp -> "Open $host in App"
            LinkMenuAction.Copy -> "Copy Link to $host"
            LinkMenuAction.Share -> "Share Link to $host"
            LinkMenuAction.OpenSession, LinkMenuAction.CopySessionID -> action.title
        }
    }

    /**
     * Every link run in [text] (`LinkAnnotation.Url`), in order, with adjacent runs of the same
     * URL joined: the rest of a link whose first character carries its icon slot is the same link.
     */
    fun linkRanges(text: AnnotatedString): List<Pair<IntRange, String>> {
        val runs = text.getLinkAnnotations(0, text.length)
            .mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.let { (range.start until range.end) to it.url } }
            .sortedBy { it.first.first }
        val joined = ArrayList<Pair<IntRange, String>>()
        for ((range, url) in runs) {
            val last = joined.lastOrNull()
            if (last != null && last.second == url && last.first.last + 1 >= range.first) {
                joined[joined.lastIndex] = (last.first.first..maxOf(last.first.last, range.last)) to url
            } else {
                joined += range to url
            }
        }
        return joined
    }
}

/**
 * Finds which link, if any, is under a point of laid-out text (iOS `LinkHitLayout`, which lays
 * the text out again with TextKit; Compose hands over the [TextLayoutResult] the text drew with,
 * inline icon slots included). [links] and [fileLinks] are character ranges in the laid-out text.
 */
class LinkHitLayout(
    private val layout: TextLayoutResult,
    private val links: List<Pair<IntRange, String>>,
    private val fileLinks: List<Pair<IntRange, AgentFileReference>> = emptyList(),
    /** Touch slop around each line rect, in pixels (iOS 6 × 5 points). */
    private val slopX: Float,
    private val slopY: Float,
) {
    data class Hit<T>(val value: T, val rects: List<Rect>)

    /** The link under [point], with the rects it covers, allowing a little slop. */
    fun link(point: Offset): Hit<String>? = hit(links, point)

    /** The inline `opencode-file://` link under [point], with the rects it covers. */
    fun fileLink(point: Offset): Hit<AgentFileReference>? = hit(fileLinks, point)

    /** The session chip under [point]: the line rects it covers, for the pressed highlight. */
    fun sessionChipRects(point: Offset): List<Rect>? = hit(links, point) { SessionLink.id(it) != null }?.rects

    /** Every web link in the laid-out text and the line rects it covers. */
    val laidOutLinks: List<Hit<String>> get() = links.map { (range, url) -> Hit(url, rects(range)) }

    private fun <T> hit(items: List<Pair<IntRange, T>>, point: Offset, predicate: (T) -> Boolean = { true }): Hit<T>? {
        for ((range, value) in items) {
            if (!predicate(value)) continue
            val rects = rects(range)
            if (rects.any { Rect(it.left - slopX, it.top - slopY, it.right + slopX, it.bottom + slopY).contains(point) }) {
                return Hit(value, rects)
            }
        }
        return null
    }

    /** One rect per line the range covers (TextKit's enclosing rects). */
    fun rects(range: IntRange): List<Rect> {
        val textLength = layout.layoutInput.text.length
        val start = range.first.coerceIn(0, textLength)
        val end = (range.last + 1).coerceIn(start, textLength)
        if (start >= end) return emptyList()
        val result = ArrayList<Rect>()
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            val lineStart = maxOf(start, layout.getLineStart(line))
            val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true))
            if (lineStart >= lineEnd) continue
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (offset in lineStart until lineEnd) {
                val box = layout.getBoundingBox(offset)
                left = minOf(left, box.left)
                right = maxOf(right, box.right)
            }
            result += Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line))
        }
        return result
    }
}

/**
 * Holding a web link in [text] opens the link menu with a preview of the link; tapping it still
 * opens it as before ([onOpen], the row's link opener). Touches anywhere else go to the text
 * underneath (selection included). TalkBack gets the menu's actions on the text. Port of iOS
 * `View.linkMenu(for:…)` (`LinkMenuModifier` + `LinkMenuOverlay` + `LinkMenuView`); wrap the
 * text's `Text` in it and pass its latest [TextLayoutResult].
 */
@Composable
fun LinkMenu(
    text: AnnotatedString,
    textLayout: () -> TextLayoutResult?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val handler = LocalLinkMenu.current
    val ranges = remember(text) { LinkMenuText.linkRanges(text) }
    val webRanges = remember(ranges) { ranges.filter { LinkMenuAction.offers(it.second) } }
    val links = remember(webRanges) { webRanges.map { it.second }.distinct() }
    if (handler == null || links.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val slopX = with(density) { 6.dp.toPx() }
    val slopY = with(density) { 5.dp.toPx() }
    var menu by remember { mutableStateOf<LinkHitLayout.Hit<String>?>(null) }
    var pressed by remember { mutableStateOf<List<Rect>>(emptyList()) }
    val pressAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val pressColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.16f else 0.1f)
    HapticEffect(Haptic.threshold, menu?.value) { old, new -> old == null && new != null }

    Box(
        modifier
            .semantics {
                customActions = links.flatMap { url ->
                    handler.accessibilityActions(url).map { action ->
                        CustomAccessibilityAction(LinkMenuText.accessibilityName(action, url, links)) {
                            handler.perform(action, url)
                            true
                        }
                    }
                }
            }
            .drawBehind {
                val alpha = pressAlpha.value
                if (alpha <= 0f) return@drawBehind
                for (rect in pressed) {
                    drawRoundRect(
                        color = pressColor.copy(alpha = pressColor.alpha * alpha),
                        topLeft = rect.topLeft,
                        size = rect.size,
                        cornerRadius = CornerRadius(rect.height * 0.3f),
                    )
                }
            }
            .pointerInput(webRanges, handler, onOpen) {
                awaitEachGesture {
                    // Only a link's own touches; everything else reaches the text.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val layout = textLayout() ?: return@awaitEachGesture
                    val hits = LinkHitLayout(layout, webRanges, slopX = slopX, slopY = slopY)
                    val hit = hits.link(down.position) ?: return@awaitEachGesture
                    down.consume()
                    // A session chip darkens while a finger is on it.
                    hits.sessionChipRects(down.position)?.let { rects ->
                        pressed = rects
                        scope.launch { pressAlpha.snapTo(1f) }
                    }
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation(PointerEventPass.Initial)
                    }
                    if (up != null) {
                        up.consume()
                        onOpen(hit.value)
                    } else {
                        menu = hit
                        // Swallow the rest of the hold.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                    if (pressAlpha.value > 0f) scope.launch { pressAlpha.animateTo(0f, tween(180)) }
                }
            },
    ) {
        content()
        menu?.let { hit ->
            val anchor = hit.rects.firstOrNull() ?: Rect.Zero
            Box(
                Modifier
                    .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                    .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() }),
            ) {
                LinkMenuPopup(
                    url = hit.value,
                    actions = handler.actions(hit.value),
                    expanded = true,
                    onAction = { action ->
                        menu = null
                        handler.perform(action, hit.value)
                    },
                    onOpen = {
                        menu = null
                        onOpen(hit.value)
                    },
                    onDismiss = { menu = null },
                )
            }
        }
    }
}

/**
 * The hold menu: the link's preview card (a tap opens the link, as iOS's preview commit does)
 * over its actions. Anchor it in a box covering the held link or card.
 */
@Composable
fun LinkMenuPopup(
    url: String,
    actions: List<LinkMenuAction>,
    expanded: Boolean,
    onAction: (LinkMenuAction) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        LinkPreviewCardContent(url, Modifier.clickable(onClick = onOpen))
        if (actions.isNotEmpty()) HorizontalDivider()
        for (action in actions) {
            DropdownMenuItem(
                text = { Text(action.title) },
                leadingIcon = { OpenCodeIcon(action.systemImage, contentDescription = null) },
                onClick = { onAction(action) },
            )
        }
    }
}

/**
 * The menu's preview: the site and the address, like a browser's link preview without loading
 * the page. Port of iOS `LinkPreviewCardContent` (`LinkPreviewCardController` sizes it 320 wide).
 */
@Composable
fun LinkPreviewCardContent(url: String, modifier: Modifier = Modifier) {
    val sessionID = SessionLink.id(url)
    Row(
        modifier
            .width(320.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(
                if (sessionID == null) "globe" else "bubble.left.and.text.bubble.right",
                contentDescription = null,
                size = 28.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (sessionID != null) {
                Text(SessionLink.title(url) ?: "Session", style = OpenCodeTextStyles.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    sessionID,
                    style = OpenCodeTextStyles.footnote.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            } else {
                Text(LinkMenuText.displayHost(url), style = OpenCodeTextStyles.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    url,
                    style = OpenCodeTextStyles.footnote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
    }
}
