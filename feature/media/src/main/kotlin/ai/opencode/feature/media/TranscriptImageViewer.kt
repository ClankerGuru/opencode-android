package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.files.AttachmentFileType
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: - Photo save

/** iOS `PhotoSaveClientError`. */
sealed class PhotoSaveClientError : Exception() {
    /** The system refused the write (iOS: add-only Photos access denied). */
    data object Denied : PhotoSaveClientError()
}

/**
 * Saves an image to the photo library (iOS `PhotoSaveClient`, `@Dependency(\.photoSave)`).
 * Android: a MediaStore insert into Pictures, which needs no permission at this app's
 * minimum SDK (35; `WRITE_EXTERNAL_STORAGE` only mattered on Android 9 and earlier).
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
 * Shares an image through the system share sheet (iOS `ImageShareClient`,
 * `@Dependency(\.imageShare)`): written to the cache and offered with `ACTION_SEND` through
 * [MediaFileProvider].
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
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
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

/** iOS `TranscriptImageViewerFeature`. */
class TranscriptImageViewerFeature : Reducer<TranscriptImageViewerFeature.State, TranscriptImageViewerFeature.Action> {
    data class State(
        val preview: TranscriptImagePreview,
        val selectedID: String = preview.id,
        val isShowingControls: Boolean = false,
        val isSaving: Boolean = false,
        val isDismissed: Boolean = false,
    ) {
        val selectedPage: TranscriptImagePreview.Page?
            get() = preview.pages.firstOrNull { it.id == selectedID } ?: preview.pages.firstOrNull()
    }

    sealed interface Action {
        data class PageSelected(val id: String) : Action
        data object ImageTapped : Action
        data object CloseTapped : Action
        data object SaveTapped : Action
        data class SaveFinished(val saved: Boolean) : Action
        data object ShareTapped : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.PageSelected -> Reduced(state.copy(selectedID = action.id))

        Action.ImageTapped -> Reduced(state.copy(isShowingControls = !state.isShowingControls))

        Action.CloseTapped -> Reduced(state.copy(isDismissed = true))

        Action.SaveTapped -> {
            val image = state.selectedPage?.image
            if (state.isSaving || image == null) {
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
                            // iOS logs the NSError's domain and code; a Swift enum error's code is its case index.
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

        Action.ShareTapped -> {
            val page = state.selectedPage
            if (page == null) {
                Reduced(state)
            } else {
                val imageShare = dependency(ImageShareClient)
                Reduced(state, Effect.run { _ -> imageShare.share(page.image, page.label) })
            }
        }
    }
}

// MARK: - View

/**
 * Transcript images, full screen (iOS `TranscriptImageViewer`): swipe between them, pinch or
 * double-tap to zoom, single-tap to toggle controls, drag down to put them back. Opens
 * showing only the photo (controls visible when TalkBack is on).
 */
@Composable
fun TranscriptImageViewer(
    preview: TranscriptImagePreview,
    viewer: MediaViewerState,
) {
    val store = remember(preview.id) {
        Store(TranscriptImageViewerFeature.State(preview), TranscriptImageViewerFeature())
    }
    DisposableEffect(store) { onDispose { store.close() } }
    TranscriptImageViewer(store, viewer)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TranscriptImageViewer(
    store: StoreOf<TranscriptImageViewerFeature.State, TranscriptImageViewerFeature.Action>,
    viewer: MediaViewerState,
) {
    val state by store.collectState()
    val talkBack = rememberTalkBackEnabled()
    val pages = state.preview.pages

    // iOS `init`: the status bar and the image on screen, before the flight starts.
    DisposableEffect(viewer, state.selectedID) {
        viewer.show(sourceID = state.selectedID, image = state.selectedPage?.image)
        onDispose { }
    }
    val showing = state.isShowingControls || talkBack
    DisposableEffect(viewer, showing) {
        viewer.setStatusBarHidden(!showing)
        onDispose { }
    }
    LaunchedEffect(state.isDismissed) {
        if (state.isDismissed) viewer.dismiss()
    }

    val controlsVisible = controlsAreVisible(state.isShowingControls, talkBack, viewer.chromeHidden)

    // The overlays keep to the safe area; the pages fill the screen, so a page's fitted image
    // is where the flight lands.
    Box(Modifier.fillMaxSize()) {
        val initialPage = remember { pages.indexOfFirst { it.id == state.selectedID }.coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
                val id = pages.getOrNull(index)?.id ?: return@collect
                if (id != store.state.selectedID) store.send(TranscriptImageViewerFeature.Action.PageSelected(id))
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("image-gallery"),
            beyondViewportPageCount = 1,
            key = { pages[it].id },
        ) { index ->
            val page = pages[index]
            val selected = state.selectedID == page.id
            ZoomableImagePage(
                image = page.image,
                label = page.label,
                isSelected = selected,
                isHidden = viewer.mediaHidden && selected,
                onSingleTap = { store.send(TranscriptImageViewerFeature.Action.ImageTapped) },
                onZoomChange = { zoomed -> if (store.state.selectedID == page.id) viewer.isZoomed = zoomed },
                onFrame = { viewer.mediaFrames[page.id] = it },
            )
        }

        val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        val enter = fadeIn(Motion.easeOut(0.18))
        val exit = fadeOut(Motion.easeOut(0.18))
        Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp),
            ) {
                OpenCodeIconButton(
                    symbol = SFSymbol.Xmark,
                    contentDescription = "Close",
                    onClick = { store.send(TranscriptImageViewerFeature.Action.CloseTapped) },
                    modifier = Modifier.testTag("image-gallery-close"),
                    style = OpenCodeIconButtonStyle.Circle,
                )
            }
            val title = state.selectedPage?.displayTitle
            AnimatedVisibility(
                visible = controlsVisible && title != null,
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 76.dp).padding(top = 8.dp),
            ) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = title.orEmpty(),
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.wrapContentHeight().testTag("image-gallery-title"),
                    )
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = enter,
                exit = exit,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier.height(52.dp).padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OpenCodeIconButton(
                            symbol = SFSymbol.SquareAndArrowDown,
                            contentDescription = "Save to Photos",
                            onClick = { store.send(TranscriptImageViewerFeature.Action.SaveTapped) },
                            modifier = Modifier.testTag("image-gallery-save"),
                            style = OpenCodeIconButtonStyle.Standard,
                            enabled = !state.isSaving,
                        )
                        OpenCodeIconButton(
                            symbol = SFSymbol.SquareAndArrowUp,
                            contentDescription = "Share",
                            onClick = { store.send(TranscriptImageViewerFeature.Action.ShareTapped) },
                            modifier = Modifier.testTag("image-gallery-share"),
                            style = OpenCodeIconButtonStyle.Standard,
                        )
                    }
                }
            }
        }
    }
}

/** iOS `TranscriptImageViewer.controlsAreVisible`. */
fun controlsAreVisible(isShowingControls: Boolean, voiceOver: Boolean, chromeHidden: Boolean): Boolean =
    (isShowingControls || voiceOver) && !chromeHidden

/** iOS `accessibilityVoiceOverEnabled`: TalkBack (touch exploration) is on. Live-updating. */
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
