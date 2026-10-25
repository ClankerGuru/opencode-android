package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.AttachmentMenuItem
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerFileRouting
import ai.opencode.feature.session.seams.ComposerImageSource
import ai.opencode.feature.session.seams.ComposerMediaLoader
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.feature.system.share.SharedImageEncoder
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.PastedText
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

// Port of iOS `Session/SessionView+Attachments.swift`: the composer's pending attachments row
// (images, pasted text, inline PDFs, uploading files, the location card), image and file imports
// with their errors and the large-upload confirmation, dropped text, and the + menu's items. The
// + menu's open/close/morph state is VIEW-COMPOSER's (iOS keeps it in `SessionView`'s @State);
// the photo sheet is `SessionViewPhotoSheet.kt`.

// region Imports (iOS `startImageImport`, `startFileImport`, `importPickedFiles`, drops)

/**
 * The session screen's attachment imports, iOS `SessionView`'s `imageImporter`, `fileImporter`,
 * `attachmentError` and `largeUpload`. Owned by the screen ([rememberSessionAttachmentImporter]
 * in `SessionView`) so an import outlives the picker or sheet that started it; its results go to
 * the reducer as `attachmentAdded` / `filesStaged`. One image import runs at a time (a new one
 * supersedes it); file imports each finish on their own. Results for a composer that's no longer
 * on screen (another session, or a draft that became one) are dropped.
 */
@Stable
class SessionAttachmentImporter internal constructor(
    private val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    private val scope: CoroutineScope,
    private val loader: ComposerMediaLoader,
) {
    /** iOS `ComposerImageImporter.Result`. */
    data class ImageImportResult(val addedCount: Int = 0, val sawOversizedSource: Boolean = false)

    /** Files over [LARGE_UPLOAD_BYTES] wait for this confirmation (iOS `SessionView.LargeUpload`). */
    data class LargeUpload(val files: List<ComposerStagedFile>, val onStaged: (() -> Unit)?) {
        val title: String get() = "Upload ${AttachmentFileType.byteCount(files.sumOf { it.size })}?"

        val message: String
            get() {
                val large = files.filter { it.size > LARGE_UPLOAD_BYTES }
                val subject = if (large.size == 1) "“${large[0].name}” is a large file" else "${large.size} files are large"
                return "$subject. The message sends once the upload to your server finishes."
            }
    }

    /** “Couldn’t Add Image” alert's message (iOS `attachmentError`). */
    var attachmentError: String? by mutableStateOf(null)

    /** The large-upload confirmation waiting, if any. */
    var largeUpload: LargeUpload? by mutableStateOf(null)
        private set

    private var imageJob: Job? by mutableStateOf(null)
    private var imageCancelHandler: (() -> Unit)? = null
    private var activeFileImports by mutableIntStateOf(0)

    /** iOS `imageImporter.isLoading`. */
    val isLoadingImages: Boolean get() = imageJob != null

    /** iOS `fileImporter.isLoading`. */
    val isLoadingFiles: Boolean get() = activeFileImports > 0

    /** The composer an import started in is still the one on screen. */
    private fun sameComposer(identity: SessionFeature.State.TaskIdentity): Boolean = store.state.taskIdentity == identity

    /**
     * iOS `startImageImport`: loads, size-checks (20 MB) and re-encodes each image, adding each as
     * it lands. [onFailure] gets a source's chosen attachment ID when it fails; [onAdded] runs once
     * when at least one was added; otherwise the error explains why none were.
     */
    fun startImageImport(
        sources: List<ComposerImageSource>,
        onAdded: (() -> Unit)? = null,
        onFailure: (String?) -> Unit = {},
        onCancel: () -> Unit = {},
        onCompletion: (ImageImportResult) -> Unit = {},
    ) {
        val identity = store.state.taskIdentity
        attachmentError = null
        cancelImageImportJob()
        for (dropped in sources.drop(AttachmentPickerButton.MAX_PHOTO_SELECTION)) onFailure(dropped.attachmentID)
        val limited = sources.take(AttachmentPickerButton.MAX_PHOTO_SELECTION)
        if (limited.isEmpty()) {
            onCancel()
            return
        }
        imageCancelHandler = onCancel
        lateinit var job: Job
        job = scope.launch {
            var result = ImageImportResult()
            for (source in limited) {
                ensureActive()
                val data = load(source)
                ensureActive()
                if (data == null) {
                    onFailure(source.attachmentID)
                    continue
                }
                if (data.size > SharedImageEncoder.MAX_SOURCE_BYTES) {
                    result = result.copy(sawOversizedSource = true)
                    onFailure(source.attachmentID)
                    continue
                }
                val jpeg = withContext(Dispatchers.Default) { SharedImageEncoder.jpeg(data) }
                ensureActive()
                if (jpeg == null) {
                    onFailure(source.attachmentID)
                    continue
                }
                val attachment = OpenCodeOutgoingAttachment(
                    id = source.attachmentID ?: UUID.randomUUID().toString(),
                    data = jpeg,
                    mime = SharedImageEncoder.MIME,
                )
                if (sameComposer(identity)) store.send(SessionFeature.Action.attachmentAdded(attachment))
                result = result.copy(addedCount = result.addedCount + 1)
            }
            ensureActive()
            if (imageJob === job) {
                imageCancelHandler = null
                imageJob = null
            }
            onCompletion(result)
            if (!sameComposer(identity)) return@launch
            if (result.addedCount > 0) {
                onAdded?.invoke()
            } else {
                attachmentError = if (result.sawOversizedSource) {
                    "Choose an image smaller than 20 MB."
                } else {
                    "This image couldn’t be loaded. Try another photo."
                }
            }
        }
        imageJob = job
    }

    private suspend fun load(source: ComposerImageSource): ByteArray? = when (source) {
        is ComposerImageSource.Content -> loader.loadImage(source.uri)
        is ComposerImageSource.Data -> source.data
        is ComposerImageSource.Loader -> source.load()
    }

    private fun cancelImageImportJob() {
        imageJob?.cancel()
        imageJob = null
        val handler = imageCancelHandler
        imageCancelHandler = null
        handler?.invoke()
    }

    /** iOS `cancelImageImport`. */
    fun cancelImageImport() {
        cancelImageImportJob()
        attachmentError = null
    }

    /**
     * iOS `startFileImport`: stages each picked file (a private copy the upload streams from). A
     * file over 200 MB asks first ([largeUpload]); otherwise they go to the reducer as
     * `filesStaged`. [ids] gives each staged file its ID (a picked video keeps its pick's).
     */
    fun startFileImport(
        uris: List<String>,
        ids: List<UUID>? = null,
        onStaged: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        if (uris.isEmpty()) return
        val identity = store.state.taskIdentity
        attachmentError = null
        activeFileImports += 1
        scope.launch {
            val staged = mutableListOf<ComposerStagedFile>()
            var failures = 0
            uris.forEachIndexed { index, uri ->
                val file = loader.stage(uri, ids?.getOrNull(index) ?: UUID.randomUUID())
                if (file != null) staged += file else failures += 1
            }
            activeFileImports -= 1
            if (staged.isNotEmpty()) {
                if (!sameComposer(identity)) {
                    staged.forEach { loader.discard(it.url.absolutePath) }
                } else if (staged.any { it.size > LARGE_UPLOAD_BYTES }) {
                    // The server holds an upload in memory, so very large files ask first.
                    largeUpload = LargeUpload(staged, onStaged)
                } else {
                    store.send(SessionFeature.Action.filesStaged(staged))
                    onStaged?.invoke()
                }
            }
            if (failures > 0 && sameComposer(identity)) {
                onFailed?.invoke()
                attachmentError = if (failures == 1) "This file couldn’t be added. Try another file." else "$failures files couldn’t be added."
            }
        }
    }

    /** Upload in the large-upload alert. */
    fun confirmLargeUpload() {
        val upload = largeUpload ?: return
        store.send(SessionFeature.Action.filesStaged(upload.files))
        upload.onStaged?.invoke()
        largeUpload = null
    }

    /** Cancel in the large-upload alert: the staged copies go. */
    fun cancelLargeUpload() {
        val upload = largeUpload ?: return
        upload.files.forEach { loader.discard(it.url.absolutePath) }
        largeUpload = null
    }

    /**
     * iOS `importPickedFiles`: from Files (`OpenMultipleDocuments`), images keep the inline image
     * path and everything else uploads. [onFocus] is `focusComposer`.
     */
    fun importPickedFiles(uris: List<String>, onFocus: () -> Unit) {
        if (uris.isEmpty()) return
        scope.launch {
            val described = uris.map { it to loader.describe(it) }
            val images = described.filter { (_, info) -> ComposerFileRouting.isInlineImage(info.first, info.second) }.map { it.first }
            val files = described.filter { (_, info) -> !ComposerFileRouting.isInlineImage(info.first, info.second) }.map { it.first }
            if (images.isNotEmpty()) startImageImport(images.map { ComposerImageSource.Content(it) }, onAdded = onFocus)
            if (files.isNotEmpty()) startFileImport(files, onStaged = onFocus)
        }
    }

    /**
     * The text half of iOS `handleComposerDrop` (and a pasted text the field didn't take): a long
     * text becomes a Pasted Text attachment, anything else joins the draft on its own line.
     */
    fun insertDroppedText(text: String) {
        if (text.isEmpty()) return
        val state = store.state
        if (PastedText.isLong(text) && state.takesPasteAttachments) {
            store.send(SessionFeature.Action.attachmentAdded(PastedText.attachment(text)))
        } else if (state.draft.isEmpty()) {
            store.send(SessionFeature.Action.Binding(BindingAction.Draft(text)))
        } else {
            val separator = if (state.draft.endsWith("\n") || state.draft.endsWith(" ")) "" else "\n"
            store.send(SessionFeature.Action.Binding(BindingAction.Draft(state.draft + separator + text)))
        }
    }

    /**
     * iOS `syncPickedPhotos` / `addLibraryPhotos`: photos picked in the system picker attach at
     * once — images on the inline image path, videos as uploads (when the server takes them).
     * At most [AttachmentPickerButton.MAX_PHOTO_SELECTION]. [onAdded] is `focusComposer`.
     */
    fun importPickedMedia(uris: List<String>, onAdded: (() -> Unit)? = null) {
        if (uris.isEmpty()) return
        scope.launch {
            val picks = uris.take(AttachmentPickerButton.MAX_PHOTO_SELECTION)
            val kinds = picks.map { it to loader.isVideo(it) }
            val videos = kinds.filter { it.second }.map { it.first }
            val images = kinds.filter { !it.second }.map { it.first }
            if (videos.isNotEmpty() && store.state.supportsFileUploads) startFileImport(videos, onStaged = onAdded)
            // A video the server can't take as a file attaches as its first frame, as iOS's loader does.
            val imageSources = images + if (store.state.supportsFileUploads) emptyList() else videos
            if (imageSources.isNotEmpty()) startImageImport(imageSources.map { ComposerImageSource.Content(it) }, onAdded = onAdded)
        }
    }

    /** iOS `captureCameraPhoto`: a photo taken in the composer's camera attaches like a picked one. */
    fun captureCameraPhoto(data: ByteArray, onAdded: (() -> Unit)? = null) {
        cancelImageImportJob()
        startImageImport(listOf(ComposerImageSource.Data(data)), onAdded = onAdded)
    }

    /** iOS `pasteAttachments`: images from the clipboard (the + menu's Paste). */
    fun pasteAttachments(sources: List<ComposerImageSource>, onAdded: (() -> Unit)? = null) {
        startImageImport(sources, onAdded = onAdded)
    }

    /** iOS `removeComposerAttachment`: an image, paste or inline file by its ID, or an upload. */
    fun removeComposerAttachment(id: String) {
        val state = store.state
        val index = state.pendingAttachments.indexOfFirst { it.id == id }
        if (index >= 0) {
            store.send(SessionFeature.Action.attachmentRemoved(index))
            return
        }
        val file = state.pendingFiles.firstOrNull { it.id.toString() == id } ?: return
        store.send(SessionFeature.Action.fileRemoved(file.id))
    }

    /** iOS `composerAttachmentIDs`: what the composer holds, by ID. */
    val composerAttachmentIDs: Set<String>
        get() = store.state.pendingAttachments.map { it.id }.toSet() + store.state.pendingFiles.map { it.id.toString() }

    companion object {
        /** iOS `SessionView.largeUploadBytes`. */
        const val LARGE_UPLOAD_BYTES: Long = 200L * 1024 * 1024
    }
}

/** The screen's [SessionAttachmentImporter]; hoist it to `SessionView` so imports outlive their pickers. */
@Composable
fun rememberSessionAttachmentImporter(store: StoreOf<SessionFeature.State, SessionFeature.Action>): SessionAttachmentImporter {
    val scope = rememberCoroutineScope()
    return remember(store, scope) { SessionAttachmentImporter(store, scope, dependency(ComposerMediaLoader)) }
}

/**
 * The two import alerts iOS `SessionView` presents: “Couldn’t Add Image” with the import's
 * error, and “Upload <size>?” for very large files.
 */
@Composable
fun SessionAttachmentAlerts(importer: SessionAttachmentImporter) {
    val error = importer.attachmentError
    if (error != null) {
        AlertDialog(
            onDismissRequest = { importer.attachmentError = null },
            title = { Text("Couldn’t Add Image") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { importer.attachmentError = null }) { Text("OK") } },
        )
    }
    val upload = importer.largeUpload
    if (upload != null) {
        AlertDialog(
            onDismissRequest = importer::cancelLargeUpload,
            title = { Text(upload.title) },
            text = { Text(upload.message) },
            confirmButton = { TextButton(onClick = importer::confirmLargeUpload) { Text("Upload") } },
            dismissButton = { TextButton(onClick = importer::cancelLargeUpload) { Text("Cancel") } },
        )
    }
}

// endregion

// region + menu items

/**
 * iOS `attachmentMenuItems`: Camera (when there is one), Photos, Files (when the server takes
 * uploads), Location (one at a time; kept while the menu closes after choosing it), and Paste
 * (when the clipboard has an image).
 */
fun sessionAttachmentMenuItems(
    hasCamera: Boolean,
    supportsFileUploads: Boolean,
    wantsLocationAttachment: Boolean,
    keepingLocationRowWhileMenuCloses: Boolean,
    clipboardHasImages: Boolean,
): List<AttachmentMenuItem> = buildList {
    if (hasCamera) add(AttachmentMenuItem.Camera)
    add(AttachmentMenuItem.Photos)
    if (supportsFileUploads) add(AttachmentMenuItem.Files)
    if (!wantsLocationAttachment || keepingLocationRowWhileMenuCloses) add(AttachmentMenuItem.Location)
    if (clipboardHasImages) add(AttachmentMenuItem.Paste)
}

// endregion

// region Pending attachments row

/** iOS `PhotoFlights.slotSide` / `slotRadius` and `ComposerFileChip` metrics. */
object PendingAttachmentMetrics {
    val slotSide: Dp = 76.dp
    val slotRadius: Dp = 22.dp
    val chipWidth: Dp = 220.dp
    val chipHeight: Dp = 76.dp
    val chipRadius: Dp = 22.dp
    /** iOS `TranscriptMetrics.cardCornerRadius`. */
    val cardRadius: Dp = 14.dp
    /** iOS `TranscriptMetrics.standard`. */
    val standard: Dp = 12.dp
    /** Location card: `SharedLocationCard.compactWidth`, `compactMapHeight`, `defaultCompactFooterHeight`. */
    val locationWidth: Dp = 220.dp
    val locationMapHeight: Dp = 72.dp
    const val LOCATION_FOOTER_HEIGHT = 64f
    /** iOS `ComposerLocationAttachment.entranceScale`. */
    const val LOCATION_ENTRANCE_SCALE = 0.965f
}

/**
 * iOS `pendingAttachmentsRow`: the composer's attachments in one horizontal row — images, pasted
 * texts, inline files (PDFs the model reads), uploading files, then the location card — each with
 * a remove button over its top trailing corner. Values in, actions out; no store.
 *
 * @param attachments `state.pendingAttachments`.
 * @param files `state.pendingFiles`.
 * @param canRetryFiles `state.supportsFileUploads` (a failed upload offers retry).
 * @param showsLocation whether the location card is mounted (iOS `locationGate.isMounted`).
 * @param location `state.pendingLocation`; null while locating.
 * @param isLocating `state.isLocating`.
 * @param onRemoveAttachment `.attachmentRemoved(index)`.
 * @param onRemoveFile `.fileRemoved(id)` (cancels an upload in flight).
 * @param onRetryFile `.fileRetryTapped(id)`.
 * @param onRemoveLocation `.locationRemoved`.
 * @param onOpenPastedText opens a paste's viewer (iOS `PastedTextCard` tap); null: not tappable.
 * @param onOpenLocation opens the card's maps URL (iOS `openURL(location.mapsURL)`).
 */
@Composable
fun PendingAttachmentsRow(
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<ComposerFileAttachment>,
    canRetryFiles: Boolean,
    showsLocation: Boolean,
    location: SharedLocation?,
    isLocating: Boolean,
    onRemoveAttachment: (index: Int) -> Unit,
    onRemoveFile: (UUID) -> Unit,
    onRetryFile: (UUID) -> Unit,
    onRemoveLocation: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPastedText: ((OpenCodeOutgoingAttachment) -> Unit)? = null,
    onOpenLocation: ((String) -> Unit)? = null,
) {
    val reduceMotion = rememberReduceMotion()
    LazyRow(
        modifier,
        // Room for the remove buttons, which sit over the top trailing corner.
        contentPadding = PaddingValues(top = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(attachments, key = { _, attachment -> "attachment-${attachment.id}" }) { index, attachment ->
            val item = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
            val text = attachment.pastedText
            val summary = attachment.pasted
            when {
                attachment.isImage -> RemovableAttachment(
                    label = "Remove image attachment ${index + 1}",
                    identifier = "remove-pending-attachment-$index",
                    reduceMotion = reduceMotion,
                    onRemove = { onRemoveAttachment(index) },
                    modifier = item,
                ) {
                    AttachmentThumbnail(attachment, index)
                }
                text != null && summary != null -> RemovableAttachment(
                    label = "Remove ${PastedText.NAME}",
                    identifier = "remove-pasted-text-$index",
                    reduceMotion = reduceMotion,
                    onRemove = { onRemoveAttachment(index) },
                    modifier = item,
                ) {
                    ComposerPastedTextCard(
                        summary = summary,
                        onOpen = onOpenPastedText?.let { open -> { open(attachment) } },
                    )
                }
                else -> {
                    // A PDF the selected model reads natively: sent inline, no upload.
                    val name = attachment.name ?: "File"
                    RemovableAttachment(
                        label = "Remove $name",
                        identifier = "remove-pending-file-$name",
                        reduceMotion = reduceMotion,
                        onRemove = { onRemoveAttachment(index) },
                        modifier = item,
                    ) {
                        FileAttachmentCard(
                            name = name,
                            symbolName = AttachmentFileType.symbolName(name, attachment.mime),
                            width = PendingAttachmentMetrics.chipWidth,
                            height = PendingAttachmentMetrics.chipHeight,
                            modifier = Modifier
                                .background(secondarySystemFill(), RoundedCornerShape(PendingAttachmentMetrics.chipRadius))
                                .clearAndSetSemantics { contentDescription = "File attachment $name" }
                                .testTag("pending-file-$name"),
                        ) {
                            Text(AttachmentFileType.subtitle(name, attachment.mime, attachment.data.size.toLong()))
                        }
                    }
                }
            }
        }
        items(files, key = { "file-${it.id}" }) { file ->
            RemovableAttachment(
                label = if (file.isUploading) "Cancel upload of ${file.name}" else "Remove ${file.name}",
                identifier = "remove-pending-file-${file.name}",
                reduceMotion = reduceMotion,
                onRemove = { onRemoveFile(file.id) },
                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
            ) {
                ComposerFileChip(file, canRetry = canRetryFiles, onRetry = { onRetryFile(file.id) })
            }
        }
        if (showsLocation) {
            item(key = "location") {
                ComposerLocationAttachment(
                    location = location,
                    isLocating = isLocating,
                    onRemove = onRemoveLocation,
                    onOpen = onOpenLocation,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                )
            }
        }
    }
}

/**
 * iOS `removableAttachment`: [content] with a remove button (×) over its top trailing corner,
 * entering scaled from 0.72 with a fade (a fade under reduce motion). [arriving]: a photo flying
 * into it hasn't landed; its remove button hides until it does.
 */
@Composable
fun RemovableAttachment(
    label: String,
    identifier: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReduceMotion(),
    arriving: Boolean = false,
    content: @Composable () -> Unit,
) {
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entrance.value < 1f) entrance.animateTo(1f, Motion.microPop.spec())
    }
    Box(
        modifier.graphicsLayer {
            val progress = entrance.value.coerceIn(0f, 1f)
            alpha = progress
            val scale = 0.72f + 0.28f * entrance.value
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
        if (!arriving) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onRemove)
                    .openCodeTapTarget()
                    .semantics { contentDescription = label }
                    .testTag(identifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = 13.dp, tint = Color.White)
                }
            }
        }
    }
}

/** iOS `attachmentThumbnail`: the image, 76 × 76 with 22 dp corners, decoded off the main thread. */
@Composable
private fun AttachmentThumbnail(attachment: OpenCodeOutgoingAttachment, index: Int) {
    val density = LocalDensity.current
    val target = with(density) { PendingAttachmentMetrics.slotSide.roundToPx() }
    val image by produceState<ImageBitmap?>(null, attachment.id) {
        value = withContext(Dispatchers.Default) { decodeThumbnail(attachment.data, target) }
    }
    Box(
        Modifier
            .size(PendingAttachmentMetrics.slotSide)
            .clip(RoundedCornerShape(PendingAttachmentMetrics.slotRadius))
            .background(secondarySystemFill())
            .semantics { contentDescription = "Image attachment ${index + 1}" }
            .testTag("pending-attachment-$index"),
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun decodeThumbnail(data: ByteArray, target: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (_: Exception) {
    null
}

/**
 * The composer's Pasted Text card (iOS `PastedTextCard` as `pastedTextChip` configures it:
 * 220 × 76, the secondary fill, 22 dp corners, room for the remove button): the name, its length
 * and its first lines fading out. A tap opens the text.
 */
@Composable
fun ComposerPastedTextCard(
    summary: ai.opencode.server.model.PastedTextSummary,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PendingAttachmentMetrics.chipRadius)
    Column(
        modifier
            .width(PendingAttachmentMetrics.chipWidth)
            .heightIn(min = PendingAttachmentMetrics.chipHeight)
            .clip(shape)
            .background(secondarySystemFill(), shape)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Opens the text", onClick = onOpen) else Modifier)
            .clearAndSetSemantics {
                contentDescription = PastedText.NAME
                stateDescription = summary.summary
                if (onOpen != null) {
                    role = Role.Button
                    onClick("Opens the text") { onOpen(); true }
                }
            }
            .testTag("pasted-text-card")
            .padding(horizontal = PendingAttachmentMetrics.standard, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OpenCodeIcon(SFSymbol.DocPlaintext, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
            Text(
                PastedText.NAME,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(summary.length, style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"), color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
        }
        // The text goes on past the card.
        Column(
            Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Black.copy(alpha = 0.25f)),
                        blendMode = BlendMode.DstIn,
                    )
                },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            for (line in summary.preview) {
                Text(
                    line,
                    style = OpenCodeTextStyles.caption2.copy(fontFamily = FontFamily.Monospace),
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * iOS `FileAttachmentCard`: the two-line file card desktop uses — a type glyph, the name above a
 * type label ([detail]), and an optional quiet trailing glyph.
 */
@Composable
fun FileAttachmentCard(
    name: String,
    symbolName: String,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp? = null,
    detailLines: Int = 1,
    trailingSymbolName: String? = null,
    detail: @Composable () -> Unit,
) {
    Row(
        modifier
            .width(width)
            .heightIn(min = height ?: 0.dp)
            .padding(horizontal = PendingAttachmentMetrics.standard, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(symbolName, contentDescription = null, size = 24.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides OpenCodeTheme.colors.secondaryText,
                androidx.compose.material3.LocalTextStyle provides OpenCodeTextStyles.caption1.copy(color = OpenCodeTheme.colors.secondaryText),
            ) {
                Box(Modifier.heightIn(max = with(LocalDensity.current) { (16 * detailLines + 20).dp })) { detail() }
            }
        }
        if (trailingSymbolName != null) {
            OpenCodeIcon(trailingSymbolName, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.tertiaryText)
        }
    }
}

/**
 * iOS `ComposerFileChip`: a composer file's name, size and upload progress, or its failure with
 * a retry when the server takes uploads.
 */
@Composable
fun ComposerFileChip(file: ComposerFileAttachment, canRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val progress = file.progress
    val status = when (val s = file.status) {
        is ComposerFileAttachment.Status.Uploading -> "Uploading, ${percent(progress)}"
        is ComposerFileAttachment.Status.Uploaded -> AttachmentFileType.subtitle(file.name, file.mime, file.size)
        is ComposerFileAttachment.Status.Failed -> s.message
    }
    val retries = file.isFailed && canRetry
    val shape = RoundedCornerShape(PendingAttachmentMetrics.chipRadius)
    FileAttachmentCard(
        name = file.name,
        symbolName = AttachmentFileType.symbolName(file.name, file.mime),
        width = PendingAttachmentMetrics.chipWidth,
        height = PendingAttachmentMetrics.chipHeight,
        detailLines = if (file.isFailed) 2 else 1,
        modifier = modifier
            .clip(shape)
            .background(secondarySystemFill(), shape)
            .then(if (retries) Modifier.clickable(role = Role.Button, onClick = onRetry) else Modifier)
            .clearAndSetSemantics {
                contentDescription = if (retries) "Retry uploading ${file.name}" else "File attachment ${file.name}"
                stateDescription = status
                if (retries) {
                    role = Role.Button
                    onClick { onRetry(); true }
                }
            }
            .testTag("pending-file-${file.name}"),
    ) {
        when (val s = file.status) {
            is ComposerFileAttachment.Status.Uploading -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Uploading ${percent(progress)}", style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"), color = OpenCodeTheme.colors.secondaryText)
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.widthIn(max = 120.dp).fillMaxWidth())
            }
            is ComposerFileAttachment.Status.Uploaded -> Text(
                AttachmentFileType.subtitle(file.name, file.mime, file.size),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is ComposerFileAttachment.Status.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                val red = OpenCodeTheme.colors.destructive
                OpenCodeIcon(if (canRetry) SFSymbol.ArrowClockwise else SFSymbol.ExclamationmarkTriangle, contentDescription = null, size = 14.dp, tint = red)
                Text(s.message, color = red, maxLines = 2, overflow = TextOverflow.Ellipsis, style = OpenCodeTextStyles.caption1)
            }
        }
    }
}

/** iOS `ComposerFileAttachment.progress`: an upload's share sent; 1 once uploaded, 0 after a failure. */
private val ComposerFileAttachment.progress: Double
    get() = when (val s = status) {
        is ComposerFileAttachment.Status.Uploading -> if (size > 0) minOf(1.0, s.sent.toDouble() / size.toDouble()) else 0.0
        is ComposerFileAttachment.Status.Uploaded -> 1.0
        is ComposerFileAttachment.Status.Failed -> 0.0
    }

/** iOS `ComposerFileChip.percent`: “42%”. */
private fun percent(value: Double): String =
    NumberFormat.getPercentInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(value)

/**
 * iOS `ComposerLocationAttachment`: one stable 220 × (72 + footer) card across “Finding
 * Location…” and the resolved location, entering once from a 0.965 scale and revealing the
 * place in place when it's found. The remove button cancels while locating.
 *
 * @param onOpen opens [SharedLocationLinks.mapsURL] (a tap on the resolved card); null: inert.
 */
@Composable
fun ComposerLocationAttachment(
    location: SharedLocation?,
    isLocating: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((String) -> Unit)? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val fontScale = LocalDensity.current.fontScale
    val footerHeight = (PendingAttachmentMetrics.LOCATION_FOOTER_HEIGHT * fontScale).dp
    var displayed by remember { mutableStateOf(location) }
    LaunchedEffect(location, isLocating) {
        if (location != null) displayed = location else if (isLocating) displayed = null
    }
    val entrance = remember { Animatable(if (reduceMotion) 1f else PendingAttachmentMetrics.LOCATION_ENTRANCE_SCALE) }
    LaunchedEffect(Unit) { if (entrance.value < 1f) entrance.animateTo(1f, Motion.panelOpen.spec()) }
    val shape = RoundedCornerShape(PendingAttachmentMetrics.cardRadius)
    val active = location ?: displayed
    val label = if (location != null) "Remove location" else "Cancel location"
    RemovableAttachment(
        label = label,
        identifier = if (location != null) "remove-pending-location" else "cancel-pending-location",
        onRemove = onRemove,
        reduceMotion = true,
        modifier = modifier.scale(entrance.value),
    ) {
        Crossfade(
            targetState = active,
            animationSpec = if (reduceMotion) tween(0) else Motion.panelOpen.spec(),
            label = "locationReveal",
            modifier = Modifier
                .width(PendingAttachmentMetrics.locationWidth)
                .height(PendingAttachmentMetrics.locationMapHeight + footerHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) { resolved ->
            if (resolved == null) {
                LocatingSurface(footerHeight)
            } else {
                SharedLocationCompactCard(resolved, footerHeight, onOpen)
            }
        }
    }
}

@Composable
private fun LocatingSurface(footerHeight: Dp) {
    Column(Modifier.fillMaxSize().semantics(mergeDescendants = true) {}) {
        MapPlaceholder(Modifier.fillMaxWidth().height(PendingAttachmentMetrics.locationMapHeight))
        Column(
            Modifier.fillMaxWidth().height(footerHeight).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    "Finding Location…",
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Current coordinates", style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
        }
    }
}

/** iOS `SharedLocationCard(compact: true)`: the spot, its place or address, and its coordinates. */
@Composable
private fun SharedLocationCompactCard(location: SharedLocation, footerHeight: Dp, onOpen: ((String) -> Unit)?) {
    val title = location.place ?: location.address ?: "Shared Location"
    val subtitle = if (location.place != null) location.address else null
    Column(
        Modifier
            .fillMaxSize()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(SharedLocationLinks.mapsURL(location)) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = "Location: $title" + (subtitle?.let { ", $it" } ?: "")
                if (onOpen != null) {
                    role = Role.Button
                    onClick("Opens in Maps") { onOpen(SharedLocationLinks.mapsURL(location)); true }
                }
            }
            .testTag("composer-location-card"),
    ) {
        MapPlaceholder(Modifier.fillMaxWidth().height(PendingAttachmentMetrics.locationMapHeight))
        Column(
            Modifier.fillMaxWidth().height(footerHeight).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon(SFSymbol.LocationFill, contentDescription = null, size = 12.dp, tint = Color(0xFF0A84FF))
                Text(
                    title,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(subtitle, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                SharedLocationLinks.coordinateText(location),
                style = OpenCodeTextStyles.caption2.copy(fontFamily = FontFamily.Monospace),
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
            )
        }
    }
}

/** The map's place: the tertiary fill with the red pin (Android has no MapKit snapshot). */
@Composable
private fun MapPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            OpenCodeIcon(SFSymbol.MappinCircleFill, contentDescription = null, size = 30.dp, tint = Color(0xFFFF3B30))
        }
    }
}

/** iOS `SharedLocation.coordinateText` / `mapsURL`, for the composer's card. */
object SharedLocationLinks {
    fun coordinateText(location: SharedLocation): String = location.coordinateText

    /** Android's maps link: a `geo:` URI at the spot, labelled as iOS's Apple Maps link is. */
    fun mapsURL(location: SharedLocation): String {
        val ll = String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude)
        val label = android.net.Uri.encode(location.place ?: location.address ?: "Shared Location")
        return "geo:$ll?q=$ll($label)"
    }
}

/** iOS `Color(.secondarySystemFill)`. */
@Composable
private fun secondarySystemFill(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

// endregion
