package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.PlusMenuTunables
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The `composer.morph.*` drag thresholds and corner radii the photo sheet reads (iOS
 * `ComposerMorphTunables` in `ComposerMorph.swift`). A local copy until a `ComposerMorph`
 * port owns them; the values match iOS. The `composer.plusMenu.*` values live in
 * [PlusMenuTunables] (`AttachmentMenu.kt`).
 */
object PlusPhotoMorphTunables {
    /** `composer.morph.topRadius` / `bottomRadius` (dp). */
    const val topRadius = 30f
    const val bottomRadius = 46f

    /** `composer.morph.photoExpandDrag` (dp), `photoExpandVelocity` (dp/s), `photoCollapseDrag` (dp). */
    const val photoExpandDrag = 36f
    const val photoExpandVelocity = 450f
    const val photoCollapseDrag = 48f

    /** `composer.morph.dragCloses` (dp) and `throwCloses` (dp/s): `ComposerMorph.closes`. */
    const val dragCloses = 90f
    const val throwCloses = 700f
}

/**
 * The newest photos for the New Plus Menu (OC-354), shared by the photo sheet and the recent
 * strip a hold on + shows, and the photos picked in the sheet, in tap order. iOS
 * `PlusPhotoLibrary`. A plain object with snapshot state, created by the session screen
 * (`remember { PlusPhotoLibrary() }`); call [stop] when the screen goes.
 */
class PlusPhotoLibrary(private val client: PhotoLibraryClient = dependency(PhotoLibraryClient)) {
    data class ThumbnailCacheKey(val id: String, val pixelSide: Int, val modified: java.time.Instant?)

    var access: PhotoLibraryAccess by mutableStateOf(PhotoLibraryAccess.NotDetermined)
        private set
    var photos: List<LibraryPhoto> by mutableStateOf(emptyList())
        private set

    /** Picked in the sheet, in tap order; attached with Add. */
    var picks: List<String> by mutableStateOf(emptyList())
        private set

    /** Pick count held while the sheet folds away after Add, so the Add pill stays and dissolves with the foot. */
    var closingPickCount: Int by mutableStateOf(0)
        private set

    /** Top visible photo ID when the user scrolled the library, so reopening the sheet in the same session returns there. */
    var savedScrollPhotoID: String? = null

    /** Pixels per dp of the display (iOS `defaultDisplayScale`); views keep it current. */
    var displayScale: Float = Resources.getSystem().displayMetrics.density.takeIf { it > 0 } ?: 3f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var warmupTask: Job? = null
    private var generation = 0L
    private var contentGeneration = 0L
    private var reloadGeneration = 0L
    private var photosById: Map<String, LibraryPhoto> by mutableStateOf(emptyMap())
    private val thumbnails = HashMap<ThumbnailCacheKey, ImageBitmap>()
    private val thumbnailOrder = ArrayList<ThumbnailCacheKey>()

    val canRead: Boolean get() = access.canRead

    fun cacheKey(id: String, side: Float, scale: Float): ThumbnailCacheKey {
        val pixelSide = (side * max(scale, 1f)).roundToInt()
        return ThumbnailCacheKey(id, pixelSide, photosById[id]?.modified)
    }

    fun photo(id: String): LibraryPhoto? = photosById[id]

    fun cachedThumbnail(id: String, side: Float = GRID_THUMBNAIL_SIDE, scale: Float? = null): ImageBitmap? =
        thumbnails[cacheKey(id, side, max(scale ?: displayScale, 1f))]

    private fun clearCache() {
        contentGeneration += 1
        warmupTask?.cancel()
        warmupTask = null
        thumbnails.clear()
        thumbnailOrder.clear()
        client.stopCaching()
    }

    /** iOS memory-warning purge. */
    fun purgeCache() {
        generation += 1
        clearCache()
    }

    fun stop() {
        watch?.cancel()
        watch = null
        purgeCache()
    }

    /** The screen is gone for good. */
    fun close() {
        stop()
        scope.cancel()
    }

    private fun storeThumbnail(image: ImageBitmap, key: ThumbnailCacheKey) {
        val current = photosById[key.id] ?: return
        if (current.modified != key.modified) return
        if (thumbnails[key] == null) {
            thumbnailOrder.add(key)
            while (thumbnailOrder.size > MAX_CACHED_THUMBNAILS) thumbnails.remove(thumbnailOrder.removeAt(0))
        }
        thumbnails[key] = image
    }

    /** Reads what access there is without asking, and the newest photos if it can: the recent strip only ever shows what's already allowed. */
    suspend fun prime() {
        val expected = generation
        val now = client.access()
        if (access != now) access = now
        reload(expected)
        if (!currentCoroutineContext().isActive || generation != expected) return
        startWatching(expected)
    }

    /** Asks for access if it was never asked (the sheet is open: the dialog is expected), then loads. */
    suspend fun load() {
        val expected = generation
        var now = client.access()
        if (now == PhotoLibraryAccess.NotDetermined) {
            now = client.requestAccess()
            if (!currentCoroutineContext().isActive || generation != expected) return
        }
        if (access != now) access = now
        reload(expected)
        if (!currentCoroutineContext().isActive || generation != expected) return
        startWatching(expected)
    }

    fun toggle(id: String) {
        closingPickCount = 0
        if (id in picks) {
            picks = picks - id
        } else if (picks.size < AttachmentPickerButton.MAX_PHOTO_SELECTION) {
            picks = picks + id
        }
    }

    fun order(id: String): Int? = picks.indexOf(id).takeIf { it >= 0 }?.plus(1)

    /** Picks handed over to be attached; the sheet starts over next time. */
    fun takePicks(): List<String> {
        closingPickCount = picks.size
        val taken = picks
        picks = emptyList()
        return taken
    }

    fun clearPicks() {
        if (picks.isNotEmpty()) picks = emptyList()
    }

    fun resetClosingPicks() {
        if (closingPickCount != 0) closingPickCount = 0
    }

    fun resetSavedScrollPosition() {
        savedScrollPhotoID = null
    }

    suspend fun thumbnail(id: String, side: Float, scale: Float, onDegraded: ((ImageBitmap) -> Unit)? = null): ImageBitmap? {
        val key = cacheKey(id, side, scale)
        thumbnails[key]?.let { return it }
        val expected = generation
        val expectedContent = contentGeneration
        val loaded = client.thumbnail(id, side, scale) { degraded ->
            scope.launch {
                if (generation == expected && contentGeneration == expectedContent && access.canRead) onDegraded?.invoke(degraded)
            }
        }
        if (!currentCoroutineContext().isActive || generation != expected || contentGeneration != expectedContent || !access.canRead) return null
        if (loaded != null) {
            storeThumbnail(loaded, key)
            return loaded
        }
        return thumbnails[key]
    }

    suspend fun imageData(id: String): ByteArray? = client.imageData(id)

    fun manageLimitedSelection() = client.manageLimitedSelection()

    fun openSettings() = client.openSettings()

    private suspend fun reload(expectedGeneration: Long = generation) {
        reloadGeneration += 1
        val expectedReload = reloadGeneration
        warmupTask?.cancel()
        warmupTask = null
        if (!access.canRead) {
            if (photos.isNotEmpty()) photos = emptyList()
            if (photosById.isNotEmpty()) photosById = emptyMap()
            clearCache()
            clearPicks()
            return
        }
        val expectedContent = contentGeneration
        val newest = client.recent(GRID_LIMIT)
        if (!currentCoroutineContext().isActive || generation != expectedGeneration || contentGeneration != expectedContent ||
            reloadGeneration != expectedReload || !access.canRead
        ) {
            return
        }
        if (newest != photos) photos = newest
        val byId = newest.associateBy { it.id }
        if (byId != photosById) photosById = byId
        thumbnails.keys.retainAll { key -> byId[key.id]?.modified == key.modified && byId.containsKey(key.id) }
        thumbnailOrder.retainAll { thumbnails.containsKey(it) }
        if (picks.any { it !in byId }) picks = picks.filter { it in byId }
        val scale = displayScale
        val warmPhotos = newest.take(18)
        warmupTask = scope.launch {
            for (photo in warmPhotos) {
                if (!isActive || generation != expectedGeneration || contentGeneration != expectedContent ||
                    reloadGeneration != expectedReload || !access.canRead
                ) {
                    return@launch
                }
                if (thumbnails[cacheKey(photo.id, GRID_THUMBNAIL_SIDE, scale)] != null) continue
                thumbnail(photo.id, GRID_THUMBNAIL_SIDE, scale)
            }
        }
    }

    private fun startWatching(expectedGeneration: Long) {
        if (generation != expectedGeneration || watch != null) return
        val changes = client.changes()
        watch = scope.launch {
            changes.collect {
                val current = generation
                val now = client.access()
                if (access != now) access = now
                reload(current)
            }
        }
    }

    companion object {
        const val GRID_LIMIT = 300
        const val MAX_CACHED_THUMBNAILS = 72

        /** dp. */
        const val GRID_THUMBNAIL_SIDE = 140f
    }
}

/**
 * Pure detent state and 1:1 finger-tracking geometry for the two-detent photo sheet (compact
 * over the keyboard's place, expanded up to the header ceiling). iOS `PlusPhotoSheetDetent`.
 * Units are whatever the caller measures in (dp for the iOS-matching thresholds). The M3
 * [PlusPhotoSheet] uses `SheetState`'s own detents; this stays for a custom morph surface.
 */
data class PlusPhotoSheetDetent(
    var isExpanded: Boolean = false,
    var compactTop: Float = 0f,
    var ceiling: Float = 0f,
    var top: Float = if (isExpanded) ceiling else compactTop,
    var drag: Float = 0f,
) {
    sealed interface Settlement {
        data class Stay(val expanded: Boolean, val top: Float, val initialVelocity: Float) : Settlement
        data object Close : Settlement
    }

    fun open(compactTop: Float, ceiling: Float) {
        isExpanded = false
        this.compactTop = compactTop
        this.ceiling = ceiling
        top = compactTop
        drag = 0f
    }

    /** Maps raw vertical finger translation to sheet `drag`, 1:1 between detents and rubber-banding past them. */
    fun dragChanged(translation: Float): Float {
        val maxUp = max(top - ceiling, 0f)
        drag = if (translation < 0) {
            val up = -translation
            if (up <= maxUp) translation else -(maxUp + rubberBand(up - maxUp, limit = 28f, factor = 0.22f))
        } else if (isExpanded) {
            val maxDown = max(compactTop - top, 0f)
            if (translation <= maxDown) translation else maxDown + rubberBand(translation - maxDown, limit = 48f, factor = 0.35f)
        } else {
            translation
        }
        return drag
    }

    /** Resolves the target detent (or dismissal) when a drag ends, with the normalized spring velocity that continues the finger. */
    fun settle(
        predicted: Float?,
        velocity: Float,
        expandDrag: Float = PlusPhotoMorphTunables.photoExpandDrag,
        expandVelocity: Float = PlusPhotoMorphTunables.photoExpandVelocity,
        collapseDrag: Float = PlusPhotoMorphTunables.photoCollapseDrag,
    ): Settlement {
        val currentDrag = drag
        val end = predicted ?: currentDrag
        val currentTop = top + currentDrag
        fun stay(expanded: Boolean): Settlement {
            val targetTop = if (expanded) ceiling else compactTop
            val v0 = normalizedInitialVelocity(velocity, currentTop, targetTop)
            isExpanded = expanded
            top = targetTop
            drag = 0f
            return Settlement.Stay(expanded, targetTop, v0)
        }
        return if (!isExpanded) {
            if (currentDrag < -expandDrag || (velocity < -expandVelocity && currentDrag < -12) || (end < -expandDrag && currentDrag < -16)) {
                stay(true)
            } else if (currentDrag > 0 && closes(currentDrag, end, velocity)) {
                drag = 0f
                Settlement.Close
            } else {
                stay(false)
            }
        } else {
            if (currentDrag > collapseDrag || (velocity > expandVelocity && currentDrag > 12) || (end > collapseDrag && currentDrag > 16)) {
                stay(false)
            } else {
                stay(true)
            }
        }
    }

    fun toggle(): Settlement {
        isExpanded = !isExpanded
        top = if (isExpanded) ceiling else compactTop
        drag = 0f
        return Settlement.Stay(isExpanded, top, 0f)
    }

    companion object {
        fun rubberBand(excess: Float, limit: Float, factor: Float): Float {
            if (excess <= 0 || limit <= 0 || factor <= 0) return 0f
            val scaled = excess * factor
            return (limit * scaled) / (limit + scaled)
        }

        fun normalizedInitialVelocity(velocity: Float, from: Float, to: Float): Float {
            val remaining = to - from
            if (abs(remaining) <= 1) return 0f
            val normalized = velocity / remaining
            if (normalized <= 0) return 0f
            return min(normalized, 18f)
        }

        /** iOS `ComposerMorph.closes(translation:predicted:velocity:)`. */
        fun closes(translation: Float, predicted: Float, velocity: Float): Boolean =
            translation > PlusPhotoMorphTunables.dragCloses ||
                (velocity > PlusPhotoMorphTunables.throwCloses && predicted > translation)
    }
}

/**
 * Cancels tile and camera taps while a sheet drag is in flight and for 80 ms after it ends,
 * without sticking if a drag begins and ends within one frame or clearing mid-drag if a
 * second drag starts within 80 ms. iOS `PlusPhotoSheetTapSuppression`. (Compose already
 * cancels a click when its pointer turns into a drag; this covers drags owned elsewhere.)
 */
class PlusPhotoSheetTapSuppression(private val scope: CoroutineScope) {
    var isSuppressed: Boolean by mutableStateOf(false)
        private set
    private var task: Job? = null

    fun beginDrag() {
        task?.cancel()
        task = null
        isSuppressed = true
    }

    fun scheduleReset(delayMillis: Long = 80) {
        task?.cancel()
        if (!isSuppressed) {
            task = null
            return
        }
        task = scope.launch {
            delay(delayMillis)
            isSuppressed = false
            task = null
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
        isSuppressed = false
    }
}

/** A node's bounds in screen pixels (iOS `frame(in: .global)` in window points). */
internal fun LayoutCoordinates.boundsOnScreen(): Rect =
    Rect(positionOnScreen(), Size(size.width.toFloat(), size.height.toFloat()))

/**
 * The photo sheet (iOS `PlusPhotoSheetContent` + `PlusPhotoSheetFoot` inside
 * `ComposerMorphSurface`): an M3 modal bottom sheet whose partial detent is iOS's compact
 * detent and whose expanded detent is the header ceiling. The camera tile, then the newest
 * photos three across, with Back and All Photos / Add along its foot. Tapping photos picks
 * them (up to four, in order); Add hands the picks over ([onAdd]) for the session to reserve
 * slots, launch [PhotoFlights] and close the sheet.
 *
 * @param onBack ‹ Back: return to the + menu.
 * @param onAdd the picked photo IDs, in tap order ([PlusPhotoLibrary.takePicks] already ran).
 * @param onCapture a photo taken from the sheet's camera (JPEG), with no review.
 * @param onDismiss the sheet was swiped or tapped away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusPhotoSheet(
    library: PlusPhotoLibrary,
    flights: PhotoFlights,
    camera: PlusCameraPresentation,
    onBack: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onCapture: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
) {
    val scope = rememberCoroutineScope()
    val isExpanded = sheetState.currentValue == SheetValue.Expanded
    fun toggleExpand() {
        scope.launch { if (isExpanded) sheetState.partialExpand() else sheetState.expand() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { PlusPhotoSheetGrabber(isExpanded = isExpanded, onToggle = ::toggleExpand) },
    ) {
        PhotoLibraryAccessHost()
        var hiddenBottom by remember { mutableFloatStateOf(0f) }
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val navigationInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    // Keep the foot on the sheet's visible edge at the partial detent.
                    val bottom = coordinates.boundsOnScreen().bottom
                    hiddenBottom = max(0f, bottom - (windowHeight - navigationInset))
                },
        ) {
            PlusPhotoSheetContent(
                library = library,
                flights = flights,
                camera = camera,
                isExpanded = isExpanded,
                onCamera = { camera.expanded = true },
                onDismiss = onDismiss,
                contentBottomPadding = { hiddenBottom },
            )
            PlusPhotoSheetFoot(
                library = library,
                isExpanded = isExpanded,
                onBack = onBack,
                onAllPhotos = { scope.launch { sheetState.expand() } },
                onAdd = { onAdd(library.takePicks()) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = -hiddenBottom },
            )
        }
        PlusCameraSurface(presentation = camera, sheetIsOpen = true, onCapture = onCapture)
    }
}

/** iOS's grabber header: a 36 × 5 capsule in a 22 pt band; tap toggles Half/Full. */
@Composable
private fun PlusPhotoSheetGrabber(isExpanded: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GRABBER_HEIGHT.dp)
            .clickable(onClickLabel = null, role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = "Sheet Grabber"
                stateDescription = if (isExpanded) "Full" else "Half"
                role = Role.Button
                onClick { onToggle(); true }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(width = 36.dp, height = 5.dp)
                .background(OpenCodeTheme.colors.tertiaryText, OpenCodeShape.capsule),
        )
    }
}

private const val GRID_SPACING = 2f
private const val GRABBER_HEIGHT = 22f
private const val CAMERA_KEY = "__camera__"

/** The sheet's grid (iOS `PlusPhotoSheetContent`). */
@Composable
fun PlusPhotoSheetContent(
    library: PlusPhotoLibrary,
    flights: PhotoFlights,
    camera: PlusCameraPresentation,
    isExpanded: Boolean,
    onCamera: () -> Unit,
    onDismiss: () -> Unit,
    contentBottomPadding: () -> Float = { 0f },
) {
    val density = LocalDensity.current.density
    library.displayScale = density
    val gridInset = PlusMenuTunables.GRID_INSET
    val topRadius = max(PlusPhotoMorphTunables.topRadius - gridInset, 14f)
    val bottomRadius = max(PlusPhotoMorphTunables.bottomRadius - gridInset, 20f)
    val gridShape = RoundedCornerShape(topRadius.dp, topRadius.dp, bottomRadius.dp, bottomRadius.dp)
    val photos = library.photos
    val initialIndex = remember {
        library.savedScrollPhotoID?.let { saved -> photos.indexOfFirst { it.id == saved }.takeIf { it >= 0 }?.plus(1) } ?: 0
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)

    LaunchedEffect(library) { library.load() }
    LaunchedEffect(gridState, isExpanded) {
        if (!isExpanded) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (index == 0 && offset <= 4 * density) {
                    library.savedScrollPhotoID = null
                } else if (library.photos.isNotEmpty()) {
                    val photoIndex = (index - 1).coerceIn(0, library.photos.size - 1)
                    library.savedScrollPhotoID = library.photos[photoIndex].id
                }
            }
    }
    val footHeight = PlusPhotoSheetFootDefaults.HEIGHT + PlusPhotoSheetFootDefaults.INSET * 2
    val bottomPadding = with(LocalDensity.current) { contentBottomPadding().toDp() } + footHeight.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING.dp),
        contentPadding = PaddingValues(bottom = bottomPadding),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = gridInset.dp, end = gridInset.dp, bottom = gridInset.dp)
            .clip(gridShape)
            .onGloballyPositioned { coordinates ->
                val frame = coordinates.boundsOnScreen()
                flights.sheetViewport = frame
                camera.viewport = frame
            }
            .semantics { contentDescription = "Photos" },
    ) {
        item(key = CAMERA_KEY) {
            PlusCameraTile(camera = camera, onCamera = onCamera)
        }
        items(photos.size, key = { photos[it].id }) { index ->
            val photo = photos[index]
            PlusPhotoTile(
                photo = photo,
                order = library.order(photo.id),
                scale = density,
                inFlight = flights.hidesPhoto(photo.id),
                flights = flights,
                cached = library.cachedThumbnail(photo.id, PlusPhotoLibrary.GRID_THUMBNAIL_SIDE, density),
                load = { id, side, scale, onDegraded -> library.thumbnail(id, side, scale, onDegraded) },
                onTap = { library.toggle(photo.id) },
            )
        }
        if (library.access == PhotoLibraryAccess.Denied) {
            item(span = { GridItemSpan(maxLineSpan) }) { DeniedNotice(onOpenSettings = library::openSettings) }
        }
    }
}

@Composable
private fun PlusCameraTile(camera: PlusCameraPresentation, onCamera: () -> Unit) {
    DisposableEffect(camera) {
        camera.mounted = true
        onDispose { camera.mounted = false }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onGloballyPositioned { camera.tile = it.boundsOnScreen() }
            .clickable(onClickLabel = null, role = Role.Button, onClick = onCamera)
            .semantics { contentDescription = "Camera" },
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(SFSymbol.Camera, contentDescription = null, size = 28.dp, tint = OpenCodeTheme.colors.text)
    }
}

@Composable
private fun DeniedNotice(onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Photo access is off", style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
        Text(
            "Allow access in Settings to pick photos here.",
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        OpenCodeSecondaryButton(text = "Open Settings", onClick = onOpenSettings)
    }
}

/** iOS `PlusPhotoSheetFoot.height` / `inset` (dp). */
object PlusPhotoSheetFootDefaults {
    const val HEIGHT = 48f
    const val INSET = 16f
}

private enum class FootPill { Add, AllPhotos, SelectMore, None }

/**
 * Back on the leading edge and the trailing All Photos / Add N Photos pill along the photo
 * sheet's foot. In compact mode with no picks, All Photos expands the sheet (or, under
 * limited access, offers Browse All Photos and Select More Photos…); picking photos turns
 * that pill into Add N Photos; expanded with no picks it hides, or shows Select More… under
 * limited access. iOS `PlusPhotoSheetFoot`.
 */
@Composable
fun PlusPhotoSheetFoot(
    library: PlusPhotoLibrary,
    isExpanded: Boolean,
    onBack: () -> Unit,
    onAllPhotos: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val count = if (library.picks.isEmpty()) library.closingPickCount else library.picks.size
    val pill = when {
        count > 0 -> FootPill.Add
        !isExpanded -> FootPill.AllPhotos
        library.access == PhotoLibraryAccess.Limited -> FootPill.SelectMore
        else -> FootPill.None
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = (PlusPhotoSheetFootDefaults.INSET + 2).dp, end = (PlusPhotoSheetFootDefaults.INSET + 2).dp, bottom = PlusPhotoSheetFootDefaults.INSET.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIconButton(
            symbol = SFSymbol.ChevronLeft,
            contentDescription = "Back",
            onClick = onBack,
        )
        Spacer(Modifier.weight(1f).width(12.dp))
        AnimatedContent(
            targetState = pill to count,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(Motion.easeInOut(0.18)) togetherWith fadeOut(Motion.easeInOut(0.18))
                } else {
                    val spring = MotionSpring(0.3, 0.12)
                    (fadeIn(spring.spec()) + scaleIn(spring.spec(), initialScale = 0.92f)) togetherWith
                        (fadeOut(spring.spec()) + scaleOut(spring.spec(), targetScale = 0.92f))
                }
            },
            contentKey = { it.first },
            label = "plus-photo-foot",
        ) { (shown, picked) ->
            when (shown) {
                FootPill.Add -> {
                    val title = if (picked == 1) "Add 1 Photo" else "Add $picked Photos"
                    OpenCodePrimaryButton(onClick = onAdd, modifier = Modifier.heightIn(min = PlusPhotoSheetFootDefaults.HEIGHT.dp)) {
                        Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                }
                FootPill.AllPhotos -> if (library.access == PhotoLibraryAccess.Limited) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        FootPillLabel("All Photos", onClick = { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Browse All Photos") },
                                leadingIcon = { OpenCodeIcon(SFSymbol.PhotoOnRectangle, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAllPhotos()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Select More Photos…") },
                                leadingIcon = { OpenCodeIcon(SFSymbol.PhotoBadgePlus, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    library.manageLimitedSelection()
                                },
                            )
                        }
                    }
                } else {
                    FootPillLabel("All Photos", onClick = onAllPhotos)
                }
                FootPill.SelectMore -> FootPillLabel("Select More…", accessibilityLabel = "Select More Photos", onClick = library::manageLimitedSelection)
                FootPill.None -> Spacer(Modifier.size(0.dp))
            }
        }
    }
}

/** The glass capsule pill (iOS `pillLabel`): a tonal capsule, 48 dp tall. */
@Composable
private fun FootPillLabel(title: String, onClick: () -> Unit, accessibilityLabel: String = title) {
    Surface(
        onClick = onClick,
        shape = OpenCodeShape.capsule,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = OpenCodeTheme.colors.text,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .heightIn(min = PlusPhotoSheetFootDefaults.HEIGHT.dp)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Box(Modifier.heightIn(min = PlusPhotoSheetFootDefaults.HEIGHT.dp).padding(horizontal = PlusMenuTunables.ALL_PHOTOS_PADDING.dp), contentAlignment = Alignment.Center) {
            Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** The bottom-leading video duration pill (`composer.plusMenu.videoBadge*`). */
@Composable
internal fun VideoDurationBadge(duration: String, compact: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(
                Color.Black.copy(alpha = PlusMenuTunables.VIDEO_BADGE_OPACITY.toFloat()),
                RoundedCornerShape(PlusMenuTunables.VIDEO_BADGE_RADIUS.dp),
            )
            .padding(horizontal = if (compact) 5.dp else 6.dp, vertical = if (compact) 2.dp else 3.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        OpenCodeIcon(SFSymbol.PlayFill, contentDescription = null, size = if (compact) 8.dp else 10.dp, tint = Color.White)
        Text(
            duration,
            color = Color.White,
            style = (if (compact) OpenCodeTextStyles.caption2 else OpenCodeTextStyles.caption2).copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

private val tileDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** One photo or video in the sheet: its square thumbnail, a duration badge for videos, and its place in the picks. iOS `PlusPhotoTile`. */
@Composable
private fun PlusPhotoTile(
    photo: LibraryPhoto,
    order: Int?,
    scale: Float,
    inFlight: Boolean,
    flights: PhotoFlights,
    cached: ImageBitmap?,
    load: suspend (String, Float, Float, (ImageBitmap) -> Unit) -> ImageBitmap?,
    onTap: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    var image by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    val resolved = image ?: cached
    val place = remember { PhotoTilePlace() }
    val picked = order != null

    fun report() {
        val frame = place.frame
        flights.source(
            photo.id,
            if (order == null || frame == Rect.Zero) null else PhotoFlights.Source(frame = frame, radius = 0f, image = resolved, isSheet = true),
        )
    }

    val key = PlusPhotoLibrary.ThumbnailCacheKey(photo.id, (PlusPhotoLibrary.GRID_THUMBNAIL_SIDE * max(scale, 1f)).roundToInt(), photo.modified)
    LaunchedEffect(key) {
        if (cached != null) image = cached
        val loaded = load(photo.id, PlusPhotoLibrary.GRID_THUMBNAIL_SIDE, scale) { preview -> if (image == null) image = preview }
        if (loaded != null) image = loaded
    }
    LaunchedEffect(order, resolved) { if (order != null) report() }
    DisposableEffect(photo.id) { onDispose { flights.source(photo.id, null) } }

    val alpha by animateFloatAsState(
        targetValue = if (inFlight) 0f else 1f,
        animationSpec = if (inFlight) androidx.compose.animation.core.snap() else Motion.easeInOut(0.25),
        label = "tile-in-flight",
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (picked) 1f else 0.5f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else MotionSpring(0.25, 0.2).spec(),
        label = "tile-badge",
    )
    val dim = if (picked) 0.18f else 0f
    val description = listOfNotNull(photo.formattedDuration, photo.created?.let(tileDateFormatter::format)).joinToString(", ")
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned {
                place.frame = it.boundsOnScreen()
                if (order != null) report()
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawWithContent {
                drawContent()
                resolved?.let { drawAspectFill(it, Rect(Offset.Zero, size)) }
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            }
            .clickable(enabled = !inFlight, role = Role.Button, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = if (photo.isVideo) "Video" else "Photo"
                stateDescription = description
                selected = picked
                role = Role.Button
                if (!inFlight) onClick { onTap(); true }
            },
    ) {
        photo.formattedDuration?.let { duration ->
            VideoDurationBadge(duration, modifier = Modifier.align(Alignment.BottomStart).padding(PlusMenuTunables.VIDEO_BADGE_INSET.dp))
        }
        if (picked || badgeScale > 0.51f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .graphicsLayer {
                        scaleX = if (reduceMotion) 1f else badgeScale
                        scaleY = if (reduceMotion) 1f else badgeScale
                        this.alpha = if (picked) 1f else ((badgeScale - 0.5f) * 2f).coerceIn(0f, 1f)
                    }
                    .size(24.dp)
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

/** A tile's frame on screen, read when a flight leaves from it; not observed, so the sheet moving never recomposes its tiles. */
class PhotoTilePlace {
    var frame: Rect = Rect.Zero
}
