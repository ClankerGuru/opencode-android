package ai.opencode.feature.session.seams

import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.pickerModels
import ai.opencode.server.model.AttachmentFileType
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.OpenCodeModel
import android.graphics.Bitmap
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
                                url = destination,
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
