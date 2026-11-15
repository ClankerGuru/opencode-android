package ai.opencode.feature.media

import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.MediaReferenceFetch
import ai.opencode.server.model.OpenCodeImageAttachment
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Robolectric's native graphics decode the sample PNG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaReferenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val hashA = "a1".repeat(32)
    private val hashB = "b2".repeat(32)

    private fun samplePNGData(color: Int = Color.BLUE): ByteArray {
        val image = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("media-ref-tests-").toFile()

    @Test
    fun loaderKeepsCachedDataInMemoryOnlyAndScopes404PerServerInMemory() = runTest {
        val tempDir = tempDir()
        try {
            val server1 = OpenCodeServer(url = "https://server-one.invalid", username = "user", password = "secret")
            val server2 = OpenCodeServer(url = "https://server-two.invalid", username = "user", password = "secret")
            val blobData = samplePNGData()
            val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val fetch = MediaReferenceLoader.Fetch { server, hash ->
                calls += server.url to hash
                if (hash == hashB && server.url == server1.url) {
                    throw OpenCodeClientError("The server returned HTTP 404.", statusCode = 404)
                }
                MediaReferenceFetch.Response(200, blobData)
            }

            // A private center: another test's memory warning would purge the memory tier mid-check.
            val center = MemoryWarningCenter()
            val client = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)

            // 1. Cold miss -> 200 fetch, stores in memory and disk
            val first = client.load(server1, hashA)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), first)
            assertEquals(1, calls.size)
            assertArrayEquals(blobData, client.cachedData(hashA))

            // 2. Warm memory hit -> no network request, shared across servers by hash
            val second = client.load(server2, hashA)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), second)
            assertEquals(1, calls.size)

            // 3. Fresh loader backed by the same disk directory: `cachedData` is memory-only
            //    (null before a load), and `load` reads disk without the network.
            val diskClient = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)
            assertNull(diskClient.cachedData(hashA))
            val fromDisk = diskClient.load(server1, hashA)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), fromDisk)
            assertArrayEquals(blobData, diskClient.cachedData(hashA))
            assertEquals(1, calls.size)

            // 4. HTTP 404 on server1 -> cached in memory for server1 only, not server2 and not on disk
            val missingOnServer1 = client.load(server1, hashB)
            assertEquals(MediaReferenceLoadResult.Unavailable, missingOnServer1)
            assertTrue(client.isUnavailable(server1, hashB))
            assertFalse(client.isUnavailable(server2, hashB))
            assertEquals(2, calls.size)

            // Repeat on server1 hits per-server in-memory 404 cache
            val missingAgainOnServer1 = client.load(server1, hashB)
            assertEquals(MediaReferenceLoadResult.Unavailable, missingAgainOnServer1)
            assertEquals(2, calls.size)

            // Fresh loader (simulating relaunch) does not retain 404 across launches
            val relaunchedClient = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)
            assertFalse(relaunchedClient.isUnavailable(server1, hashB))

            // Loading hashB from server2 succeeds and stores positive content-addressed bytes
            val loadedOnServer2 = client.load(server2, hashB)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), loadedOnServer2)
            assertEquals(3, calls.size)
            assertFalse(client.isUnavailable(server1, hashB))
            assertArrayEquals(blobData, client.cachedData(hashB))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun memoryWarningPurgesTheMemoryTierAndKeepsDiskAnd404s() = runTest {
        val tempDir = tempDir()
        try {
            val server = OpenCodeServer(url = "https://server-one.invalid", username = "user", password = "secret")
            val blobData = samplePNGData()
            var calls = 0
            val fetch = MediaReferenceLoader.Fetch { _, hash ->
                synchronized(this@MediaReferenceTests) { calls += 1 }
                if (hash == hashB) throw OpenCodeClientError("The server returned HTTP 404.", statusCode = 404)
                MediaReferenceFetch.Response(200, blobData)
            }
            // A private center, so other tests' warnings never purge this loader mid-test.
            val center = MemoryWarningCenter()
            val client = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)

            assertEquals(MediaReferenceLoadResult.Loaded(blobData), client.load(server, hashA))
            assertEquals(MediaReferenceLoadResult.Unavailable, client.load(server, hashB))
            assertArrayEquals(blobData, client.cachedData(hashA))
            assertEquals(2, calls)

            center.post()
            assertNull(client.cachedData(hashA))
            assertTrue(client.isUnavailable(server, hashB))

            // The disk tier answers without the network and refills memory.
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), client.load(server, hashA))
            assertArrayEquals(blobData, client.cachedData(hashA))
            assertEquals(MediaReferenceLoadResult.Unavailable, client.load(server, hashB))
            assertEquals(2, calls)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun retryStateClearsTransientFailureAndImageViewerRetriesFailedPage() = runTest {
        var retryState = MediaAttachmentRetryState(failure = MediaAttachmentFailure.Failed, attempt = 0)
        assertTrue(retryState.isTransientFailure)
        retryState = retryState.retry()
        assertNull(retryState.failure)
        assertFalse(retryState.isTransientFailure)
        assertEquals(1, retryState.attempt)

        val pngData = samplePNGData()
        val server = OpenCodeServer(url = "https://example.invalid", username = "user", password = "secret")
        val attachments = listOf(
            OpenCodeImageAttachment(id = "img-1", data = ByteArray(0), mediaHash = hashA, name = "diagram.png", mime = "image/png"),
            OpenCodeImageAttachment(id = "img-2", data = ByteArray(0), mediaHash = hashB, name = "missing.png", mime = "image/png"),
        )
        val decodedCache = DecodedImageCache()
        val shouldFailFirst = AtomicBoolean(true)
        val mediaReferences = MediaReferenceClient(
            cachedData = { null },
            unavailable = { _, _ -> false },
            loader = { _, hash ->
                when {
                    hash != hashA -> MediaReferenceLoadResult.Unavailable
                    shouldFailFirst.getAndSet(false) -> MediaReferenceLoadResult.Failed("Couldn’t load image")
                    else -> MediaReferenceLoadResult.Loaded(pngData)
                }
            },
        )
        val preview = withDependencies({
            this[DecodedImageCache] = decodedCache
            this[MediaReferenceClient] = mediaReferences
        }) {
            TranscriptImagePreview.fromAttachments(attachments, selectedID = "img-1", label = "Attached image", server = server)
        }
        assertNotNull(preview)

        val store = TestStore(FullscreenViewerFeature.State(preview!!), FullscreenViewerFeature(), this) {
            this[DecodedImageCache] = decodedCache
            this[MediaReferenceClient] = mediaReferences
        }

        store.send(FullscreenViewerFeature.Action.Task)
        store.receive({ it is FullscreenViewerFeature.Action.MediaLoaded }) {
            it.withPage(0) { page -> page.copy(loadFailed = true) }
        }

        val expectedImage = store.withDependencies { CachedImage.decoded("img-1", pngData, hashA) }
        assertNotNull(expectedImage)

        store.send(FullscreenViewerFeature.Action.RetryPageTapped("img-1")) {
            it.withPage(0) { page -> page.copy(loadFailed = false) }
        }
        store.receive({ it is FullscreenViewerFeature.Action.MediaLoaded }) {
            it.withPage(0) { page -> page.copy(image = expectedImage, loadFailed = false) }
        }
        assertTrue(store.state.preview.pages[0].image === expectedImage)

        store.send(FullscreenViewerFeature.Action.PageSelected("img-2")) {
            it.copy(selectedID = "img-2")
        }
        store.receive({ it is FullscreenViewerFeature.Action.MediaLoaded }) {
            it.withPage(1) { page -> page.copy(isUnavailable = true) }
        }

        // Saving an unavailable page is a no-op.
        store.send(FullscreenViewerFeature.Action.SaveTapped)
        store.finish()
    }

    @Test
    fun coldPagesLoadOnlyOnSelectionAndRemovalStartsTheReplacement() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val pixels = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val pages = (1..4).map { n ->
            val id = "link$n"
            TranscriptImagePreview.Page(id, image = if (n == 4) pixels else null, label = id,
                imageSource = SessionVisualImageSource.Link(id))
        }
        val state = FullscreenViewerFeature.State(TranscriptImagePreview("link1", pages), allowsRemove = true)
        val store = TestStore(state, FullscreenViewerFeature(), this) {
            this[ImageLinkPreviewClient] = ImageLinkPreviewClient(load = { id, _ -> calls.add(id); pixels })
        }
        store.send(FullscreenViewerFeature.Action.Task) { it.withPage(3) { p -> p.copy(image = null) } }
        store.receive(FullscreenViewerFeature.Action.SourceLoaded("link1", pixels)) { it.withPage(0) { p -> p.copy(image = pixels) } }
        store.send(FullscreenViewerFeature.Action.RemoveTapped) {
            it.copy(preview = it.preview.copy(pages = it.preview.pages.drop(1)), selectedID = "link2")
        }
        store.receive(FullscreenViewerFeature.Action.Delegate(FullscreenViewerFeature.Action.DelegateAction.Removed("link1")))
        store.receive(FullscreenViewerFeature.Action.SourceLoaded("link2", pixels)) { it.withPage(0) { p -> p.copy(image = pixels) } }
        store.send(FullscreenViewerFeature.Action.SourceLoaded("link3", pixels)) // stale completion
        store.finish()
        assertEquals(listOf("link1", "link2"), calls)
    }

    @Test
    fun selectionAndCloseCancelPendingLoadsAndIgnoreLateResults() = runTest {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val pages = listOf("a", "b").map { TranscriptImagePreview.Page(it, null, it, imageSource = SessionVisualImageSource.Link(it)) }
        val store = TestStore(FullscreenViewerFeature.State(TranscriptImagePreview("a", pages)), FullscreenViewerFeature(), this) {
            this[ImageLinkPreviewClient] = ImageLinkPreviewClient(load = { id, _ ->
                started += id
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled += id }
            })
        }
        store.send(FullscreenViewerFeature.Action.Task)
        testScheduler.runCurrent()
        store.send(FullscreenViewerFeature.Action.PageSelected("b")) { it.copy(selectedID = "b") }
        testScheduler.runCurrent()
        store.send(FullscreenViewerFeature.Action.SourceLoaded("a", null))
        store.send(FullscreenViewerFeature.Action.CloseTapped) { it.copy(isDismissed = true) }
        store.send(FullscreenViewerFeature.Action.SourceLoaded("b", null))
        store.finish()
        assertEquals(listOf("a", "b"), started)
        assertEquals(started, cancelled)
    }

    @Test
    fun failedOriginalKeepsThumbnailAndDoesNotAutomaticallyRetry() = runTest {
        val thumbnail = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val page = TranscriptImagePreview.Page("photo", thumbnail, "Photo", original = byteArrayOf(0))
        val state = FullscreenViewerFeature.State(TranscriptImagePreview(page.id, listOf(page)))
        val store = TestStore(state, FullscreenViewerFeature(), this)
        store.send(FullscreenViewerFeature.Action.Task)
        store.receive(FullscreenViewerFeature.Action.SourceLoaded(page.id, null)) {
            it.withPage(0) { p -> p.copy(loadFailed = true, originalFailed = true) }
        }
        assertTrue(store.state.selectedPage?.image === thumbnail)
        store.send(FullscreenViewerFeature.Action.Task)
        store.finish()
    }

    private fun FullscreenViewerFeature.State.withPage(
        index: Int,
        update: (TranscriptImagePreview.Page) -> TranscriptImagePreview.Page,
    ): FullscreenViewerFeature.State =
        copy(preview = preview.copy(pages = preview.pages.toMutableList().also { it[index] = update(it[index]) }))
}
