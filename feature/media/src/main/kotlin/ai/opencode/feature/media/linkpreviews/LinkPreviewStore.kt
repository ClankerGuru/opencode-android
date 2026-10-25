package ai.opencode.feature.media

import ai.opencode.core.dependencies.dependency
import ai.opencode.server.retry.NetworkPathMonitor
import android.icu.number.Notation
import android.icu.number.NumberFormatter
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A link as a card shows it right now (iOS `LinkPreview`). */
data class LinkPreview(
    val target: LinkPreviewTarget,
    val display: Display,
    /** The site's icon for a plain or page card; the globe until it arrives, and when there is none. */
    val icon: SiteIcon? = null,
    val image: LinkPreviewBitmap? = null,
) {
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
    /** iOS `url.path`: decoded, empty for the root. */
    val path: String
        get() {
            val path = url.toHttpUrlOrNull()?.let { "/" + it.pathSegments.joinToString("/") } ?: return ""
            return if (path.length > 1) path else ""
        }
    val loadsMetadata: Boolean get() = target.loadsMetadata
    val richSource: RichLinkSource? get() = RichLinkSource.of(url)

    val isXPost: Boolean get() = target is LinkPreviewTarget.XPost

    /** The author's handle: oEmbed's once loaded, else the URL's, except for `x.com/i/status/…`, whose `i` is no one's. */
    val handle: String?
        get() {
            (display as? Display.Post)?.handle?.let { return it }
            val post = target as? LinkPreviewTarget.XPost ?: return null
            return post.handle.takeIf { it != "i" }
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
                        GitHubLink.Kind.Issue, GitHubLink.Kind.PullRequest ->
                            listOf(details.caption, details.state?.rawValue, display.title)
                        GitHubLink.Kind.Commit -> {
                            val additions = details.additions
                            val deletions = details.deletions
                            val diff = if (additions != null && deletions != null) "+$additions −$deletions" else details.author
                            listOf(details.caption, display.title, diff)
                        }
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
        fun of(target: LinkPreviewTarget, result: LinkPreviewResult?, icon: SiteIcon? = null): LinkPreview {
            val metadata = (result as? LinkPreviewResult.Loaded)?.metadata
            val display = when {
                metadata != null && metadata.kind == LinkPreviewMetadata.Kind.Github && metadata.github != null ->
                    Display.Github(metadata.title, metadata.github, metadata.text)
                metadata != null && metadata.kind == LinkPreviewMetadata.Kind.Video ->
                    Display.Video(metadata.title, metadata.text, metadata.site)
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
            val stars = details.stars?.let {
                "★ " + NumberFormatter.withLocale(Locale.getDefault()).notation(Notation.compactShort()).format(it).toString()
            }
            val parts = listOfNotNull(stars, details.language)
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        /** Swift's `Int.formatted()`: grouped digits in the reader's locale (a GitHub issue's comments). */
        fun formatted(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

        /**
         * A link without metadata, named from its path: `/guides/background-transfers` reads
         * “Background transfers”, `/incidents/42` “Incidents 42”, an index page its folder.
         * Null when the path names nothing readable (a hash, an ID).
         */
        fun readableTitle(url: String): String? {
            val parts = url.toHttpUrlOrNull()?.pathSegments?.toMutableList() ?: return null
            fun words(part: String): String =
                part.replace(Regex("\\.(html?|php|aspx?|md)$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[-_+.]+"), " ")
                    .trim(' ', '\t')
            while (parts.isNotEmpty() && words(parts.last()).lowercase() in setOf("index", "readme", "")) parts.removeAt(parts.lastIndex)
            val last = parts.lastOrNull() ?: return null
            var title = words(last)
            if (title.all { it.isDigit() } && parts.size > 1) {
                title = (words(parts[parts.size - 2]) + " " + title).trim(' ', '\t')
            }
            val letters = title.count { it.isLetter() }
            val ideographic = title.codePoints().anyMatch(Character::isIdeographic)
            // A single token mixing letters and digits is a hash or an ID, not a name.
            val isToken = !title.contains(" ") && title.any { it.isDigit() }
            val length = title.codePointCount(0, title.length)
            if (letters < (if (ideographic) 1 else 3) || length > 80 || isToken) return null
            return title.take(1).uppercase() + title.drop(1)
        }
    }
}

/**
 * The transcript list's link previews (iOS `LinkPreviewStore`): which links each finished reply
 * names, what is known about them, and which rows to reload when an answer arrives. Not
 * observed: a row redraws by being reloaded ([onArrival]) with a bumped [revision].
 *
 * Main-thread confined, as iOS's `@MainActor`; [close] cancels its work (iOS `deinit`).
 */
class LinkPreviewStore(
    client: LinkPreviewClient? = null,
    private val now: () -> Long = System::currentTimeMillis,
    pathUpdates: () -> Flow<NetworkPathMonitor.Snapshot> = { NetworkPathMonitor.shared?.updates() ?: emptyFlow() },
) {
    /** Reloads rows (their list identities) once their metadata arrives. */
    var onArrival: ((List<String>) -> Unit)? = null

    private val client: LinkPreviewClient = client ?: dependency(LinkPreviewClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = HashMap<String, LinkPreviewResult>()
    private val resultExpires = HashMap<String, Long>()
    /** A key present with null: decoded, no picture. */
    private val bitmaps = HashMap<String, LinkPreviewBitmap?>()
    /** Metadata the server supplied with a reply (not sent yet: the seam). */
    private val supplied = HashMap<String, LinkPreviewMetadata>()
    /** Icons that came with supplied metadata, decoded once. */
    private val suppliedIcons = HashMap<String, SiteIcon>()
    private val waiting = HashMap<String, MutableSet<String>>()
    /** Site icons by registrable domain, and the rows waiting for each. */
    private val icons = HashMap<String, SiteIconResult>()
    private val waitingIcons = HashMap<String, MutableSet<String>>()
    private val deferredTargets = HashMap<String, Pair<LinkPreviewTarget, Set<String>>>()
    private val deferredOrder = ArrayDeque<String>()
    private val targetPathRetries = HashMap<String, Int>()
    private val deferredIcons = HashMap<String, MutableSet<String>>()
    private val deferredIconOrder = ArrayDeque<String>()
    private val iconRetryExpires = HashMap<String, Long>()
    private val iconPathRetries = HashMap<String, Int>()
    /** Bumped for a row when its metadata arrives, so a reloaded row redraws. */
    private val revisions = HashMap<String, Int>()
    private val targetsByEntry = HashMap<String, Pair<List<LinkTextBlock>, List<LinkPreviewTarget>>>()
    /** Per-row motion state (iOS `LinkPreviewRowPresentation`, owned by the transcript). */
    private val presentations = HashMap<String, Any>()

    init {
        scope.launch {
            var wasConstrained = false
            pathUpdates().collect { snapshot ->
                val constrained = !snapshot.isSatisfied || snapshot.isConstrained
                if (!constrained && wasConstrained) retryDeferredOnUnconstrainedPath()
                wasConstrained = constrained
            }
        }
    }

    /** iOS `deinit`: stops observing the path and drops pending loads. */
    fun close() = scope.cancel()

    /**
     * The row's presentation, made once by [make]. iOS types it `LinkPreviewRowPresentation`; that
     * lives in `:feature:transcript`, above media, so the transcript passes its own.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> presentation(row: String, make: () -> T): T = presentations.getOrPut(row, make) as T

    private fun isLoaded(result: LinkPreviewResult?) = result is LinkPreviewResult.Loaded

    /** The reply's links, extracted once per finished reply. */
    fun targets(blocks: List<LinkTextBlock>, entryID: String): List<LinkPreviewTarget> {
        targetsByEntry[entryID]?.let { (cachedBlocks, targets) -> if (cachedBlocks == blocks) return targets }
        val targets = LinkPreviewExtractor.targets(blocks)
        targetsByEntry[entryID] = blocks to targets
        return targets
    }

    fun preview(target: LinkPreviewTarget): LinkPreview {
        supplied[target.key]?.let { metadata ->
            return render(target, LinkPreviewResult.Loaded(metadata), suppliedIcons[target.key] ?: icon(target))
        }
        val key = target.key
        val result: LinkPreviewResult? = if (target.loadsMetadata) {
            val cached = client.cached(target)
            val existing = results[key]
            when {
                cached != null -> {
                    if (existing != cached) bitmaps.remove(key)
                    results[key] = cached
                    cached
                }
                existing != null -> existing
                else -> client.stale(target)?.let { stale ->
                    LinkPreviewResult.Loaded(stale).also { results[key] = it }
                }
            }
        } else {
            LinkPreviewResult.Unavailable
        }
        return render(target, result, icon(target))
    }

    private fun render(target: LinkPreviewTarget, result: LinkPreviewResult?, icon: SiteIcon?): LinkPreview {
        if (!bitmaps.containsKey(target.key) && result is LinkPreviewResult.Loaded) {
            bitmaps[target.key] = result.metadata.image?.let(LinkPreviewBitmap::of)
        }
        val preview = LinkPreview.of(target, result, icon)
        return if (result is LinkPreviewResult.Loaded) preview.copy(image = bitmaps[target.key]) else preview
    }

    /** A web link's icon, if one is known for its registrable domain. Remembered once drawn, so [appeared] reloads only a row drawn without it. */
    private fun icon(target: LinkPreviewTarget): SiteIcon? {
        if (target !is LinkPreviewTarget.Web) return null
        val domain = SiteIconDomain.registrable(target.url) ?: return null
        return icon(domain = domain)
    }

    private fun icon(domain: String): SiteIcon? {
        if (icons[domain] == null) {
            client.cachedIcon(domain)?.takeIf { it != SiteIconResult.Deferred }?.let { icons[domain] = it }
        }
        return (icons[domain] as? SiteIconResult.Icon)?.icon
    }

    /** A link's site icon in reply text (Link Icons), from the same caches as the cards: null for a link [LinkPreviewPolicy] refuses, and until it arrives. */
    fun inlineIcon(url: String): SiteIcon? {
        val target = LinkPreviewTarget.of(url) ?: return null
        suppliedIcons[target.key]?.let { return it }
        val domain = SiteIconDomain.registrable(target.url) ?: return null
        return icon(domain = domain)
    }

    /** Links drawn in [row]'s text: each domain's icon is asked for once, as for the cards, and its arrival reloads the row. */
    fun inlineLinksAppeared(urls: List<String>, row: String) {
        for (url in urls) {
            val target = LinkPreviewTarget.of(url) ?: continue
            appearedIcon(target, row)
        }
    }

    /**
     * A link came into view in [row]: the first view of a rich link fetches it, and of a web link
     * its site's icon. A post draws X's mark itself.
     */
    fun appeared(target: LinkPreviewTarget, row: String) {
        val key = target.key
        val currentTime = now()
        // An expired failure keeps showing as plain while it's retried below; only an answer
        // that changes something reloads the row.
        if (target is LinkPreviewTarget.Web) appearedIcon(target, row)
        if (!target.loadsMetadata || supplied[key] != null) return
        client.cached(target)?.let { cached ->
            val changed = results[key] != cached
            results[key] = cached
            // Another row loaded it after this one was drawn: redraw this one.
            if (changed) {
                bitmaps.remove(key)
                arrived(setOf(row))
            }
            // Keep the loader's expiry authoritative rather than pinning this cached value for
            // the lifetime of an open transcript.
            return
        }
        if (results[key] == null) client.stale(target)?.let { results[key] = LinkPreviewResult.Loaded(it) }
        val expiry = resultExpires[key]
        if (results[key] != null && expiry != null && expiry > currentTime) {
            if (results[key] == LinkPreviewResult.Deferred || deferredTargets[key] != null) {
                recordDeferredTarget(target, setOf(row))
            }
            return
        }
        val isLoading = waiting[key] != null
        waiting.getOrPut(key) { mutableSetOf() }.add(row)
        if (isLoading) return
        scope.launch {
            val result = client.load(target)
            val rows = waiting.remove(key) ?: emptySet()
            if (result == LinkPreviewResult.Deferred) {
                recordDeferredTarget(target, rows)
            } else {
                deferredTargets.remove(key)
                targetPathRetries.remove(key)
            }
            if (result == LinkPreviewResult.Deferred && isLoaded(results[key])) {
                // Stale-while-revalidate: keep the last good rich metadata when a refresh fails
                // while offline, rate-limited, or on a 5xx response.
                resultExpires[key] = now() + RETRY_FLOOR
                return@launch
            }
            val changed = results[key] != result
            results[key] = result
            if (changed) bitmaps.remove(key)
            // A new appearance may retry a transient failure. Never schedule a retry from the
            // arrival reload itself or poll an offscreen card.
            val lifetime = when {
                result == LinkPreviewResult.Deferred -> RETRY_FLOOR
                RichLinkSource.of(target.url) == null -> LinkPreviewLoader.LOADED_LIFETIME
                else -> LinkPreviewLoader.RICH_LIFETIME
            }
            resultExpires[key] = now() + lifetime
            if (changed) arrived(rows)
        }
    }

    /** Reloads rows whose links changed; the bumped revision redraws them. */
    private fun arrived(rows: Set<String>) {
        for (row in rows) revisions[row] = (revisions[row] ?: 0) + 1
        onArrival?.invoke(rows.sorted())
    }

    /** One request per domain however many cards show it. Only an icon that arrives reloads its rows; a missing one leaves the globe in place. */
    private fun appearedIcon(target: LinkPreviewTarget, row: String) {
        if (suppliedIcons[target.key] != null) return
        val domain = SiteIconDomain.registrable(target.url) ?: return
        if (icons[domain] != null) return
        val cached = client.cachedIcon(domain)
        if (cached != null && cached != SiteIconResult.Deferred) {
            icons[domain] = cached
            deferredIcons.remove(domain)
            iconRetryExpires.remove(domain)
            // Cached since this row was drawn: show it.
            if (cached is SiteIconResult.Icon) arrived(setOf(row))
            return
        }
        val expiry = iconRetryExpires[domain]
        if (expiry != null && expiry > now()) {
            recordDeferredIcon(domain, setOf(row))
            return
        }
        loadIcon(domain, setOf(row))
    }

    private fun loadIcon(domain: String, rows: Set<String>) {
        val isLoading = waitingIcons[domain] != null
        waitingIcons.getOrPut(domain) { mutableSetOf() }.addAll(rows)
        if (isLoading) return
        scope.launch {
            val result = client.loadIcon(domain)
            val waitingRows = waitingIcons.remove(domain) ?: emptySet()
            if (result == SiteIconResult.Deferred) {
                iconRetryExpires[domain] = now() + RETRY_FLOOR
                recordDeferredIcon(domain, waitingRows)
                return@launch
            }
            deferredIcons.remove(domain)
            iconRetryExpires.remove(domain)
            iconPathRetries.remove(domain)
            icons[domain] = result
            if (result is SiteIconResult.Icon) arrived(waitingRows)
        }
    }

    private fun recordDeferredTarget(target: LinkPreviewTarget, rows: Set<String>) {
        if (rows.isEmpty()) return
        val key = target.key
        if (deferredTargets[key] == null) {
            deferredOrder.addLast(key)
            while (deferredOrder.size > MAXIMUM_DEFERRED_ENTRIES) deferredTargets.remove(deferredOrder.removeFirst())
        }
        deferredTargets[key] = target to ((deferredTargets[key]?.second ?: emptySet()) + rows)
    }

    private fun recordDeferredIcon(domain: String, rows: Set<String>) {
        if (rows.isEmpty()) return
        if (deferredIcons[domain] == null) {
            deferredIconOrder.addLast(domain)
            while (deferredIconOrder.size > MAXIMUM_DEFERRED_ENTRIES) deferredIcons.remove(deferredIconOrder.removeFirst())
        }
        deferredIcons.getOrPut(domain) { mutableSetOf() }.addAll(rows)
    }

    /**
     * Retries visible/appeared deferred previews and site icons (bounded by
     * [MAXIMUM_PATH_RETRIES]) when the network path becomes unconstrained.
     */
    fun retryDeferredOnUnconstrainedPath() {
        for ((key, entry) in deferredTargets.toMap()) {
            val attempts = targetPathRetries[key] ?: 0
            if (attempts >= MAXIMUM_PATH_RETRIES) {
                deferredTargets.remove(key)
                continue
            }
            targetPathRetries[key] = attempts + 1
            resultExpires.remove(key)
            for (row in entry.second) appeared(entry.first, row)
        }
        for ((domain, rows) in deferredIcons.mapValues { it.value.toSet() }) {
            val attempts = iconPathRetries[domain] ?: 0
            if (attempts >= MAXIMUM_PATH_RETRIES) {
                deferredIcons.remove(domain)
                continue
            }
            iconPathRetries[domain] = attempts + 1
            iconRetryExpires.remove(domain)
            loadIcon(domain, rows)
        }
    }

    fun revision(row: String): Int = revisions[row] ?: 0

    /**
     * Metadata the server sent with a reply (`metadata.links`, proposed): shown without any fetch
     * from the phone. Its icon, if usable, is the card's, and the icon service isn't asked.
     */
    fun supply(metadata: LinkPreviewMetadata, url: String) {
        val target = LinkPreviewTarget.of(url) ?: return
        supplied[target.key] = metadata
        bitmaps.remove(target.key)
        val icon = metadata.icon?.let(SiteIcon::ofDataURL)
        if (icon != null) suppliedIcons[target.key] = icon else suppliedIcons.remove(target.key)
    }

    companion object {
        const val MAXIMUM_DEFERRED_ENTRIES = 64
        const val MAXIMUM_PATH_RETRIES = 3
        /** A failed refresh waits at least 30 s, and is retried only by a later appearance. */
        const val RETRY_FLOOR: Long = 30_000
    }
}

/** iOS `EnvironmentValues.linkPreviewStore`: the transcript's store, provided by its list host. */
val LocalLinkPreviewStore = staticCompositionLocalOf<LinkPreviewStore?> { null }

/** The row's [LinkPreviewStore.revision], set when it's configured (iOS `linkPreviewRevision`). */
val LocalLinkPreviewRevision = compositionLocalOf { 0 }
