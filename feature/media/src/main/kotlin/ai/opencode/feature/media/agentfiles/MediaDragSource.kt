package ai.opencode.feature.media

import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.MimeTypeMap
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import java.util.Locale

// The media-level half of iOS `Transcript/TranscriptDragSource.swift`: the `.url` and
// `.agentFile` payloads media's own surfaces drag (links in `LinkMenu`, gallery tiles, file
// cards). `:feature:transcript` keeps the rest of `TranscriptDragPayload` above this module; its
// `AgentFile` and `Image` cases (no clip data there yet) can hand over to [MediaDragPayload], and
// its type helpers ([MediaDragPayload.fileExtension], [MediaDragPayload.codeMimeType], …) are the
// same port, so the transcript can drop its copies.

/**
 * Server and directory context for resolving agent files dragged out of a row (iOS
 * `TranscriptDragContext`, the `\.transcriptDragContext` environment value).
 */
@Immutable
data class MediaDragContext(val server: OpenCodeServer? = null, val directory: String? = null)

/** iOS `EnvironmentValues.transcriptDragContext`: read by [mediaDragSource]. */
val LocalMediaDragContext = staticCompositionLocalOf { MediaDragContext() }

/**
 * What a media surface provides when dragged into another app or the composer (iOS
 * `TranscriptDragPayload.url` / `.agentFile`). Android carries it as [ClipData] ([clipData]);
 * an agent file goes out as a `content://` URI of [AgentFileDragProvider], which downloads it
 * through [AgentFileLoader] only when the drop target reads it, as iOS's item provider loads
 * lazily.
 */
@Immutable
sealed interface MediaDragPayload {
    /** A web or pairing URL (`LinkMenuView`, `LinkPreviewButton`). */
    data class Url(val url: String) : MediaDragPayload

    /** A file or image named by the agent (`AgentFileReference`) or attached to a user message. */
    data class AgentFile(
        val file: AgentFileReference,
        val server: OpenCodeServer? = null,
        val directory: String? = null,
        val mime: String? = null,
        val displayName: String? = null,
        val cachedImage: Bitmap? = null,
    ) : MediaDragPayload

    /**
     * The drag's data. Registers an agent file with [AgentFileDragProvider] (nothing is read
     * until the drop target opens it). A file no server serves drags as its path, as on iOS.
     */
    fun clipData(context: Context, dragContext: MediaDragContext = MediaDragContext()): ClipData? = when (this) {
        is Url -> {
            val shareURL = OpenCodePairingLink.shareableURL(url) ?: url
            val label = shareURL.toUri().host ?: "link"
            ClipData(
                ClipDescription(label, arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST, ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item(shareURL, null, shareURL.toUri()),
            )
        }
        is AgentFile -> agentFileClip(context, dragContext)
    }

    private fun AgentFile.agentFileClip(context: Context, dragContext: MediaDragContext): ClipData {
        val resolvedServer = server ?: dragContext.server
        val resolvedDirectory = directory ?: dragContext.directory
        val fileName = displayName ?: file.name
        val type = fileMimeType(fileName, mime ?: file.mime, file.kind)
        val target = file.target(resolvedDirectory)
        val request = if (resolvedServer != null && target != null) resolvedServer.fileReadRequest(target) else null
        if (resolvedServer != null && target != null && request != null) {
            val uri = AgentFileDragProvider.register(
                context,
                AgentFileDragProvider.Source.Server(resolvedServer, request, target.absolutePath, fileName),
                mime = type,
                suggestedName = sanitizedSuggestedName(fileName, "file", type),
                cachedImage = cachedImage?.takeIf { type.startsWith("image/") },
            )
            return ClipData(ClipDescription(sanitizedSuggestedName(fileName, "file", type), arrayOf(type)), ClipData.Item(uri))
        }
        val path = file.path.takeIf { it.startsWith("/") }
        if (cachedImage != null && (path == null || type.startsWith("image/"))) {
            // In memory only: the decoded pixels as a PNG, with the path as text when it has one.
            val name = sanitizedSuggestedName(fileName, "image", if (path == null) "image/png" else type)
            val uri = AgentFileDragProvider.register(
                context,
                AgentFileDragProvider.Source.Image(fileName),
                mime = "image/png",
                suggestedName = name,
                cachedImage = cachedImage,
            )
            val mimes = if (path == null) arrayOf("image/png") else arrayOf("image/png", ClipDescription.MIMETYPE_TEXT_PLAIN)
            return ClipData(ClipDescription(name, mimes), ClipData.Item(path, null, uri))
        }
        val label = sanitizedSuggestedName(fileName, "file", type)
        return ClipData(ClipDescription(label, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item(file.path))
    }

    companion object {
        private const val OCTET_STREAM = "application/octet-stream"

        /** Maps a Markdown code fence language tag (or a file extension) to a file extension. */
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

        /**
         * The MIME type for a code block's fence language (iOS `codeUTType(for:)`); null where
         * iOS answers the generic `.sourceCode`.
         */
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
            }
        }

        /** The MIME type for an agent or attached file (iOS `fileUTType(fileName:mime:kind:)`). */
        fun fileMimeType(fileName: String, mime: String?, kind: AgentFileReference.Kind): String {
            val ext = AttachmentFileType.fileExtension(fileName)
            if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
                val code = codeMimeType(ext)
                if (code != null) return code
                if (kind == AgentFileReference.Kind.Text) return ClipDescription.MIMETYPE_TEXT_PLAIN
            }
            if (mime != null && mime != OCTET_STREAM) return mime
            return when (kind) {
                AgentFileReference.Kind.Image -> "image/png"
                AgentFileReference.Kind.Pdf -> "application/pdf"
                AgentFileReference.Kind.Video -> "video/quicktime"
                AgentFileReference.Kind.Audio -> "audio/*"
                AgentFileReference.Kind.Text -> ClipDescription.MIMETYPE_TEXT_PLAIN
                AgentFileReference.Kind.Document -> OCTET_STREAM
            }
        }

        /** iOS `sanitizedSuggestedName(_:defaultName:utType:)`: the type's extension when the name has none. */
        fun sanitizedSuggestedName(name: String, defaultName: String, mime: String): String {
            val sanitized = AttachmentFileType.sanitizedName(name.ifEmpty { defaultName })
            if (AttachmentFileType.fileExtension(sanitized).isNotEmpty()) return sanitized
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?: if (mime.startsWith("text/")) "txt" else null
            return if (ext != null) "$sanitized.$ext" else sanitized
        }
    }
}

/**
 * Makes a media surface draggable into other apps and the composer (iOS
 * `.transcriptDragSource(_:cornerRadius:)` for the payloads media owns). Holding it lifts the
 * surface as drawn (its own fill and corners, iOS's lift preview) and starts the system drag with
 * [payload]'s [ClipData], `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`, so another app can read
 * the file's `content://` URI. The clip data is built when the drag starts. Taps and scrolling
 * stay with the surface. `null` leaves the surface untouched.
 */
fun Modifier.mediaDragSource(payload: MediaDragPayload?): Modifier = if (payload == null) this else composed {
    val context = LocalContext.current
    val dragContext = LocalMediaDragContext.current
    val current by rememberUpdatedState(payload)
    val currentContext by rememberUpdatedState(dragContext)
    dragAndDropSource { _ ->
        val clip = current.clipData(context, currentContext) ?: return@dragAndDropSource null
        markDragBegin()
        DragAndDropTransferData(clip, flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
    }
}

/** iOS `LabMarks.mark("transcript_drag_begin")` (DEBUG builds only). */
internal fun markDragBegin() {
    if (InternalBuild.isDebug) LabMarks.mark("transcript_drag_begin")
}
