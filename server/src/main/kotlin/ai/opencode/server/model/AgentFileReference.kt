package ai.opencode.server.model

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A file the agent named in its reply, a tool row, or a sent attachment: the phone opens it
 * through the server's file reader. Mirrors desktop's local-link handling (session-ui
 * `markdown-image.ts` `localLinkPath`, `markdown-inline-code-kind.ts` `inlineCodeKind`,
 * gui-extensions file `renderer.tsx` LinkHandler), which also strips a `:line:col` suffix.
 *
 * Port of iOS `Media/AgentFiles/AgentFileReference.swift`. Lives in `:server` (not
 * `:feature:media`) because `OpenCodeToolCall.fileReference` needs it.
 */
@Serializable
data class AgentFileReference(
    /** The path as the agent wrote it, without a `file://` prefix or `:line:col`. */
    val path: String,
    /** The 1-based line a `path:42` reference points at, for text files. */
    val line: Int? = null,
) {
    val id: String get() = path

    /** The card the viewer zooms out of and back into. */
    val zoomSourceID: String get() = "file:$path"

    val name: String get() = path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path

    val kind: Kind get() = Kind.of(name)

    /** The MIME type implied by the extension; the server guesses the same way. */
    val mime: String get() = mimeForExtension(AttachmentFileType.fileExtension(name)) ?: "application/octet-stream"

    val typeLabel: String
        get() = when (kind) {
            Kind.Video -> "Video · " + AttachmentFileType.fileExtension(name).uppercase()
            Kind.Audio -> "Audio · " + AttachmentFileType.fileExtension(name).uppercase()
            else -> AttachmentFileType.label(name, mime)
        }

    /** SF Symbol name (`OpenCodeIcon("sf.name")` maps it to a Material Symbol). */
    val symbolName: String
        get() = when (kind) {
            Kind.Video -> "film"
            Kind.Audio -> "waveform"
            Kind.Image -> "photo"
            Kind.Pdf -> "doc.richtext"
            Kind.Text -> AttachmentFileType.symbolName(name, "text/plain")
            Kind.Document -> AttachmentFileType.symbolName(name, mime)
        }

    /** A file (not a folder) whose name the viewer recognizes. */
    val hasViewableName: Boolean
        get() = !path.endsWith("/") &&
            (name.lowercase() in knownFileNames || AttachmentFileType.fileExtension(name) in viewableExtensions)

    @Serializable
    enum class Kind(val rawValue: String) {
        Video("video"), Audio("audio"), Image("image"), Pdf("pdf"), Text("text"), Document("document");

        /** Media and documents earn a card under the reply; code paths stay inline links. */
        val showsCard: Boolean get() = this != Text

        /** Streams through range requests when the server supports them. */
        val isTimeBased: Boolean get() = this == Video || this == Audio

        /** Shown in a dark room and dragged down to dismiss, like Photos. */
        val isMedia: Boolean get() = this == Image || isTimeBased

        companion object {
            fun of(fileName: String): Kind {
                val suffix = AttachmentFileType.fileExtension(fileName)
                if (suffix in textExtensions || fileName.lowercase() in knownFileNames) return Text
                if (suffix == "pdf") return Pdf
                if (suffix in imageExtensions) return Image
                if (suffix in videoExtensions) return Video
                if (suffix in audioExtensions) return Audio
                // iOS falls back to UTType conformance; the extension table stands in for it.
                val type = mimeForExtension(suffix) ?: return Document
                return when {
                    type == AttachmentFileType.PDF_MIME -> Pdf
                    type.startsWith("video/") -> Video
                    type.startsWith("audio/") -> Audio
                    type.startsWith("image/") -> Image
                    type.startsWith("text/") -> Text
                    else -> Document
                }
            }
        }
    }

    /**
     * Where the server reads it: the session directory when the file lives inside it,
     * otherwise the file's own folder (desktop's approach). A file outside the session starts
     * a location for that folder on the server, so only an explicit tap, or a Markdown image
     * drawn on screen, resolves one.
     */
    data class Target(val location: String, val relativePath: String, val absolutePath: String)

    fun target(directory: String?): Target? {
        if (isWindowsAbsolute(path) || directory?.let(::isWindowsAbsolute) == true) return windowsTarget(directory)
        var path = this.path
        if (path.startsWith("~/") || path == "~") {
            val home = home(directory) ?: return null
            path = home + path.drop(1)
        }
        if (!path.startsWith("/")) {
            if (directory == null || !directory.startsWith("/")) return null
            path = "$directory/$path"
        }
        val absolute = normalized(path) ?: return null
        if (absolute == "/") return null
        val dir = directory?.let(::normalized)
        if (dir != null && absolute.startsWith(if (dir == "/") "/" else "$dir/")) {
            val relative = absolute.drop(if (dir == "/") 1 else dir.length + 1)
            return Target(dir, relative, absolute)
        }
        val components = absolute.split('/').filter { it.isNotEmpty() }
        val last = components.lastOrNull() ?: return null
        val parent = "/" + components.dropLast(1).joinToString("/")
        return Target(parent, last, absolute)
    }

    /**
     * The same rules as [target] on a Windows server, compared without regard to case or
     * separator. A file inside the session reads from the session's location exactly as the
     * server spelled it, so it never starts another location for the same folder.
     */
    private fun windowsTarget(directory: String?): Target? {
        var path = this.path.replace("\\", "/")
        val base = directory?.let(::normalizedWindows)
        if (path.startsWith("~/") || path == "~") {
            val home = home(directory) ?: return null
            path = home + path.drop(1)
        } else if (path.startsWith("/") && base != null) {
            // `\tmp\a.log` is on the session's drive.
            path = base.take(2) + path
        }
        if (!isWindowsAbsolute(path)) {
            if (base == null) return null
            path = "$base/$path"
        }
        val absolute = normalizedWindows(path) ?: return null
        if (absolute.length <= 3) return null
        if (directory != null && base != null) {
            val prefix = if (base.endsWith("/")) base else "$base/"
            if (absolute.lowercase().startsWith(prefix.lowercase())) {
                return Target(directory, absolute.drop(prefix.length), absolute)
            }
        }
        val slash = absolute.lastIndexOf('/')
        if (slash < 0) return null
        var parent = absolute.substring(0, slash)
        if (parent.length == 2) parent += "/"
        return Target(parent.replace("/", "\\"), absolute.substring(slash + 1), absolute)
    }

    /** A private URL carried on the rendered text; the transcript opens it. */
    val linkURL: String
        get() = buildString {
            append(LINK_SCHEME).append("://open?path=").append(queryEncode(path))
            line?.let { append("&line=").append(it) }
        }

    companion object {
        const val LINK_SCHEME = "opencode-file"

        /** iOS `init?(linkURL:)`. */
        fun fromLinkURL(url: String): AgentFileReference? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != LINK_SCHEME) return null
            val items = (uri.rawQuery ?: return null).split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.isEmpty()) null else parts[0] to runCatching {
                    URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                }.getOrDefault(parts.getOrElse(1) { "" })
            }
            val path = items.firstOrNull { it.first == "path" }?.second?.takeIf { it.isNotEmpty() } ?: return null
            return AgentFileReference(path, items.firstOrNull { it.first == "line" }?.second?.toIntOrNull())
        }

        private fun queryEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%2F", "/").replace("%7E", "~")

        private val windowsDrivePath = Regex("^/[A-Za-z]:/")
        private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d+(?::\\d+)?$)")
        private val lineAnchorRegex = Regex("#L\\d+(?:-L?\\d+)?$")
        private val urlSchemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val inlineCodeForbidden = Regex("[\\s()\\[\\]{}*+=<>|&^\"';,$`]")
        private val lineColSuffix = Regex(":(\\d+)(?::\\d+)?$")
        private val lineHashSuffix = Regex("#L(\\d+)(?:-L?\\d+)?$")
        private val windowsAbsolute = Regex("^[A-Za-z]:[\\\\/]")

        /**
         * A Markdown link's destination when it names a local file: a relative or absolute path,
         * `~/…`, or a `file://` URL on this host. Web and other schemes, fragments, and queries
         * are not files.
         */
        fun fromLink(destination: String): AgentFileReference? {
            var value = destination.trim().replace("\\", "/")
            if (value.isEmpty() || value.startsWith("#") || value.startsWith("?") || value.startsWith("//")) return null
            if (value.lowercase().startsWith("file:")) {
                val uri = runCatching { URI(value) }.getOrNull() ?: return null
                val host = uri.host
                if (!(host == null || host == "" || host == "localhost")) return null
                value = uri.path ?: return null
                // `file:///C:/Users/kit/a.pdf` names a Windows path.
                if (windowsDrivePath.containsMatchIn(value)) value = value.drop(1)
            } else if (!isWindowsAbsolute(value) && schemePrefix.containsMatchIn(value)) {
                // A scheme, but not `SessionView.swift:42` (a dot is legal in schemes) or a
                // Windows drive (`C:/Users/kit/a.pdf`).
                return null
            } else {
                // Keep a GitHub-style `#L42` for `withLocation`; drop other fragments.
                val lineAnchor = lineAnchorRegex.find(value)?.value ?: ""
                value = value.split("#", limit = 2).first()
                value = value.split("?", limit = 2).first()
                value = percentDecoded(value) + lineAnchor
            }
            if (value.isEmpty() || value.any { Character.isISOControl(it) }) return null
            return withLocation(value)
        }

        /** `removingPercentEncoding`: nil (keep the original) on malformed escapes. */
        private fun percentDecoded(value: String): String {
            if (!value.contains('%')) return value
            return runCatching {
                val bytes = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < value.length) {
                    val c = value[i]
                    if (c == '%') {
                        require(i + 2 < value.length)
                        bytes.write(value.substring(i + 1, i + 3).toInt(16))
                        i += 3
                    } else {
                        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                        i++
                    }
                }
                val decoder = Charsets.UTF_8.newDecoder()
                decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            }.getOrDefault(value)
        }

        /**
         * Inline code that reads as a file path. Stricter than desktop's rule (which accepts any
         * slash): the last component needs a recognizable file name, so API routes, flags, and
         * `and/or` stay plain code.
         */
        fun fromInlineCode(code: String): AgentFileReference? {
            val text = code.trim(' ', '\t')
            if (text.length !in 2..512 || urlSchemeRegex.containsMatchIn(text) ||
                inlineCodeForbidden.containsMatchIn(text) || text.startsWith("-") || text.startsWith("@")
            ) return null
            val reference = withLocation(text)?.takeIf { it.hasViewableName } ?: return null
            // A bare `Type.member` is code, not a file; require a path or a real name.
            val name = reference.name
            if (!reference.path.contains("/") && name.lowercase() !in knownFileNames &&
                name.firstOrNull()?.isUpperCase() == true && codeLikeMemberAccess(name)
            ) return null
            return reference
        }

        /** Splits a trailing `:line` or `:line:col` (also `#L42`) off a path. */
        fun withLocation(raw: String): AgentFileReference? {
            var path = raw
            var line: Int? = null
            val colon = lineColSuffix.find(path)
            if (colon != null) {
                line = colon.groupValues[1].toIntOrNull()
                path = path.substring(0, colon.range.first)
            } else {
                lineHashSuffix.find(path)?.let { match ->
                    line = match.groupValues[1].toIntOrNull()
                    path = path.substring(0, match.range.first)
                }
            }
            if (path.isEmpty() || path == "/" || path == "~" || path == "." || path == "..") return null
            return AgentFileReference(path, line)
        }

        /** `Config.lock` or `Logger.log` read as member access, not files, when the suffix doubles as an identifier. */
        private fun codeLikeMemberAccess(name: String): Boolean =
            AttachmentFileType.fileExtension(name) in setOf("json", "map", "log", "lock", "env")

        /**
         * `/Users/kit`, `/home/kit`, or (on Windows) `C:/Users/kit` from a session directory;
         * the server's info doesn't report a home folder.
         */
        fun home(directory: String?): String? {
            directory ?: return null
            normalizedWindows(directory)?.let { windows ->
                val parts = windows.split('/').filter { it.isNotEmpty() }
                if (parts.size < 3 || parts[1].lowercase() != "users") return null
                return "${parts[0]}/${parts[1]}/${parts[2]}"
            }
            val parts = directory.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] !in setOf("Users", "home")) return null
            return "/${parts[0]}/${parts[1]}"
        }

        /** A Windows server reports its directories as `C:\Users\kit\app`; agents there write `C:\…` or `C:/…`. */
        fun isWindowsAbsolute(path: String): Boolean = windowsAbsolute.containsMatchIn(path)

        /** `C:\Users\kit\app\..\x` → `C:/Users/kit/x`: forward slashes, an upper-case drive, and `C:/` for the root. */
        fun normalizedWindows(path: String): String? {
            val slashed = path.replace("\\", "/")
            if (!isWindowsAbsolute(slashed)) return null
            val rest = normalized(slashed.drop(2)) ?: return null
            return slashed.take(2).uppercase() + rest
        }

        /** A directory as the app compares paths: Windows ones normalized. */
        fun comparableDirectory(directory: String?): String? = directory?.let { normalizedWindows(it) ?: it }

        /** Collapses `.`, `..`, and repeated slashes without touching the phone's file system. */
        fun normalized(path: String): String? {
            if (!path.startsWith("/")) return null
            val stack = ArrayList<String>()
            for (component in path.split('/')) {
                if (component.isEmpty() || component == ".") continue
                if (component == "..") {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    continue
                }
                stack.add(component)
            }
            return "/" + stack.joinToString("/")
        }

        val textExtensions: Set<String> = setOf(
            "astro", "bash", "c", "cc", "cfg", "cjs", "clj", "conf", "cpp", "cs", "css", "csv", "cts", "dart", "diff",
            "dockerfile", "env", "erl", "ex", "exs", "fish", "go", "gql", "gradle", "graphql", "h", "hpp", "hs", "htm",
            "html", "ini", "java", "jl", "js", "json", "jsonc", "jsonl", "jsx", "kt", "kts", "less", "lock", "log", "lua",
            "m", "markdown", "md", "mdx", "mjs", "ml", "mm", "mts", "nix", "patch", "php", "pl", "plist", "properties",
            "proto", "ps1", "py", "r", "rb", "rs", "sass", "scala", "scss", "sh", "sql", "svelte", "swift", "tf", "toml",
            "ts", "tsv", "tsx", "txt", "vue", "xml", "yaml", "yml", "zig", "zsh",
        )

        val videoExtensions: Set<String> = setOf("avi", "m4v", "mkv", "mov", "mp4", "ogv", "webm")

        val audioExtensions: Set<String> =
            setOf("aac", "aif", "aiff", "caf", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "weba")

        val imageExtensions: Set<String> =
            setOf("bmp", "gif", "heic", "ico", "jpeg", "jpg", "png", "svg", "tif", "tiff", "webp")

        val mediaExtensions: Set<String> = videoExtensions + audioExtensions

        val documentExtensions: Set<String> = setOf(
            "bmp", "doc", "docx", "epub", "gif", "heic", "ico", "jpeg", "jpg", "key", "numbers", "pages", "pdf", "png",
            "ppt", "pptx", "rtf", "svg", "tif", "tiff", "webp", "xls", "xlsx", "zip",
        )

        val viewableExtensions: Set<String> = textExtensions + mediaExtensions + documentExtensions

        val knownFileNames: Set<String> = setOf(
            "brewfile", "changelog", "codeowners", "containerfile", "dockerfile", "gemfile", "justfile", "license",
            "makefile", "podfile", "procfile", "readme", "vagrantfile",
        )

        /**
         * The preferred MIME type for an extension (iOS `UTType(filenameExtension:).preferredMIMEType`).
         * A fixed table so JVM unit tests and every device agree; covers the viewer's vocabulary.
         */
        fun mimeForExtension(suffix: String): String? = extensionMimes[suffix.lowercase()]

        private val extensionMimes: Map<String, String> = mapOf(
            // Images
            "bmp" to "image/bmp", "gif" to "image/gif", "heic" to "image/heic", "ico" to "image/vnd.microsoft.icon",
            "jpeg" to "image/jpeg", "jpg" to "image/jpeg", "png" to "image/png", "svg" to "image/svg+xml",
            "tif" to "image/tiff", "tiff" to "image/tiff", "webp" to "image/webp", "heif" to "image/heif",
            "avif" to "image/avif",
            // Video
            "avi" to "video/x-msvideo", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
            "mp4" to "video/mp4", "ogv" to "video/ogg", "webm" to "video/webm", "3gp" to "video/3gpp",
            "mpeg" to "video/mpeg", "mpg" to "video/mpeg",
            // Audio
            "aac" to "audio/aac", "aif" to "audio/aiff", "aiff" to "audio/aiff", "caf" to "audio/x-caf",
            "flac" to "audio/flac", "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "oga" to "audio/ogg",
            "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/wav", "weba" to "audio/webm",
            "mid" to "audio/midi", "midi" to "audio/midi",
            // Documents
            "pdf" to "application/pdf", "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "epub" to "application/epub+zip", "key" to "application/x-iwork-keynote-sffkey",
            "numbers" to "application/x-iwork-numbers-sffnumbers", "pages" to "application/x-iwork-pages-sffpages",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "rtf" to "text/rtf", "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "odp" to "application/vnd.oasis.opendocument.presentation",
            // Archives
            "zip" to "application/zip", "gz" to "application/gzip", "tgz" to "application/gzip", "tar" to "application/x-tar",
            "bz2" to "application/x-bzip2", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
            "xz" to "application/x-xz",
            // Text and code
            "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown", "html" to "text/html",
            "htm" to "text/html", "css" to "text/css", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
            "js" to "text/javascript", "mjs" to "text/javascript", "cjs" to "text/javascript",
            "json" to "application/json", "xml" to "application/xml", "yaml" to "application/yaml",
            "yml" to "application/yaml", "toml" to "application/toml", "sh" to "application/x-sh",
            "swift" to "text/x-swift", "c" to "text/x-c", "h" to "text/x-c", "cpp" to "text/x-c++src",
            "java" to "text/x-java-source", "py" to "text/x-python-script", "rb" to "text/x-ruby-script",
            "ics" to "text/calendar", "vcf" to "text/vcard",
            // Binaries
            "dmg" to "application/x-apple-diskimage", "apk" to "application/vnd.android.package-archive",
            "wasm" to "application/wasm", "bin" to "application/octet-stream",
        )
    }
}
