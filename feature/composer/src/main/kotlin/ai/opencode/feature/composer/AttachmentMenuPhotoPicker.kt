package ai.opencode.feature.composer

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.composer.plusmenu.LibraryPhoto
import ai.opencode.feature.composer.plusmenu.PhotoLibraryAccess
import ai.opencode.feature.composer.plusmenu.PhotoLibraryAccessHost
import ai.opencode.feature.composer.plusmenu.PhotoLibraryClient
import ai.opencode.feature.composer.plusmenu.VideoDurationBadge
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The inline photo picker's query (iOS `PhotosPicker(matching: filter.matching(includesVideos:))`
 * with `PHPickerFilter.images / .videos / .screenshots`). Android has no inline system picker
 * with a Screenshots filter, so the card reads `MediaStore` itself; thumbnails, access and
 * change ticks come from the plus menu's [PhotoLibraryClient], whose ids are the same content
 * URIs this returns.
 */
class InlinePhotoLibraryClient(
    /** The newest matching photos and videos, newest first; [LibraryPhoto.id] is the item's content URI. */
    val photos: suspend (filter: InlinePhotoFilter, includesVideos: Boolean, limit: Int) -> List<LibraryPhoto>,
) {
    companion object Key : DependencyKey<InlinePhotoLibraryClient> {
        /** The whole library, bounded so a huge one never holds the card on its first load. */
        const val MAX_PHOTOS = 2_000

        override val liveValue: InlinePhotoLibraryClient by lazy {
            val context = dependency(AppContext).applicationContext
            InlinePhotoLibraryClient(photos = { filter, videos, limit -> query(context, filter, videos, limit) })
        }

        override val testValue: InlinePhotoLibraryClient = InlinePhotoLibraryClient(photos = { _, _, _ -> emptyList() })

        private suspend fun query(context: Context, filter: InlinePhotoFilter, includesVideos: Boolean, limit: Int): List<LibraryPhoto> =
            withContext(Dispatchers.IO) {
                val type = MediaStore.Files.FileColumns.MEDIA_TYPE
                val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
                val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                val (selection, args) = when (filter) {
                    InlinePhotoFilter.All ->
                        if (includesVideos) "$type = ? OR $type = ?" to arrayOf(image, video) else "$type = ?" to arrayOf(image)
                    // iOS `PHPickerFilter.screenshots`: Android keeps them in a Screenshots folder.
                    InlinePhotoFilter.Screenshots ->
                        "$type = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf(image, "%Screenshots/%")
                    InlinePhotoFilter.Videos -> "$type = ?" to arrayOf(video)
                }
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    type,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DURATION,
                )
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                val photos = ArrayList<LibraryPhoto>()
                val cursor = runCatching {
                    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, queryArgs, null)
                }.onFailure { AppLog.app.error { "Inline photo query failed: ${it::class.java.simpleName}" } }.getOrNull()
                cursor?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val typeCol = c.getColumnIndexOrThrow(type)
                    val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                    while (c.moveToNext() && photos.size < limit) {
                        ensureActive()
                        val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val created = when {
                            !c.isNull(takenCol) && c.getLong(takenCol) > 0 -> Instant.ofEpochMilli(c.getLong(takenCol))
                            !c.isNull(addedCol) -> Instant.ofEpochSecond(c.getLong(addedCol))
                            else -> null
                        }
                        photos += LibraryPhoto(
                            id = ContentUris.withAppendedId(base, c.getLong(idCol)).toString(),
                            created = created,
                            modified = if (c.isNull(modifiedCol)) null else Instant.ofEpochSecond(c.getLong(modifiedCol)),
                            videoDuration = if (isVideo) (if (c.isNull(durationCol)) 0L else c.getLong(durationCol)).coerceAtLeast(0) / 1000.0 else null,
                        )
                    }
                }
                photos
            }
    }
}

/**
 * The card picker's library: access, the photos matching the filter, and their thumbnails
 * (what iOS's out-of-process `PhotosPicker` keeps for itself). Owned by the overlay, so the
 * list survives the picker parking and unparking; reads go through the dependency clients.
 */
@Stable
class InlinePhotoLibrary(
    private val client: PhotoLibraryClient = dependency(PhotoLibraryClient),
    private val query: InlinePhotoLibraryClient = dependency(InlinePhotoLibraryClient),
) {
    var access: PhotoLibraryAccess by mutableStateOf(PhotoLibraryAccess.NotDetermined)
        private set
    var photos: List<LibraryPhoto> by mutableStateOf(emptyList())
        private set
    var isLoaded: Boolean by mutableStateOf(false)
        private set

    private val thumbnails = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_THUMBNAILS
    }

    fun refreshAccess(): PhotoLibraryAccess = client.access().also { access = it }

    /** Asks once (the card was chosen), as iOS's picker needs no prompt but MediaStore does. */
    suspend fun requestAccessIfNeeded() {
        if (refreshAccess() == PhotoLibraryAccess.NotDetermined) access = client.requestAccess()
    }

    suspend fun load(filter: InlinePhotoFilter, includesVideos: Boolean) {
        if (!refreshAccess().canRead) {
            photos = emptyList()
            isLoaded = true
            return
        }
        val loaded = query.photos(filter, includesVideos, InlinePhotoLibraryClient.MAX_PHOTOS)
        photos = loaded
        isLoaded = true
        AppLog.app.debug { "Inline photos loaded: ${loaded.size} (${filter.name})" }
    }

    fun changes() = client.changes()

    fun cachedThumbnail(id: String): ImageBitmap? = thumbnails[id]

    suspend fun thumbnail(id: String, side: Float, scale: Float): ImageBitmap? {
        thumbnails[id]?.let { return it }
        val image = client.thumbnail(id, side, scale, null) ?: return null
        thumbnails[id] = image
        return image
    }

    fun openSettings() = client.openSettings()

    fun manageLimitedSelection() = client.manageLimitedSelection()

    companion object {
        private const val MAX_THUMBNAILS = 240

        /** Thumbnail side (dp) the grid asks for; tiles are about a quarter of the card. */
        const val THUMBNAIL_SIDE = 120f
    }
}

private val tileDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private const val GRID_SPACING = 2

/**
 * The card's inline picker (iOS `PhotosPicker(selection:maxSelectionCount:selectionBehavior:
 * .continuousAndOrdered, matching:)` in `.inline` style, accessories hidden): a grid of the
 * library, newest first, where a tap adds or removes a photo from [selection] in tap order
 * (numbered), up to [AttachmentPickerButton.MAX_PHOTO_SELECTION]; the rest dim once the limit
 * is reached. The session mirrors [InlinePhotoSelection.items] onto attachments. A new
 * [filter] reloads the grid from the top, as iOS's picker does.
 *
 * @param isActive the card is showing: access is asked for then, never while it's parked.
 */
@Composable
internal fun InlinePhotoPicker(
    library: InlinePhotoLibrary,
    selection: InlinePhotoSelection<Uri>,
    filter: InlinePhotoFilter,
    includesVideos: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    PhotoLibraryAccessHost()
    val gridState = rememberLazyGridState()
    LaunchedEffect(isActive) {
        if (isActive) library.requestAccessIfNeeded()
    }
    LaunchedEffect(filter, includesVideos, library.access) {
        library.load(filter, includesVideos)
        gridState.scrollToItem(0)
    }
    LaunchedEffect(library, filter, includesVideos) {
        library.changes().collect { library.load(filter, includesVideos) }
    }

    val density = LocalDensity.current.density
    val picked = selection.items
    val full = picked.size >= AttachmentPickerButton.MAX_PHOTO_SELECTION
    Box(modifier.background(OpenCodeTheme.colors.canvas)) {
        when {
            library.access == PhotoLibraryAccess.Denied -> InlinePhotoNotice(
                title = "Photo access is off",
                message = "Allow access in Settings to pick photos here.",
                action = "Open Settings",
                onAction = library::openSettings,
            )
            library.isLoaded && library.photos.isEmpty() && library.access.canRead -> InlinePhotoNotice(
                title = if (filter == InlinePhotoFilter.All) "No Photos" else "No ${filter.title}",
                message = null,
                action = if (library.access == PhotoLibraryAccess.Limited) "Select More Photos…" else null,
                onAction = library::manageLimitedSelection,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
                verticalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val photos = library.photos
                items(photos.size, key = { photos[it].id }) { index ->
                    val photo = photos[index]
                    val uri = remember(photo.id) { photo.id.toUri() }
                    val order = picked.indexOf(uri).takeIf { it >= 0 }?.plus(1)
                    InlinePhotoTile(
                        photo = photo,
                        order = order,
                        enabled = order != null || !full,
                        cached = library.cachedThumbnail(photo.id),
                        load = { library.thumbnail(photo.id, InlinePhotoLibrary.THUMBNAIL_SIDE, density) },
                        onTap = {
                            val current = selection.items
                            selection.items = when {
                                uri in current -> current - uri
                                current.size < AttachmentPickerButton.MAX_PHOTO_SELECTION -> current + uri
                                else -> current
                            }
                        },
                    )
                }
                if (library.access == PhotoLibraryAccess.Limited) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "select-more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = library::manageLimitedSelection) { Text("Select More Photos…") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlinePhotoNotice(title: String, message: String?, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        }
        if (action != null) OpenCodeSecondaryButton(text = action, onClick = onAction)
    }
}

/** One photo or video: its square thumbnail, a duration badge for videos, and its place in the picks. */
@Composable
private fun InlinePhotoTile(
    photo: LibraryPhoto,
    order: Int?,
    enabled: Boolean,
    cached: ImageBitmap?,
    load: suspend () -> ImageBitmap?,
    onTap: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    var image by remember(photo.id) { mutableStateOf(cached) }
    LaunchedEffect(photo.id, photo.modified) {
        if (image == null) image = load()
    }
    val picked = order != null
    val badgeScale by animateFloatAsState(
        targetValue = if (picked) 1f else 0.5f,
        animationSpec = if (reduceMotion) snap() else MotionSpring(0.25, 0.2).spec(),
        label = "inline-tile-badge",
    )
    val description = listOfNotNull(photo.formattedDuration, photo.created?.let(tileDateFormatter.withLocale(appFormattingLocale())::format)).joinToString(", ")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(OpenCodeTheme.colors.text.copy(alpha = 0.06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = if (photo.isVideo) "Video" else "Photo"
                stateDescription = description
                selected = picked
                role = Role.Button
                if (!enabled) disabled()
                onClick { onTap(); true }
            },
    ) {
        image?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                    .drawWithContent {
                        drawContent()
                        if (picked) drawRect(Color.Black.copy(alpha = 0.18f))
                    },
            )
        }
        photo.formattedDuration?.let { duration ->
            VideoDurationBadge(duration, compact = true, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        if (picked || badgeScale > 0.51f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .graphicsLayer {
                        scaleX = if (reduceMotion) 1f else badgeScale
                        scaleY = if (reduceMotion) 1f else badgeScale
                        alpha = if (picked) 1f else ((badgeScale - 0.5f) * 2f).coerceIn(0f, 1f)
                    }
                    .size(22.dp)
                    .background(OpenCodeTheme.colors.accent, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    order?.toString() ?: "",
                    color = Color.White,
                    style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}
