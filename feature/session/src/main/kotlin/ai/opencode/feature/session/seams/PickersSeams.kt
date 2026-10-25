package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.files.ComposerStagedFile
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

// The picked-content loader the VIEW-PICKERS group (SessionViewAttachments.kt,
// SessionViewPhotoSheet.kt) uses until those views adopt :feature:composer's importers.

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

/**
 * SEAM: the session's attachment views still pass picked content URIs as strings with string
 * attachment IDs; `:feature:composer`'s `ComposerImageSource` takes `Uri`s and `UUID`s. Delete once
 * those views adopt the composer's importers (view step).
 */
sealed interface ComposerImageSource {
    /** `attachmentID`: chosen up front, so what it becomes is known before it loads. */
    val attachmentID: String? get() = null

    /** A picked or dropped content URI (iOS `.photosPickerItem` / `.itemProvider`). */
    data class Content(val uri: String, override val attachmentID: String? = null) : ComposerImageSource
    /** Bytes already in hand (a paste). */
    class Data(val data: ByteArray) : ComposerImageSource
    class Loader(val load: suspend () -> ByteArray?, override val attachmentID: String? = null) : ComposerImageSource
}
