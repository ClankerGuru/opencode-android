package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.MediaReferenceFetch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections

/**
 * The media half of iOS `MediaReferenceTests` (the loader's tiers, the retry state). The wire
 * and decoding tests are `:server`'s `MediaReferenceTests`; the mosaic's are the transcript's.
 */
class MediaReferenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val hashA = "a1".repeat(32)
    private val hashB = "b2".repeat(32)

    private fun samplePNGData(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00,
    )

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
}
