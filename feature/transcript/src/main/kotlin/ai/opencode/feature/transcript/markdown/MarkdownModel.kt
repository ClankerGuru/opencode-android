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

        /** Runs with empty ones dropped and adjacent equal-attribute runs merged (as `AttributedString` coalesces them). */
        fun coalesced(runs: List<InlineRun>): InlineText {
            val out = ArrayList<InlineRun>(runs.size)
            for (run in runs) {
                if (run.text.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && last.sameAttributes(run)) out[out.size - 1] = last.copy(text = last.text + run.text)
                else out.add(run)
            }
            return if (out.isEmpty()) empty else InlineText(out)
        }
    }
}

/**
 * One attribute run. Flags mirror iOS `InlinePresentationIntent` (`emphasized`,
 * `stronglyEmphasized`, `code`, `strikethrough`); [link] is the run's URL string (web,
 * `opencode-file://`, `opencode-session://`); [languageIdentifier] marks lifted math
 * (`MathSyntax.Delimiter.languageIdentifier`, `x-math-…`); [secondary] is iOS's
 * `foregroundColor = .secondary` (inline code that is not a file link, and math source).
 * [sessionChip] is iOS's `SessionChipKey`, set only by `SessionLinkText.present` on the padding
 * and label of a session link drawn as a chip.
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
    val sessionChip: SessionChip? = null,
) {
    /** Same attributes (everything but the text): adjacent runs that are can merge. */
    fun sameAttributes(other: InlineRun): Boolean =
        emphasized == other.emphasized && strong == other.strong && code == other.code &&
            strikethrough == other.strikethrough && link == other.link &&
            languageIdentifier == other.languageIdentifier && secondary == other.secondary &&
            sessionChip == other.sessionChip
}

/**
 * iOS `SessionChip` (`SessionChipKey`'s value): one session link drawn as a chip. [muted] while
 * nothing named it yet (the short ID in code); [isSubagent] picks the subagent glyph.
 */
@Immutable
data class SessionChip(val id: String, val muted: Boolean, val isSubagent: Boolean)

/** A token the syntax highlighter coloured: `[start, end)` in UTF-16 chars of the source (media's [SyntaxHighlighter]). */
typealias SyntaxToken = ai.opencode.feature.media.SyntaxHighlighter.Token

typealias SyntaxTokenKind = ai.opencode.feature.media.SyntaxHighlighter.TokenKind
