package ai.opencode.feature.media

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A drag source for a file card or a gallery tile, given by the transcript (iOS
 * `.transcriptDragSource(.agentFile(...))` on `AgentFileCards` and `AgentImageGallery` tiles).
 * The media area can't see the transcript's drag code, so the transcript provides it here; null
 * (the default) draws the cards without one. `cachedImage` is the tile's decoded preview,
 * `cornerRadius` the surface's, for the lifted preview.
 */
val LocalAgentFileDragSource = staticCompositionLocalOf<((file: AgentFileReference, cachedImage: Bitmap?, cornerRadius: Dp) -> Modifier)?> { null }

/**
 * A drag source for a decoded image media draws in the transcript (iOS
 * `.transcriptDragSource(.image(image: shown, name: "diagram.png"))` on the Mermaid card), given
 * by the transcript like [LocalAgentFileDragSource]. Holding the surface runs `onLift` (where the
 * card opens its hold menu) and moving the held finger drags, closing that menu (`onDragStart`).
 */
val LocalTranscriptImageDragSource = staticCompositionLocalOf<((image: Bitmap, name: String, cornerRadius: Dp, onLift: () -> Unit, onDragStart: () -> Unit) -> Modifier)?> { null }

/**
 * Whether a finger still holds a lifted transcript surface (iOS: the context menu's lift, which a
 * move turns into a drag). A hold menu opened by the lift is a focusable popup window, and a
 * focusable window appearing cancels the touch still going to the surface, so the drag could
 * never start. Menus opened by a lift stay unfocusable while held ([liftMenuProperties]) and
 * become focusable (outside taps and Back dismiss them) once the finger lifts.
 */
object TranscriptLiftHold {
    var isHeld by mutableStateOf(false)
}

/** The popup properties for a menu a transcript surface's lift opens (see [TranscriptLiftHold]). */
fun liftMenuProperties(): PopupProperties = PopupProperties(focusable = !TranscriptLiftHold.isHeld)

/**
 * The bytes behind a dragged image or file, as `content://` URIs another app reads through
 * [AgentFileProvider] (iOS `NSItemProvider` file and data representations). Android's
 * [android.content.ClipData] is built when the drag starts, so what iOS loads lazily when the
 * drop asks for it starts loading here at once; [AgentFileProvider] holds a reader's `openFile`
 * until that finishes, so the drop still gets the whole file.
 */
object AgentFileDrag {
    /** `<cache>/TranscriptDrag/`: images written for a drag (iOS `TranscriptDrag-<uuid>` temporaries). */
    const val DIRECTORY = "TranscriptDrag"

    /** How long a reader waits for a download still running when it opens the file. */
    private const val WAIT_MILLIS = 5 * 60 * 1000L

    /** Older drag temporaries are removed when a new one is written. */
    private const val KEEP_MILLIS = 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /**
     * A server file: its cached download ([AgentFileLoader.cacheURL]) as a URI, downloading it
     * now when needed. When the download fails and [fallbackImage] is set (a tile's decoded
     * preview), the drop gets that as a PNG instead (iOS `writeTemporaryPNG` fallback).
     */
    fun serverFile(
        context: Context,
        server: OpenCodeServer,
        target: AgentFileReference.Target,
        fileName: String,
        fallbackImage: Bitmap? = null,
    ): Uri? {
        val request = server.fileReadRequest(target) ?: return null
        val destination = AgentFileLoader.cacheURL(server, target.absolutePath, fileName, context.cacheDir)
        val uri = FileProvider.getUriForFile(context, AgentFileProvider.authority(context), destination)
        val key = uri.toString()
        val done = CompletableDeferred<Unit>()
        if (pending.putIfAbsent(key, done) != null) return uri
        scope.launch {
            try {
                AgentFileLoader.download(request, destination, server) { _, _ -> }
            } catch (_: Exception) {
                if (fallbackImage != null) runCatching { writePng(fallbackImage, destination) }
            } finally {
                done.complete(Unit)
                pending.remove(key, done)
            }
        }
        return uri
    }

    /** Bytes already in memory (a sent image), written now. */
    fun bytes(context: Context, name: String, data: ByteArray): Uri? = runCatching {
        val file = temporaryFile(context, name)
        file.writeBytes(data)
        FileProvider.getUriForFile(context, AgentFileProvider.authority(context), file)
    }.getOrNull()

    /** A decoded image, encoded as PNG off the main thread; a reader waits for it. */
    fun png(context: Context, name: String, image: Bitmap): Uri? {
        val file = runCatching { temporaryFile(context, name) }.getOrNull() ?: return null
        val uri = FileProvider.getUriForFile(context, AgentFileProvider.authority(context), file)
        val key = uri.toString()
        val done = CompletableDeferred<Unit>()
        pending[key] = done
        scope.launch {
            try {
                runCatching { writePng(image, file) }
            } finally {
                done.complete(Unit)
                pending.remove(key, done)
            }
        }
        return uri
    }

    /** Called by [AgentFileProvider] before it opens or describes [uri]. */
    internal fun awaitPending(uri: Uri) {
        val job = pending[uri.toString()] ?: return
        runBlocking { withTimeoutOrNull(WAIT_MILLIS) { job.await() } }
    }

    private fun temporaryFile(context: Context, name: String): File {
        val root = File(context.cacheDir, DIRECTORY)
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { if (now - it.lastModified() > KEEP_MILLIS) it.deleteRecursively() }
        val folder = File(root, UUID.randomUUID().toString().uppercase())
        folder.mkdirs()
        return File(folder, AttachmentFileType.sanitizedName(name))
    }

    private fun writePng(image: Bitmap, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.path + ".part")
        temporary.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }
}
