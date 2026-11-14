package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.collectState
import ai.opencode.core.store.ifLet
import ai.opencode.core.store.scope
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.OpenCodeTopBarDefaults
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.rememberAppAppearance
import ai.opencode.feature.media.FullscreenViewerFeature.Action
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import ai.opencode.server.model.AgentFileReference.Kind
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

// MARK: - Photo save

sealed class PhotoSaveClientError : Exception() {
    /** The system refused the write. */
    data object Denied : PhotoSaveClientError()
}

/**
 * Saves an image to the photo library. Android: a MediaStore insert into Pictures, which needs no
 * permission at this app's minimum SDK (35; `WRITE_EXTERNAL_STORAGE` only mattered on Android 9 and
 * earlier).
 */
class PhotoSaveClient(
    val saveImage: suspend (Bitmap) -> Unit = {},
) {
    companion object Key : DependencyKey<PhotoSaveClient> {
        override val liveValue: PhotoSaveClient by lazy {
            PhotoSaveClient(saveImage = { image -> saveToPictures(dependency(AppContext), image) })
        }
        override val testValue: PhotoSaveClient = PhotoSaveClient()

        private suspend fun saveToPictures(context: Context, image: Bitmap) = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val png = image.hasAlpha()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$stamp.${if (png) "png" else "jpg"}")
                put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = try {
                resolver.insert(collection, values)
            } catch (_: SecurityException) {
                throw PhotoSaveClientError.Denied
            } ?: throw PhotoSaveClientError.Denied
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    val written = image.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, stream)
                    check(written) { "compress failed" }
                } ?: error("no output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }
    }
}

// MARK: - Share

/**
 * Shares an image through the system share sheet: written to the cache and offered with
 * `ACTION_SEND` through [MediaFileProvider].
 */
class ImageShareClient(
    val share: suspend (Bitmap, String) -> Unit = { _, _ -> },
) {
    companion object Key : DependencyKey<ImageShareClient> {
        override val liveValue: ImageShareClient by lazy {
            ImageShareClient(share = { image, label -> shareImage(dependency(AppContext), image, label) })
        }
        override val testValue: ImageShareClient = ImageShareClient()

        private suspend fun shareImage(context: Context, image: Bitmap, label: String) {
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, MediaFileProvider.SHARE_DIRECTORY)
                directory.deleteRecursively()
                directory.mkdirs()
                val base = AttachmentFileType.sanitizedName(label.trim().ifEmpty { "Image" }).take(80)
                val file = File(directory, "$base.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            }
            val uri = FileProvider.getUriForFile(context, MediaFileProvider.authority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        }
    }
}

// MARK: - Page title

/**
 * A real file or attachment name to show at the top centre when controls are visible, or
 * `null` when [TranscriptImagePreview.Page.label] is a generic accessibility placeholder.
 */
val TranscriptImagePreview.Page.displayTitle: String?
    get() {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (labelIsPlaceholder) return null
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        // Labels that arrive already written (a model's alt text) are matched as English words.
        val placeholders = setOf(
            "image",
            "photo",
            "attachment",
            "attached image",
            "tool image",
            "agent image preview",
            "diagram",
        )
        if (lower in placeholders ||
            lower.startsWith("tool image ") ||
            lower.startsWith("attached image ") ||
            lower.startsWith("image ") ||
            lower.startsWith("photo ") ||
            lower.startsWith("attachment ") ||
            lower.startsWith("diagram, ")
        ) {
            return null
        }
        return trimmed
    }

// MARK: - Feature

/** [addDrawing] is the opener's prompt, for a viewer that [State.allowsDrawing]. */
class FullscreenViewerFeature(
    addDrawing: ImageDrawingAdd? = null,
) : Reducer<FullscreenViewerFeature.State, FullscreenViewerFeature.Action> {
    data class State(
        val preview: TranscriptImagePreview,
        val selectedID: String = preview.id,
        val coveredDocument: DocumentReturn? = null,
        val filePath: List<TranscriptImagePreview.Page> = emptyList(),
        val shareError: AgentFileError? = null,
        val noAppOpens: Boolean = false,
        val isShowingControls: Boolean = preview.pages.firstOrNull { it.id == selectedID }?.file != null,
        val isSaving: Boolean = false,
        val isDismissed: Boolean = false,
        /** Pages can be taken out (a composer's pending photos). */
        val allowsRemove: Boolean = false,
        /** The last page was removed: the viewer goes without flying home. */
        val isDiscarded: Boolean = false,
        /** Draw on Image hands a marked-up copy to the opener's prompt (`addDrawing`). */
        val allowsDrawing: Boolean = false,
        /** Draw on Image's editor, while it's up. */
        val drawing: ImageDrawingFeature.State? = null,
        /** Add to Prompt landed: the viewer goes once the editor has. */
        val closesAfterDrawing: Boolean = false,
    ) {
        val selectedPage: TranscriptImagePreview.Page?
            get() = filePath.lastOrNull() ?: preview.pages.firstOrNull { it.id == selectedID } ?: preview.pages.firstOrNull()
    }

    data class DocumentReturn(val preview: TranscriptImagePreview, val selectedID: String, val path: List<TranscriptImagePreview.Page>, val controls: Boolean)

    sealed interface Action {
        data object Task : Action
        data class PageSelected(val id: String) : Action
        data class RetryPageTapped(val id: String) : Action
        data class MediaLoaded(val pageID: String, val image: Bitmap?, val unavailable: Boolean = false) : Action
        data class SourceLoaded(val pageID: String, val image: Bitmap?) : Action
        data object ImageTapped : Action
        data object CloseTapped : Action
        data object SaveTapped : Action
        data class SaveFinished(val saved: Boolean) : Action
        data class ShareTapped(val file: AgentFileViewerModel? = null) : Action
        data class ShareFailed(val error: AgentFileError) : Action
        data object ShareErrorDismissed : Action
        data class OpenFile(val file: AgentFileDestination, val gallery: TranscriptImagePreview? = null) : Action
        data object BackTapped : Action
        data object SourceTapped : Action
        data object WrapTapped : Action
        data class ExternalOpenTapped(val file: File) : Action
        data class FileImageLoaded(val file: AgentFileDestination, val image: Bitmap) : Action
        data object RemoveTapped : Action
        data class DrawCapturedFrame(val pageID: String, val frame: Bitmap) : Action
        data object DrawTapped : Action
        data class Drawing(val action: PresentationAction<ImageDrawingFeature.Action>) : Action
        data class Delegate(val action: DelegateAction) : Action

        sealed interface DelegateAction {
            /** The page's media is no longer wanted. */
            data class Removed(val id: String) : DelegateAction
        }
    }

    private data class PageLoadID(val id: String)

    private object ExportSlot
    private object DrawingSlot

    private val body: Reducer<State, Action> = ifLet(
        id = DrawingSlot,
        parent = Reducer(::reduceViewer),
        state = { it.drawing },
        setState = { state, drawing -> state.copy(drawing = drawing) },
        action = { (it as? Action.Drawing)?.action },
        embed = { Action.Drawing(it) },
        child = ImageDrawingFeature(addDrawing ?: { false }),
    )

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)

    private fun reduceViewer(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> loadSelection(state)

        is Action.PageSelected -> if (!state.isDismissed && !state.isDiscarded && state.filePath.isEmpty() &&
            state.selectedID != action.id && state.preview.pages.any { it.id == action.id }
        ) {
            val next = loadSelection(state.copy(selectedID = action.id))
            Reduced(next.state, Effect.merge(Effect.cancel(listOf(ExportSlot, PageLoadID(state.selectedID))), next.effect))
        } else Reduced(state)

        is Action.OpenFile -> if (state.selectedPage?.file != null) {
            val gallery = action.gallery
            if (gallery != null && gallery.pages.any { it.id == gallery.id } && gallery.pages.all { it.isVisual }) {
                val back = DocumentReturn(state.preview, state.selectedID, state.filePath, state.isShowingControls)
                val next = loadSelection(state.copy(preview = gallery, selectedID = gallery.id, filePath = emptyList(),
                    coveredDocument = back, noAppOpens = false, shareError = null, isShowingControls = true))
                Reduced(next.state, Effect.merge(Effect.cancel(ExportSlot), next.effect))
            } else navigate(state, state.filePath + TranscriptImagePreview.Page(action.file))
        } else Reduced(state)
        Action.BackTapped -> when {
            state.filePath.isNotEmpty() -> navigate(state, state.filePath.dropLast(1))
            state.coveredDocument != null -> {
                val back = state.coveredDocument
                Reduced(state.copy(preview = back.preview, selectedID = back.selectedID, filePath = back.path,
                    coveredDocument = null, isShowingControls = back.controls, drawing = null, shareError = null, noAppOpens = false),
                    Effect.cancel(listOf(ExportSlot, PageLoadID(state.selectedID))))
            }
            else -> Reduced(state)
        }
        Action.SourceTapped -> {
            val page = state.selectedPage
            if (page?.file == null) Reduced(state) else Reduced(state.withSelectedFile(page.copy(showsSource = !page.showsSource)))
        }
        Action.WrapTapped -> {
            val wraps = shared(SharedKeys.fileViewerWrapsLines)
            wraps.set(!wraps.value)
            Reduced(state)
        }
        is Action.ExternalOpenTapped -> {
            val reference = state.selectedPage?.file?.reference
            if (reference == null) Reduced(state) else Reduced(state.copy(noAppOpens = !dependency(AgentFileShareClient).view(action.file, reference.mime)))
        }
        is Action.FileImageLoaded -> {
            val page = state.selectedPage
            when {
                page == null || page.file != action.file -> Reduced(state)
                else -> Reduced(state.withSelectedFile(page.copy(image = action.image)))
            }
        }

        is Action.RetryPageTapped -> loadPageIfNeeded(action.id, state, forceRetry = true)

        is Action.MediaLoaded -> {
            val index = state.activePageIndex(action.pageID)
            if (index < 0) Reduced(state) else {
                val page = state.preview.pages[index]
                Reduced(state.withPage(index, page.copy(image = action.image ?: page.image,
                    isUnavailable = action.unavailable, loadFailed = action.image == null && !action.unavailable)))
            }
        }

        is Action.SourceLoaded -> {
            val index = state.activePageIndex(action.pageID)
            if (index < 0) Reduced(state) else {
                val page = state.preview.pages[index]
                Reduced(state.withPage(index, page.copy(image = action.image ?: page.image,
                    originalFailed = action.image == null && page.original != null,
                    loadFailed = action.image == null,
                    hasDecodedOriginal = action.image != null && page.original != null)))
            }
        }

        Action.ImageTapped -> Reduced(state.copy(isShowingControls = !state.isShowingControls))

        Action.CloseTapped -> Reduced(state.copy(isDismissed = true, drawing = null), Effect.cancel(listOf(ExportSlot, PageLoadID(state.selectedID))))

        Action.SaveTapped -> {
            val image = state.selectedPage?.image
            if (state.isSaving || state.selectedPage?.isVideo == true || state.selectedPage?.file != null || image == null) {
                Reduced(state)
            } else {
                val photoSave = dependency(PhotoSaveClient)
                Reduced(
                    state.copy(isSaving = true),
                    Effect.run { send ->
                        val saved = try {
                            photoSave.saveImage(image)
                            true
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            val code = if (error is PhotoSaveClientError.Denied) 0 else -1
                            AppLog.app.error { "Photo save failed: ${error::class.java.name} code=$code" }
                            false
                        }
                        send(Action.SaveFinished(saved))
                    },
                )
            }
        }

        is Action.SaveFinished -> {
            dependency(HapticsClient).play(if (action.saved) Haptic.success else Haptic.error)
            Reduced(state.copy(isSaving = false))
        }

        is Action.ShareFailed -> Reduced(state.copy(shareError = action.error))
        Action.ShareErrorDismissed -> Reduced(state.copy(shareError = null))
        is Action.ShareTapped -> {
            val page = state.selectedPage
            val image = page?.image
            val model = action.file
            if (state.isDismissed || state.isDiscarded) Reduced(state)
            else if (model != null && page?.file == AgentFileDestination(model.reference, model.directory)) {
                val share = dependency(AgentFileShareClient)
                Reduced(state, Effect.run<Action>(onError = { error, send ->
                    send(Action.ShareFailed(error as? AgentFileError ?: AgentFileError.network(error)))
                }) { _ -> model.exportFile()?.let { share.share(it, model.reference.mime) } }.cancellable(ExportSlot))
            } else if (page?.videoURL != null && page.file == null) {
                val share = dependency(LocalMovieShareClient)
                Reduced(state, Effect.run<Action>(onError = { error, send -> send(Action.ShareFailed(AgentFileError.network(error))) }) {
                    share.share(page.videoURL, page.name ?: page.label)
                }.cancellable(ExportSlot, cancelInFlight = true))
            } else if (page == null || page.isVideo || page.file != null || image == null) {
                Reduced(state)
            } else {
                val imageShare = dependency(ImageShareClient)
                Reduced(state, Effect.run { _ -> imageShare.share(image, page.label) })
            }
        }

        Action.RemoveTapped -> {
            val index = state.preview.pages.indexOfFirst { it.id == state.selectedID }
            if (!state.allowsRemove || state.filePath.isNotEmpty() || state.coveredDocument != null || state.isDiscarded || index < 0) {
                Reduced(state)
            } else {
                val pages = state.preview.pages.toMutableList()
                val removed = pages.removeAt(index)
                val removedEffect = Effect.send(Action.Delegate(Action.DelegateAction.Removed(removed.id)))
                if (pages.isEmpty()) {
                    Reduced(state.copy(isDiscarded = true), Effect.merge(Effect.cancel(listOf(ExportSlot, PageLoadID(removed.id))), removedEffect))
                } else {
                    val next = loadSelection(state.copy(preview = state.preview.copy(pages = pages),
                        selectedID = pages[min(index, pages.lastIndex)].id))
                    Reduced(next.state, Effect.merge(Effect.cancel(listOf(ExportSlot, PageLoadID(removed.id))), Effect.concatenate(removedEffect, next.effect)))
                }
            }
        }

        is Action.DrawCapturedFrame -> {
            val page = state.selectedPage
            if (page?.id != action.pageID || !page.isVideo || page.isUnavailable || page.loadFailed || page.originalFailed || !state.allowsDrawing || state.drawing != null ||
                state.closesAfterDrawing || state.isDismissed || state.isDiscarded) Reduced(state)
            else Reduced(state.copy(drawing = ImageDrawingFeature.State(action.frame)))
        }

        Action.DrawTapped -> {
            val page = state.selectedPage
            val image = page?.image
            if (!state.allowsDrawing || state.isDismissed || state.isDiscarded || page == null || page.isVideo || page.originalPending || page.originalFailed || page.loadFailed || image == null) {
                Reduced(state)
            } else {
                Reduced(state.copy(drawing = ImageDrawingFeature.State(image)))
            }
        }

        is Action.Drawing -> when ((action.action as? PresentationAction.Presented)?.action) {
            ImageDrawingFeature.Action.Delegate(ImageDrawingFeature.Action.DelegateAction.Added) ->
                Reduced(state.copy(closesAfterDrawing = true))
            ImageDrawingFeature.Action.Delegate(ImageDrawingFeature.Action.DelegateAction.Dismiss) ->
                Reduced(state.copy(drawing = null))
            else -> Reduced(state)
        }

        is Action.Delegate -> Reduced(state)
    }

    private fun State.withSelectedFile(page: TranscriptImagePreview.Page): State = if (filePath.isEmpty()) {
        withPage(preview.pages.indexOfFirst { it.id == selectedID }, page)
    } else copy(filePath = filePath.dropLast(1) + page)

    private fun navigate(state: State, path: List<TranscriptImagePreview.Page>): Reduced<State, Action> = Reduced(
        state.copy(filePath = path, selectedID = path.lastOrNull()?.id ?: state.preview.id,
            drawing = null, noAppOpens = false, shareError = null),
        Effect.cancel(ExportSlot),
    )

    private fun State.activePageIndex(id: String): Int =
        if (isDismissed || isDiscarded || filePath.isNotEmpty() || selectedID != id) -1
        else preview.pages.indexOfFirst { it.id == id }

    private fun loadSelection(state: State): Reduced<State, Action> {
        val selected = state.activePageIndex(state.selectedID)
        if (selected < 0) return Reduced(state)
        val pages = state.preview.pages.mapIndexed { index, page ->
            if (kotlin.math.abs(index - selected) > 1) page.evicted() else page
        }
        return loadPageIfNeeded(state.selectedID, state.copy(preview = state.preview.copy(pages = pages)))
    }

    private fun loadPageIfNeeded(id: String, state: State, forceRetry: Boolean = false): Reduced<State, Action> {
        val index = state.activePageIndex(id)
        if (index < 0) return Reduced(state)
        val page = state.preview.pages[index]
        if (page.usesFileViewer || page.isUnavailable || (!forceRetry && (page.loadFailed || page.originalFailed))) return Reduced(state)
        val original = page.original.takeIf { !page.hasDecodedOriginal }
        val source = page.imageSource.takeIf { page.image == null }
        val server = state.preview.server
        val decodeContext = dependency(VisualDecodeContext)
        val effect: Effect<Action> = if (original != null || source != null) {
            Effect.run { send ->
                val image = try {
                    withContext(decodeContext) {
                        if (original != null) decodeBitmap(original) else source?.load(server)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                send(Action.SourceLoaded(id, image))
            }
        } else {
            val hash = page.mediaHash ?: return Reduced(state)
            if (page.image != null) return Reduced(state)
            val references = dependency(MediaReferenceClient)
            Effect.run { send ->
                val loaded = withContext(decodeContext) {
                    val result = if (server != null) references.load(server, hash) else {
                        references.cachedData(hash)?.let(MediaReferenceLoadResult::Loaded) ?: MediaReferenceLoadResult.Unavailable
                    }
                    val image = (result as? MediaReferenceLoadResult.Loaded)?.let { CachedImage.decoded(id, it.data, hash) }
                    Action.MediaLoaded(id, image, unavailable = result == MediaReferenceLoadResult.Unavailable)
                }
                send(loaded)
            }
        }
        return Reduced(state.withPage(index, page.copy(loadFailed = false)), effect.cancellable(PageLoadID(id), cancelInFlight = true))
    }

    private fun State.withPage(index: Int, page: TranscriptImagePreview.Page): State =
        copy(preview = preview.copy(pages = preview.pages.toMutableList().also { it[index] = page }))

    private fun decodeBitmap(data: ByteArray): Bitmap? =
        if (data.isEmpty()) null else BitmapFactory.decodeByteArray(data, 0, data.size)
}

// MARK: - View

/** The session supplies a snapshot for visual links, resolved relative to the open document. */
val LocalDocumentVisualGallery = staticCompositionLocalOf<(AgentFileDestination) -> TranscriptImagePreview?> { { null } }

private val TranscriptImagePreview.Page.isVisual: Boolean
    get() = file == null || file.reference.kind == Kind.Image || file.reference.kind == Kind.Video

/**
 * The opener’s exact gallery or a single file. Linked documents scroll in their own page,
 * never inside the image pager. Both surfaces use the same selection, toolbar and editor.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun FullscreenViewer(
    store: StoreOf<FullscreenViewerFeature.State, FullscreenViewerFeature.Action>,
    viewer: MediaViewerState,
) {
    val state by store.collectState()
    val talkBack = rememberTalkBackEnabled()
    val page = state.selectedPage ?: return
    val root = state.filePath.isEmpty() && state.coveredDocument == null
    val gallery = state.filePath.isEmpty() && page.isVisual
    val tracksSource = root || state.coveredDocument != null
    val documentGallery = LocalDocumentVisualGallery.current
    val file = rememberSelectedFile(state)
    val ready = file?.phase as? AgentFileViewerModel.Phase.Ready
    val image = (ready?.content as? AgentFileViewerModel.Content.Image)?.image
    LaunchedEffect(file, image) {
        if (image != null && page.file != null) store.send(Action.FileImageLoaded(page.file, image))
    }
    MediaBackHandler(enabled = !root && state.drawing == null) { store.send(Action.BackTapped) }

    // The status bar and the image on screen, before the flight starts; again once a `media:<hash>`
    // page's image arrives.
    DisposableEffect(viewer, tracksSource, page.id, page.image) {
        if (tracksSource) viewer.show(sourceID = page.id, image = page.image) else viewer.isZoomed = false
        onDispose { }
    }
    LaunchedEffect(store) { store.send(FullscreenViewerFeature.Action.Task) }
    val showing = state.isShowingControls || talkBack
    DisposableEffect(viewer, showing) {
        viewer.setStatusBarHidden(!showing)
        onDispose { }
    }
    LaunchedEffect(state.isDismissed) {
        if (state.isDismissed) viewer.dismiss()
    }
    LaunchedEffect(state.isDiscarded) {
        if (state.isDiscarded) viewer.discard()
    }
    // The image flies home from the viewer, never from under the editor.
    val closesAfterDrawing = state.closesAfterDrawing && state.drawing == null
    LaunchedEffect(closesAfterDrawing) {
        if (closesAfterDrawing) viewer.dismiss()
    }

    val remote = ready?.content as? AgentFileViewerModel.Content.Player
    val movie = remote?.takeUnless { it.isAudio }?.player ?: page.videoURL?.takeIf { page.file == null }?.let { rememberLocalMoviePlayer(it) }
    val pip = movie?.let { rememberAgentFilePictureInPicture(it, false) }
    // Each registration belongs to this selection; an old surface's disposal cannot clear its successor.
    var capture by remember(page.id, movie) { mutableStateOf<(() -> Bitmap?)?>(null) }
    var captureFailed by remember(page.id, movie) { mutableStateOf(false) }
    val visualMovie: @Composable () -> Unit = {
        if (movie != null) MovieContentPage(movie, page.label, { store.send(Action.ImageTapped) }, { viewer.isZoomed = it },
            { frame -> if (tracksSource) viewer.mediaFrames[page.id] = frame; pip?.videoFrame = frame }, { capture = it },
            modifier = Modifier.testTag(if (pip?.isActive == true) "agent-file-pip" else if (remote?.isStreaming == true) "agent-file-player-streaming" else "agent-file-player"),
            isHidden = tracksSource && viewer.mediaHidden && viewer.image != null)
    }
    val wrapsLines by remember { shared(SharedKeys.fileViewerWrapsLines) }.flow.collectAsState()
    val visible = pip?.isActive != true && controlsAreVisible(state.isShowingControls, talkBack, viewer.chromeHidden)
    val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val reduceMotion = rememberReduceMotion()
    val enter = fadeIn(MotionRole.Swap.animation(MotionPhase.Enter, reduceMotion))
    val exit = fadeOut(MotionRole.Swap.animation(MotionPhase.Exit, reduceMotion))
    val actions: @Composable () -> Unit = {
        ViewerActions(state, file, wrapsLines, pip, capture != null, onDraw = {
            if (!page.isVideo) store.send(Action.DrawTapped) else {
                val frame = capture?.invoke()
                if (frame != null) store.send(Action.DrawCapturedFrame(page.id, frame)) else captureFailed = true
            }
        }, send = store::send)
    }
    val body: @Composable () -> Unit = {
        val background = when {
            file == null -> Color.Transparent
            !file.reference.kind.isMedia -> OpenCodeTheme.colors.canvas
            root -> Color.Transparent
            else -> Color.Black
        }
        Box(Modifier.fillMaxSize().background(background).then(if (file != null) Modifier.testTag("agent-file-viewer") else Modifier)) {
            when {
                gallery -> VisualPager(state, viewer, store::send, flight = tracksSource,
                    selectedContent = when {
                        movie != null -> visualMovie
                        file != null && image == null -> ({ AgentFilePage(file, wrapsLines, false, false, true, null, {}, {}) })
                        else -> null
                    })
                movie != null -> visualMovie()
                image != null -> ViewerImage(page, image, viewer, selected = true, flight = root, onTap = { store.send(Action.ImageTapped) })
                file != null -> Box(
                    Modifier.fillMaxSize().padding(top = safe.asPaddingValues().calculateTopPadding() + OpenCodeTopBarDefaults.height),
                    contentAlignment = Alignment.Center,
                ) {
                    AgentFilePage(file, wrapsLines, page.showsSource, state.noAppOpens, background == Color.Transparent, pip,
                        onOpen = { store.send(Action.OpenFile(it, documentGallery(it))) }, onExternalOpen = { store.send(Action.ExternalOpenTapped(it)) })
                }
                else -> Unit
            }
            AnimatedVisibility(visible, enter = enter, exit = exit, modifier = Modifier.align(Alignment.TopCenter)) {
                OpenCodeTopBar(
                    modifier = if (gallery) Modifier.padding(horizontal = 16.dp, vertical = 8.dp) else Modifier,
                    containerColor = background, hairline = file != null && !file.reference.kind.isMedia, centerTitle = true,
                    contentColor = if (gallery) Color.White else OpenCodeTheme.colors.text,
                    title = { ViewerTitle(page, file) },
                    navigation = {
                        when {
                            !root -> OpenCodeTopBarBackButton(onClick = { store.send(Action.BackTapped) })
                            !gallery && file != null -> TextButton(onClick = { store.send(Action.CloseTapped) }, modifier = Modifier.testTag("agent-file-done")) { Text(stringResource(CoreR.string.common_done)) }
                            else -> OpenCodeIconButton(SFSymbol.Xmark, stringResource(CoreR.string.common_close), { store.send(Action.CloseTapped) }, Modifier.testTag("image-gallery-close"))
                        }
                    },
                    actions = { if (!gallery) actions() },
                )
            }
            if (visible && movie != null) MovieTransport(movie,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(safe).padding(start = 16.dp, end = 16.dp, bottom = if (gallery) 72.dp else 16.dp))
            if (gallery) Box(Modifier.fillMaxSize().windowInsetsPadding(safe).padding(16.dp)) {
                AnimatedVisibility(visible && root && state.allowsRemove, enter = enter, exit = exit, modifier = Modifier.align(Alignment.BottomStart)) {
                    OpenCodeIconButton(SFSymbol.Trash, stringResource(CoreR.string.common_remove), { store.send(Action.RemoveTapped) },
                        Modifier.testTag("image-gallery-remove"), tint = MaterialTheme.colorScheme.error, enabled = !state.isDiscarded)
                }
                AnimatedVisibility(visible, enter = enter, exit = exit, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Surface(shape = OpenCodeShape.control, color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = Color.White) {
                        Row(Modifier.height(52.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) { actions() }
                    }
                }
            }
        }
    }
    if (!root && file?.reference?.kind?.isMedia == true) {
        OpenCodeTheme(theme = rememberAppAppearance().theme, appearance = AppearanceColorScheme.Dark, content = body)
    } else body()

    if (captureFailed) {
        val dismiss = { captureFailed = false }
        OpenCodeAlertDialog(onDismissRequest = dismiss, title = { Text("Couldn’t capture frame") },
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } })
    }
    state.shareError?.let { error ->
        val dismiss = { store.send(Action.ShareErrorDismissed) }
        OpenCodeAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.media_file_share_failed_title)) }, text = { Text(error.detail) },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(CoreR.string.common_ok)) } })
    }

    state.drawing?.let { first ->
        val drawing = remember(store) {
            // The last state, so the scope never reads `null` while its dismissal lands.
            var last = first
            store.scope<FullscreenViewerFeature.State, FullscreenViewerFeature.Action, ImageDrawingFeature.State, ImageDrawingFeature.Action>(
                state = { it.drawing?.also { current -> last = current } ?: last },
                action = { FullscreenViewerFeature.Action.Drawing(PresentationAction.Presented(it)) },
            )
        }
        ImageDrawingEditor(drawing)
    }
}

/** A link covers its document without dropping the download or PDF; popping releases just the link. */
@Composable
private fun rememberSelectedFile(state: FullscreenViewerFeature.State): AgentFileViewerModel? {
    val back = state.coveredDocument
    val preview = back?.preview ?: state.preview
    val links = back?.path ?: state.filePath
    val rootID = if (links.isEmpty()) back?.selectedID ?: state.selectedID else preview.id
    val path = listOfNotNull(preview.pages.firstOrNull { it.id == rootID }?.file) + links.mapNotNull { it.file } +
        listOfNotNull(state.selectedPage?.file.takeIf { back != null })
    val models = path.mapIndexed { index, file ->
        key(index, file) {
            val model = remember { AgentFileViewerModel(file.reference, requireNotNull(preview.server), file.directory) }
            DisposableEffect(model) { onDispose { model.release() } }
            model
        }
    }
    val selected = models.lastOrNull()?.takeIf { state.selectedPage?.file != null } ?: return null
    DisposableEffect(selected) {
        selected.open()
        onDispose { selected.stop() }
    }
    return selected
}

@Composable
private fun ViewerTitle(page: TranscriptImagePreview.Page, file: AgentFileViewerModel?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        page.displayTitle?.let { title ->
            Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                style = if (file != null) OpenCodeTextStyles.headline else OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.testTag("image-gallery-title"))
        }
        (file?.locationLabel ?: page.detail)?.let { detail ->
            Text(detail, maxLines = 1, overflow = TextOverflow.StartEllipsis, style = OpenCodeTextStyles.caption1,
                color = if (file != null) OpenCodeTheme.colors.secondaryText else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.testTag("image-gallery-detail"))
        }
    }
}

/** The same actions in the document title bar or the gallery's bottom toolbar. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun ViewerActions(
    state: FullscreenViewerFeature.State,
    file: AgentFileViewerModel?,
    wrapsLines: Boolean,
    pip: AgentFilePictureInPicture?,
    canCapture: Boolean,
    onDraw: () -> Unit,
    send: (Action) -> Unit,
) {
    val page = state.selectedPage ?: return
    val content = (file?.phase as? AgentFileViewerModel.Phase.Ready)?.content
    val markdown = content is AgentFileViewerModel.Content.Markdown && LocalAgentFileMarkdown.current != null
    if (markdown) {
        IconButton(onClick = { send(Action.SourceTapped) }, modifier = Modifier.testTag("agent-file-source-toggle")) {
            OpenCodeIcon(if (page.showsSource) "doc.richtext" else "chevron.left.forwardslash.chevron.right",
                contentDescription = if (page.showsSource) stringResource(R.string.media_file_show_preview_description) else stringResource(R.string.media_file_show_source_description))
        }
    }
    if (content is AgentFileViewerModel.Content.Text || (content is AgentFileViewerModel.Content.Markdown && (!markdown || page.showsSource))) {
        IconButton(onClick = { send(Action.WrapTapped) }) {
            OpenCodeIcon(if (wrapsLines) "arrow.left.and.right.text.vertical" else "text.word.spacing",
                contentDescription = if (wrapsLines) stringResource(R.string.media_file_unwrap_lines_description) else stringResource(R.string.media_file_wrap_lines_description))
        }
    }
    if (pip?.isSupported == true) {
        IconButton(onClick = pip::start, modifier = Modifier.testTag("agent-file-pip-start")) {
            OpenCodeIcon("pip.enter", contentDescription = stringResource(R.string.media_file_start_pip_description))
        }
    }
    if (state.allowsDrawing && (page.isVideo || file == null || content is AgentFileViewerModel.Content.Image)) {
        OpenCodeIconButton(SFSymbol.Pencil, stringResource(R.string.media_drawing_draw_on_image_description), onDraw, Modifier.testTag("image-gallery-draw"),
            style = OpenCodeIconButtonStyle.Standard, enabled = if (page.isVideo) canCapture else page.image != null && !page.originalPending && !page.originalFailed && !page.loadFailed)
    }
    if (file == null && !page.isVideo) {
        OpenCodeIconButton(SFSymbol.SquareAndArrowDown, stringResource(R.string.media_viewer_save_to_photos_description), { send(Action.SaveTapped) }, Modifier.testTag("image-gallery-save"),
            style = OpenCodeIconButtonStyle.Standard, enabled = !state.isSaving && page.image != null)
    }
    val progress = file?.shareProgress
    if (progress != null) {
        val downloadingDescription = stringResource(R.string.media_file_share_downloading_description)
        Box(Modifier.size(48.dp).testTag("agent-file-share-progress"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp).semantics {
                contentDescription = downloadingDescription
                stateDescription = progressText(progress.received, progress.expected)
            }, strokeWidth = 2.dp)
        }
    } else if (file == null || content != null) {
        OpenCodeIconButton(SFSymbol.SquareAndArrowUp, stringResource(CoreR.string.common_share), { send(Action.ShareTapped(file)) },
            Modifier.testTag(if (file == null) "image-gallery-share" else "agent-file-share"),
            style = OpenCodeIconButtonStyle.Standard, enabled = file != null || page.videoURL != null || (!page.isVideo && page.image != null))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VisualPager(state: FullscreenViewerFeature.State, viewer: MediaViewerState, send: (Action) -> Unit,
    flight: Boolean, selectedContent: (@Composable () -> Unit)?) {
    val pages = state.preview.pages
    val selectedID = rememberUpdatedState(state.selectedID)
    val initialPage = remember { pages.indexOfFirst { it.id == state.selectedID }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
            val id = pages.getOrNull(index)?.id ?: return@collect
            if (id != selectedID.value) send(Action.PageSelected(id))
        }
    }
    // A removed page hands the selection on: the pager follows it.
    LaunchedEffect(pagerState, pages, state.selectedID) {
        val target = pages.indexOfFirst { it.id == state.selectedID }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().testTag("image-gallery"),
        beyondViewportPageCount = 1,
        key = { pages[it].id },
    ) { index ->
        val page = pages[index]
        val selected = state.selectedID == page.id
        val image = page.image
        val tap = { send(Action.ImageTapped) }
        val placeholder = Modifier.fillMaxSize().background(Color.Black).clickable(
            interactionSource = null,
            indication = null,
            onClick = tap,
        )
        when {
            selected && selectedContent != null -> selectedContent()
            image != null -> ViewerImage(page, image, viewer, selected, flight = flight, onTap = tap)
            page.isUnavailable -> Box(placeholder) {
                MediaAttachmentUnavailableView(name = page.name ?: page.displayTitle)
            }
            page.loadFailed -> Box(placeholder) {
                MediaAttachmentUnavailableView(
                    name = page.name ?: page.displayTitle,
                    onRetry = { send(Action.RetryPageTapped(page.id)) },
                    isTransientFailure = true,
                )
            }
            else -> Box(placeholder, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun ViewerImage(
    page: TranscriptImagePreview.Page,
    image: Bitmap,
    viewer: MediaViewerState,
    selected: Boolean,
    flight: Boolean = true,
    onTap: () -> Unit,
) {
    ZoomableImagePage(
        image = image, label = page.label, isSelected = selected, isHidden = flight && viewer.mediaHidden && selected,
        modifier = if (page.file != null) Modifier.fillMaxSize().testTag("agent-file-image") else Modifier.fillMaxSize(),
        onSingleTap = onTap, onZoomChange = { if (selected) viewer.isZoomed = it },
        onFrame = { if (flight) viewer.mediaFrames[page.id] = it },
    )
}

fun controlsAreVisible(isShowingControls: Boolean, voiceOver: Boolean, chromeHidden: Boolean): Boolean =
    (isShowingControls || voiceOver) && !chromeHidden

/** TalkBack (touch exploration) is on. Live-updating. */
@Composable
internal fun rememberTalkBackEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The media area's `FileProvider` (its own subclass so the manifest merger never clashes
 * with another module's provider). Serves only the share cache directory.
 */
class MediaFileProvider : FileProvider(R.xml.media_file_paths) {
    companion object {
        const val SHARE_DIRECTORY = "media-share"
        fun authority(context: Context): String = "${context.packageName}.media.files"
    }
}
