package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.transcript.LinkMenuAction
import ai.opencode.feature.transcript.markdown.SessionLink
import ai.opencode.server.model.AgentFileReference
import android.net.Uri

/**
 * The private and web links rendered Markdown carries, as the renderer reads them: iOS
 * `SessionLink.id(from:)`/`title(from:)`/`url(id:title:)`/`shortID`,
 * `AgentFileReference(linkURL:)`, `LinkMenuAction.offers`/`actions(for:)` and `LinkMenuText`.
 * The link formats themselves live with the parser (`SessionLink`) and `:server`
 * (`AgentFileReference`).
 */
object MarkdownLinks {
    private fun uri(url: String): Uri? = runCatching { Uri.parse(url) }.getOrNull()

    fun sessionID(url: String): String? = SessionLink.id(url)

    fun sessionTitle(url: String): String? = SessionLink.title(url)

    fun sessionURL(id: String, title: String? = null): String = SessionLink.url(id, title)

    fun shortID(id: String): String = SessionLink.shortID(id)

    /** `AgentFileReference(linkURL:)`: `opencode-file://open?path=…&line=…`. */
    fun fileReference(url: String): AgentFileReference? = AgentFileReference.fromLinkURL(url)

    fun isWeb(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Only web links and session links get the hold menu; file links keep opening the viewer. */
    fun offersMenu(url: String): Boolean = sessionID(url) != null || isWeb(url)

    /**
     * What holding [url] offers, in order (iOS `LinkMenuAction.actions(for:experimental:)`).
     * Open in App Browser rides the Experimental in-app browser, off by default and not ported.
     */
    fun menuActions(url: String): List<LinkMenuAction> {
        if (sessionID(url) != null) return listOf(LinkMenuAction.CopySessionID)
        if (!isWeb(url)) return emptyList()
        val public = isPublicWebPage(url)
        return buildList {
            add(LinkMenuAction.OpenInSafari)
            if (public) add(LinkMenuAction.OpenInApp)
            add(LinkMenuAction.Copy)
            add(LinkMenuAction.Share)
        }.sortedBy { it.ordinal }
    }

    /** A session link also opens from TalkBack's actions, named for its session. */
    fun accessibilityActions(url: String): List<LinkMenuAction> =
        (if (sessionID(url) != null) listOf(LinkMenuAction.OpenSession) else emptyList()) + menuActions(url)

    fun displayHost(url: String): String =
        uri(url)?.host?.let { if (it.startsWith("www.")) it.drop(4) else it } ?: url

    /** One link: the menu's own titles. Several: each names its host (iOS `LinkMenuText.accessibilityName`). */
    fun accessibilityName(action: LinkMenuAction, url: String, among: List<String>): String {
        sessionID(url)?.let { id ->
            val name = sessionTitle(url) ?: shortID(id)
            return when (action) {
                LinkMenuAction.CopySessionID -> if (among.size > 1) "Copy ID of $name" else "Copy ID"
                else -> "Open session $name"
            }
        }
        if (among.size <= 1) return action.title.replace("…", "")
        val host = displayHost(url)
        return when (action) {
            LinkMenuAction.OpenInAppBrowser -> "Open $host in App Browser"
            LinkMenuAction.OpenInSafari -> "Open $host in Browser"
            LinkMenuAction.OpenInApp -> "Open $host in App"
            LinkMenuAction.Copy -> "Copy Link to $host"
            LinkMenuAction.Share -> "Share Link to $host"
            LinkMenuAction.OpenSession, LinkMenuAction.CopySessionID -> action.title
        }
    }

    /**
     * The registrable domain the icon service may be asked about; null for a private, tailnet
     * or credentialed link, which keeps a neutral glyph (iOS `SiteIconDomain.registrable`,
     * simplified: Media's port owns the full rule).
     */
    fun iconDomain(url: String): String? {
        val parsed = uri(url) ?: return null
        if (!parsed.userInfo.isNullOrEmpty()) return null
        val host = parsed.host?.lowercase() ?: return null
        if (!host.contains('.') || host == "localhost") return null
        if (host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".ts.net")) return null
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return null
        return if (host.startsWith("www.")) host.drop(4) else host
    }

    /** iOS `BrowserLinkPolicy.isPublicWebPage`, simplified to the same private-host rule. */
    fun isPublicWebPage(url: String): Boolean = isWeb(url) && iconDomain(url) != null
}

