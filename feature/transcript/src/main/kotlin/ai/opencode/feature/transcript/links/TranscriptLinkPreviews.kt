package ai.opencode.feature.transcript.links

import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The transcript's link previews, as its views read them: the hand-off seam to iOS
 * `LinkPreviewStore` (`Media/LinkPreviews/`), which `:feature:media` implements. iOS reads the
 * store and its per-row revision from the environment (`linkPreviewStore`,
 * `linkPreviewRevision`); here both come from [LocalTranscriptLinkPreviews]. Without one (the
 * default) a reply end draws its Copy row alone, exactly as iOS does without a store.
 *
 * Implementations must be main-thread objects whose [revision] reads snapshot state
 * (`mutableIntStateOf` per row, bumped where iOS's `arrived(_:)` bumps `revisions`), so a row
 * recomposes when its links' metadata or icons arrive; nothing else is observed.
 */
@Stable
interface TranscriptLinkPreviews {
    /**
     * The reply's links, extracted once per finished reply (iOS `targets(in:entryID:)`): cache
     * [LinkPreviewExtractor.targets] by [entryID] while [blocks] are equal.
     */
    fun targets(blocks: List<OpenCodeMarkdownBlock>, entryID: String): List<LinkPreviewTarget>

    /**
     * What [target] shows right now (iOS `preview(for:)`): supplied, cached or stale metadata,
     * else [LinkPreview.Display.Pending] while rich metadata may still come, else plain; with its
     * site icon and decoded picture when known. See [LinkPreview.from].
     */
    fun preview(target: LinkPreviewTarget): LinkPreview

    /**
     * A link came into view in [row] (iOS `appeared(_:row:)`): the first view of a rich link
     * fetches it, and of a web link its site's icon; an arrival bumps [row]'s [revision].
     */
    fun appeared(target: LinkPreviewTarget, row: String)

    /** iOS `revision(of:)`; must read snapshot state (see the type's KDoc). */
    fun revision(row: String): Int

    /**
     * The row's arrival presentation (iOS `presentation(for:)`), owned with the transcript so
     * a row leaving the screen neither replays an arrival nor loses its pixels. Keep one per row
     * (a [LinkPreviewRowPresentations] does). The list sets [LinkPreviewRowPresentation.arrives]
     * for a reply end inserted when a run finishes with links (iOS
     * `TranscriptListViewController+Snapshots`).
     */
    fun presentation(row: String): LinkPreviewRowPresentation
}

/** One [LinkPreviewRowPresentation] per row, created on first use (iOS `LinkPreviewStore.presentations`). */
class LinkPreviewRowPresentations {
    private val presentations = HashMap<String, LinkPreviewRowPresentation>()

    fun presentation(row: String): LinkPreviewRowPresentation = presentations.getOrPut(row) { LinkPreviewRowPresentation() }
}

/** iOS `EnvironmentValues.linkPreviewStore`: null (no previews) until the session provides one. */
val LocalTranscriptLinkPreviews = staticCompositionLocalOf<TranscriptLinkPreviews?> { null }

/**
 * The row's stable list identity (iOS `EnvironmentValues.linkPreviewRow`), which image links
 * use to name their media. The list provides it around each row; null outside one.
 */
val LocalLinkPreviewRow = staticCompositionLocalOf<String?> { null }
