package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.ExperimentalFeatures
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.OpenCodePageContext
import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** What a send read from the open page (iOS `PageReading`). */
class PageReading(
    val context: OpenCodePageContext,
    /** A JPEG of what's on screen, when the chip asked for one. */
    val screenshot: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PageReading && other.context == context && other.screenshot.contentEquals(screenshot)
    override fun hashCode(): Int = context.hashCode() * 31 + (screenshot?.contentHashCode() ?: 0)
}

/**
 * The in-app browser's web views, for reducers: read the open page at send, navigate, and
 * release or discard a page's web content. Port of iOS `PageReaderClient` (`\.pageReader`).
 */
class PageReaderClient(
    /**
     * The page's URL, title, selection, visible text, and scroll position, read now. A page
     * whose content was released (or failed to answer) still gives its URL and title.
     */
    val read: suspend (PageShareRequest) -> PageReading,
    val goBack: suspend (UUID) -> Unit,
    val goForward: suspend (UUID) -> Unit,
    /** Forgets the page entirely. */
    val discard: suspend (UUID) -> Unit,
) {
    companion object Key : DependencyKey<PageReaderClient> {
        override val liveValue: PageReaderClient by lazy {
            PageReaderClient(
                read = { request -> withContext(Dispatchers.Main) { BrowserWebViews.shared.read(request) } },
                goBack = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.goBack(id) } },
                goForward = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.goForward(id) } },
                discard = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.discard(id) } },
            )
        }
        override val testValue = PageReaderClient(
            read = { PageReading(OpenCodePageContext(url = it.url, title = it.title)) },
            goBack = {},
            goForward = {},
            discard = {},
        )
    }
}

/**
 * Owns each browser visit's [WebView], so the page (and its scroll position) outlives the
 * composable that shows it, which the session's page stack may rebuild. Main thread only.
 *
 * Privacy: every page shares one WebView profile (`opencode-browser`, when the WebView supports
 * profiles) whose cookies, storage and cache are cleared the first time a page opens in each run,
 * so cookies and logins last until the app quits and never mix with other web content: pages
 * open signed out and what the agent can read is public by default. (iOS's store never reaches
 * disk; Android WebView has no in-memory profile, so clearing at first use stands in for it.)
 * Only public `http(s)` pages load ([BrowserLinkPolicy]); other links go to the system. Media
 * waits for a tap. Port of iOS `BrowserWebViews`.
 */
class BrowserWebViews private constructor() {
    class Entry(var url: String, val id: UUID) {
        var webView: WebView? = null
        var onEvent: ((BrowserFeature.Action) -> Unit)? = null
        /** The host's hold menu: a link held in the page, at a point in the web view. */
        var onLinkHeld: ((url: String, x: Float, y: Float) -> Unit)? = null
        var canGoBack = false
        var canGoForward = false
        var lastTouchX = 0f
        var lastTouchY = 0f
        /** The context a page needs for its dialogs and pickers: the host's while shown. */
        val context = MutableContextWrapper(dependency(AppContext))
    }

    private val entries = HashMap<UUID, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clearedThisRun = false

    /** The page's web view, made on first use. */
    fun webView(id: UUID, url: String): WebView {
        val entry = entries.getOrPut(id) { Entry(url, id) }
        entry.webView?.let { return it }
        val webView = makeWebView(entry)
        entry.webView = webView
        webView.loadUrl(entry.url)
        return webView
    }

    /** Where the page's title, address, and loading state go while a view shows it. Null detaches. */
    fun setEventHandler(id: UUID, handler: ((BrowserFeature.Action) -> Unit)?) {
        entries[id]?.onEvent = handler
    }

    fun setLinkHeldHandler(id: UUID, handler: ((String, Float, Float) -> Unit)?) {
        entries[id]?.onLinkHeld = handler
    }

    /** Pages held now: zero once every session has let go. */
    val entryCount: Int get() = entries.size
    fun hasEventHandler(id: UUID): Boolean = entries[id]?.onEvent != null

    /** Off screen, nothing plays. */
    fun pauseMedia(id: UUID) {
        val webView = entries[id]?.webView ?: return
        webView.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        webView.onPause()
    }

    /** Back on screen. */
    fun resume(id: UUID) {
        entries[id]?.webView?.onResume()
    }

    fun goBack(id: UUID) {
        entries[id]?.webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward(id: UUID) {
        entries[id]?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    /**
     * The page has its own history to go back through: the back gesture is the page's then, not
     * the stack's (the session stack's `yieldsBackSwipe`).
     */
    fun canGoBack(id: UUID): Boolean = entries[id]?.webView?.canGoBack() ?: false

    fun scrollToTop(id: UUID) {
        entries[id]?.webView?.pageUp(true)
    }

    fun discard(id: UUID) {
        val entry = entries.remove(id) ?: return
        entry.webView?.let { tearDown(it, entry) }
    }

    /** Whether the page is still held (not discarded). */
    fun holds(id: UUID): Boolean = entries.containsKey(id)

    /** Lends the page the host's context (dialogs, pickers) while shown; the app's otherwise. */
    fun setHostContext(id: UUID, context: Context?) {
        val entry = entries[id] ?: return
        entry.context.baseContext = context ?: dependency(AppContext)
    }

    suspend fun read(request: PageShareRequest): PageReading {
        var context = OpenCodePageContext(url = request.url, title = request.title)
        val webView = entries[request.pageID]?.webView ?: return PageReading(context)
        val raw = evaluate(webView, "(function(limit) {\n$READER_SCRIPT\n})(${OpenCodePageContext.VISIBLE_TEXT_LIMIT})")
        val result = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (result != null) {
            result.optString("url").takeIf { it.isNotEmpty() && BrowserLinkPolicy.isPublicWebPage(it) }
                ?.let { context = context.copy(url = it) }
            result.optString("title").trim().takeIf { it.isNotEmpty() }?.let { context = context.copy(title = it) }
            context = context.copy(
                visibleText = if (result.has("visible") && !result.isNull("visible")) result.optString("visible") else null,
                selection = result.optString("selection").takeIf { it.isNotEmpty() },
                scroll = if (result.has("scroll") && !result.isNull("scroll")) result.optDouble("scroll") else null,
            )
        }
        val screenshot = if (request.includesScreenshot) snapshot(webView) else null
        return PageReading(context.bounded, screenshot)
    }

    // Back swipe: the session stack's back gesture, while the page has history (`canGoBack`),
    // drives these; from the first page the stack pops instead, so there's only ever one back
    // gesture (iOS `BrowserPageContainer`).

    /** The page follows the back gesture, [fraction] of its width. */
    fun backProgress(id: UUID, fraction: Float) {
        val webView = entries[id]?.webView ?: return
        webView.animate().cancel()
        webView.translationX = fraction.coerceIn(0f, 1f) * webView.width
    }

    /** The back gesture let go short: the page settles back. */
    fun backCancelled(id: UUID) {
        val webView = entries[id]?.webView ?: return
        webView.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    /** The back gesture committed: the page slides away and the page before comes in from beneath. */
    fun backCommitted(id: UUID, reduceMotion: Boolean) {
        val webView = entries[id]?.webView ?: return
        webView.animate().cancel()
        if (reduceMotion) {
            webView.translationX = 0f
            arrive(webView, reduceMotion = true)
            return
        }
        webView.animate().translationX(webView.width.toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { arrive(webView, reduceMotion = false) }.start()
    }

    /** The page before comes in from beneath, as a pop reveals its parent. */
    private fun arrive(webView: WebView, reduceMotion: Boolean) {
        if (webView.canGoBack()) webView.goBack()
        if (reduceMotion) {
            webView.translationX = 0f
            webView.alpha = 0.4f
            webView.animate().alpha(1f).setDuration(250).start()
            return
        }
        webView.translationX = -webView.width * PARALLAX
        webView.alpha = 0.6f
        webView.animate().translationX(0f).alpha(1f).setDuration(350).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    // Hold menu

    /** The hold menu's preview was tapped: what a tap on the link would do. */
    fun commitLinkPreview(id: UUID, url: String) {
        val entry = entries[id] ?: return
        when {
            OpenCodePairingLink.shouldHandleOpenURL(url) -> entry.onEvent?.invoke(BrowserFeature.Action.PairingLinkTapped(url))
            BrowserLinkPolicy.isPublicWebPage(url) -> entry.webView?.loadUrl(url)
            else -> openInSystem(url)
        }
    }

    /** One of the hold menu's actions, on the link's shareable form. */
    fun performLinkMenu(id: UUID, action: LinkMenuAction, url: String) {
        val entry = entries[id] ?: return
        val shareURL = OpenCodePairingLink.shareableURL(url) ?: return
        if (action == LinkMenuAction.Share) dependency(LinkShareSheet).present(shareURL)
        entry.onEvent?.invoke(BrowserFeature.Action.LinkMenu(action, shareURL))
    }

    /**
     * What holding [url] in a page offers. A pairing link (valid or malformed) gets a static,
     * non-fetching card and only Copy and Share (or nothing); other links get the reply links'
     * menu without Open in App Browser (they're already in it). iOS shows WebKit's own menu for
     * those; Android's WebView has none.
     */
    fun linkMenuActions(url: String): List<LinkMenuAction> {
        val noExperiments = ExperimentalFeatures { false }
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
            return if (OpenCodePairingLink.shareableURL(url) != null) LinkMenuAction.actions(url, noExperiments) else emptyList()
        }
        return LinkMenuAction.actions(url, noExperiments)
    }

    // Web view

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun makeWebView(entry: Entry): WebView {
        val profile = prepareProfile()
        val webView = WebView(entry.context)
        if (profile != null) WebViewCompat.setProfile(webView, profile)
        if (!clearedThisRun) {
            clearedThisRun = true
            // The previous run's cache goes too (cookies and storage went with the profile).
            webView.clearCache(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.isVerticalScrollBarEnabled = true
        webView.contentDescription = null
        if (isDebuggable) WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = Client(entry)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                entry.onEvent?.invoke(BrowserFeature.Action.TitleChanged(title ?: ""))
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100) entry.onEvent?.invoke(BrowserFeature.Action.LoadingChanged(false))
            }
        }
        // Downloads and anything the page can't show go to the system.
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            if (BrowserNavigationPolicy.handsToSystem(canShow = false, disposition = contentDisposition) &&
                BrowserLinkPolicy.isPublicWebPage(url)
            ) openInSystem(url)
        }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                entry.lastTouchX = event.x
                entry.lastTouchY = event.y
            }
            false
        }
        webView.setOnLongClickListener { view ->
            val result = (view as WebView).hitTestResult
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val url = result.extra ?: return@setOnLongClickListener false
                    entry.onLinkHeld?.invoke(url, entry.lastTouchX, entry.lastTouchY) ?: return@setOnLongClickListener false
                    true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val handler = entry.onLinkHeld ?: return@setOnLongClickListener false
                    val x = entry.lastTouchX
                    val y = entry.lastTouchY
                    val message = Handler(Looper.getMainLooper()) { msg: Message ->
                        msg.data.getString("url")?.takeIf { it.isNotEmpty() }?.let { handler(it, x, y) }
                        true
                    }.obtainMessage()
                    view.requestFocusNodeHref(message)
                    true
                }
                else -> false
            }
        }
        return webView
    }

    /** The browser's own profile, cleared once per run. Null where the WebView has no profiles. */
    private fun prepareProfile(): String? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            if (!clearedThisRun) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            return null
        }
        val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME)
        if (!clearedThisRun) {
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        }
        return PROFILE_NAME
    }

    private fun tearDown(webView: WebView, entry: Entry) {
        entry.onEvent = null
        entry.onLinkHeld = null
        webView.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        webView.stopLoading()
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        // A page sliding off the stack keeps drawing until its view goes
        // (the host's `onRelease` removes it then).
        if (!webView.isAttachedToWindow) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } else {
            webView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) = Unit
                override fun onViewDetachedFromWindow(v: android.view.View) {
                    webView.removeOnAttachStateChangeListener(this)
                    webView.destroy()
                }
            })
        }
        entry.webView = null
    }

    private fun openInSystem(url: String) {
        val opener = dependency(OpenURLClient)
        scope.launch { opener.open(url) }
    }

    private fun emitHistory(entry: Entry, view: WebView) {
        val back = view.canGoBack()
        val forward = view.canGoForward()
        if (back != entry.canGoBack) {
            entry.canGoBack = back
            entry.onEvent?.invoke(BrowserFeature.Action.CanGoBackChanged(back))
        }
        if (forward != entry.canGoForward) {
            entry.canGoForward = forward
            entry.onEvent?.invoke(BrowserFeature.Action.CanGoForwardChanged(forward))
        }
    }

    private inner class Client(private val entry: Entry) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val decision = BrowserNavigationPolicy.decide(
                request.url?.toString(),
                isMainFrame = request.isForMainFrame,
                isUserTap = request.hasGesture() && !request.isRedirect,
            )
            return when (decision) {
                BrowserNavigationPolicy.Decision.Allow -> false
                BrowserNavigationPolicy.Decision.Cancel -> true
                is BrowserNavigationPolicy.Decision.Pair -> {
                    entry.onEvent?.invoke(BrowserFeature.Action.PairingLinkTapped(decision.url))
                    true
                }
                is BrowserNavigationPolicy.Decision.OpenInSystem -> {
                    // A private host, a signed link, or another app's link the reader tapped.
                    openInSystem(decision.url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            entry.onEvent?.invoke(BrowserFeature.Action.LoadingChanged(true))
        }

        override fun onPageFinished(view: WebView, url: String?) {
            entry.onEvent?.invoke(BrowserFeature.Action.LoadingChanged(false))
            emitHistory(entry, view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) {
                entry.url = url
                entry.onEvent?.invoke(BrowserFeature.Action.Navigated(url, view.title ?: ""))
            }
            emitHistory(entry, view)
        }

        /** The renderer went away (memory pressure): the page loads again in a fresh web view. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.app.notice("Browser page renderer ended crashed=${detail.didCrash()}")
            if (entries[entry.id] !== entry || entry.webView !== view) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
            val parent = view.parent as? ViewGroup
            val index = parent?.indexOfChild(view) ?: -1
            val layoutParams = view.layoutParams
            parent?.removeView(view)
            view.destroy()
            val fresh = makeWebView(entry)
            entry.webView = fresh
            fresh.loadUrl(entry.url)
            if (parent != null) parent.addView(fresh, index, layoutParams)
            return true
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value?.takeIf { it != "null" })
        }
    }

    private fun snapshot(webView: WebView): ByteArray? {
        if (webView.width <= 0 || webView.height <= 0) return null
        val density = webView.resources.displayMetrics.density
        val width = (SNAPSHOT_WIDTH * density).roundToInt().coerceAtMost(webView.width)
        val scale = width.toFloat() / webView.width
        val height = (webView.height * scale).roundToInt().coerceAtLeast(1)
        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            webView.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            bitmap.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    companion object {
        /** Process-wide, like iOS's `.shared`; reducers reach it only through [PageReaderClient]. */
        val shared: BrowserWebViews by lazy { BrowserWebViews() }

        private const val PROFILE_NAME = "opencode-browser"
        /** iOS `WKSnapshotConfiguration.snapshotWidth`, in points → dp. */
        private const val SNAPSHOT_WIDTH = 390
        /** iOS `SessionPageStack.parallax` (`SettingsPresentation.parallax`). */
        private const val PARALLAX = 0.3f

        private val isDebuggable: Boolean
            get() = dependency(AppContext).applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        private const val PAUSE_MEDIA_SCRIPT =
            "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}});"

        /**
         * Visible text (no form fields, hidden, or scripted content), the selection unless it's
         * in a form field, and the scroll position. iOS runs it in its own content world; Android
         * WebView has none, so it runs wrapped in a function and leaves no globals behind.
         */
        val READER_SCRIPT = """
            const skip = 'input,textarea,select,option,button,script,style,noscript,template,svg,canvas,iframe,[contenteditable=""],[contenteditable="true"],[aria-hidden="true"],[type="password"]';
            const top = 0, bottom = window.innerHeight;
            const walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, {
              acceptNode(node) {
                const element = node.parentElement;
                if (!element || element.closest(skip) || !node.textContent.trim()) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
              }
            });
            const range = document.createRange();
            const parts = [];
            let lastBlock = null, length = 0;
            while (walker.nextNode()) {
              const node = walker.currentNode;
              range.selectNodeContents(node);
              const rect = range.getBoundingClientRect();
              if (rect.width === 0 || rect.height === 0 || rect.bottom <= top || rect.top >= bottom) continue;
              const style = getComputedStyle(node.parentElement);
              if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) continue;
              const block = node.parentElement.closest('p,li,h1,h2,h3,h4,h5,h6,pre,blockquote,td,th,dd,dt,figcaption,caption,label,div,section,article,main');
              const text = node.textContent.replace(/\s+/g, ' ').trim();
              if (parts.length) parts.push(block === lastBlock ? ' ' : '\n');
              parts.push(text);
              lastBlock = block;
              length += text.length;
              if (length > limit * 2) break;
            }
            let selection = '';
            const picked = window.getSelection();
            if (picked && picked.rangeCount && !picked.isCollapsed) {
              const anchor = picked.anchorNode && (picked.anchorNode.nodeType === 1 ? picked.anchorNode : picked.anchorNode.parentElement);
              const active = document.activeElement;
              if (!(anchor && anchor.closest(skip)) && !(active && active.matches('input,textarea,[contenteditable]'))) selection = picked.toString();
            }
            const scrollable = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
            return { url: location.href, title: document.title, visible: parts.join(''), selection, scroll: Math.min(1, Math.max(0, window.scrollY / scrollable)) };
        """.trimIndent()
    }
}
