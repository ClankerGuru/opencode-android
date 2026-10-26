package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ImageLinkPreviewTests.swift`: the link policy and the read queue. iOS's replies
 * are Markdown (`:feature:transcript`); here they're the [LinkTextBlock]s the transcript hands
 * media. The loader tests that decode pixels aren't ported (see docs/parity/media.md).
 */
class ImageLinkPreviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun links(blocks: List<LinkTextBlock>, growing: Boolean = false): List<String> {
        val plan = ImageLinkPolicy.plan(blocks, growing = growing)
        return blocks.flatMap { plan[it.id] ?: emptyList() }
    }

    private fun text(value: String) = listOf(LinkBlocks.paragraph(0, LinkBlocks.text(value)))

    @Test
    fun detectsBareMarkdownQueryAndUppercaseImages() {
        for (suffix in listOf("png", "jpg", "jpeg", "gif", "webp", "PNG", "JpEg")) {
            val url = "https://example.com/photo.$suffix?width=800#detail"
            assertEquals(listOf(url), links(text(url)))
            assertEquals(listOf(url), links(listOf(LinkBlocks.paragraph(0, LinkBlocks.link("screenshot", url)))))
        }
        assertTrue(links(text("https://example.com/movie.mp4 https://example.com/page.html https://example.com/readme.pdf")).isEmpty())
        assertTrue(links(listOf(LinkBlocks.paragraph(0, LinkBlocks.code("https://example.com/photo.png")))).isEmpty())
        // `![image](…)` is an image block, not prose.
        assertTrue(links(listOf(LinkBlocks.other(0))).isEmpty())
    }

    @Test
    fun optInAllowsTailnetHTTPSButKeepsMetadataPolicyUnchanged() {
        val url = "https://studio.tail1234.ts.net:8444/shot.png?width=800"
        assertTrue(ImageLinkPolicy.allows(url))
        assertFalse(LinkPreviewPolicy.isPreviewable(url))
        for (source in listOf(
            "http://studio.tail1234.ts.net/shot.png", "https://127.0.0.1/a.png", "https://nas.local/a.png",
            "https://user:pass@example.com/a.png", "https://example.com/a.png?token=secret",
            "https://studio.tail1234.ts.net/a.png?signature=secret",
        )) {
            assertFalse(source, ImageLinkPolicy.allows(source))
        }
    }

    /** The growing-block half of iOS's test; its streaming half drives `MarkdownStream` (`:feature:transcript`). */
    @Test
    fun completedLinkAndBlockIdentitySurviveEveryLaterDelta() {
        assertTrue(links(text("https://example.com/shot.png"), growing = true).isEmpty())
        assertTrue(links(text("https://example.com/shot.png?width=1"), growing = true).isEmpty())
        assertEquals(1, links(text("https://example.com/shot.png done"), growing = true).size)
    }

    @Test
    fun boundsAndDeduplicatesLinksAcrossTheMessage() {
        val runs = (0 until 8).flatMap { listOf(LinkBlocks.link("image", "https://example.com/$it.png"), LinkBlocks.text(" ")) }
        assertEquals(3, links(listOf(LinkBlocks.paragraph(0, *runs.toTypedArray()))).size)
        assertEquals(
            1,
            links(listOf(LinkBlocks.paragraph(0, LinkBlocks.link("a", "https://example.com/a.png"), LinkBlocks.text(" "), LinkBlocks.link("b", "https://example.com/a.png")))).size,
        )
        assertEquals(3, links((0 until 8).map { LinkBlocks.paragraph(it, LinkBlocks.link("image", "https://example.com/$it.png")) }).size)
        val headed = listOf(
            LinkBlocks.paragraph(0, LinkBlocks.link("a", "https://example.com/a.png")),
            LinkBlocks.paragraph(1, LinkBlocks.link("same", "https://example.com/a.png")),
            LinkBlocks.paragraph(2, LinkBlocks.text("https://example.com/b.png")),
        )
        assertEquals(listOf("a.png", "b.png"), links(headed).map { it.substringAfterLast('/') })
        assertTrue(links(text("example.com/a.png")).isEmpty())
        assertEquals(listOf("b.png"), links(text("example.com/a.png and https://example.com/b.png")).map { it.substringAfterLast('/') })
    }

    /** iOS checks `AgentImageDownload`'s data-task delegate; Android checks the response before reading its body. */
    @Test
    fun rejectsMissingOrNonImageMIMEBeforeReceivingTheBody() {
        val request = Request.Builder().url("https://example.com/shot.png").build()
        for (mime in listOf(null, "text/html", "application/octet-stream", "image/png", "IMAGE/JPEG")) {
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("")
            mime?.let { builder.header("Content-Type", it) }
            val response = builder.body(ByteArray(0).toResponseBody(null)).build()
            assertEquals("$mime", mime?.lowercase()?.startsWith("image/") == true, AgentImageDownload.isImageResponse(response))
        }
    }

    @Test
    fun cancellingAQueuedReadReturnsBeforeTheActiveReadFinishes() = runTest {
        val queue = CoalescedReadQueue<Int, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            queue.load("first", report = {}) {
                started.complete(Unit)
                release.await()
                1
            }
        }
        started.await()
        val second = async {
            queue.load("second", report = {}) {
                fail("Cancelled queued read reached the transport")
                2
            }
        }
        // Let it queue behind the active read first.
        yield()
        second.cancel()
        try {
            second.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }
        release.complete(Unit)
        assertEquals(1, first.await())
    }
}
