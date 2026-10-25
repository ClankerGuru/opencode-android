package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.transcript.markdown.MarkdownQuote
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** A block whose text can be quoted (iOS `QuoteSelectionBlockSource`). */
class QuoteSelectionBlockSource private constructor(
    val isCodeBlock: Boolean,
    val codeLanguage: String?,
    private val kind: OpenCodeMarkdownBlock.Kind,
) {
    /** The selection as quoted: a code block's selection keeps its fence. */
    fun formatted(selected: String): String? {
        val formatted = if (isCodeBlock) MarkdownQuote.fencedCode(selected, codeLanguage) else selected
        return if (MarkdownQuote.blockquote(formatted) != null) formatted else null
    }

    /** The whole block, for TalkBack's Quote action. */
    fun fullBlockQuoteText(): String? = when (kind) {
        is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }
        is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }
        is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }
        is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }?.let { "${kind.marker} $it" }
        is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }?.let {
            val prefix = when (kind.marker) {
                OpenCodeMarkdownBlock.doneTask -> "- [x]"
                OpenCodeMarkdownBlock.openTask -> "- [ ]"
                else -> "-"
            }
            "$prefix $it"
        }
        is OpenCodeMarkdownBlock.Kind.Code ->
            if (MarkdownQuote.blockquote(kind.code.source) != null) MarkdownQuote.fencedCode(kind.code.source, kind.code.language) else null
        else -> null
    }

    companion object {
        fun of(kind: OpenCodeMarkdownBlock.Kind): QuoteSelectionBlockSource? = when (kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.Blockquote -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.Heading -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.ListItem -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.Code ->
                if (kind.code.source.isBlank()) null else QuoteSelectionBlockSource(true, kind.code.language, kind)
            else -> null
        }

        private fun prose(kind: OpenCodeMarkdownBlock.Kind, text: String) =
            if (text.isBlank()) null else QuoteSelectionBlockSource(false, null, kind)
    }
}

/**
 * The clipboard Copy writes to, unless Quote asked for the selection: then the copied text
 * goes to Quote and the system clipboard is left alone.
 */
private class QuoteClipboard(var base: Clipboard) : Clipboard {
    var capture: ((String) -> Unit)? = null

    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val take = capture
        if (take != null) {
            capture = null
            val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text != null) take(text)
            return
        }
        base.setClipEntry(clipEntry)
    }

    @Deprecated("Use the suspend APIs")
    override val nativeClipboard get() = base.nativeClipboard
}

/**
 * Selectable reply text that offers Quote beside Copy (iOS `quoteSelectionSource(for:handler:)`
 * and the hosting cell's Quote menu action): the selection is read through the selection's
 * own Copy (into a private clipboard), formatted (a code block's selection keeps its fence),
 * and sent as `TranscriptAction.QuoteTextTapped` by the row's [LocalQuoteTextHandler]; the
 * session plays the tap. TalkBack gets a Quote action for the whole block.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuoteSelectionContainer(kind: OpenCodeMarkdownBlock.Kind, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val handler = LocalQuoteTextHandler.current
    val source = remember(kind, handler != null) { if (handler != null) QuoteSelectionBlockSource.of(kind) else null }
    if (handler == null || source == null) {
        SelectionContainer(modifier) { content() }
        return
    }
    val currentHandler by rememberUpdatedState(handler)
    val systemClipboard = LocalClipboard.current
    val clipboard = remember { QuoteClipboard(systemClipboard) }.also { it.base = systemClipboard }
    val copyItem = remember { arrayOfNulls<TextContextMenuItem>(1) }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        SelectionContainer(
            modifier
                .filterTextContextMenuComponents { component ->
                    if (component.key == TextContextMenuKeys.CopyKey && component is TextContextMenuItem) copyItem[0] = component
                    true
                }
                .appendTextContextMenuComponents {
                    item(key = QuoteMenuKey, label = "Quote") {
                        val copy = copyItem[0]
                        if (copy != null) {
                            clipboard.capture = { selected -> source.formatted(selected)?.let { currentHandler.quote(it) } }
                            copy.onClick(this)
                        }
                        close()
                    }
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Quote") {
                            source.fullBlockQuoteText()?.let { currentHandler.quote(it) }
                            true
                        },
                    )
                },
        ) { content() }
    }
}

private object QuoteMenuKey
