package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.pickerModels
import ai.opencode.server.model.AttachmentFileType
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.OpenCodeModel
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

// Stand-ins for Composer/Settings/System types the VIEW-PICKERS group (SessionViewModelPicker.kt,
// SessionViewAttachments.kt, SessionViewPhotoSheet.kt) needs whose real ports belong to other
// areas. Each carries only what those views use.

// region Settings

/**
 * SEAM: real type belongs to :feature:settings (iOS `Settings/ExperimentalFeatures.swift`
 * `ModelPickerStyle`); delete when that area merges. Settings › Advanced › Model Picker.
 */
enum class ModelPickerStyle(val rawValue: String) {
    today("today"),
    sheet("sheet"),
    growFromComposer("grow-from-composer");

    val title: String
        get() = when (this) {
            today -> "Today"
            sheet -> "Sheet"
            growFromComposer -> "Grow from Composer"
        }

    companion object {
        const val STORAGE_KEY = "experimental-model-picker"

        /** iOS `.modelPickerStyle`: Today by default. Stored as its raw value. */
        val key = SharedKey.appStorage(STORAGE_KEY, today.rawValue)

        /** The style in use: the saved one (unknown values read as Today). */
        fun active(stored: String): ModelPickerStyle = entries.firstOrNull { it.rawValue == stored } ?: today
    }
}

// endregion

// region Model picker (Composer/ModelPicker)

/** SEAM: real tunables belong to :feature:composer (iOS `ModelPickerOverlays.swift`); shipped defaults. */
object ModelPickerTunables {
    /** Sheet: a flick down from full closes at this speed (pt/s → dp/s). */
    const val FLICK_CLOSES = 800f
    /** Large gap below the session header. */
    const val HEADER_GAP = 6f
}

/** SEAM: real tunables belong to :feature:composer (iOS `FusedModelPickerTunables`); shipped defaults. */
object FusedModelPickerTunables {
    /** Recent rows. */
    const val RECENTS = 3
    /** All Models section on (1). */
    const val ALL_MODELS = 1
}

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/ModelPicker/ModelPickerSections.swift`);
 * delete when that area merges. Favorites, Recent, then one section per provider.
 */
data class ModelPickerSection(val kind: Kind, val models: List<OpenCodeModel>) {
    sealed interface Kind {
        data object Favorites : Kind
        data object Recent : Kind
        data object AllModels : Kind
        data object SearchResults : Kind
        data class Provider(val id: String) : Kind
    }

    val id: String
        get() = when (val k = kind) {
            Kind.Favorites -> "favorites"
            Kind.Recent -> "recent"
            Kind.AllModels -> "all"
            Kind.SearchResults -> "search"
            is Kind.Provider -> "provider-${k.id}"
        }

    val title: String
        get() = when (val k = kind) {
            Kind.Favorites -> "Favorites"
            Kind.Recent -> "Recent"
            Kind.AllModels -> "All Models"
            Kind.SearchResults -> ""
            is Kind.Provider -> ModelProviderGroup.title(k.id)
        }

    companion object {
        /**
         * [recents] (most recent first) adds a Recent section of up to [recentLimit] models after
         * Favorites, leaving out favorites. Searching drops both.
         */
        fun sections(
            models: List<OpenCodeModel>,
            favorites: Set<String>,
            filter: String = "",
            recents: List<String> = emptyList(),
            recentLimit: Int = 0,
        ): List<ModelPickerSection> {
            val needle = filter.trim()
            // Searching, providers come in the order of their best match.
            val shown = ModelSearch.rank(models, needle, favorites)
            val sections = mutableListOf<ModelPickerSection>()
            if (needle.isEmpty()) {
                val favored = shown.filter { it.id in favorites }
                if (favored.isNotEmpty()) sections += ModelPickerSection(Kind.Favorites, favored)
                val byID = LinkedHashMap<String, OpenCodeModel>()
                for (model in models) byID.putIfAbsent(model.id, model)
                val recent = recents.asSequence().filter { it !in favorites }.mapNotNull { byID[it] }.take(maxOf(recentLimit, 0)).toList()
                if (recent.isNotEmpty()) sections += ModelPickerSection(Kind.Recent, recent)
            }
            // Providers in the order the server lists them.
            val byProvider = LinkedHashMap<String, MutableList<OpenCodeModel>>()
            for (model in shown) byProvider.getOrPut(model.providerID) { mutableListOf() } += model
            sections += byProvider.map { (id, list) -> ModelPickerSection(Kind.Provider(id), list) }
            return sections
        }
    }
}

/**
 * SEAM: real member belongs to the port of iOS `ModelPickerSections.swift`
 * (`modelSheetSections(recents:recentLimit:)`), which extends `SessionFeature.State`.
 */
fun SessionFeature.State.modelSheetSections(recents: List<String>, recentLimit: Int): List<ModelPickerSection> =
    ModelPickerSection.sections(pickerModels, favoriteModelIDs.value, modelFilter, recents, recentLimit)

// endregion

// region Attachments (Composer/AttachmentMenu, ComposerImageImport, System/Share)

/** SEAM: real type belongs to :feature:composer (iOS `Composer/AttachmentMenu.swift` `AttachmentMenuItem`). */
enum class AttachmentMenuItem { camera, photos, files, location, paste }

/** SEAM: real constant belongs to :feature:composer (iOS `Composer/AttachmentPicker.swift`). */
object AttachmentPickerButton {
    const val MAX_PHOTO_SELECTION = 4
}

/** SEAM: real type belongs to :feature:system (iOS `System/Share/ShareHandoff.swift` `SharedImageEncoder`). */
object SharedImageEncoder {
    const val MAX_SOURCE_BYTES = 20_000_000L
    const val MAX_PIXEL_SIZE = 1568
    const val COMPRESSION_QUALITY = 80
    const val MIME = "image/jpeg"

    /** Downscaled (longest side [MAX_PIXEL_SIZE], orientation applied) and re-encoded as JPEG. */
    fun jpeg(data: ByteArray): ByteArray? {
        if (data.size > MAX_SOURCE_BYTES) return null
        val bitmap = decode(data) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, out)) null else out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(data: ByteArray): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_PIXEL_SIZE) {
                val scale = MAX_PIXEL_SIZE.toDouble() / longest
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * SEAM: real client belongs to :feature:composer / :feature:system (iOS `ComposerImageSource.load`,
 * `ComposerItemProviderLoader`, `ComposerFileStaging`); delete when those areas merge. Reads picked
 * content off the main thread; never logs names or contents.
 */
class ComposerMediaLoader(
    /** The bytes of an image at [uri] (a video's first frame as JPEG), or null. */
    val loadImage: suspend (uri: String) -> ByteArray?,
    /** Whether [uri] is a video (picked photos and videos share one picker). */
    val isVideo: suspend (uri: String) -> Boolean,
    /** Copies [uri] into a fresh per-file staging folder, keeping its name; null when it can't. */
    val stage: suspend (uri: String, id: UUID) -> ComposerStagedFile?,
    /** Removes a staged copy (iOS `ComposerFileStaging.discard`). */
    val discard: (path: String) -> Unit,
    /** The display name and declared MIME type of [uri], for routing a pick. */
    val describe: suspend (uri: String) -> Pair<String, String?> = { it.substringAfterLast('/') to null },
) {
    companion object Key : DependencyKey<ComposerMediaLoader> {
        override val liveValue: ComposerMediaLoader by lazy { live() }
        override val testValue = ComposerMediaLoader({ null }, { false }, { _, _ -> null }, {})

        private fun live(): ComposerMediaLoader {
            val context = dependency(AppContext)
            val resolver = context.contentResolver
            val stagingRoot = File(context.cacheDir, "ComposerFiles")
            fun mimeOf(uri: Uri): String? = resolver.getType(uri)
            fun nameOf(uri: Uri): String? = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: Exception) {
                null
            }
            return ComposerMediaLoader(
                loadImage = { raw ->
                    withContext(Dispatchers.IO) {
                        val uri = raw.toUri()
                        try {
                            if (mimeOf(uri)?.startsWith("video/") == true) {
                                MediaMetadataRetriever().use { retriever ->
                                    retriever.setDataSource(context, uri)
                                    val frame = retriever.getFrameAtTime(0) ?: return@withContext null
                                    ByteArrayOutputStream().use { out ->
                                        frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                        out.toByteArray()
                                    }
                                }
                            } else {
                                resolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                },
                isVideo = { raw -> withContext(Dispatchers.IO) { mimeOf(raw.toUri())?.startsWith("video/") == true } },
                stage = { raw, id ->
                    withContext(Dispatchers.IO) {
                        val uri = raw.toUri()
                        try {
                            val name = nameOf(uri)
                            val declared = mimeOf(uri)
                            val fallbackName = "file" + (declared?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: "")
                            val fileName = AttachmentFileType.sanitizedName(name ?: uri.lastPathSegment ?: fallbackName)
                            val folder = File(stagingRoot, id.toString())
                            folder.mkdirs()
                            val destination = File(folder, fileName)
                            try {
                                val input = resolver.openInputStream(uri) ?: run {
                                    folder.deleteRecursively()
                                    return@withContext null
                                }
                                input.use { source -> destination.outputStream().use { source.copyTo(it) } }
                            } catch (error: Exception) {
                                folder.deleteRecursively()
                                throw error
                            }
                            ComposerStagedFile(
                                id = id,
                                name = fileName,
                                mime = declared ?: "application/octet-stream",
                                size = destination.length(),
                                url = destination.absolutePath,
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                },
                discard = { path -> File(path).parentFile?.takeIf { it.parentFile == stagingRoot }?.deleteRecursively() },
                describe = { raw ->
                    withContext(Dispatchers.IO) {
                        val uri = raw.toUri()
                        (nameOf(uri) ?: uri.lastPathSegment ?: "file") to mimeOf(uri)
                    }
                },
            )
        }
    }
}

/**
 * SEAM: real members belong to :feature:composer (iOS `Composer/AttachmentFileType.swift`
 * `label`, `subtitle`, `symbolName`); the :server port has only the naming helpers.
 */
object AttachmentFileTypeLabels {
    const val PDF_MIME = "application/pdf"

    /** The common subset of the language names desktop takes from Shiki. */
    private val languageNames = mapOf(
        "c" to "C", "cjs" to "JavaScript", "cpp" to "C++", "cs" to "C#", "css" to "CSS", "dart" to "Dart",
        "go" to "Go", "html" to "HTML", "java" to "Java", "js" to "JavaScript",
        "json" to "JSON", "jsx" to "JSX", "kt" to "Kotlin", "lua" to "Lua", "md" to "Markdown", "mjs" to "JavaScript",
        "php" to "PHP", "py" to "Python", "rb" to "Ruby", "rs" to "Rust", "sh" to "Shell", "sql" to "SQL",
        "swift" to "Swift", "toml" to "TOML", "ts" to "TypeScript", "tsx" to "TSX",
        "xml" to "XML", "yaml" to "YAML", "yml" to "YAML", "zsh" to "Zsh",
    )
    private val archiveMimes = setOf(
        "application/zip", "application/x-zip-compressed", "application/x-tar", "application/gzip", "application/x-gzip",
        "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar", "application/x-bzip2",
    )
    private val spreadsheetMimes = setOf(
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.apple.numbers",
    )
    private val presentationMimes = setOf(
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.presentation", "application/vnd.apple.keynote",
    )

    /** The chip subtitle: "PDF", a language name, or the extension in capitals. */
    fun label(name: String, mime: String): String {
        if (mime == PDF_MIME) return "PDF"
        val suffix = AttachmentFileType.fileExtension(name)
        if (suffix.isEmpty()) return "File"
        return languageNames[suffix] ?: suffix.uppercase()
    }

    fun subtitle(name: String, mime: String, size: Long): String = "${label(name, mime)} · ${formatFileSize(size)}"

    /** The SF Symbol name for the file's type glyph. */
    fun symbolName(name: String, mime: String): String {
        if (mime == PDF_MIME) return "doc.richtext"
        if (mime.startsWith("image/")) return "photo"
        if (mime.startsWith("video/")) return "film"
        if (mime.startsWith("audio/")) return "waveform"
        if (mime == "text/plain") {
            return if (languageNames[AttachmentFileType.fileExtension(name)] == null) "doc.text" else "chevron.left.forwardslash.chevron.right"
        }
        if (mime in archiveMimes) return "doc.zipper"
        if (mime in spreadsheetMimes) return "tablecells"
        if (mime in presentationMimes) return "rectangle.on.rectangle"
        return "doc"
    }

    /** Like `ByteCountFormatter` `.file`: decimal units. */
    fun formatFileSize(bytes: Long): String {
        if (bytes == 0L) return "Zero KB"
        if (bytes < 1000) return "$bytes ${if (bytes == 1L) "byte" else "bytes"}"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        val text = if (unit == 0) Math.round(value).toString() else String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")
        return "$text ${units[unit]}"
    }
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/ComposerImageImport.swift` `ComposerImageSource`). */
sealed interface ComposerImageSource {
    /** `attachmentID`: chosen up front, so what it becomes is known before it loads. */
    val attachmentID: String? get() = null

    /** A picked or dropped content URI (iOS `.photosPickerItem` / `.itemProvider`). */
    data class Content(val uri: String, override val attachmentID: String? = null) : ComposerImageSource
    /** Bytes already in hand (a paste). */
    class Data(val data: ByteArray) : ComposerImageSource
    class Loader(val load: suspend () -> ByteArray?, override val attachmentID: String? = null) : ComposerImageSource
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/ComposerFileImport.swift` `ComposerFileRouting`). */
object ComposerFileRouting {
    private val inlineImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif")

    /** Image files from Files keep the inline image path (re-encoded like photos); everything else uploads. */
    fun isInlineImage(name: String, mime: String?): Boolean =
        AttachmentFileType.fileExtension(name) in inlineImageExtensions ||
            (mime != null && mime.lowercase() in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif"))
}

// endregion
