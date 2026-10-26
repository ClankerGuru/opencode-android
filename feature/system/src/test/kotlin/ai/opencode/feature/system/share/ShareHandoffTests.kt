package ai.opencode.feature.system.share

import ai.opencode.core.testing.TestIsolationRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

// Ports of iOS `ShareHandoffTests.swift`. `ShareInboxFeatureTests` drives `HomeFeature`
// (`:feature:drawer`). `SharedImageEncoderTests` needs Android's `ImageDecoder`/`Bitmap`, which
// JVM tests don't have (no Robolectric).

class ShareHandoffStoreTests {
    @get:Rule val isolation = TestIsolationRule()

    private val root: File = Files.createTempDirectory("share-handoff-").toFile().also { it.delete() }
    private val store: ShareHandoffStore get() = ShareHandoffStore(root)
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    private fun handoff(
        id: UUID = UUID.randomUUID(),
        createdAt: Instant,
        target: ShareHandoff.Target = ShareHandoff.Target.Session(id = "ses_1", projectID = "proj_1"),
        images: List<ShareHandoff.Image> = emptyList(),
    ) = ShareHandoff(id = id, createdAt = createdAt, serverID = "server", target = target, text = "Look at this", images = images)

    private fun entries(): List<String> = root.list().orEmpty().sorted()

    @Test
    fun savedSharesRoundTripOldestFirstAndAreConsumedOnce() {
        val imageID = UUID.randomUUID()
        val newer = handoff(
            createdAt = now.minusSeconds(10), target = ShareHandoff.Target.NewSession(projectID = "proj_2"),
            images = listOf(ShareHandoff.Image(id = imageID, fileName = "image-0.jpg", mime = "image/jpeg")),
        )
        val older = handoff(createdAt = now.minusSeconds(60))
        store.save(newer, images = listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
        store.save(older, images = emptyList())

        val received = store.consumeAll(now = now)
        assertEquals(listOf(older, newer), received.map { it.handoff })
        assertEquals(listOf(ReceivedShare.Image(id = imageID, data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01), mime = "image/jpeg")), received[1].images)
        assertTrue(store.consumeAll(now = now).isEmpty())
        assertTrue(entries().isEmpty())
    }

    @Test
    fun expiredUnreadableAndAbandonedEntriesAreDiscarded() {
        store.save(handoff(createdAt = now.minus(ShareHandoffStore.MAXIMUM_AGE).minusSeconds(1)), images = emptyList())
        val corrupt = File(root, UUID.randomUUID().toString().uppercase()).apply { mkdirs() }
        File(corrupt, "share.json").writeText("not json")
        val abandoned = File(root, ".staging-${UUID.randomUUID().toString().uppercase()}").apply { mkdirs() }
        abandoned.setLastModified(now.minusSeconds(2 * 60 * 60).toEpochMilli())
        val inProgress = File(root, ".staging-${UUID.randomUUID().toString().uppercase()}").apply { mkdirs() }
        inProgress.setLastModified(now.toEpochMilli())

        assertTrue(store.consumeAll(now = now).isEmpty())
        // Only the share sheet's still-fresh write survives.
        assertEquals(listOf(inProgress.name), entries())
    }

    @Test
    fun imageNamesCannotEscapeTheirShare() {
        root.mkdirs()
        File(root, "outside.jpg").writeBytes(byteArrayOf(1))
        var share = handoff(createdAt = now, images = listOf(ShareHandoff.Image(id = UUID.randomUUID(), fileName = "inside.jpg", mime = "image/jpeg")))
        store.save(share, images = listOf(byteArrayOf(2)))
        // Rewrite the manifest as a hostile writer would.
        share = share.copy(images = share.images + ShareHandoff.Image(id = UUID.randomUUID(), fileName = "../outside.jpg", mime = "image/jpeg"))
        File(File(root, share.id.toString().uppercase()), "share.json").writeText(ShareJson.encodeToString(ShareHandoff.serializer(), share))

        val received = store.consumeAll(now = now).first()
        assertEquals(listOf(listOf<Byte>(2)), received.images.map { it.data.toList() })
    }
}

/**
 * Port of the parts of iOS `SharedItemLoaderAndActivationTests` Android has. iOS walks an
 * `NSItemProvider`'s representations (file, file URL, data, item) and checks the extension's
 * `NSExtensionActivationRule`; Android reads a share's one `content://` stream, named from
 * `OpenableColumns.DISPLAY_NAME` or the URI's last segment, and filters shares with the
 * manifest's intent filters. What carries over is the name each JSON share is staged under
 * ([SharedItemLoader.resolvedFileName]); the representation-order, sparse/FIFO-file,
 * cancellation and precedence probes are provider mechanics. The non-JSON `clip.mov` case reads
 * Android's `MimeTypeMap`, which JVM tests don't have.
 */
class SharedItemLoaderAndActivationTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun stagesJSONAcrossFileURLTransferableAndInMemoryRepresentationsWithoutRewritingBytes() {
        val json = "application/json"
        // 1a/6. A file's own name (no suggested name): the compound name keeps `.json`.
        assertEquals("opencode-diagnostics.txt.json", SharedItemLoader.resolvedFileName(suggested = null, fallbackName = "opencode-diagnostics.txt.json", type = json))
        // 2. Only the provider's temporary copy is named.
        assertEquals("JSON.json", SharedItemLoader.resolvedFileName(suggested = null, fallbackName = "JSON.json", type = json))
        // 3. A suggested `.txt` name for a JSON file gains `.json`.
        assertEquals("opencode-diagnostics.txt.json", SharedItemLoader.resolvedFileName(suggested = "opencode-diagnostics.txt", fallbackName = "JSON.json", type = json))
        // 4. A named in-memory JSON share keeps its name.
        assertEquals("synthetic-events.json", SharedItemLoader.resolvedFileName(suggested = "synthetic-events.json", fallbackName = null, type = json))
        // 5. Unnamed JSON data.
        assertEquals("file.json", SharedItemLoader.resolvedFileName(suggested = null, fallbackName = null, type = json))
    }
}
