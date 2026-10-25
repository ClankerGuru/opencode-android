package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How much of the photo library the app may read (iOS `PhotoLibraryAccess`). */
enum class PhotoLibraryAccess {
    NotDetermined,

    /** Every photo. */
    Full,

    /**
     * Only the photos the person picked: iOS's limited library, Android 14+'s “Selected
     * photos” (`READ_MEDIA_VISUAL_USER_SELECTED` without `READ_MEDIA_IMAGES`).
     */
    Limited,
    Denied,
    ;

    val canRead: Boolean get() = this == Full || this == Limited
}

/** One photo or video in the library, by its MediaStore content URI (iOS `LibraryPhoto`). */
data class LibraryPhoto(
    val id: String,
    val created: Instant?,
    val modified: Instant? = null,
    /** Non-null for videos; its duration in seconds. */
    val videoDuration: Double? = null,
) {
    val isVideo: Boolean get() = videoDuration != null
    val formattedDuration: String? get() = videoDuration?.let(::formatDuration)

    companion object {
        fun formatDuration(seconds: Double): String {
            if (!seconds.isFinite()) return "0:00"
            val total = max(0, seconds.roundToInt())
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val secs = total % 60
            if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
            return String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }
}

/**
 * Asks the system for photo permissions from the visible activity. Installed while the UI
 * that may ask is composed ([PhotoLibraryAccessHost]); the client never holds an activity
 * otherwise. Returns when the system dialog answered.
 */
fun interface PhotoPermissionRequester {
    suspend fun request(permissions: Array<String>)
}

/**
 * The photo library for the New Plus Menu's grid and recent strip (OC-354): access, the
 * newest photos, thumbnails, and the full image to attach. The grid and strip read it;
 * nothing else touches MediaStore. iOS `PhotoLibraryClient`.
 */
class PhotoLibraryClient(
    val access: () -> PhotoLibraryAccess,
    /** Asks for read access if it was never asked; returns what it is now. */
    val requestAccess: suspend () -> PhotoLibraryAccess,
    /** The newest photos, newest first. */
    val recent: suspend (limit: Int) -> List<LibraryPhoto>,
    /**
     * A thumbnail at least `side` dp square at `scale` px/dp, or null. `onDegraded` receives
     * any low-resolution preview before the full thumbnail lands (MediaStore has none, so the
     * live client never calls it; the seam stays for parity).
     */
    val thumbnail: suspend (id: String, side: Float, scale: Float, onDegraded: ((ImageBitmap) -> Unit)?) -> ImageBitmap?,
    /** Stops any thumbnail preheating and drops cached assets. */
    val stopCaching: () -> Unit = {},
    /** The image's data, as stored (HEIC or JPEG; a 1600 px JPEG frame for a video). */
    val imageData: suspend (id: String) -> ByteArray?,
    /** Ticks whenever the library changes (a selected-photos edit too). */
    val changes: () -> Flow<Unit>,
    /** Lets the person choose which photos a limited library shares (Android 14 re-request). */
    val manageLimitedSelection: () -> Unit,
    /** Installs (or, with null, removes) the activity seam permission requests go through. */
    val installRequester: (PhotoPermissionRequester?) -> Unit = {},
    /** iOS `UIApplication.openSettingsURLString`: this app's system settings page. */
    val openSettings: () -> Unit = {},
) {
    companion object Key : DependencyKey<PhotoLibraryClient> {
        override val liveValue: PhotoLibraryClient by lazy { live(dependency(AppContext)) }

        override val testValue: PhotoLibraryClient = PhotoLibraryClient(
            access = { PhotoLibraryAccess.Denied },
            requestAccess = { PhotoLibraryAccess.Denied },
            recent = { emptyList() },
            thumbnail = { _, _, _, _ -> null },
            stopCaching = {},
            imageData = { null },
            changes = { emptyFlow() },
            manageLimitedSelection = {},
        )

        /** iOS `labVideoDuration(forIndex:)`. */
        fun labVideoDuration(index: Int): Double? = when (index) {
            1 -> 14.0
            4 -> 8.0
            7 -> 31.0
            else -> if (index >= 10 && index % 4 == 1) ((index * 7) % 95 + 5).toDouble() else null
        }

        /**
         * iOS `-lab-photo-fixture`: a library of drawn swatches, so previews and lab runs never
         * wait on the device's photos or its access dialog. [limited] makes it a limited library.
         */
        fun labFixture(limited: Boolean): PhotoLibraryClient {
            val count = if (limited) 7 else 48
            val photos = (0 until count).map { index ->
                LibraryPhoto(
                    id = "lab-photo-$index",
                    created = Instant.ofEpochSecond(1_790_000_000L - index * 3_600L),
                    videoDuration = labVideoDuration(index),
                )
            }
            fun index(id: String): Int = id.substringAfterLast('-').toIntOrNull() ?: 0
            fun render(id: String, side: Int): Bitmap {
                val i = index(id)
                val hue = ((i * 37) % 360).toFloat()
                val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
                val bottom = Color.HSVToColor(floatArrayOf((hue + 0.12f * 360f) % 360f, 0.7f, 0.45f))
                val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), top, bottom, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
                paint.shader = null
                paint.color = Color.argb(217, 255, 255, 255)
                paint.textSize = side * 0.28f
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                val y = side / 2f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText("${i + 1}", side / 2f, y, paint)
                return bitmap
            }
            val access = if (limited) PhotoLibraryAccess.Limited else PhotoLibraryAccess.Full
            return PhotoLibraryClient(
                access = { access },
                requestAccess = { access },
                recent = { limit -> photos.take(limit) },
                thumbnail = { id, side, scale, _ -> render(id, max(1, (side * scale).roundToInt())).asImageBitmap() },
                stopCaching = {},
                imageData = { id ->
                    ByteArrayOutputStream().use { out ->
                        render(id, 600).compress(Bitmap.CompressFormat.JPEG, 80, out)
                        out.toByteArray()
                    }
                },
                changes = { emptyFlow() },
                manageLimitedSelection = {},
            )
        }

        private fun live(context: Context): PhotoLibraryClient {
            val live = LivePhotoLibrary(context.applicationContext)
            return PhotoLibraryClient(
                access = {
                    val status = live.currentAccess()
                    if (!status.canRead) live.stopCaching()
                    status
                },
                requestAccess = {
                    val status = live.requestAccess(limitedSelection = false)
                    if (!status.canRead) live.stopCaching()
                    status
                },
                recent = { limit ->
                    val expectedGeneration = live.beginFetch()
                    live.fetchRecent(limit, expectedGeneration)
                },
                thumbnail = { id, side, scale, _ -> live.loadThumbnail(id, side, scale) },
                stopCaching = { live.stopCaching() },
                imageData = { id -> live.loadImageData(id) },
                changes = { live.changes() },
                manageLimitedSelection = { live.manageLimitedSelection() },
                installRequester = { live.requester.set(it) },
                openSettings = { live.openSettings() },
            )
        }
    }
}

/**
 * Synchronizes one cancellable MediaStore request: the coroutine's cancellation cancels the
 * platform request through its [CancellationSignal] exactly once, and a cancelled or failed
 * request resumes with null. iOS `PhotoRequestGate` (which guards a `PHImageRequestID` and a
 * continuation); on Android the signal plus structured cancellation are the whole gate.
 */
class PhotoRequestGate {
    val signal = CancellationSignal()

    val isCancelled: Boolean get() = signal.isCanceled

    fun cancel() {
        if (!signal.isCanceled) signal.cancel()
    }

    /** Runs [load] on the IO dispatcher, cancelling the platform request if the caller is cancelled. */
    suspend fun <T : Any> run(load: (CancellationSignal) -> T?): T? = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { if (it != null) cancel() }
        try {
            if (isCancelled) null else load(signal)
        } catch (_: OperationCanceledException) {
            null
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        } finally {
            handle?.dispose()
        }
    }
}

/**
 * Serializes the asset cache, preheat start/stop, and lifecycle generation under one lock so
 * [stopCaching] can't race a fetch. iOS `PhotoCacheGate`.
 */
class PhotoCacheGate<Asset>(
    private val stopAll: () -> Unit,
    private val startWarmup: (List<Asset>, Float) -> Unit,
) {
    private val lock = Any()
    private var generation: Long = 0
    private var assets: Map<String, Asset> = emptyMap()

    fun currentGeneration(): Long = synchronized(lock) { generation }

    fun isCurrent(expectedGeneration: Long): Boolean = synchronized(lock) { generation == expectedGeneration }

    fun cachedAsset(id: String): Asset? = synchronized(lock) { assets[id] }

    fun commit(expectedGeneration: Long, assetsById: Map<String, Asset>, warmupAssets: List<Asset>, scale: Float, cancelled: Boolean = false): Boolean =
        synchronized(lock) {
            if (cancelled || generation != expectedGeneration) return false
            assets = assetsById
            stopAll()
            if (warmupAssets.isNotEmpty()) startWarmup(warmupAssets, scale)
            true
        }

    fun stopCaching() = synchronized(lock) {
        generation += 1
        assets = emptyMap()
        stopAll()
    }
}

/** What MediaStore says about one row: where it lives and whether it's a video. */
private data class MediaAsset(val uri: Uri, val isVideo: Boolean)

private class LivePhotoLibrary(private val context: Context) {
    val requester = AtomicReference<PhotoPermissionRequester?>(null)
    private val resolver: ContentResolver = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manualChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val prefs = context.getSharedPreferences("opencode.photoLibrary", Context.MODE_PRIVATE)

    /** PHCachingImageManager's preheat cache: thumbnails by `uri@pixels`. */
    private val warm = object : LruCache<String, ImageBitmap>(MAX_WARM_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private var warmup: Job? = null
    private val cache = PhotoCacheGate<MediaAsset>(
        stopAll = {
            warmup?.cancel()
            warmup = null
            warm.evictAll()
        },
        startWarmup = { assets, scale ->
            val pixels = max(1, (140 * max(scale, 1f)).roundToInt())
            warmup = scope.launch {
                for (asset in assets) {
                    if (!isActive) return@launch
                    val key = "${asset.uri}@$pixels"
                    if (warm.get(key) != null) continue
                    val image = PhotoRequestGate().run { signal -> resolver.loadThumbnail(asset.uri, Size(pixels, pixels), signal) }
                    if (image != null) warm.put(key, image.asImageBitmap())
                }
            }
        },
    )
    private val memoryObserver = AtomicReference<ComponentCallbacks2?>(null)

    private fun installMemoryObserverIfNeeded() {
        if (memoryObserver.get() != null) return
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) stopCaching()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = stopCaching()
        }
        if (memoryObserver.compareAndSet(null, callbacks)) context.registerComponentCallbacks(callbacks)
    }

    fun beginFetch(): Long {
        installMemoryObserverIfNeeded()
        return cache.currentGeneration()
    }

    fun stopCaching() {
        cache.stopCaching()
        memoryObserver.getAndSet(null)?.let { context.unregisterComponentCallbacks(it) }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun currentAccess(): PhotoLibraryAccess = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoLibraryAccess.Full
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoLibraryAccess.Limited
        prefs.getBoolean(ASKED, false) -> PhotoLibraryAccess.Denied
        else -> PhotoLibraryAccess.NotDetermined
    }

    /**
     * Asks through the installed activity seam. With [limitedSelection], re-asks while
     * limited so Android 14+ shows its photo selection again (iOS `presentLimitedLibraryPicker`).
     */
    suspend fun requestAccess(limitedSelection: Boolean): PhotoLibraryAccess {
        val before = currentAccess()
        if (before != PhotoLibraryAccess.NotDetermined && !limitedSelection) return before
        val seam = requester.get() ?: return before
        seam.request(PERMISSIONS)
        prefs.edit().putBoolean(ASKED, true).apply()
        val after = currentAccess()
        AppLog.app.info { "Photo access ${before.name} → ${after.name}" }
        if (after != before || limitedSelection) manualChanges.tryEmit(Unit)
        return after
    }

    fun manageLimitedSelection() {
        scope.launch(Dispatchers.Main) { requestAccess(limitedSelection = true) }
    }

    fun openSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.app.error { "Couldn’t open Settings: ${it::class.java.simpleName}" } }
    }

    suspend fun fetchRecent(limit: Int, expectedGeneration: Long): List<LibraryPhoto> = withContext(Dispatchers.IO) {
        if (!cache.isCurrent(expectedGeneration)) return@withContext emptyList()
        val boundedLimit = min(max(limit, 0), MAX_CACHED_ASSETS)
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION,
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
            )
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, boundedLimit)
        }
        val photos = ArrayList<LibraryPhoto>()
        val assetsById = HashMap<String, MediaAsset>()
        val warmupAssets = ArrayList<MediaAsset>()
        val cursor = runCatching { resolver.query(uri, projection, args, null) }
            .onFailure { AppLog.app.error { "Photo library query failed: ${it::class.java.simpleName}" } }
            .getOrNull()
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            while (c.moveToNext() && photos.size < boundedLimit) {
                ensureActive()
                val rowId = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val itemUri = ContentUris.withAppendedId(base, rowId)
                val id = itemUri.toString()
                val created = if (!c.isNull(takenCol) && c.getLong(takenCol) > 0) {
                    Instant.ofEpochMilli(c.getLong(takenCol))
                } else if (!c.isNull(addedCol)) {
                    Instant.ofEpochSecond(c.getLong(addedCol))
                } else {
                    null
                }
                val modified = if (c.isNull(modifiedCol)) null else Instant.ofEpochSecond(c.getLong(modifiedCol))
                val duration = if (isVideo) max(if (c.isNull(durationCol)) 0L else c.getLong(durationCol), 0L) / 1000.0 else null
                photos.add(LibraryPhoto(id = id, created = created, modified = modified, videoDuration = duration))
                val asset = MediaAsset(itemUri, isVideo)
                assetsById[id] = asset
                if (photos.size <= WARMUP_COUNT) warmupAssets.add(asset)
            }
        }
        val scale = context.resources.displayMetrics.density.takeIf { it > 0 } ?: 3f
        val committed = cache.commit(
            expectedGeneration = expectedGeneration,
            assetsById = assetsById,
            warmupAssets = warmupAssets,
            scale = scale,
            cancelled = !coroutineContext.isActive,
        )
        if (committed) photos else emptyList()
    }

    private fun resolveAsset(id: String): MediaAsset? {
        cache.cachedAsset(id)?.let { return it }
        val uri = runCatching { Uri.parse(id) }.getOrNull() ?: return null
        val isVideo = uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())
        return MediaAsset(uri, isVideo)
    }

    suspend fun loadThumbnail(id: String, side: Float, scale: Float): ImageBitmap? {
        val asset = resolveAsset(id) ?: return null
        val pixels = max(1, (side * max(scale, 1f)).roundToInt())
        // The warm cache was filled at the grid's size; a hit is the opportunistic answer.
        warm.get("${asset.uri}@$pixels")?.let { return it }
        return PhotoRequestGate().run { signal -> resolver.loadThumbnail(asset.uri, Size(pixels, pixels), signal) }
            ?.asImageBitmap()
    }

    suspend fun loadImageData(id: String): ByteArray? {
        val asset = resolveAsset(id) ?: return null
        val gate = PhotoRequestGate()
        if (asset.isVideo) {
            return gate.run { signal ->
                val frame = resolver.loadThumbnail(asset.uri, Size(1600, 1600), signal)
                ByteArrayOutputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            }
        }
        return gate.run { signal ->
            resolver.openInputStream(asset.uri)?.use { input ->
                signal.throwIfCanceled()
                input.readBytes()
            }
        }
    }

    fun changes(): Flow<Unit> = merge(
        callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        },
        manualChanges,
    )

    companion object {
        const val MAX_CACHED_ASSETS = 300
        const val WARMUP_COUNT = 36
        private const val MAX_WARM_BYTES = 24 * 1024 * 1024
        private const val ASKED = "asked"
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }
}

/** The activity hosting [this] context, unwrapping wrappers (theme, dialog). */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Requests [permissions] from [activity] and returns once the system answered: the
 * permission screen pauses the activity and resumes it when it closes; an answer the system
 * gives without a screen (asked too often) resumes after [IMMEDIATE_ANSWER_MS].
 * `androidx.activity`'s result registry isn't on this module's classpath, so this uses the
 * framework call and the activity's lifecycle.
 */
internal suspend fun requestPermissionsAndAwait(activity: Activity, lifecycle: Lifecycle, permissions: Array<String>) {
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var paused = false
            lateinit var observer: LifecycleEventObserver
            fun finish() {
                lifecycle.removeObserver(observer)
                handler.removeCallbacksAndMessages(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> paused = true
                    Lifecycle.Event.ON_RESUME -> if (paused) finish()
                    Lifecycle.Event.ON_DESTROY -> finish()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            continuation.invokeOnCancellation {
                handler.post {
                    lifecycle.removeObserver(observer)
                    handler.removeCallbacksAndMessages(null)
                }
            }
            activity.requestPermissions(permissions, PERMISSION_REQUEST_CODE)
            handler.postDelayed({ if (!paused) finish() }, IMMEDIATE_ANSWER_MS)
        }
    }
}

private const val PERMISSION_REQUEST_CODE = 0x0C4D
private const val IMMEDIATE_ANSWER_MS = 700L

/**
 * Installs the activity seam [PhotoLibraryClient.requestAccess] and
 * [PhotoLibraryClient.manageLimitedSelection] ask through, while composed. Mount it once in
 * any UI that may ask (the photo sheet does).
 */
@Composable
fun PhotoLibraryAccessHost(client: PhotoLibraryClient = remember { dependency(PhotoLibraryClient) }) {
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle, client) {
        if (activity == null) return@DisposableEffect onDispose {}
        client.installRequester(PhotoPermissionRequester { permissions -> requestPermissionsAndAwait(activity, lifecycle, permissions) })
        onDispose { client.installRequester(null) }
    }
}
