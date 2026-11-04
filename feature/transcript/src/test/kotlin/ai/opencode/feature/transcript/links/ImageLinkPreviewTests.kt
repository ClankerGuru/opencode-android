package ai.opencode.feature.transcript.links

import ai.opencode.feature.media.ImageLinkPolicy
import ai.opencode.feature.media.AgentImageRowGrowth
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.markdown.MarkdownStream
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `:feature:transcript` tests of iOS `OpenCodeTests/ImageLinkPreviewTests.swift`: the
 * `MarkdownStream` half of `completedLinkAndBlockIdentitySurviveEveryLaterDelta`, the row growth
 * hold ([holdsImages], `AgentImageRowGrowth`) and the layout snapshot. The policy and loader
 * tests are `:feature:media`'s. Robolectric: bare-URL detection reads the framework's
 * `Patterns.WEB_URL`.
 *
 * `imageLinkBlocksParticipateInTheRowGrowthHold` drops its source-text wiring guard (it reads
 * `ImageLinkPreviews.swift` for strings); the reported growth is [ImageLinkPreviews]'
 * `rowGrowth?.add(block, height - laidOut)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageLinkPreviewTests {
    @Test
    fun completedLinkAndBlockIdentitySurviveEveryLaterDelta() {
        val stream = MarkdownStream(inlineCache = null)
        val source = "Here is [screenshot](https://example.com/shot.png).\n\nMore text follows."
        var first: Pair<Int, String>? = null
        for (end in source.indices) {
            val blocks = stream.update(source.substring(0, end + 1), streaming = true)
            val images = blocks.flatMap { block ->
                ImageLinkPolicy.links(block.linkTextBlock(), growing = block.id == blocks.lastOrNull()?.id).map { block.id to it }
            }
            val image = images.firstOrNull()
            if (image != null) {
                val seen = first
                if (seen != null) assertEquals(seen, image) else first = image
            } else {
                assertNull("A completed preview never disappears on a later delta", first)
            }
        }
        assertEquals("https://example.com/shot.png", first?.second)
    }

    @Test
    fun imageLinkBlocksParticipateInTheRowGrowthHold() {
        val blocks = OpenCodeMarkdownBlock.blocks("https://example.com/a.png\n\nFollowing paragraph", inlineCache = null)
        val plan = ImageLinkPolicy.plan(blocks.linkTextBlocks(), growing = false)
        assertTrue(holdsImages(blocks[0], imageLinks = plan[blocks[0].id] ?: emptyList()))
        assertFalse(holdsImages(blocks[1]))
        val growth = AgentImageRowGrowth(now = { 1000.0 })
        growth.add(block = blocks[0].id, delta = 232f)
        assertEquals(232f, growth.cut(listOf(blocks[0].id)))
        assertEquals(232f, growth.lift(before = blocks[1].id))
        growth.settle()
        assertTrue(growth.pending.isEmpty())
    }

    @Test
    fun imageLinkSettingsArePartOfTheEquatableLayoutSnapshot() {
        val enabled = TranscriptTunables.layoutSnapshot()
        val taller = enabled.copy(linkMaxHeight = enabled.linkMaxHeight + 10)
        assertNotEquals(enabled, taller)
    }
}
