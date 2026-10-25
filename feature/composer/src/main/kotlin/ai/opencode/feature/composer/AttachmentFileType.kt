package ai.opencode.feature.composer

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale
import ai.opencode.server.model.AttachmentFileType as ServerAttachmentFileType

/**
 * Port of iOS `AttachmentFileType` (`Composer/AttachmentFileType.swift`), compiled on iOS into
 * both the app and the share extension. Mirrors the desktop app's attachment typing
 * (packages/app/src/composer/attachments/attachments.ts `attachmentMime`,
 * packages/session-ui/src/components/message-file.ts `typeLabel`).
 *
 * [sanitizedName] and [fileExtension] already live in `:server`
 * (`ai.opencode.server.model.AttachmentFileType`, the upload path needs them) and are
 * forwarded here so composer code has one name to call. Everything else (MIME sniffing,
 * labels, symbols) is here until it moves down to `:server` for the share sheet and
 * `AgentFileReference` (`:feature:media`), which need it too.
 *
 * `UTType` lookups map to [MimeTypeMap] (extension ↔ MIME) and explicit MIME sets for the
 * `archive`, `spreadsheet` and `presentation` conformances.
 */
object AttachmentFileType {
    val imageMimes: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    const val PDF_MIME = "application/pdf"

    /** The server rejects inline attachments above this size, so larger media takes the path route. */
    const val MAX_INLINE_BYTES: Long = 20L * 1024 * 1024

    private val imageExtensions: Map<String, String> = mapOf(
        "gif" to "image/gif", "jpeg" to "image/jpeg", "jpg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    )
    private val textMimes: Set<String> = setOf(
        "application/json", "application/ld+json", "application/toml", "application/x-toml",
        "application/x-yaml", "application/xml", "application/yaml",
    )

    /** The declared MIME type for a file name, as a browser's `File.type` would report it. */
    fun declaredMime(fileName: String): String? {
        val suffix = fileExtension(fileName)
        if (suffix.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix)
            ?: fallbackMimes[suffix]
    }

    /**
     * Text-like files normalize to text/plain so the chip labels them as text; every other
     * file keeps a binary type. [head] is the file's first bytes.
     */
    fun mime(fileName: String, declared: String?, head: ByteArray): String {
        val type = (declared ?: "").split(";", limit = 2).firstOrNull()?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (type in imageMimes || type == PDF_MIME) return type
        val suffix = fileExtension(fileName)
        val fallback = imageExtensions[suffix] ?: if (suffix == "pdf") PDF_MIME else null
        if ((type.isEmpty() || type == "application/octet-stream") && fallback != null) return fallback
        if (type.startsWith("text/") || type in textMimes || type.endsWith("+json") || type.endsWith("+xml")) {
            return "text/plain"
        }
        val binary = type.ifEmpty { "application/octet-stream" }
        val bytes = if (head.size > 4096) head.copyOf(4096) else head
        if (bytes.any { it == 0.toByte() }) return binary
        val control = bytes.count { val b = it.toInt() and 0xFF; b < 9 || (b in 14..31) }
        if (bytes.isNotEmpty() && control.toDouble() / bytes.size.toDouble() > 0.3) return binary
        return "text/plain"
    }

    /** Reads only the first 4 KB to sniff text. [declared] is the content resolver's type, when known. */
    fun mime(file: File, fileName: String? = null, declared: String? = null): String {
        val name = fileName ?: file.name
        val head = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(4096)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        return mime(name, declared ?: declaredMime(name), head)
    }

    /** The chip subtitle: "PDF", a language name, or the extension in capitals. */
    fun label(name: String, mime: String): String {
        if (mime == PDF_MIME) return "PDF"
        val suffix = fileExtension(name)
        if (suffix.isEmpty()) return "File"
        return languageNames[suffix] ?: suffix.uppercase(Locale.ROOT)
    }

    fun subtitle(name: String, mime: String, size: Long): String =
        "${label(name, mime)} · ${formatFileBytes(size)}"

    /** An SF Symbol name; draw it with `OpenCodeIcon(sfName, …)`. */
    fun symbolName(name: String, mime: String): String {
        if (mime == PDF_MIME) return "doc.richtext"
        if (mime.startsWith("image/")) return "photo"
        if (mime.startsWith("video/")) return "film"
        if (mime.startsWith("audio/")) return "waveform"
        if (mime == "text/plain") {
            return if (languageNames[fileExtension(name)] == null) "doc.text" else "chevron.left.forwardslash.chevron.right"
        }
        val type = mime.lowercase(Locale.ROOT)
        if (type in archiveMimes) return "doc.zipper"
        if (type in spreadsheetMimes) return "tablecells"
        if (type in presentationMimes) return "rectangle.on.rectangle"
        return "doc"
    }

    /** A plain file name the server can use as the last path component. */
    fun sanitizedName(name: String): String = ServerAttachmentFileType.sanitizedName(name)

    /** The lowercased extension of the last path component; a leading dot is a dotfile like .gitignore, not an extension. */
    fun fileExtension(name: String): String = ServerAttachmentFileType.fileExtension(name)

    /**
     * Like `ByteCountFormatter.string(fromByteCount:countStyle: .file)`: decimal units,
     * "Zero KB", "12 bytes", "340 KB", "1.2 MB".
     */
    fun formatFileBytes(bytes: Long): String {
        if (bytes == 0L) return "Zero KB"
        if (bytes < 1000) return "$bytes ${if (bytes == 1L) "byte" else "bytes"}"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        val text = if (unit == 0) Math.round(value).toString() else String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        return "$text ${units[unit]}"
    }

    /** The common subset of the language names desktop takes from Shiki. */
    private val languageNames: Map<String, String> = mapOf(
        "c" to "C", "cjs" to "JavaScript", "cpp" to "C++", "cs" to "C#", "css" to "CSS", "dart" to "Dart",
        "go" to "Go", "html" to "HTML", "java" to "Java", "js" to "JavaScript",
        "json" to "JSON", "jsx" to "JSX", "kt" to "Kotlin", "lua" to "Lua", "md" to "Markdown", "mjs" to "JavaScript",
        "php" to "PHP", "py" to "Python", "rb" to "Ruby", "rs" to "Rust", "sh" to "Shell", "sql" to "SQL",
        "swift" to "Swift", "toml" to "TOML", "ts" to "TypeScript", "tsx" to "TSX",
        "xml" to "XML", "yaml" to "YAML", "yml" to "YAML", "zsh" to "Zsh",
    )

    /** Extensions `UTType` knows that Android's `MimeTypeMap` may not. */
    private val fallbackMimes: Map<String, String> = mapOf(
        "heic" to "image/heic", "heif" to "image/heif", "md" to "text/markdown", "yaml" to "application/yaml",
        "yml" to "application/yaml", "toml" to "application/toml", "ts" to "video/mp2t", "swift" to "text/x-swift",
        "kt" to "text/x-kotlin", "rs" to "text/x-rust", "go" to "text/x-go", "sh" to "application/x-sh",
    )

    /** `UTType.archive` conformers. */
    private val archiveMimes: Set<String> = setOf(
        "application/zip", "application/x-zip-compressed", "application/gzip", "application/x-gzip",
        "application/x-tar", "application/x-gtar", "application/x-bzip2", "application/x-xz",
        "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed",
        "application/java-archive", "application/x-apple-diskimage", "application/x-compress",
    )

    /** `UTType.spreadsheet` conformers. */
    private val spreadsheetMimes: Set<String> = setOf(
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet", "application/x-iwork-numbers-sffnumbers",
        "application/vnd.apple.numbers",
    )

    /** `UTType.presentation` conformers. */
    private val presentationMimes: Set<String> = setOf(
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.presentation", "application/x-iwork-keynote-sffkey",
        "application/vnd.apple.keynote",
    )
}
