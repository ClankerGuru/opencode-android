package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.motion.SpringSolver
import ai.opencode.feature.composer.ComposerImageImporter
import ai.opencode.feature.composer.ComposerImageSource
import ai.opencode.feature.composer.ComposerMorph
import ai.opencode.feature.composer.ComposerMorphGeometry
import ai.opencode.feature.composer.ComposerMorphTunables
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of iOS `PhotoFlightTests.swift`.
 *
 * Geometry is at density 1 (px = dp = iOS points), so the iOS numbers carry over unchanged.
 * iOS `FlightSpring` is core's [SpringSolver] here (`docs/parity/composer.md`).
 *
 * Dropped:
 * - `aCleanFlightHasNoFindings`, `aFlightThatStartsAwayFromItsTileIsAPopIn`, `aFrameThatLeapsIsASnap`,
 *   `aCappedSolverHitchReportsLostFramesWithoutInventingAcceleration`, `anOvershootIsAReversal`,
 *   `turningBackForANewSlotIsARetargetNotAReversal`, `dippingWithTheComposerIsAReversalEvenWhenTheSlotMoved`,
 *   `vanishingWithoutLandingIsAPopOut`, `softeningAwayIsNoPopOut`, `landingOffItsThumbnailIsAMiss`:
 *   they test `PhotoFlightAudit` (Motion Watch / lab trace telemetry), not ported (n/a in the parity table).
 * - `photoRequestGateResumesExactlyOnceAcrossCancelAndLateCompletion`: iOS's `PhotoRequestGate`
 *   guards a `PHImageRequestID` and a continuation (`arm`/`registered`/`finish`); Android's gate is
 *   an `android.os.CancellationSignal` plus structured cancellation, with no such states.
 * - In `plusPhotoLibraryExposesVideoAssetsAndLabFixtureIncludesVideosAndCameraCapture`, the
 *   `renderLabCameraCapture` check: Android's lab fixture has no camera-capture render.
 *
 * Runs under Robolectric (native graphics, xxhdpi = iOS's 3× screen) so the lab fixture's image
 * data draws and decodes real Bitmaps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFlightTests {
    /** iOS's main actor runs a callback made on it inline (`MainActor.assumeIsolated`): Main.immediate here. */
    private lateinit var main: kotlinx.coroutines.test.TestDispatcher

    @Before fun setUp() {
        main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private val bounds = rect(0f, 0f, 400f, 900f)

    private fun batch(): Pair<PhotoFlights, UUID> {
        val flights = PhotoFlights()
        val id = UUID.randomUUID()
        flights.source("photo", PhotoFlights.Source(frame = rect(20f, 500f, 100f, 100f), radius = 0f))
        flights.prepare(listOf(PhotoFlights.Pick("photo", id)), windowBounds = bounds)
        return flights to id
    }

    @Test fun membershipArrivesBeforeLayoutAndRemovalRetiresFlight() {
        val (flights, id) = batch()
        try {
            flights.attachmentsChanged(listOf(id))
            assertEquals(true, flights.flights.firstOrNull()?.arrived)
            // No slot report or display tick occurs before removal/send.
            flights.attachmentsChanged(emptyList())
            assertEquals(PhotoFlights.Flight.State.Leaving, flights.flights.firstOrNull()?.state)
            assertFalse(flights.hidesAttachment(id))
        } finally {
            flights.reset()
        }
    }

    @Test fun sendAfterImportCompletionRetiresFlightBeforeLayout() {
        val (flights, id) = batch()
        try {
            flights.attachmentsChanged(listOf(id))
            flights.importFinished()
            flights.attachmentsChanged(emptyList())
            assertEquals(PhotoFlights.Flight.State.Leaving, flights.flights.firstOrNull()?.state)
            assertFalse(flights.hidesAttachment(id))
        } finally {
            flights.reset()
        }
    }

    @Test fun failedImportRetiresUnresolvedFlight() {
        val (flights, id) = batch()
        try {
            flights.importFinished()
            assertEquals(PhotoFlights.Flight.State.Leaving, flights.flights.firstOrNull()?.state)
            assertFalse(flights.hidesAttachment(id))
        } finally {
            flights.reset()
        }
    }

    @Test fun supersedingBatchRetiresOnlyUnimportedFlights() {
        val (flights, first) = batch()
        try {
            val second = UUID.randomUUID()
            val third = UUID.randomUUID()
            flights.prepare(listOf(PhotoFlights.Pick("photo", second)), windowBounds = bounds)
            assertEquals(PhotoFlights.Flight.State.Leaving, flights.flights.firstOrNull()?.state)
            assertFalse(flights.hidesAttachment(first))
            flights.attachmentsChanged(listOf(second))
            flights.prepare(listOf(PhotoFlights.Pick("photo", third)), windowBounds = bounds)
            assertEquals(PhotoFlights.Flight.State.Flying, flights.flights.firstOrNull { it.id == second }?.state)
            assertTrue(flights.hidesAttachment(second))
            assertTrue(flights.hidesAttachment(third))
        } finally {
            flights.reset()
        }
    }

    @Test fun resetClearsPresentationAndAllowsAnotherBatch() {
        val (flights, id) = batch()
        flights.attachmentsChanged(listOf(id)) // Also wakes the frame loop.
        flights.reset()
        assertTrue(flights.flights.isEmpty())
        assertTrue(flights.hiddenAttachments.isEmpty())
        assertTrue(flights.hiddenPhotos.isEmpty())
        assertFalse(flights.isFlying)
        assertNull(flights.restingComposer)
        flights.reset() // Idempotent on scene-inactive followed by disappear.
        val next = UUID.randomUUID()
        flights.prepare(listOf(PhotoFlights.Pick("photo", next)), windowBounds = bounds)
        try {
            assertEquals(1, flights.flights.size)
            assertTrue(flights.hidesAttachment(next))
        } finally {
            flights.reset()
        }
    }

    @Test fun clearingRestingTargetPreservesFlightAndReopeningCancelsIt() {
        val (flights, _) = batch()
        try {
            val id = UUID.randomUUID()
            val rest = rect(0f, 300f, 400f, 150f)
            flights.prepare(listOf(PhotoFlights.Pick("photo", id)), windowBounds = bounds, restingComposer = rest)
            assertEquals(rest, flights.restingComposer)
            flights.clearRestingComposer()
            assertNull(flights.restingComposer)
            assertEquals(PhotoFlights.Flight.State.Flying, flights.flights.lastOrNull()?.state)
            flights.cancelAll()
            assertTrue(flights.flights.all { it.state == PhotoFlights.Flight.State.Leaving })
            assertTrue(flights.hiddenAttachments.isEmpty())
        } finally {
            flights.reset()
        }
    }

    @Test fun invisibleSourcesAreNotAdmitted() {
        val flights = PhotoFlights()
        try {
            flights.sheetViewport = rect(0f, 500f, 400f, 400f)
            flights.source("above", PhotoFlights.Source(frame = rect(0f, 390f, 100f, 100f), radius = 0f, isSheet = true))
            flights.source("partial", PhotoFlights.Source(frame = rect(0f, 450f, 100f, 100f), radius = 0f, isSheet = true))
            flights.source("outside", PhotoFlights.Source(frame = rect(410f, 500f, 100f, 100f), radius = 0f))
            flights.source("gone", PhotoFlights.Source(frame = rect(0f, 500f, 100f, 100f), radius = 0f))
            flights.source("gone", null)
            flights.prepare(listOf("above", "partial", "outside", "gone").map { PhotoFlights.Pick(it, UUID.randomUUID()) }, windowBounds = bounds)
            assertTrue(flights.flights.isEmpty())
            assertTrue(flights.hiddenAttachments.isEmpty())
            assertFalse(flights.isFlying)
            flights.source("visible", PhotoFlights.Source(frame = rect(20f, 520f, 100f, 100f), radius = 0f, isSheet = true))
            flights.prepare(listOf(PhotoFlights.Pick("visible", UUID.randomUUID())), windowBounds = bounds)
            assertEquals(1, flights.flights.size)
        } finally {
            flights.reset()
        }
    }

    @Test fun clippedDestinationRetiresFlightInsteadOfHoldingOverlay() {
        val (flights, id) = batch()
        try {
            flights.composer(rect(10f, 350f, 380f, 200f))
            flights.attachmentsChanged(listOf(id))
            val flight = flights.flights.first()
            val visible = rect(20f, 370f, 76f, 76f)
            flights.slot(id, visible)
            assertEquals(visible, flights.resolvedSlot(flight))
            flights.slot(id, rect(360f, 370f, 76f, 76f))
            assertNull(flights.resolvedSlot(flight))
            assertEquals(PhotoFlights.Flight.State.Leaving, flight.state)
            assertFalse(flights.hidesAttachment(id))
        } finally {
            flights.reset()
        }
    }

    /** The importer launches on Dispatchers.Main, which dispatches (as Android's main looper does). */
    private fun dispatchingMainTest(body: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) { body() }
    }

    @Test fun cameraEphemeralSourceRetiresOnCancelAndReset() {
        val flights = PhotoFlights()
        val id = UUID.randomUUID()
        val photoID = "camera-$id"
        flights.source(photoID, PhotoFlights.Source(frame = rect(20f, 600f, 100f, 100f), radius = 18f, isEphemeral = true))
        flights.source("tile-1", PhotoFlights.Source(frame = rect(130f, 600f, 100f, 100f), radius = 0f))
        assertNotNull(flights.source(photoID))
        flights.cancelAll()
        assertNull(flights.source(photoID))
        assertNotNull(flights.source("tile-1"))

        val secondPhotoID = "camera-2"
        flights.source(secondPhotoID, PhotoFlights.Source(frame = rect(20f, 600f, 100f, 100f), radius = 18f, isEphemeral = true))
        flights.reset()
        assertNull(flights.source(secondPhotoID))
        assertNotNull(flights.source("tile-1"))
    }

    /**
     * The library's reload warms the first thumbnails at its display scale; iOS races that warmup
     * against the 2x/3x reads. A display scale of 1 keeps the warmup on its own cache key.
     */
    @Test fun photoLibraryUpgradesOpportunisticThumbnailsRespectsScaleAndInvalidatesModifiedAssets() = runTest(main) {
        val modifiedDate1 = Instant.ofEpochSecond(1_000)
        val modifiedDate2 = Instant.ofEpochSecond(2_000)
        var currentModified = modifiedDate1
        var currentAccess = PhotoLibraryAccess.Full
        var stopCachingCount = 0
        val requestedScales = mutableListOf<Float>()

        val degradedImage = FakeImage(10)
        val highQuality2x = FakeImage(280)
        val highQuality3x = FakeImage(420)

        val client = PhotoLibraryClient(
            access = { currentAccess },
            requestAccess = { currentAccess },
            recent = { listOf(LibraryPhoto(id = "p1", created = modifiedDate1, modified = currentModified)) },
            thumbnail = { _, _, scale, onDegraded ->
                requestedScales += scale
                onDegraded?.invoke(degradedImage)
                if (scale >= 3) highQuality3x else highQuality2x
            },
            stopCaching = { stopCachingCount += 1 },
            imageData = { null },
            changes = { emptyFlow() },
            manageLimitedSelection = {},
        )
        val library = PlusPhotoLibrary(client)
        library.displayScale = 1f
        library.load()
        var sawDegraded = false
        val loaded2x = library.thumbnail("p1", side = 140f, scale = 2f) { preview ->
            if (preview === degradedImage) sawDegraded = true
        }
        assertTrue(sawDegraded)
        assertSame(highQuality2x, loaded2x)
        assertSame(highQuality2x, library.cachedThumbnail("p1", side = 140f, scale = 2f))
        assertNull(library.cachedThumbnail("p1", side = 140f, scale = 3f))

        val loaded3x = library.thumbnail("p1", side = 140f, scale = 3f)
        assertSame(highQuality3x, loaded3x)
        assertSame(highQuality3x, library.cachedThumbnail("p1", side = 140f, scale = 3f))

        // Modifying the asset invalidates cached thumbnails on reload.
        currentModified = modifiedDate2
        library.load()
        assertNull(library.cachedThumbnail("p1", side = 140f, scale = 2f))
        assertNull(library.cachedThumbnail("p1", side = 140f, scale = 3f))

        // Losing access or stopping tears down cached thumbnails and calls stopCaching.
        library.thumbnail("p1", side = 140f, scale = 3f)
        assertSame(highQuality3x, library.cachedThumbnail("p1", side = 140f, scale = 3f))
        currentAccess = PhotoLibraryAccess.Denied
        library.load()
        assertNull(library.cachedThumbnail("p1", side = 140f, scale = 3f))
        assertTrue(stopCachingCount >= 1)

        library.stop()
        assertTrue(stopCachingCount >= 2)
        assertTrue(requestedScales.containsAll(listOf(2f, 3f)))
        library.close()
    }

    @Test fun supersedingImportAndSynchronousMembershipKeepLastFlightAliveAndRetireOldBatchOnly() = dispatchingMainTest {
        val flights = PhotoFlights()
        val importer = ComposerImageImporter(backgroundScope)
        val bounds = rect(0f, 0f, 430f, 932f)
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        val batch1 = UUID.randomUUID()
        val batch2 = UUID.randomUUID()
        flights.source("p1", PhotoFlights.Source(frame = rect(20f, 500f, 120f, 120f), radius = 0f))
        flights.source("p2", PhotoFlights.Source(frame = rect(150f, 500f, 120f, 120f), radius = 0f))
        flights.source("p3", PhotoFlights.Source(frame = rect(280f, 500f, 120f, 120f), radius = 0f))

        val gate1 = CompletableDeferred<ByteArray?>()
        var gate1Started = false
        val presentIDs = mutableListOf<UUID>()

        flights.reserve(listOf(id1), batch = batch1)
        flights.prepare(listOf(PhotoFlights.Pick("p1", id1)), batch = batch1, windowBounds = bounds)
        importer.start(
            listOf(
                ComposerImageSource.Loader(attachmentID = id1) {
                    gate1Started = true
                    gate1.await()
                },
            ),
            onAttachment = { attachment ->
                presentIDs += UUID.fromString(attachment.id)
                flights.attachmentsChanged(presentIDs.toList())
            },
            onFailure = { failedID -> if (failedID != null) flights.dropReservation(failedID) },
            onCancel = { flights.importFinished(batch = batch1) },
            onCompletion = {
                flights.attachmentsChanged(presentIDs.toList())
                flights.importFinished(batch = batch1)
            },
        )

        repeat(20) { if (!gate1Started) yield() }

        // Supersede batch 1 with batch 2 (even if reserve/prepare happen before start() cancels batch 1).
        val sampleData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        flights.reserve(listOf(id2, id3), batch = batch2)
        flights.prepare(listOf(PhotoFlights.Pick("p2", id2), PhotoFlights.Pick("p3", id3)), batch = batch2, windowBounds = bounds)

        val done = CompletableDeferred<Unit>()
        importer.start(
            listOf(
                ComposerImageSource.Loader(attachmentID = id2) { sampleData },
                ComposerImageSource.Loader(attachmentID = id3) { sampleData },
            ),
            process = { data, id -> OpenCodeOutgoingAttachment(id = id.toString(), data = data, mime = "image/jpeg") },
            onAttachment = { attachment ->
                presentIDs += UUID.fromString(attachment.id)
                flights.attachmentsChanged(presentIDs.toList())
            },
            onFailure = { failedID -> if (failedID != null) flights.dropReservation(failedID) },
            onCancel = { flights.importFinished(batch = batch2) },
            onCompletion = {
                flights.attachmentsChanged(presentIDs.toList())
                flights.importFinished(batch = batch2)
                done.complete(Unit)
            },
        )
        done.await()
        gate1.complete(sampleData)

        assertEquals(listOf(id2, id3), presentIDs)
        assertTrue(flights.reservedAttachments.isEmpty())
        assertEquals(PhotoFlights.Flight.State.Leaving, flights.flights.firstOrNull { it.id == id1 }?.state)
        // Both batch 2 flights (including the last one) remain flying and arrived, not retired as failed.
        val flight2 = flights.flights.firstOrNull { it.id == id2 }
        val flight3 = flights.flights.firstOrNull { it.id == id3 }
        assertEquals(PhotoFlights.Flight.State.Flying, flight2?.state)
        assertEquals(true, flight2?.arrived)
        assertEquals(PhotoFlights.Flight.State.Flying, flight3?.state)
        assertEquals(true, flight3?.arrived)
        flights.reset()
    }

    @Test fun degradedPreviewFollowedByNilIsNotCachedAndGenerationGuardsStopStaleWrites() = runTest(main) {
        val degradedImage = FakeImage(8)
        val finalImage = FakeImage(16)
        var thumbGate: CompletableDeferred<ImageBitmap?>? = null
        val thumbStarted = CompletableDeferred<Unit>()

        val client = PhotoLibraryClient(
            access = { PhotoLibraryAccess.Full },
            requestAccess = { PhotoLibraryAccess.Full },
            recent = { listOf(LibraryPhoto(id = "p1", created = Instant.ofEpochSecond(1_000), modified = Instant.ofEpochSecond(2_000))) },
            thumbnail = { _, _, _, onDegraded ->
                onDegraded?.invoke(degradedImage)
                val gate = thumbGate
                if (gate != null) {
                    thumbStarted.complete(Unit)
                    gate.await()
                } else {
                    null
                }
            },
            stopCaching = {},
            imageData = { null },
            changes = { emptyFlow() },
            manageLimitedSelection = {},
        )
        val library = PlusPhotoLibrary(client)
        library.load()

        // Degraded preview followed by terminal nil must not be cached as final.
        var sawDegraded = false
        val failedFinal = library.thumbnail("p1", side = 140f, scale = 2f) { preview ->
            if (preview === degradedImage) sawDegraded = true
        }
        assertTrue(sawDegraded)
        assertNull(failedFinal)
        assertNull(library.cachedThumbnail("p1", side = 140f, scale = 2f))

        // Generation bump during in-flight thumbnail load prevents stale cache writes.
        val gate = CompletableDeferred<ImageBitmap?>()
        thumbGate = gate
        val inflightThumb = async { library.thumbnail("p1", side = 140f, scale = 2f) }
        thumbStarted.await()
        thumbGate = null

        // Purge bumps generation before the in-flight thumbnail finishes.
        library.purgeCache()
        gate.complete(finalImage)
        val staleLoaded = inflightThumb.await()
        assertNull(staleLoaded)
        assertNull(library.cachedThumbnail("p1", side = 140f, scale = 2f))
        library.close()
    }

    @Test fun photoCacheGateSerializesCommitAndStopUnderOneLock() {
        val events = mutableListOf<String>()
        val gate = PhotoCacheGate<String>(
            stopAll = { events += "stop" },
            startWarmup = { assets, scale -> events += "start:${assets.joinToString(",")}@${scale.toInt()}x" },
        )

        // Token captured before a suspension; stopCaching before commit rejects the stale fetch.
        val staleToken = gate.currentGeneration()
        gate.stopCaching()
        assertFalse(gate.isCurrent(staleToken))
        assertFalse(gate.commit(expectedGeneration = staleToken, assetsById = mapOf("p1" to "asset-1"), warmupAssets = listOf("asset-1"), scale = 3f))
        assertNull(gate.cachedAsset("p1"))
        assertEquals(listOf("stop"), events)

        // Current token commits atomically under the lock.
        val validToken = gate.currentGeneration()
        assertTrue(gate.commit(expectedGeneration = validToken, assetsById = mapOf("p1" to "asset-1"), warmupAssets = listOf("asset-1"), scale = 3f))
        assertEquals("asset-1", gate.cachedAsset("p1"))
        assertEquals(listOf("stop", "stop", "start:asset-1@3x"), events)

        gate.stopCaching()
        assertNull(gate.cachedAsset("p1"))
        assertEquals(listOf("stop", "stop", "start:asset-1@3x", "stop"), events)
    }

    /** iOS's `withObservationTracking` is Compose's snapshot read observer plus an apply observer here. */
    @Test fun deniedAccessStartsWatchingAndReloadsWhenLaterAuthorizedAndCacheKeyIsObservable() = runTest(main) {
        var currentAccess = PhotoLibraryAccess.Denied
        val modifiedDate1 = Instant.ofEpochSecond(1_000)
        val modifiedDate2 = Instant.ofEpochSecond(2_000)
        var currentModified = modifiedDate1
        var watching = false
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val client = PhotoLibraryClient(
            access = { currentAccess },
            requestAccess = { currentAccess },
            recent = { listOf(LibraryPhoto(id = "p1", created = modifiedDate1, modified = currentModified)) },
            thumbnail = { _, _, _, _ -> null },
            stopCaching = {},
            imageData = { null },
            changes = {
                watching = true
                changes
            },
            manageLimitedSelection = {},
        )
        val library = PlusPhotoLibrary(client)
        library.load()
        assertEquals(PhotoLibraryAccess.Denied, library.access)
        assertTrue(library.photos.isEmpty())
        assertTrue(watching)

        // Later authorization change delivered over changes() reloads photos.
        currentAccess = PhotoLibraryAccess.Full
        changes.tryEmit(Unit)
        repeat(40) { if (library.photos.isEmpty()) yield() }
        assertEquals(PhotoLibraryAccess.Full, library.access)
        assertEquals(listOf("p1"), library.photos.map { it.id })
        assertEquals(modifiedDate1, library.cacheKey("p1", side = 64f, scale = 2f).modified)

        // Reading cacheKey is tracked (snapshot state) so RecentPhotoPeekTile restarts on modification.
        val reads = HashSet<Any>()
        Snapshot.observe(readObserver = { reads += it }) { library.cacheKey("p1", side = 64f, scale = 2f) }
        var observedChange = false
        val handle = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) observedChange = true }
        try {
            currentModified = modifiedDate2
            library.load()
            Snapshot.sendApplyNotifications()
            assertTrue(observedChange)
        } finally {
            handle.dispose()
        }
        assertEquals(modifiedDate2, library.cacheKey("p1", side = 64f, scale = 2f).modified)
        library.stop()
        library.close()
    }

    @Test fun staleAuthorizedReloadDoesNotRepublishAfterAccessLossWhileWatcherSurvives() = runTest(main) {
        var currentAccess = PhotoLibraryAccess.Full
        var recentGate: CompletableDeferred<List<LibraryPhoto>>? = null
        var watching = false
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val freshPhoto = LibraryPhoto(id = "fresh", created = Instant.ofEpochSecond(1_000), modified = Instant.ofEpochSecond(2_000))
        val stalePhoto = LibraryPhoto(id = "stale", created = Instant.ofEpochSecond(500), modified = Instant.ofEpochSecond(600))

        val client = PhotoLibraryClient(
            access = { currentAccess },
            requestAccess = { currentAccess },
            recent = {
                if (recentGate == null && currentAccess == PhotoLibraryAccess.Full && !watching) {
                    val gate = CompletableDeferred<List<LibraryPhoto>>()
                    recentGate = gate
                    gate.await()
                } else {
                    listOf(freshPhoto)
                }
            },
            thumbnail = { _, _, _, _ -> null },
            stopCaching = {},
            imageData = { null },
            changes = {
                watching = true
                changes
            },
            manageLimitedSelection = {},
        )
        val library = PlusPhotoLibrary(client)
        val staleLoadTask = launch { library.load() }
        repeat(40) { if (recentGate == null) yield() }
        assertNotNull(recentGate)

        // Access is revoked while the first reload is still awaiting client.recent.
        currentAccess = PhotoLibraryAccess.Denied
        library.prime()
        assertEquals(PhotoLibraryAccess.Denied, library.access)
        assertTrue(library.photos.isEmpty())
        assertTrue(watching)

        // Even if the older reload resumes, its invalidated generations prevent republishing stale photos.
        recentGate?.complete(listOf(stalePhoto))
        staleLoadTask.join()
        assertTrue(library.photos.isEmpty())

        // The watcher started during the denied prime() remains active and reloads when later authorized.
        currentAccess = PhotoLibraryAccess.Full
        changes.tryEmit(Unit)
        repeat(40) { if (library.photos.isEmpty()) yield() }
        assertEquals(PhotoLibraryAccess.Full, library.access)
        assertEquals(listOf(freshPhoto), library.photos)
        library.stop()
        library.close()
    }

    @Test fun morphGeometryKeepingBottomGrowsHeightAsTopRises() {
        val geometry = ComposerMorphGeometry(
            source = rect(14f, 480f, 402f, 96f),
            sourceRadius = 28f,
            target = rect(8f, 480f, 414f, 468f),
            topRadius = 30f,
            bottomRadius = 46f,
        )
        val expanded = geometry.resting(116f, keepingBottom = true, minHeightPx = MIN_HEIGHT)
        assertEquals(116f, expanded.target.top, 0f)
        assertEquals(948f, expanded.target.bottom, 0f)
        assertEquals(832f, expanded.target.height, 0f)
    }

    @Test fun criticallyDampedSpringNeverOvershootsAndLandsOnItsTarget() {
        val (omega, zeta) = SpringSolver.parameters(duration = 0.52, bounce = 0.0)
        assertEquals(1.0, zeta, 0.0)
        val spring = spring(0.0)
        var last = 0.0
        repeat(120) {
            spring.step(300.0, omega, zeta, 1.0 / 60)
            assertTrue(spring.value >= last)
            assertTrue(spring.value <= 300)
            last = spring.value
        }
        assertTrue(abs(spring.value - 300) < 0.01)
    }

    @Test fun retargetingKeepsPositionAndVelocity() {
        val (omega, zeta) = SpringSolver.parameters(duration = 0.52, bounce = 0.0)
        val spring = spring(0.0)
        repeat(10) { spring.step(300.0, omega, zeta, 1.0 / 60) }
        val before = spring.copy()
        val moved = spring.copy()
        moved.step(200.0, omega, zeta, 1e-6)
        assertTrue(abs(moved.value - before.value) < 0.01)
        assertTrue(abs(moved.velocity - before.velocity) < 0.5)
    }

    @Test fun bouncySpringOvershoots() {
        val (omega, zeta) = SpringSolver.parameters(duration = 0.5, bounce = 0.4)
        val spring = spring(0.0)
        var peak = 0.0
        repeat(120) {
            spring.step(100.0, omega, zeta, 1.0 / 60)
            peak = max(peak, spring.value)
        }
        assertTrue(peak > 105)
        assertTrue(abs(spring.value - 100) < 0.5)
    }

    @Test fun libraryPhotoDurationFormattingAndVideoMetadata() {
        assertEquals("0:00", LibraryPhoto.formatDuration(-4.0))
        assertEquals("0:00", LibraryPhoto.formatDuration(0.0))
        assertEquals("0:00", LibraryPhoto.formatDuration(0.4))
        assertEquals("0:01", LibraryPhoto.formatDuration(0.6))
        assertEquals("0:08", LibraryPhoto.formatDuration(8.0))
        assertEquals("0:14", LibraryPhoto.formatDuration(14.2))
        assertEquals("0:31", LibraryPhoto.formatDuration(31.0))
        assertEquals("1:05", LibraryPhoto.formatDuration(65.0))
        assertEquals("9:59", LibraryPhoto.formatDuration(599.4))
        assertEquals("10:00", LibraryPhoto.formatDuration(599.6))
        assertEquals("1:00:00", LibraryPhoto.formatDuration(3_600.0))
        assertEquals("1:01:05", LibraryPhoto.formatDuration(3_665.0))
        assertEquals("0:00", LibraryPhoto.formatDuration(Double.POSITIVE_INFINITY))
        assertEquals("0:00", LibraryPhoto.formatDuration(Double.NaN))

        val still = LibraryPhoto(id = "photo-1", created = Instant.ofEpochSecond(1_000))
        assertFalse(still.isVideo)
        assertNull(still.videoDuration)
        assertNull(still.formattedDuration)

        val video = LibraryPhoto(id = "video-1", created = Instant.ofEpochSecond(2_000), videoDuration = 14.0)
        assertTrue(video.isVideo)
        assertEquals(14.0, video.videoDuration)
        assertEquals("0:14", video.formattedDuration)
    }

    @Test fun plusPhotoLibraryExposesVideoAssetsAndLabFixtureIncludesVideosAndCameraCapture() = runTest(main) {
        val fixture = PhotoLibraryClient.labFixture(limited = false)
        val recent = fixture.recent(10)
        assertEquals(10, recent.size)
        assertFalse(recent[0].isVideo)
        assertTrue(recent[1].isVideo)
        assertEquals("0:14", recent[1].formattedDuration)
        assertFalse(recent[2].isVideo)
        assertTrue(recent[4].isVideo)
        assertEquals("0:08", recent[4].formattedDuration)
        assertTrue(recent[7].isVideo)
        assertEquals("0:31", recent[7].formattedDuration)

        val videoData = fixture.imageData("lab-photo-1")
        assertNotNull(videoData)
        assertNotNull(android.graphics.BitmapFactory.decodeByteArray(videoData, 0, videoData!!.size))

        val listing = PhotoLibraryClient(
            access = fixture.access,
            requestAccess = fixture.requestAccess,
            recent = fixture.recent,
            thumbnail = { _, _, _, _ -> null },
            imageData = { null },
            changes = fixture.changes,
            manageLimitedSelection = fixture.manageLimitedSelection,
        )
        val library = PlusPhotoLibrary(listing)
        library.load()
        assertEquals(false, library.photo("lab-photo-0")?.isVideo)
        assertEquals(true, library.photo("lab-photo-1")?.isVideo)
        assertEquals("0:14", library.photo("lab-photo-1")?.formattedDuration)
        assertEquals("0:08", library.photo("lab-photo-4")?.formattedDuration)
        library.stop()
        library.close()
    }

    @Test fun cameraPresentationCaptureSourceFrameUsesExpandedViewportOrTileFallback() {
        val camera = PlusCameraPresentation()
        val fallback = rect(20f, 800f, 400f, 56f)
        assertEquals(fallback, camera.captureSourceFrame(density = 1f, fallback = fallback, inset = 20f))

        val tile = rect(10f, 520f, 138f, 138f)
        camera.tile = tile
        camera.viewport = rect(8f, 500f, 424f, 448f)
        assertEquals(tile, camera.captureSourceFrame(density = 1f, fallback = fallback, inset = 20f))

        camera.expanded = true
        val expandedSource = camera.captureSourceFrame(density = 1f, fallback = fallback, inset = 20f)
        assertEquals(rect(28f, 532f, 384f, 384f), expandedSource)
    }

    @Test fun flightCloseBadgeScalesAndFadesDuringFlightAndReversesOnLeave() {
        val sourceRect = rect(140f, 520f, 138f, 138f)
        val slotRect = rect(26f, 417f, PhotoFlights.SLOT_SIDE, PhotoFlights.SLOT_SIDE)
        val flight = PhotoFlights.Flight(
            id = UUID.randomUUID(),
            photo = "lab-photo-0",
            source = PhotoFlights.Source(frame = sourceRect, radius = 0f, isSheet = true),
            start = 0.0,
        )

        val (initialOpacity, initialScale) = flight.closeBadgeAppearance(start = 0.2f, span = 0.55f, initialScale = 0.45f)
        assertEquals(0f, initialOpacity, 0f)
        assertTrue(abs(initialScale - 0.45f) < 0.001f)

        flight.departed = true
        flight.target = slotRect
        val (omega, zeta) = SpringSolver.parameters(duration = 0.34, bounce = 0.0)
        var previousOpacity = 0f
        var previousScale = 0.45f
        var sawMidFlightAppearance = false

        repeat(45) {
            flight.x.step(slotRect.center.x.toDouble(), omega, zeta, 1.0 / 60)
            flight.y.step(slotRect.center.y.toDouble(), omega * 1.45, zeta, 1.0 / 60)
            flight.side.step(slotRect.width.toDouble(), omega, zeta, 1.0 / 60)
            val (opacity, scale) = flight.closeBadgeAppearance(start = 0.2f, span = 0.55f, initialScale = 0.45f)
            assertTrue(opacity >= previousOpacity - 1e-5f)
            assertTrue(scale >= previousScale - 1e-5f)
            if (opacity > 0.1f && opacity < 0.9f) sawMidFlightAppearance = true
            previousOpacity = opacity
            previousScale = scale
        }
        assertTrue(sawMidFlightAppearance)
        assertTrue(abs(previousOpacity - 1) < 0.001f)
        assertTrue(abs(previousScale - 1) < 0.001f)

        flight.x.value = slotRect.center.x.toDouble()
        flight.y.value = slotRect.center.y.toDouble()
        flight.side.value = slotRect.width.toDouble()
        flight.state = PhotoFlights.Flight.State.Landed
        val landed = flight.closeBadgeAppearance(start = 0.2f, span = 0.55f, initialScale = 0.45f)
        assertEquals(1f to 1f, landed)
        assertEquals(
            Offset(slotRect.right - PhotoFlightLayout.CLOSE_BADGE_INSET, slotRect.top + PhotoFlightLayout.CLOSE_BADGE_INSET),
            flight.closeBadgeCenter,
        )

        flight.state = PhotoFlights.Flight.State.Leaving
        flight.fade.value = 0.5
        val (halfOpacity, halfScale) = flight.closeBadgeAppearance(start = 0.2f, span = 0.55f, initialScale = 0.45f)
        assertTrue(abs(halfOpacity - 0.5f) < 0.001f)
        assertTrue(abs(halfScale - 0.725f) < 0.001f)

        flight.fade.value = 0.0
        val (goneOpacity, goneScale) = flight.closeBadgeAppearance(start = 0.2f, span = 0.55f, initialScale = 0.45f)
        assertEquals(0f, goneOpacity, 0f)
        assertTrue(abs(goneScale - 0.45f) < 0.001f)
    }

    @Test fun takePicksHoldsClosingPickCountUntilResetOrNewPick() {
        val library = PlusPhotoLibrary()
        assertEquals(0, library.closingPickCount)

        library.toggle("lab-photo-0")
        library.toggle("lab-photo-1")
        assertEquals(listOf("lab-photo-0", "lab-photo-1"), library.picks)
        assertEquals(0, library.closingPickCount)

        val taken = library.takePicks()
        assertEquals(listOf("lab-photo-0", "lab-photo-1"), taken)
        assertTrue(library.picks.isEmpty())
        assertEquals(2, library.closingPickCount)

        // closePhotoSheet clears picks immediately; closingPickCount stays latched
        // while the foot dissolves with the folding sheet.
        library.clearPicks()
        assertEquals(2, library.closingPickCount)

        library.resetClosingPicks()
        assertEquals(0, library.closingPickCount)

        library.toggle("lab-photo-2")
        library.takePicks()
        assertEquals(1, library.closingPickCount)
        library.toggle("lab-photo-0")
        assertEquals(0, library.closingPickCount)
        library.close()
    }

    @Test fun photoSheetDetentExpandAndCollapseStateTransitions() {
        val detent = PlusPhotoSheetDetent()
        detent.open(compactTop = 500f, ceiling = 120f)
        assertFalse(detent.isExpanded)
        assertEquals(500f, detent.top, 0f)
        assertEquals(0f, detent.drag, 0f)

        // Small upward drag below threshold stays compact.
        detent.dragChanged(-30f)
        assertEquals(-30f, detent.drag, 0f)
        val stayCompact = detent.settle(predicted = -36f, velocity = -80f, expandDrag = 56f, expandVelocity = 260f, collapseDrag = 64f)
        assertEquals(PlusPhotoSheetDetent.Settlement.Stay(expanded = false, top = 500f, initialVelocity = 0f), stayCompact)
        assertFalse(detent.isExpanded)
        assertEquals(500f, detent.top, 0f)
        assertEquals(0f, detent.drag, 0f)

        // Upward drag past threshold expands to ceiling with velocity handoff.
        detent.dragChanged(-120f)
        assertEquals(-120f, detent.drag, 0f)
        val expanded = detent.settle(predicted = -240f, velocity = -520f, expandDrag = 56f, expandVelocity = 260f, collapseDrag = 64f)
        if (expanded is PlusPhotoSheetDetent.Settlement.Stay) {
            assertTrue(expanded.expanded)
            assertEquals(120f, expanded.top, 0f)
            assertTrue(expanded.initialVelocity > 0)
        } else {
            fail("Expected expanded stay settlement, got $expanded")
        }
        assertTrue(detent.isExpanded)
        assertEquals(120f, detent.top, 0f)
        assertEquals(0f, detent.drag, 0f)

        // Downward drag from expanded mode (including a long drag past compactTop)
        // collapses back to compact mode instead of closing the sheet.
        val pulledLong = detent.dragChanged(420f)
        assertTrue(pulledLong > 380)
        assertTrue(pulledLong < 428)
        val collapsed = detent.settle(predicted = 520f, velocity = 900f, expandDrag = 56f, expandVelocity = 260f, collapseDrag = 64f)
        if (collapsed is PlusPhotoSheetDetent.Settlement.Stay) {
            assertFalse(collapsed.expanded)
            assertEquals(500f, collapsed.top, 0f)
        } else {
            fail("Expected compact stay settlement from expanded drag-down, got $collapsed")
        }
        assertFalse(detent.isExpanded)
        assertEquals(500f, detent.top, 0f)
        assertEquals(0f, detent.drag, 0f)

        // Downward drag from compact mode past close threshold dismisses the sheet.
        detent.dragChanged(160f)
        val closed = detent.settle(predicted = 220f, velocity = 600f, expandDrag = 56f, expandVelocity = 260f, collapseDrag = 64f)
        assertEquals(PlusPhotoSheetDetent.Settlement.Close, closed)
    }

    @Test fun photoSheetExpandAndCollapsePerFrameTraceFollowsFingerWithoutJump() {
        val baseGeometry = ComposerMorphGeometry(
            source = rect(14f, 480f, 402f, 96f),
            sourceRadius = 28f,
            target = rect(8f, 500f, 414f, 448f),
            topRadius = 30f,
            bottomRadius = 46f,
        )
        val compactTop = 500f
        val ceiling = 120f
        val dt = 1.0 / 120.0
        val (omega, zeta) = SpringSolver.parameters(duration = 0.38, bounce = 0.08)

        fun renderedHeight(restY: Float, drag: Float): Float {
            val placed = baseGeometry.resting(restY, keepingBottom = true, minHeightPx = MIN_HEIGHT)
            val rect = placed.frame(progress = 1f, bottom = 1f, drag = 0f).rect
            val (stretched, _) = ComposerMorphGeometry.stretched(rect, pull = drag, stretchesUpward = true, minHeightPx = MIN_HEIGHT)
            return stretched.height
        }

        val detent = PlusPhotoSheetDetent()
        detent.open(compactTop = compactTop, ceiling = ceiling)
        val initialHeight = renderedHeight(detent.top, detent.drag)
        assertTrue(abs(initialHeight - 448) < 0.001f)

        // 1. Expand drag at -1.25 pt per 120Hz frame across the full 380 pt travel
        // (plus rubber-band past ceiling), verifying 1:1 finger tracking and <= 1.5 pt step on every frame.
        var previousHeight = initialHeight
        val expandPerFrame = -1.25f
        val expandVelocity = (expandPerFrame / dt).toFloat()
        for (frame in 1..312) {
            val translation = frame * expandPerFrame
            val drag = detent.dragChanged(translation)
            val height = renderedHeight(detent.top, drag)
            if (-translation <= compactTop - ceiling) {
                val expectedHeight = initialHeight - translation
                assertTrue("1:1 finger tracking on expand frame $frame", abs(height - expectedHeight) < 0.001f)
            }
            assertTrue("Expand drag step <= 1.5 pt on frame $frame", abs(height - previousHeight) <= 1.5f)
            previousHeight = height
        }

        // Release and settle to expanded detent on the velocity-handoff spring:
        // verify zero jump at release and <= 1.5 pt per frame throughout settle.
        val preReleaseTop = detent.top
        val preReleaseDrag = detent.drag
        val expandSettlement = detent.settle(
            predicted = preReleaseDrag + expandVelocity * 0.15f,
            velocity = expandVelocity,
            expandDrag = 56f,
            expandVelocity = 260f,
            collapseDrag = 64f,
        )
        if (expandSettlement !is PlusPhotoSheetDetent.Settlement.Stay || !expandSettlement.expanded) {
            fail("Expected expand settlement")
            return
        }
        val targetTopUp = expandSettlement.top
        val v0Up = expandSettlement.initialVelocity
        val restSpringUp = spring(preReleaseTop.toDouble(), ((targetTopUp - preReleaseTop) * v0Up).toDouble())
        val dragSpringUp = spring(preReleaseDrag.toDouble(), ((0 - preReleaseDrag) * v0Up).toDouble())
        // Frame 0 of spring matches pre-release height with 0.0 pt jump.
        assertTrue(abs(renderedHeight(restSpringUp.value.toFloat(), dragSpringUp.value.toFloat()) - previousHeight) < 0.001f)
        for (frame in 1..180) {
            restSpringUp.step(targetTopUp.toDouble(), omega, zeta, dt)
            dragSpringUp.step(0.0, omega, zeta, dt)
            val height = renderedHeight(restSpringUp.value.toFloat(), dragSpringUp.value.toFloat())
            assertTrue("Expand spring step <= 1.5 pt on frame $frame: ${abs(height - previousHeight)}", abs(height - previousHeight) <= 1.5f)
            previousHeight = height
        }
        assertTrue(abs(previousHeight - 828) < 0.5f)

        // 2. Collapse drag from expanded detent downward at +1.25 pt per 120Hz frame
        // across the full 380 pt travel (plus rubber-band), verifying 1:1 finger tracking
        // and <= 1.5 pt step across drag, release, and settle.
        previousHeight = renderedHeight(detent.top, detent.drag)
        val expandedHeight = previousHeight
        val collapsePerFrame = 1.25f
        val collapseVelocity = (collapsePerFrame / dt).toFloat()
        for (frame in 1..312) {
            val translation = frame * collapsePerFrame
            val drag = detent.dragChanged(translation)
            val height = renderedHeight(detent.top, drag)
            if (translation <= compactTop - ceiling) {
                val expectedHeight = expandedHeight - translation
                assertTrue("1:1 finger tracking on collapse frame $frame", abs(height - expectedHeight) < 0.001f)
            }
            assertTrue("Collapse drag step <= 1.5 pt on frame $frame", abs(height - previousHeight) <= 1.5f)
            previousHeight = height
        }

        val preCollapseTop = detent.top
        val preCollapseDrag = detent.drag
        val collapseSettlement = detent.settle(
            predicted = preCollapseDrag + collapseVelocity * 0.15f,
            velocity = collapseVelocity,
            expandDrag = 56f,
            expandVelocity = 260f,
            collapseDrag = 64f,
        )
        if (collapseSettlement !is PlusPhotoSheetDetent.Settlement.Stay || collapseSettlement.expanded) {
            fail("Expected collapse to compact settlement")
            return
        }
        val targetTopDown = collapseSettlement.top
        val v0Down = collapseSettlement.initialVelocity
        val restSpringDown = spring(preCollapseTop.toDouble(), ((targetTopDown - preCollapseTop) * v0Down).toDouble())
        val dragSpringDown = spring(preCollapseDrag.toDouble(), ((0 - preCollapseDrag) * v0Down).toDouble())
        assertTrue(abs(renderedHeight(restSpringDown.value.toFloat(), dragSpringDown.value.toFloat()) - previousHeight) < 0.001f)
        for (frame in 1..180) {
            restSpringDown.step(targetTopDown.toDouble(), omega, zeta, dt)
            dragSpringDown.step(0.0, omega, zeta, dt)
            val height = renderedHeight(restSpringDown.value.toFloat(), dragSpringDown.value.toFloat())
            assertTrue("Collapse spring step <= 1.5 pt on frame $frame: ${abs(height - previousHeight)}", abs(height - previousHeight) <= 1.5f)
            previousHeight = height
        }
        assertTrue(abs(previousHeight - 448) < 0.5f)
    }

    @Test fun zeroLengthSingleFrameDragResetsTapSuppressionAndSecondDragCancelsPendingReset() = runTest {
        val suppression = PlusPhotoSheetTapSuppression(this)
        assertFalse(suppression.isSuppressed)

        // 1. A drag that begins and ends within one frame schedules its reset from its end so
        // tiles and the camera do not stay dead.
        suppression.beginDrag()
        assertTrue(suppression.isSuppressed)
        suppression.scheduleReset(delayMillis = 25)
        assertTrue(suppression.isSuppressed)
        advanceTimeBy(55.milliseconds)
        assertFalse(suppression.isSuppressed)

        // 2. A second drag starting within the reset window cancels the first drag's pending task
        // so tap suppression never clears mid-drag.
        suppression.beginDrag()
        suppression.scheduleReset(delayMillis = 60)
        advanceTimeBy(25.milliseconds)
        assertTrue(suppression.isSuppressed)

        // Second drag starts at 25 ms (before the 60 ms reset fires).
        suppression.beginDrag()
        advanceTimeBy(55.milliseconds)
        // Past the first drag's 60 ms deadline, still mid-second-drag: must still be suppressed.
        assertTrue(suppression.isSuppressed)

        // Second drag ends and resets cleanly after its window.
        suppression.scheduleReset(delayMillis = 25)
        advanceTimeBy(55.milliseconds)
        assertFalse(suppression.isSuppressed)
    }

    @Test fun photoSheetOpenFromMenuPerFrameTraceKeepsPhotosRowContinuousWithoutVanishOrJump() {
        val menuRect = rect(12f, 467f, 220f, 148f)
        val sheetRect = rect(8f, 500f, 386f, 366f)
        val geometry = ComposerMorphGeometry(source = menuRect, sourceRadius = 28f, target = sheetRect, topRadius = 30f, bottomRadius = 46f)

        // 1. Frame 0 (`Armed` right on the Photos tap): the glass and the outgoing menu rows
        // (including the Photos row) stay at full opacity on the menu's exact frame.
        val armedFade = ComposerMorphGeometry.glassFade(progress = 0f, armed = true, continuousGlass = true, dismissesDown = false, reduceMotion = false)
        val armedOutgoingOpacity = ComposerMorphGeometry.outgoingOpacity(reveal = 0f, shown = true, fade = armedFade)
        val armedRect = geometry.frame(progress = 0f, bottom = 0f, drag = 0f).rect
        assertEquals(1f, armedFade, 0f)
        assertEquals(1f, armedOutgoingOpacity, 0f)
        assertEquals(menuRect, armedRect)

        // 2. Per-frame trace of the morph from the menu bubble into the photo sheet: no step over
        // 1.5 pt, continuous glass opacity = 1, and outgoing rows + grid cross-dissolve
        // continuously without ever vanishing to 0.
        val (omega, zeta) = SpringSolver.parameters(duration = 0.3, bounce = 0.08)
        val dt = 1.0 / 1_600.0
        val progressSpring = spring(0.0)
        var previousRect = armedRect
        val steps = 1_100
        for (step in 1..steps) {
            progressSpring.step(1.0, omega, zeta, dt)
            val progress = progressSpring.value.toFloat()
            val reveal = min(1f, step.toFloat() / 480f)
            val rect = geometry.frame(progress = progress, bottom = progress, drag = 0f).rect
            val fade = ComposerMorphGeometry.glassFade(progress = progress, armed = false, continuousGlass = true, dismissesDown = false, reduceMotion = false)
            val outgoingOpacity = ComposerMorphGeometry.outgoingOpacity(reveal = reveal, shown = true, fade = fade)
            val contentOpacity = reveal
            assertEquals(1f, fade, 0f)
            assertTrue("Menu rows and grid never both vanish on step $step", outgoingOpacity + contentOpacity >= 0.99f)
            assertTrue(abs(rect.left - previousRect.left) <= 1.5f)
            assertTrue(abs(rect.top - previousRect.top) <= 1.5f)
            assertTrue(abs(rect.bottom - previousRect.bottom) <= 1.5f)
            previousRect = rect
        }
        assertTrue(abs(previousRect.left - sheetRect.left) < 0.01f)
        assertTrue(abs(previousRect.top - sheetRect.top) < 0.01f)
        assertTrue(abs(previousRect.width - sheetRect.width) < 0.01f)
        assertTrue(abs(previousRect.height - sheetRect.height) < 0.01f)
    }

    @Test fun photoSheetDismissKeepsDragFrameThenShrinksIntoPlus() {
        val compactTarget = rect(8f, 500f, 386f, 366f)
        val startDrag = 42f
        // Keep drag on the same morph clock as progress. Resetting it or baking only its top into
        // the target loses the translated bottom at release.
        val (preFoldStretched, downward) = ComposerMorphGeometry.stretched(
            compactTarget, pull = startDrag, stretchesUpward = true, minHeightPx = MIN_HEIGHT, translatesDownward = true,
        )
        val detent = PlusPhotoSheetDetent()
        detent.open(compactTop = compactTarget.top, ceiling = 120f)
        detent.dragChanged(startDrag)
        val dismissStartTarget = preFoldStretched.translate(0f, downward)
        assertEquals(compactTarget.translate(0f, startDrag), dismissStartTarget)

        val seed = rect(24f, 810f, 34f, 34f)
        val dismissGeometry = ComposerMorphGeometry(
            source = seed,
            sourceRadius = 17f,
            target = compactTarget,
            topRadius = 30f,
            bottomRadius = 46f,
        )

        val (omega, zeta) = SpringSolver.parameters(duration = 0.28, bounce = 0.0)
        val dt = 1.0 / 1_200.0
        val progressSpring = spring(1.0)
        var previousRect = dismissStartTarget
        var previousFade = 1f

        for (frame in 1..600) {
            progressSpring.step(0.0, omega, zeta, dt)
            val p = progressSpring.value.toFloat()
            val rect = dismissGeometry.frame(progress = p, bottom = p, drag = startDrag * p).rect
            val fade = ComposerMorphGeometry.glassFade(progress = p, armed = false, continuousGlass = false, dismissesDown = true, reduceMotion = false)
            // Each edge goes directly toward the seed while the surface fades.
            assertTrue("Sheet moved up on frame $frame: ${rect.top} < ${previousRect.top}", rect.top >= previousRect.top - 1e-4f)
            assertTrue(rect.bottom <= previousRect.bottom + 1e-4f)
            assertTrue(rect.width <= previousRect.width + 1e-4f)
            assertTrue(rect.height <= previousRect.height + 1e-4f)
            assertTrue(fade <= previousFade + 1e-6f)
            previousRect = rect
            previousFade = fade
        }
        assertTrue(abs(previousRect.top - seed.top) < 0.5f)
        assertTrue(abs(previousRect.width - seed.width) < 0.5f)
        assertTrue(previousFade < 0.01f)
    }

    @Test fun composerReturnFromBehindSheetPerFrameTraceRisesWithSpringWithoutPopOrJump() {
        val restY = 535.0

        // Per-frame trace of the composer rising from its lowered position behind the sheet
        // (`restY + composerSink`) back into place (`restY`) on the return spring.
        val (omega, zeta) = SpringSolver.parameters(duration = 0.28, bounce = 0.0)
        val dt = 1.0 / 240.0
        val ySpring = spring(restY + ComposerMorphTunables.composerSink)
        var previousY = ySpring.value
        var previousOpacity = 0.0

        for (frame in 1..160) {
            ySpring.step(restY, omega, zeta, dt)
            val currentY = ySpring.value
            val elapsed = frame * dt
            val opacity = min(1.0, elapsed / 0.18)
            // Rises monotonically upward (y decreases) with <= 1.5 pt step and immediate fade-in.
            assertTrue("Composer moved down during return on frame $frame", currentY <= previousY + 1e-6)
            assertTrue("Composer return step > 1.5 pt on frame $frame: ${previousY - currentY}", previousY - currentY <= 1.5)
            assertTrue(opacity >= previousOpacity)
            if (frame == 1) {
                assertTrue("Composer begins fading in on Frame 1 of its rise, never waiting for a delayed pop-in", opacity > 0)
            }
            previousY = currentY
            previousOpacity = opacity
        }
        assertTrue(abs(previousY - restY) < 0.05)
        assertEquals(1.0, previousOpacity, 0.0)
    }

    @Test fun unifiedSurfaceSeedToMenuToSheetPerFrameTraceHasZeroHandoffJumpAndPreservesScrollPosition() {
        val seedRect = rect(24f, 742f, 36f, 36f)
        val menuRect = rect(12f, 534f, 232f, 244f)
        val sheetRect = rect(8f, 500f, 386f, 366f)

        // 1. Seed -> Menu on the single surface (`shown == false`, `menuProgress: 0 -> 1`):
        val (omegaMenu, zetaMenu) = SpringSolver.parameters(duration = 0.34, bounce = 0.12)
        val dt = 1.0 / 1_200.0
        val menuSpring = spring(0.0)
        var prevMenuRect = seedRect
        for (frame in 1..800) {
            menuSpring.step(1.0, omegaMenu, zetaMenu, dt)
            val (rect, _) = ComposerMorphGeometry.menuSource(
                progress = menuSpring.value.toFloat(), seed = seedRect, seedRadius = 18f, menu = menuRect, menuRadius = 26f, reduceMotion = false,
            )
            assertTrue("Seed->menu minX step > 1.5 pt on frame $frame", abs(rect.left - prevMenuRect.left) <= 1.5f)
            assertTrue("Seed->menu minY step > 1.5 pt on frame $frame", abs(rect.top - prevMenuRect.top) <= 1.5f)
            prevMenuRect = rect
        }
        assertTrue(abs(prevMenuRect.left - menuRect.left) < 0.05f)
        assertTrue(abs(prevMenuRect.top - menuRect.top) < 0.05f)

        // 2. Handoff to Photos (`shown` becomes true, `progress == 0`): exact same rect and radius (0.0 pt jump).
        val (atMenuRest, atMenuRadius) = ComposerMorphGeometry.menuSource(
            progress = 1f, seed = seedRect, seedRadius = 18f, menu = menuRect, menuRadius = 26f, reduceMotion = false,
        )
        val sheetGeometry = ComposerMorphGeometry(source = atMenuRest, sourceRadius = atMenuRadius, target = sheetRect, topRadius = 30f, bottomRadius = 46f)
        val sheetStart = sheetGeometry.frame(progress = 0f, bottom = 0f, drag = 0f)
        assertEquals(menuRect, sheetStart.rect)
        assertEquals(26f, sheetStart.top, 0f)

        // 3. Saved scroll position survives close (`clearPicks` + `resetClosingPicks`) and resets on session reset.
        val library = PlusPhotoLibrary()
        library.savedScrollPhotoID = "lab-photo-9"
        library.clearPicks()
        library.resetClosingPicks()
        assertEquals("lab-photo-9", library.savedScrollPhotoID)
        library.resetSavedScrollPosition()
        assertNull(library.savedScrollPhotoID)
        library.close()
    }

    @Test fun backThenTapRowWithinClosingHoldReopensOrActsImmediatelyWithoutWaitingForSettle() = runTest {
        val morph = ComposerMorph(animationScope())
        morph.open()
        assertTrue(morph.isOpen)
        assertTrue(morph.isOpening)
        assertTrue(morph.isShown)

        // Tap Back (`backFromPhotoSheet`): morph enters `Closing` for 551 ms while `menuShown`
        // (`attachmentMenu == .menu`) remains true and `dismissesDown` is false.
        morph.fold(ComposerMorphTunables.close.spec(), reduceMotion = false)
        assertEquals(ComposerMorph.Phase.Closing, morph.phase)
        assertTrue(morph.isShown)
        assertFalse(morph.isOpening)
        assertFalse(morph.foldPending)

        val state = ComposerMorphGeometry.unifiedMenuState(menuShown = true, morph = morph, dismissesDown = false)
        assertTrue(state.closingToMenu)
        assertFalse(state.usesDownwardDismiss)
        assertTrue("Menu rows must be interactive immediately after Back without waiting 551 ms", state.menuInteractive)
        assertEquals(1f, state.menuProgress, 0f)
        assertEquals(1f, state.entranceProgress, 0f)

        // Tapping a row (such as Photos) within the 551 ms `Closing` window passes
        // `!morph.isOpening` and immediately reverses `Closing` into `Open`.
        var prepared = false
        var launched = false
        var opened = false
        morph.armThenOpen(
            ComposerMorphTunables.photoOpen.spec(),
            prepare = { prepared = true },
            launch = { launched = true },
            opened = { opened = true },
        )
        assertTrue(prepared)
        assertTrue(launched)
        assertTrue(opened)
        assertTrue(morph.isOpen)
        assertTrue(morph.isOpening)
    }

    @Test fun backThenDismissWithinClosingHoldResetsSheetAndShrinksMenuWithoutPop() = runTest {
        val seedRect = rect(24f, 742f, 34f, 34f)
        val menuRect = rect(12f, 534f, 232f, 244f)
        val morph = ComposerMorph(animationScope())
        morph.open()

        // 1. Back puts the morph into back-to-menu `Closing` (`morph.isShown`, `dismissesDown == false`, `menuShown`).
        morph.fold(ComposerMorphTunables.close.spec(), reduceMotion = false)
        assertEquals(ComposerMorph.Phase.Closing, morph.phase)
        val duringBack = ComposerMorphGeometry.unifiedMenuState(menuShown = true, morph = morph, dismissesDown = false)
        assertTrue(duringBack.closingToMenu)
        assertEquals(1f, duringBack.menuProgress, 0f)

        // 2. Dismissing the menu during back-to-menu `Closing` (`closeAttachmentMenu`) resets the
        // sheet without animation first, then sets `menuShown = false`.
        morph.reset()
        assertFalse(morph.isShown)
        val afterDismiss = ComposerMorphGeometry.unifiedMenuState(menuShown = false, morph = morph, dismissesDown = false)
        assertFalse(afterDismiss.closingToMenu)
        assertFalse(afterDismiss.menuInteractive)
        assertEquals("menuProgress must transition 1 -> 0 so the menu shrinks into the + seed", 0f, afterDismiss.menuProgress, 0f)
        assertEquals(0f, afterDismiss.entranceProgress, 0f)

        // Frame 0 of the menu shrink (`menuProgress == 1`, `shown == false`) starts at `menuRect`
        // and shrinks smoothly into `seedRect` with no pop.
        val (startRect, _) = ComposerMorphGeometry.menuSource(
            progress = 1f, seed = seedRect, seedRadius = 17f, menu = menuRect, menuRadius = 28f, reduceMotion = false,
        )
        val (endRect, _) = ComposerMorphGeometry.menuSource(
            progress = 0f, seed = seedRect, seedRadius = 17f, menu = menuRect, menuRadius = 28f, reduceMotion = false,
        )
        assertEquals(menuRect, startRect)
        assertEquals(seedRect, endRect)
    }

    @Test fun reopeningPlusDuringDownwardSheetDismissAnimatesMenuProgressFromZero() = runTest {
        val morph = ComposerMorph(animationScope())
        morph.open()

        // 1. Dismissing the sheet (`closePhotoSheet`) sets `menuShown = false` and
        // `dismissesDown = true` while `morph` enters `Closing` (`morph.isShown`).
        morph.fold(ComposerMorphTunables.photoReturn.spec(), reduceMotion = false)
        assertEquals(ComposerMorph.Phase.Closing, morph.phase)
        assertTrue(morph.isShown)
        assertFalse(morph.isOpening)

        val duringDismiss = ComposerMorphGeometry.unifiedMenuState(menuShown = false, morph = morph, dismissesDown = true)
        assertTrue(duringDismiss.usesDownwardDismiss)
        assertFalse(duringDismiss.closingToMenu)
        assertFalse(duringDismiss.menuInteractive)
        assertEquals("Downward sheet dismiss must not hold menuProgress at 1", 0f, duringDismiss.menuProgress, 0f)
        assertEquals("Downward sheet dismiss must not hold entranceProgress at 1", 0f, duringDismiss.entranceProgress, 0f)

        // 2. Reopening `+` (`toggleAttachmentMenu`) during the downward dismiss resets the sheet
        // and sets `menuShown = true`, driving `menuProgress` and `entranceProgress` from 0 -> 1
        // so the open animation and row stagger run in full.
        morph.reset()
        val reopened = ComposerMorphGeometry.unifiedMenuState(menuShown = true, morph = morph, dismissesDown = false)
        assertTrue(duringDismiss.menuProgress == 0f && reopened.menuProgress == 1f)
        assertTrue(duringDismiss.entranceProgress == 0f && reopened.entranceProgress == 1f)
        assertTrue(reopened.menuInteractive)
    }

    @Test fun reduceMotionContinuousGlassStaysOpaqueOnOpenAndBackAndCameraGatesOnSheetIsOpen() = runTest {
        // Under Reduce Motion, continuous glass (opening Photos from the + menu or returning to
        // the + menu with Back) keeps glassFade at 1 across Armed, Open and Closing so the glass
        // never pops to 0; downward dismiss still fades.
        for (progress in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals(
                1f,
                ComposerMorphGeometry.glassFade(
                    progress = progress, menuProgress = 1f, shown = true, armed = progress == 0f,
                    continuousGlass = true, dismissesDown = false, reduceMotion = true,
                ),
                0f,
            )
            assertEquals(
                progress,
                ComposerMorphGeometry.glassFade(
                    progress = progress, menuProgress = 0f, shown = true, armed = false,
                    continuousGlass = true, dismissesDown = true, reduceMotion = true,
                ),
                0f,
            )
        }

        // PlusCameraPresentation only activates while `sheet.isOpen`, never during the 551 ms
        // `Closing` window when `sheet.isShown` is still true.
        val camera = PlusCameraPresentation()
        val sheet = ComposerMorph(animationScope())
        sheet.open()
        camera.expanded = true
        assertTrue(camera.shouldActivateCamera(sheetIsOpen = sheet.isOpen))

        sheet.fold(ComposerMorphTunables.photoReturn.spec(), reduceMotion = false)
        assertTrue(sheet.isShown)
        assertFalse(sheet.isOpen)
        assertFalse(camera.shouldActivateCamera(sheetIsOpen = sheet.isOpen))
    }

    private companion object {
        /** iOS's `resting(at:keepingBottom:)` and `stretched` floor of 120 pt, in px at density 1. */
        const val MIN_HEIGHT = 120f

        fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(Offset(x, y), Size(width, height))

        /** iOS `FlightSpring(value:velocity:)`: [SpringSolver] stepped with explicit (ω, ζ). */
        fun spring(value: Double, velocity: Double = 0.0) = SpringSolver(value = value, velocity = velocity, omega = 1.0)
    }

    /** The morph's springs run on `Animatable`s, which need a frame clock: a 60 Hz one on virtual time. */
    private fun TestScope.animationScope(): CoroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return CoroutineScope(backgroundScope.coroutineContext + clock)
    }

    /** A stand-in thumbnail: the library only stores and hands back images, compared by identity. */
    private class FakeImage(side: Int) : ImageBitmap {
        override val width: Int = side
        override val height: Int = side
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha: Boolean = false
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }
}
