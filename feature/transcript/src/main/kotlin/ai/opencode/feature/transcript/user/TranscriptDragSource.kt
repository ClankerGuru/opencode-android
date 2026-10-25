package ai.opencode.feature.transcript.user

import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.core.files.AttachmentFileType
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.view.View
import android.webkit.MimeTypeMap
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import java.util.Locale

/**
 * Server and directory context for resolving agent file references dragged out of a transcript
 * row (iOS `TranscriptDragContext`).
 */
@Immutable
data class TranscriptDragContext(val server: OpenCodeServer? = null, val directory: String? = null)

/** Visual framing used when lifting a drag preview (iOS `TranscriptDragSurfaceStyle`). */
enum class TranscriptDragSurfaceStyle {
    /** A rounded card or image tile. */
    Card,
    /** A full-width code or equation band (rounded when lifted). */
    Band,
    /** A user message bubble (bubble corner radius). */
    Bubble,
    /** An inline file or web link inside prose. */
    InlineLink,
}

/**
 * What a transcript surface provides when dragged into another app or the composer (iOS
 * `TranscriptDragPayload`). Android carries it as [ClipData] ([clipData]); the MIME types are
 * the iOS `UTType`s' equivalents ([mimeTypes]).
 */
@Immutable
sealed interface TranscriptDragPayload {
    /** A fenced code block ([source] without fences, plus optional fence [language]). */
    data class Code(val source: String, val language: String?) : TranscriptDragPayload

    /** An image attachment with raw bytes already in memory (`OpenCodeImageAttachment`). */
    class ImageAttachment(val id: String, val data: ByteArray, val name: String) : TranscriptDragPayload {
        override fun equals(other: Any?): Boolean = other is ImageAttachment && other.id == id && other.name == name && other.data.contentEquals(data)
        override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
    }

    /** A decoded image already in memory (Markdown images, Mermaid cards), optionally backed by a server file. */
    data class Image(
        val image: ImageBitmap,
        val name: String,
        val file: AgentFileReference? = null,
        val server: OpenCodeServer? = null,
        val directory: String? = null,
    ) : TranscriptDragPayload

    /** A file or image named by the agent (`AgentFileReference`) or attached to a user message. */
    data class AgentFile(
        val file: AgentFileReference,
        val server: OpenCodeServer? = null,
        val directory: String? = null,
        val mime: String? = null,
        val displayName: String? = null,
        val cachedImage: ImageBitmap? = null,
    ) : TranscriptDragPayload

    /** A long paste card (`PastedTextCard`). */
    data class PastedText(val text: String, val name: String) : TranscriptDragPayload

    /** A web or pairing URL. */
    data class Url(val url: String) : TranscriptDragPayload

    /** Plain message or reply text (`TranscriptUserBubble`). */
    data class Text(val text: String) : TranscriptDragPayload

    /** Reply blocks serialized lazily when the drag starts (`ReplyActions`). */
    data class MarkdownBlocks(val blocks: List<OpenCodeMarkdownBlock>) : TranscriptDragPayload

    /** Inline `opencode-file://` file links inside a paragraph, list item, or quote. */
    data class InlineFileLinks(val text: AnnotatedString, val lineSpacing: Float) : TranscriptDragPayload

    /** The file name the drop destination sees (iOS `suggestedName`). */
    val suggestedName: String
        get() = when (this) {
            is Code -> "snippet.${fileExtension(language) ?: "txt"}"
            is ImageAttachment -> sanitizedSuggestedName(name, "image", imageMimeType(data, name))
            is Image -> sanitizedSuggestedName(name, "image", "image/png")
            is AgentFile -> {
                val fileName = displayName ?: fileName(file.path)
                sanitizedSuggestedName(fileName, "file", fileMimeType(fileName, mime))
            }
            is PastedText -> sanitizedSuggestedName(name, "pasted-text", "text/plain")
            is Url -> Uri.parse(url).host ?: "link"
            is Text, is MarkdownBlocks -> "message.txt"
            is InlineFileLinks -> "message.txt"
        }

    /** The MIME types offered, richest first (iOS registered `UTType`s). */
    val mimeTypes: List<String>
        get() = when (this) {
            is Code -> {
                val code = codeMimeType(language)
                when {
                    code != null && code != ClipDescription.MIMETYPE_TEXT_PLAIN -> listOf(code, ClipDescription.MIMETYPE_TEXT_PLAIN)
                    else -> listOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
                }
            }
            is ImageAttachment -> listOf(imageMimeType(data, name))
            is Image -> listOf("image/png")
            is AgentFile -> listOf(fileMimeType(displayName ?: fileName(file.path), mime))
            is Url -> listOf(ClipDescription.MIMETYPE_TEXT_URILIST, ClipDescription.MIMETYPE_TEXT_PLAIN)
            is PastedText, is Text, is MarkdownBlocks, is InlineFileLinks -> listOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
        }

    /**
     * The drag's data, or null when this payload needs bytes behind a `content://` URI (images,
     * agent files): those wait for a FileProvider and `AgentFileLoader` from `:feature:media`.
     * A path the server doesn't resolve drags as its plain text, as on iOS.
     */
    fun clipData(context: TranscriptDragContext = TranscriptDragContext()): ClipData? {
        val label = suggestedName
        fun plain(text: String, mimes: List<String> = mimeTypes) =
            ClipData(ClipDescription(label, mimes.toTypedArray()), ClipData.Item(text))
        return when (this) {
            is Code -> plain(source)
            is PastedText -> plain(text)
            is Text -> plain(text)
            is MarkdownBlocks -> plain(OpenCodeMarkdownBlock.markdown(blocks))
            is InlineFileLinks -> plain(text.text)
            is Url -> ClipData(ClipDescription(label, mimeTypes.toTypedArray()), ClipData.Item(url, null, Uri.parse(url)))
            is AgentFile -> {
                val server = server ?: context.server
                // A file a server can serve needs a lazy download (hand-off); without one, its path.
                if (server == null && cachedImage == null) plain(file.path, listOf(ClipDescription.MIMETYPE_TEXT_PLAIN)) else null
            }
            is ImageAttachment, is Image -> null
        }
    }

    companion object {
        /** Maps a Markdown code fence language tag to a file extension. */
        fun fileExtension(language: String?): String? {
            val raw = language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val token = raw.split(Regex("[\\s,;{]")).firstOrNull { it.isNotEmpty() } ?: raw
            val cleaned = token.removePrefix(".").takeIf { it.isNotEmpty() } ?: return null
            return when (cleaned) {
                "javascript", "js", "node", "mjs", "cjs" -> "js"
                "jsx" -> "jsx"
                "typescript", "ts", "mts", "cts" -> "ts"
                "tsx" -> "tsx"
                "python", "py", "python3" -> "py"
                "swift" -> "swift"
                "rust", "rs" -> "rs"
                "golang", "go" -> "go"
                "ruby", "rb" -> "rb"
                "shell", "bash", "sh", "zsh", "fish", "console", "terminal" -> "sh"
                "markdown", "md", "mdx" -> "md"
                "objective-c", "objc", "obj-c" -> "m"
                "objective-cpp", "objcpp" -> "mm"
                "kotlin", "kt", "kts" -> "kt"
                "csharp", "c#", "cs" -> "cs"
                "cplusplus", "c++", "cpp", "cxx", "cc" -> "cpp"
                "c" -> "c"
                "header", "h" -> "h"
                "hpp" -> "hpp"
                "java" -> "java"
                "json", "jsonc", "jsonl" -> "json"
                "yaml", "yml" -> "yaml"
                "toml" -> "toml"
                "xml", "plist", "svg" -> cleaned
                "html", "htm" -> "html"
                "css", "scss", "sass", "less" -> cleaned
                "sql", "postgres", "postgresql", "mysql", "sqlite" -> "sql"
                "dockerfile", "docker" -> "dockerfile"
                "makefile", "make" -> "makefile"
                "tex", "latex", "math" -> "tex"
                "diff", "patch" -> "diff"
                "plaintext", "text", "txt", "plain" -> "txt"
                else -> if (cleaned.all { it.isLetterOrDigit() || it == '-' || it == '+' }) cleaned else null
            }
        }

        /** The MIME type for a code block's fence language (iOS `codeUTType(for:)`). */
        fun codeMimeType(language: String?): String? {
            val ext = fileExtension(language) ?: return null
            return when (ext) {
                "swift" -> "text/x-swift"
                "py" -> "text/x-python"
                "js", "jsx" -> "text/javascript"
                "sh" -> "application/x-sh"
                "rb" -> "text/x-ruby"
                "c" -> "text/x-csrc"
                "cpp" -> "text/x-c++src"
                "h", "hpp" -> "text/x-chdr"
                "m" -> "text/x-objcsrc"
                "mm" -> "text/x-objc++src"
                "json" -> "application/json"
                "yaml" -> "application/yaml"
                "xml" -> "application/xml"
                "svg" -> "image/svg+xml"
                "html" -> "text/html"
                "plist" -> "application/x-plist"
                "txt" -> ClipDescription.MIMETYPE_TEXT_PLAIN
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("text/") }
                    ?: ClipDescription.MIMETYPE_TEXT_PLAIN
            }
        }

        /** Sniffs the MIME type of image bytes from magic headers (or file extension) without decoding pixels. */
        fun imageMimeType(data: ByteArray, fileName: String? = null): String {
            fun b(i: Int) = data[i].toInt() and 0xFF
            val n = data.size
            if (n >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 && b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A) return "image/png"
            if (n >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return "image/jpeg"
            if (n >= 4 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) return "image/gif"
            if (n >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50) return "image/webp"
            if (n >= 11 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 && b(8) == 0x68 && b(9) == 0x65 && b(10) == 0x69) return "image/heic"
            val prefix = String(data.copyOfRange(0, minOf(64, n)), Charsets.UTF_8).trim().lowercase(Locale.ROOT)
            if (prefix.startsWith("<svg") || prefix.contains("<svg")) return "image/svg+xml"
            if (fileName != null) {
                val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(AttachmentFileType.fileExtension(fileName))
                if (type != null && type.startsWith("image/")) return type
            }
            return "image/png"
        }

        /** The MIME type for an agent or attached file (iOS `fileUTType`, without the kind fallback). */
        fun fileMimeType(fileName: String, mime: String?): String {
            val ext = AttachmentFileType.fileExtension(fileName)
            if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
                codeMimeType(ext)?.let { return it }
            }
            if (mime != null && mime != "application/octet-stream") return mime
            return "application/octet-stream"
        }

        fun sanitizedSuggestedName(name: String, defaultName: String, mime: String): String {
            val sanitized = AttachmentFileType.sanitizedName(name.ifEmpty { defaultName })
            if (AttachmentFileType.fileExtension(sanitized).isNotEmpty()) return sanitized
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (mime.startsWith("text/")) "txt" else null
            return if (ext != null) "$sanitized.$ext" else sanitized
        }

        private fun fileName(path: String): String = path.substringAfterLast('/')
    }
}

/**
 * Makes this transcript surface draggable into other apps and the composer (iOS
 * `.transcriptDragSource`, shipped for everyone). Holding it lifts it: [onLift] runs (a row
 * opens its menu there, as iOS's context menu lifts the same surface), and moving the held
 * finger past the touch slop starts the drag with [payload]'s [ClipData] and a snapshot of the
 * surface as its shadow. A payload with no clip data on Android ([TranscriptDragPayload.clipData])
 * only lifts. Scrolling, taps and text selection inside stay untouched: the hold has to land
 * before the finger moves.
 *
 * [cornerRadius] and [surface] are kept for parity with iOS's lift preview; the shadow is the
 * surface as drawn (its own fill and corners).
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.transcriptDragSource(
    payload: TranscriptDragPayload?,
    cornerRadius: Dp = TranscriptMetrics.cardCornerRadius,
    surface: TranscriptDragSurfaceStyle = TranscriptDragSurfaceStyle.Card,
    context: TranscriptDragContext = TranscriptDragContext(),
    onLift: (() -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
): Modifier = composed {
    val clip = remember(payload, context) { payload?.clipData(context) }
    val currentLift by rememberUpdatedState(onLift)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentClip by rememberUpdatedState(clip)
    var modifier: Modifier = this
    // Outer, so the drag detector inside reads each event first (Main pass runs inner to outer).
    if (onLift != null) {
        val consumes = clip == null
        modifier = modifier.pointerInput(consumes) { awaitLift(onLift = { currentLift?.invoke() }, consumes = consumes) }
    }
    if (clip != null) {
        // The platform's hold-then-move start, with the surface as drawn for its shadow.
        modifier = modifier.dragAndDropSource { _ ->
            val data = currentClip ?: return@dragAndDropSource null
            currentDragStart?.invoke()
            DragAndDropTransferData(data, flags = View.DRAG_FLAG_GLOBAL)
        }
    }
    modifier
}

/**
 * [onLift] when a long press lands. With no drag source to take the moves, it then holds the
 * gesture ([consumes]) so the list doesn't scroll under the open menu.
 */
private suspend fun PointerInputScope.awaitLift(onLift: () -> Unit, consumes: Boolean) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        onLift()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
            if (consumes) change.consume()
            if (!change.pressed) break
        }
    }
}
