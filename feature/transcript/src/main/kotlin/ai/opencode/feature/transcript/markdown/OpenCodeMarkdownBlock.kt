package ai.opencode.feature.transcript.markdown

import ai.opencode.server.model.AgentFileReference
import androidx.compose.runtime.Immutable

/**
 * One rendered Markdown block (iOS `OpenCodeMarkdownBlock`). The parser (`blocks(from:)`) is a
 * port of iOS's self-written block parser; inline runs are [InlineText].
 */
@Immutable
data class OpenCodeMarkdownBlock(val id: Int, val kind: Kind) {
    sealed interface Kind {
        data class Paragraph(val text: InlineText) : Kind
        data class NumberedItem(val marker: String, val text: InlineText) : Kind
        data class ListItem(val marker: String, val level: Int, val text: InlineText) : Kind
        data class Blockquote(val text: InlineText) : Kind
        data class Heading(val level: Int, val text: InlineText) : Kind
        data class Code(val code: HighlightedCode) : Kind
        data class Table(val header: List<InlineText>, val rows: List<List<InlineText>>) : Kind
        data class Image(val alt: String, val source: String) : Kind
        data class PullRequest(val title: String, val url: String) : Kind
        data object Divider : Kind
        data class Files(val files: List<AgentFileReference>) : Kind
        data class Math(val math: MathBlock) : Kind
        data class Mermaid(val source: String) : Kind
    }

    @Immutable
    data class MathBlock(val source: String, /** The lines as written, delimiters included, for copying. */ val raw: String)

    /**
     * A fenced block with its highlighted tokens. Equality is source and language only (iOS:
     * the tokens are derived from them).
     */
    @Immutable
    class HighlightedCode(
        val source: String,
        val language: String?,
        val tokens: List<SyntaxToken> = emptyList(),
        val safePrefixUTF8Count: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean =
            other is HighlightedCode && other.source == source && other.language == language
        override fun hashCode(): Int = source.hashCode() * 31 + (language?.hashCode() ?: 0)
        override fun toString(): String = "HighlightedCode(language=$language, ${source.length} chars)"
    }

    companion object {
        /** Placeholder parser until the port of iOS `blocks(from:)` lands: one paragraph per blank-line chunk. */
        fun blocks(text: String): List<OpenCodeMarkdownBlock> =
            text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
                .mapIndexed { index, chunk -> OpenCodeMarkdownBlock(index, Kind.Paragraph(InlineText.plain(chunk))) }

        /** Placeholder for iOS `markdown(from:)` (Copy). */
        fun markdown(blocks: List<OpenCodeMarkdownBlock>): String =
            blocks.joinToString("\n\n") { (it.kind as? Kind.Paragraph)?.text?.text.orEmpty() }
    }
}
