package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.model.AgentFileReference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * What the renderer needs from `:feature:media` (iOS `Media/`), which isn't ported yet: the
 * Mermaid diagram card, the agent file cards and image gallery, and Link Icons' site icons.
 * Each slot is null until media provides it; the renderer then draws its fallback (a
 * highlighted code band for Mermaid, plain file rows, the globe glyph).
 */
@Immutable
class MarkdownMediaHandoff(
    /** iOS `MermaidDiagramCard(source:onPreview:)`: only a closed ```mermaid fence reaches it. */
    val mermaid: (@Composable (source: String) -> Unit)? = null,
    /** iOS `AgentFileCards` / `AgentImageGallery` for a `.files` block. */
    val files: (@Composable (files: List<AgentFileReference>) -> Unit)? = null,
    /** iOS `LinkPreviewStore.inlineIcon(for:)`: a finished link's site icon, once fetched. */
    val siteIcon: ((url: String) -> ImageBitmap?)? = null,
    /** iOS `LinkPreviewStore.inlineLinksAppeared(_:row:)`: the row's fetchable links, once per set. */
    val inlineLinksAppeared: ((urls: List<String>, row: String?) -> Unit)? = null,
)

val LocalMarkdownMediaHandoff = staticCompositionLocalOf { MarkdownMediaHandoff() }

/**
 * iOS `SessionLinkContext`: the row's resolved session links. The row sets it from
 * `RowProps.sessionLinks`; IDs a block shows are reported once per set as
 * `TranscriptAction.SessionLinksAppeared`.
 */
@Immutable
data class SessionLinkContext(val targets: Map<String, SessionLinkTarget> = emptyMap(), val row: String? = null)

val LocalSessionLinks = staticCompositionLocalOf { SessionLinkContext() }

/** Link Icons (iOS `InlineLinkStyle`, OC-348): how a web link in a reply's text shows. */
enum class InlineLinkStyle {
    /** The link's text alone, as shipped (other Markdown surfaces). */
    Today,
    /** The site's icon before every web link. */
    Icon,
    /** The icon; a bare URL folds to its domain and a short path, on one line. */
    Chip,
    /** The icon; a bare URL drops `https://` and keeps the rest of its address (the default). */
    Address,
}

/** iOS `transcriptLayoutTuning.linkStyle`: Clean Address in the transcript; surfaces outside it set Today. */
val LocalInlineLinkStyle = staticCompositionLocalOf { InlineLinkStyle.Address }

/** iOS `QuoteTextHandler`: selected reply text offers Quote beside Copy. Null where quoting isn't offered. */
@Stable
class QuoteTextHandler(val quote: (String) -> Unit)

/** iOS `EnvironmentValues.quoteText`. The reply row sets it to send `TranscriptAction.QuoteTextTapped`. */
val LocalQuoteTextHandler = staticCompositionLocalOf<QuoteTextHandler?> { null }

/** iOS `AgentImageScope`: a whole reply's images, for blocks shown in parts (Messages bubbles). */
@Immutable
data class AgentImageScope(val occurrence: String, val siblings: List<AgentFileReference>)
