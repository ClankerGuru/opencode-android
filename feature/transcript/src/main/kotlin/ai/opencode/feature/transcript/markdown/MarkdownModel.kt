package ai.opencode.feature.transcript.markdown

import androidx.compose.runtime.Immutable

/**
 * Inline styled text: the Android counterpart of the `AttributedString` iOS gets from
 * `AttributedString(markdown:options: .inlineOnlyPreservingWhitespace)` plus the attributes
 * `OpenCodeMarkdownBlock.uncachedInlineEntry` adds. Pure data (no Compose styles) so it can be
 * parsed off the main thread, cached and compared; renderers map it to an `AnnotatedString`.
 */
@Immutable
data class InlineText(val runs: List<InlineRun>) {
    /** The characters, as iOS `String(attributed.characters)`. */
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) { runs.joinToString("") { it.text } }

    val isEmpty: Boolean get() = runs.all { it.text.isEmpty() }

    override fun toString(): String = "InlineText(${text.length} chars, ${runs.size} runs)"

    companion object {
        val empty = InlineText(emptyList())
        fun plain(text: String): InlineText = if (text.isEmpty()) empty else InlineText(listOf(InlineRun(text)))
    }
}

/**
 * One attribute run. Flags mirror iOS `InlinePresentationIntent` (`emphasized`,
 * `stronglyEmphasized`, `code`, `strikethrough`); [link] is the run's URL string (web,
 * `opencode-file://`, `opencode-session://`); [languageIdentifier] marks lifted math
 * (`MathSyntax.Delimiter.languageIdentifier`, `x-math-…`); [secondary] is iOS's
 * `foregroundColor = .secondary` (inline code that is not a file link, and math source).
 */
@Immutable
data class InlineRun(
    val text: String,
    val emphasized: Boolean = false,
    val strong: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
    val languageIdentifier: String? = null,
    val secondary: Boolean = false,
)

/** A token the syntax highlighter coloured: `[start, end)` in UTF-16 chars of the source. */
@Immutable
data class SyntaxToken(val start: Int, val end: Int, val kind: SyntaxTokenKind)

enum class SyntaxTokenKind { Keyword, String, Number, Comment, Type }
