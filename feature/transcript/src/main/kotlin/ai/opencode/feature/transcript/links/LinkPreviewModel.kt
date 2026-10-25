package ai.opencode.feature.transcript.links

import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.OpenCodePairingLink
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

// The pure value side of iOS `Media/LinkPreviews/` the transcript's link views read
// (`LinkPreview.swift`, `RichLinkPreview.swift`'s URL parsing, `LinkPreviewStore.swift`'s
// `LinkPreview`). The fetching side (store, client, transport, icon service, artwork decoding)
// is `:feature:media`'s, behind [TranscriptLinkPreviews]. URLs are strings (the transcript's
// actions carry strings); [WebURL] reads them the way `URLComponents` does for http(s).

/** An http(s) URL, parsed once (iOS `URL` + `URLComponents` for web links). */
internal object WebURL {
    fun parse(url: String): HttpUrl? = url.trim().toHttpUrlOrNull()

    /** iOS `url.path`: the decoded path, `/` when empty. */
    fun path(url: HttpUrl): String = "/" + url.pathSegments.joinToString("/")
}

/**
 * A normalised public link (iOS `LinkPreviewTarget`). First-party metadata sources are selected
 * by strict URL shapes in [RichLinkSource]; arbitrary web pages aren't fetched.
 */
@Immutable
sealed interface LinkPreviewTarget {
    data class XPost(val handle: String, val id: String) : LinkPreviewTarget
    data class Web(val webURL: String) : LinkPreviewTarget

    /** The normalised URL: the cache key and what a tap opens. */
    val url: String
        get() = when (this) {
            is XPost -> "https://x.com/$handle/status/$id"
            is Web -> webURL
        }

    val key: String get() = url

    val loadsMetadata: Boolean get() = this is XPost || RichLinkSource.from(url) != null

    companion object {
        /** A previewable link, normalised; null for anything private (iOS `init?(_:)`). */
        fun from(url: String): LinkPreviewTarget? {
            if (!LinkPreviewPolicy.isPreviewable(url)) return null
            val normalized = LinkPreviewPolicy.normalized(url) ?: return null
            val parsed = WebURL.parse(normalized) ?: return null
            if (parsed.host == "x.com") {
                xPost(WebURL.path(parsed))?.let { return XPost(it.first, it.second) }
            }
            return Web(normalized)
        }

        /** `/<handle>/status/<id>`, optionally followed by `/photo/1` and the like. */
        private fun xPost(path: String): Pair<String, String>? {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size < 3 || (parts[1] != "status" && parts[1] != "statuses")) return null
            val handle = parts[0]
            val id = parts[2]
            if (handle.length !in 1..15 || !handle.all { it.isAsciiLetterOrDigit() || it == '_' }) return null
            if (id.length !in 1..25 || !id.all { it in '0'..'9' }) return null
            return handle to id
        }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** Which links may ever be previewed, and the one form each is keyed by (iOS `LinkPreviewPolicy`). */
object LinkPreviewPolicy {
    /**
     * Public web pages only: never loopback, private or tailnet hosts, IP literals, custom
     * ports, embedded credentials, or URLs that carry a token or signature in their query.
     */
    fun isPreviewable(url: String): Boolean = isPublicWebURL(url)

    fun isPublicWebURL(
        url: String,
        allowsCustomPort: Boolean = false,
        allowsLoopback: Boolean = false,
        allowsTailnetImages: Boolean = false,
    ): Boolean {
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) return false
        val parsed = WebURL.parse(url) ?: return false
        val scheme = parsed.scheme
        if (scheme != "https" && scheme != "http") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.lowercase().trim('.')
        if (host.isEmpty()) return false
        val names = parsed.queryParameterNames
        if (allowsLoopback && isLoopback(host)) return true
        if (allowsTailnetImages && scheme == "https" && host.endsWith(".ts.net") && host.split('.').size == 4) {
            return names.none(::isCredential)
        }
        // HttpUrl folds an absent port into the scheme's default, which iOS allows as well.
        if (!allowsCustomPort && parsed.port != 443 && parsed.port != 80) return false
        if (isIPLiteral(host)) return false
        // A single label (`intranet`, `nas`) is a local name.
        if (!host.contains('.')) return false
        if (privateSuffixes.any { host == it.drop(1) || host.endsWith(it) }) return false
        return names.none(::isCredential)
    }

    /**
     * Lowercased scheme and host, X's other hosts folded into x.com, without the fragment,
     * trailing slash, or tracking and share parameters.
     */
    fun normalized(url: String): String? {
        val parsed = WebURL.parse(url) ?: return null
        var host = parsed.host.lowercase()
        if (host.endsWith('.')) host = host.dropLast(1)
        if (host in xHosts) host = "x.com"
        // HttpUrl splits the query on `&` exactly as its decoded names are indexed.
        val encoded = parsed.encodedQuery?.split('&').orEmpty()
        val kept = encoded.filterIndexed { index, _ ->
            val name = parsed.queryParameterName(index).lowercase()
            // X's share links add `s` (the platform) and `t` (a per-share tracker).
            !(name.startsWith("utm_") || name in trackingParameters || (host == "x.com" && (name == "s" || name == "t")))
        }
        val builder = parsed.newBuilder().host(host).fragment(null)
            .encodedQuery(if (kept.isEmpty()) null else kept.joinToString("&"))
        val path = parsed.encodedPath
        if (path.length > 1 && path.endsWith('/')) builder.encodedPath(path.dropLast(1))
        return runCatching { builder.build().toString() }.getOrNull()
    }

    fun displayHost(url: String): String {
        val host = WebURL.parse(url)?.host?.lowercase() ?: url
        return if (host.startsWith("www.")) host.drop(4) else host
    }

    /** iOS `BrowserLinkPolicy.isLoopback`. */
    internal fun isLoopback(host: String): Boolean =
        host.trim('.') in setOf("localhost", "127.0.0.1", "::1", "[::1]")

    private val xHosts = setOf("twitter.com", "www.twitter.com", "mobile.twitter.com", "www.x.com", "mobile.x.com")
    private val trackingParameters = setOf("fbclid", "gclid", "dclid", "igshid", "mc_cid", "mc_eid", "ref_src", "ref_url", "si")
    private val privateSuffixes = listOf(
        ".localhost", ".local", ".internal", ".intranet", ".lan", ".home", ".corp", ".home.arpa",
        ".test", ".invalid", ".ts.net", ".onion",
    )

    private fun isIPLiteral(host: String): Boolean {
        if (host.contains(':') || host.startsWith('[')) return true
        val labels = host.split('.').filter { it.isNotEmpty() }
        return labels.isNotEmpty() && labels.all { label -> label.all { it.isDigit() } }
    }

    private val credentialNames = setOf(
        "key", "apikey", "api_key", "auth", "authorization", "code", "sig", "sid", "session", "sessionid",
        "jwt", "otp", "ticket", "pass", "pwd", "credential", "credentials", "x-amz-credential", "x-goog-credential",
    )

    private fun isCredential(name: String): Boolean {
        val lower = name.lowercase()
        return lower in credentialNames || listOf("token", "signature", "secret", "password").any { lower.contains(it) }
    }
}

/**
 * Bare URLs in plain text, in order (the `NSDataDetector(types: .link)` matches the iOS
 * extractors read). Only `scheme://` links are found; trailing sentence punctuation and an
 * unbalanced closing bracket stay outside the link, as the detector leaves them.
 */
internal object LinkDetector {
    data class Match(val url: String, val start: Int, val end: Int)

    private val pattern = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s<>"“”‘’「」`]+""")

    fun matches(text: String): List<Match> = pattern.findAll(text).mapNotNull { found ->
        var end = found.range.last + 1
        while (end > found.range.first) {
            val c = text[end - 1]
            val candidate = text.substring(found.range.first, end)
            val trim = when (c) {
                '.', ',', ':', ';', '!', '?', '\'' -> true
                ')' -> candidate.count { it == '(' } < candidate.count { it == ')' }
                ']' -> candidate.count { it == '[' } < candidate.count { it == ']' }
                else -> false
            }
            if (!trim) break
            end--
        }
        val url = text.substring(found.range.first, end)
        if (!url.substringAfter("://").any { it.isLetterOrDigit() }) null else Match(url, found.range.first, end)
    }.toList()
}

/** The links a reply names, in order, once each (iOS `LinkPreviewExtractor`). */
object LinkPreviewExtractor {
    fun targets(blocks: List<OpenCodeMarkdownBlock>, limit: Int = 6): List<LinkPreviewTarget> {
        val seen = HashSet<String>()
        val result = ArrayList<LinkPreviewTarget>()
        fun add(url: String) {
            if (result.size >= limit) return
            val target = LinkPreviewTarget.from(url) ?: return
            if (seen.add(target.key)) result.add(target)
        }
        for (block in blocks) {
            // Appended PR blocks are derived by an older prefix matcher. Read the original prose
            // URL only: a derived block may have lost a credential query or matched just a
            // prefix of a longer URL.
            for (text in texts(block.kind)) {
                // In reading order: a run's link, or bare URLs the Markdown left as its text. A
                // URL in inline code is code, not a link.
                for (run in text.runs) {
                    val link = run.link
                    if (link != null) {
                        add(link)
                        continue
                    }
                    if (run.code) continue
                    if (!run.text.contains("://")) continue
                    for (match in LinkDetector.matches(run.text)) add(match.url)
                }
            }
        }
        return result
    }

    internal fun texts(kind: OpenCodeMarkdownBlock.Kind): List<InlineText> = when (kind) {
        is OpenCodeMarkdownBlock.Kind.Paragraph -> listOf(kind.text)
        is OpenCodeMarkdownBlock.Kind.NumberedItem -> listOf(kind.text)
        is OpenCodeMarkdownBlock.Kind.ListItem -> listOf(kind.text)
        is OpenCodeMarkdownBlock.Kind.Blockquote -> listOf(kind.text)
        is OpenCodeMarkdownBlock.Kind.Heading -> listOf(kind.text)
        is OpenCodeMarkdownBlock.Kind.Table -> kind.header + kind.rows.flatten()
        else -> emptyList()
    }
}

/**
 * What is known about a link beyond its URL (iOS `LinkPreviewMetadata`). Posts come from X's
 * oEmbed; pages are the seam for metadata the server supplies with a reply.
 */
@Immutable
data class LinkPreviewMetadata(
    val kind: Kind,
    /** A post's author, or a page's title. */
    val title: String,
    /** A post's handle, without the @. */
    val handle: String? = null,
    /** A post's text, or a page's description. */
    val text: String? = null,
    /** A page's site name. */
    val site: String? = null,
    /** The site's icon as a `data:image/…;base64,` URL; it takes the place of the icon service's. */
    val icon: String? = null,
    val github: GitHubPreview? = null,
) {
    enum class Kind { Post, Page, Github, Video }
}

/** iOS `LinkPreviewResult`. */
@Immutable
sealed interface LinkPreviewResult {
    data class Loaded(val metadata: LinkPreviewMetadata) : LinkPreviewResult
    /** Nothing to show beyond the URL (a deleted or protected post). */
    data object Unavailable : LinkPreviewResult
    /** Not fetched now (offline, data saver); a later view tries again. */
    data object Deferred : LinkPreviewResult
}

/**
 * A site's icon, ready to draw (iOS `SiteIcon`): square, at most [maximumPixels] a side. Equal
 * when their [key]s are (iOS compares the PNG bytes); the media side picks the key.
 */
@Immutable
class SiteIcon(val image: ImageBitmap, val tone: Tone?, val key: Any = image) {
    /** A mark that would vanish on one appearance's card: a dark glyph on transparency (GitHub's) in dark mode, or a light one in light mode. */
    enum class Tone { Dark, Light }

    override fun equals(other: Any?): Boolean = other is SiteIcon && other.key == key
    override fun hashCode(): Int = key.hashCode()

    companion object {
        /** Smaller icons (a 16 px favicon) are blurry at the card's size. */
        const val minimumPixels = 32
        /** The slot is 28 pt at most, at 3x. */
        const val maximumPixels = 96
    }
}

/** A decoded immutable thumbnail, prepared once when metadata arrives (iOS `LinkPreviewBitmap`). */
@Immutable
class LinkPreviewBitmap(val image: ImageBitmap, val key: Any = image) {
    override fun equals(other: Any?): Boolean = other is LinkPreviewBitmap && other.key == key
    override fun hashCode(): Int = key.hashCode()
}

/** A link as a card shows it right now (iOS `LinkPreview`). */
@Immutable
data class LinkPreview(
    val target: LinkPreviewTarget,
    val display: Display,
    /** The site's icon for a plain or page card; the globe until it arrives, and when there is none. */
    val icon: SiteIcon? = null,
    val image: LinkPreviewBitmap? = null,
) {
    @Immutable
    sealed interface Display {
        /** Rich metadata (X, GitHub, YouTube) is on its way. */
        data object Pending : Display
        data class Post(val author: String, val handle: String?, val text: String?) : Display
        data class Page(val title: String, val site: String?, val text: String?) : Display
        data class Github(val title: String, val details: GitHubPreview, val text: String?) : Display
        data class Video(val title: String, val author: String?, val site: String?) : Display
        /** The domain and path alone. */
        data object Plain : Display
    }

    val id: String get() = target.key
    val url: String get() = target.url
    val domain: String get() = LinkPreviewPolicy.displayHost(url)
    val path: String get() = WebURL.parse(url)?.let(WebURL::path)?.takeIf { it.length > 1 } ?: ""
    val loadsMetadata: Boolean get() = target.loadsMetadata
    val richSource: RichLinkSource? get() = RichLinkSource.from(url)
    val isXPost: Boolean get() = target is LinkPreviewTarget.XPost

    /** The author's handle: oEmbed's once loaded, else the URL's, except for `x.com/i/status/…`, whose `i` is no one's. */
    val handle: String?
        get() {
            (display as? Display.Post)?.handle?.let { return it }
            val target = target
            if (target is LinkPreviewTarget.XPost && target.handle != "i") return target.handle
            return null
        }

    /** What the link shows, in reading order: TalkBack reads it as seen. */
    val accessibilityLabel: String
        get() {
            val parts: List<String?> = when (val display = display) {
                is Display.Post -> listOf(display.author, display.text, domain)
                is Display.Page -> listOf(display.title, display.site ?: domain)
                is Display.Github -> {
                    val details = display.details
                    when (details.kind) {
                        // Its title is its caption: say it once, then its stars and language.
                        GitHubLink.Kind.Repository -> listOf(details.caption, repositoryFacts(details))
                        GitHubLink.Kind.Issue, GitHubLink.Kind.PullRequest -> listOf(details.caption, details.state?.rawValue, display.title)
                        GitHubLink.Kind.Commit -> listOf(
                            details.caption,
                            display.title,
                            details.additions?.let { a -> details.deletions?.let { "+$a −$it" } } ?: details.author,
                        )
                    }
                }
                is Display.Video -> listOf(display.title, display.site ?: domain, "Video")
                Display.Pending -> when (val source = richSource) {
                    is RichLinkSource.Github -> listOf(source.link.caption, kindName(source.link.kind), domain)
                    is RichLinkSource.Youtube -> listOf("YouTube", "Video")
                    null -> listOf(handle?.let { "@$it" }, "Post", domain)
                }
                Display.Plain -> when {
                    isXPost -> listOf("Post unavailable", handle?.let { "@$it" }, domain)
                    else -> readableTitle(url)?.let { listOf(it, domain) } ?: listOf(domain)
                }
            }
            return parts.filterNotNull().joinToString(", ")
        }

    companion object {
        /** iOS `init(target:result:icon:)`: what a result shows. */
        fun from(target: LinkPreviewTarget, result: LinkPreviewResult?, icon: SiteIcon? = null): LinkPreview {
            val metadata = (result as? LinkPreviewResult.Loaded)?.metadata
            val display = when {
                metadata != null && metadata.kind == LinkPreviewMetadata.Kind.Github && metadata.github != null ->
                    Display.Github(metadata.title, metadata.github, metadata.text)
                metadata != null && metadata.kind == LinkPreviewMetadata.Kind.Video ->
                    Display.Video(metadata.title, author = metadata.text, site = metadata.site)
                metadata != null && metadata.kind == LinkPreviewMetadata.Kind.Post ->
                    Display.Post(metadata.title, metadata.handle, metadata.text)
                metadata != null -> Display.Page(metadata.title, metadata.site, metadata.text)
                result == null && target.loadsMetadata -> Display.Pending
                else -> Display.Plain
            }
            return LinkPreview(target, display, icon)
        }

        /** What a GitHub link is, before its metadata arrives. */
        fun kindName(kind: GitHubLink.Kind): String = when (kind) {
            GitHubLink.Kind.Repository -> "Repository"
            GitHubLink.Kind.Issue -> "Issue"
            GitHubLink.Kind.PullRequest -> "Pull request"
            GitHubLink.Kind.Commit -> "Commit"
        }

        /** A repository's stars and language, as its facts read. */
        fun repositoryFacts(details: GitHubPreview): String? {
            val parts = listOfNotNull(details.stars?.let { "★ ${compactName(it)}" }, details.language)
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        /** Swift's `.number.notation(.compactName)` in English: 950, 1.2K, 15K, 1.5M. */
        internal fun compactName(value: Int): String {
            val units = listOf("", "K", "M", "B", "T")
            var scaled = value.toDouble()
            var unit = 0
            while (kotlin.math.abs(scaled) >= 1000 && unit < units.size - 1) {
                scaled /= 1000
                unit++
            }
            if (unit == 0) return value.toString()
            var rounded = if (kotlin.math.abs(scaled) < 10) (scaled * 10).roundToLong() / 10.0 else scaled.roundToLong().toDouble()
            if (kotlin.math.abs(rounded) >= 1000 && unit < units.size - 1) {
                rounded /= 1000
                unit++
            }
            val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
            return text + units[unit]
        }

        /** Swift's `Int.formatted()`: grouped digits in the reader's locale. */
        internal fun formatted(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

        private val extension = Regex("""\.(html?|php|aspx?|md)$""", RegexOption.IGNORE_CASE)
        private val separators = Regex("""[-_+.]+""")

        /**
         * A link without metadata, named from its path: `/guides/background-transfers` reads
         * “Background transfers”, `/incidents/42` “Incidents 42”, an index page its folder.
         * Null when the path names nothing readable (a hash, an ID).
         */
        fun readableTitle(url: String): String? {
            val parsed = WebURL.parse(url) ?: return null
            val parts = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            fun words(part: String): String = part.replace(extension, "").replace(separators, " ").trim()
            while (parts.isNotEmpty() && words(parts.last()).lowercase() in setOf("index", "readme", "")) parts.removeAt(parts.lastIndex)
            val last = parts.lastOrNull() ?: return null
            var title = words(last)
            if (title.isNotEmpty() && title.all { it.isDigit() } && parts.size > 1) {
                title = (words(parts[parts.size - 2]) + " " + title).trim()
            }
            val letters = title.count { it.isLetter() }
            var ideographic = false
            var offset = 0
            while (offset < title.length) {
                val cp = title.codePointAt(offset)
                if (Character.isIdeographic(cp)) ideographic = true
                offset += Character.charCount(cp)
            }
            // A single token mixing letters and digits is a hash or an ID, not a name.
            val isToken = !title.contains(' ') && title.any { it.isDigit() }
            if (letters < (if (ideographic) 1 else 3) || title.codePointCount(0, title.length) > 80 || isToken) return null
            return title.take(1).uppercase() + title.drop(1)
        }
    }
}

/**
 * Only service-owned identifiers leave the phone (iOS `RichLinkSource`, URL shapes only; the
 * requests and response parsing are `:feature:media`'s).
 */
@Immutable
sealed interface RichLinkSource {
    data class Github(val link: GitHubLink) : RichLinkSource
    data class Youtube(val id: String) : RichLinkSource

    companion object {
        fun from(url: String): RichLinkSource? {
            if (!LinkPreviewPolicy.isPreviewable(url)) return null
            GitHubLink.from(url)?.let { return Github(it) }
            val parsed = WebURL.parse(url) ?: return null
            val parts = parsed.pathSegments.filter { it.isNotEmpty() }
            val id: String? = when (parsed.host.lowercase()) {
                "youtu.be" -> if (parts.size == 1) parts[0] else null
                "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
                    parts == listOf("watch") -> {
                        val values = (0 until parsed.querySize).filter { parsed.queryParameterName(it) == "v" }
                        if (values.size == 1) parsed.queryParameterValue(values[0]) else null
                    }
                    parts.size == 2 && parts[0] in setOf("shorts", "embed", "live") -> parts[1]
                    else -> null
                }
                else -> null
            }
            if (id == null || id.length != 11 || !id.all { identifierChar(it) || it == '_' }) return null
            return Youtube(id)
        }

        internal fun identifierChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-'
    }
}

/** A GitHub repository, issue, pull request or commit URL (iOS `GitHubLink`, its URL shape). */
@Immutable
data class GitHubLink(val repository: String, val kind: Kind, val reference: String? = null) {
    enum class Kind { Repository, Issue, PullRequest, Commit }

    /** What the URL alone says: `acme/widget #1251`, before any metadata. */
    val caption: String get() = GitHubPreview(repository = repository, kind = kind, reference = reference).caption

    companion object {
        private val reservedOwners = setOf(
            "about", "codespaces", "collections", "customer-stories", "discussions", "enterprise",
            "events", "explore", "features", "issues", "login", "marketplace", "new", "notifications",
            "orgs", "organizations", "pricing", "pulls", "readme", "search", "security", "settings",
            "signup", "site", "sponsors", "team", "topics", "trending", "users",
        )

        fun from(url: String): GitHubLink? {
            if (!LinkPreviewPolicy.isPreviewable(url)) return null
            val parsed = WebURL.parse(url) ?: return null
            if (parsed.host.lowercase() != "github.com" || parsed.encodedPath.contains('%')) return null
            val parts = parsed.pathSegments
            if (parts.size != 2 && parts.size != 4) return null
            val owner = parts[0]
            val name = parts[1]
            if (owner.length !in 1..39 || !owner.all(RichLinkSource::identifierChar)) return null
            if (owner.lowercase() in reservedOwners) return null
            if (name.length !in 1..100 || name == "." || name == "..") return null
            if (!name.all { RichLinkSource.identifierChar(it) || it == '_' || it == '.' }) return null
            val repository = "$owner/$name"
            if (parts.size == 2) return GitHubLink(repository, Kind.Repository)
            val reference = parts[3]
            val kind = when (parts[2]) {
                "issues", "pull" -> {
                    if (reference.length !in 1..10 || !reference.all { it in '0'..'9' }) return null
                    val number = reference.toULongOrNull() ?: return null
                    if (number == 0UL) return null
                    if (parts[2] == "pull") Kind.PullRequest else Kind.Issue
                }
                "commit" -> {
                    if (reference.length !in 7..40 || !reference.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    Kind.Commit
                }
                else -> return null
            }
            return GitHubLink(repository, kind, reference)
        }
    }
}

/** What GitHub's API said about a link (iOS `GitHubPreview`). */
@Immutable
data class GitHubPreview(
    val repository: String,
    val kind: GitHubLink.Kind,
    val reference: String? = null,
    val state: State? = null,
    val author: String? = null,
    val labels: List<String>? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val comments: Int? = null,
    val stars: Int? = null,
    val language: String? = null,
) {
    enum class State(val rawValue: String) {
        Open("Open"), Closed("Closed"), Merged("Merged"), Draft("Draft"), Completed("Completed"), NotPlanned("Not planned"),
    }

    val caption: String
        get() {
            val reference = reference ?: return repository
            return repository + if (kind == GitHubLink.Kind.Commit) " · " + reference.take(7) else " #$reference"
        }
}
