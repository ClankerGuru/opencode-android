package ai.opencode.feature.transcript.user

import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `:feature:transcript` assertion of iOS `OpenCodeTests/FileAttachmentTests.swift`
 * (`PromptPresentationTests.fileOnlyMessagesHaveNoBubble`'s `TranscriptEntry` check). Decoding
 * the message into its items (that test's first half, and all of
 * `userMessagesRenderChipsInsteadOfReferenceLines`) is `:server`'s; the rest of the file is
 * `:core`'s, `:feature:composer`'s and `:feature:session`'s.
 */
class PromptPresentationTests {
    @Test fun fileOnlyMessagesHaveNoBubble() {
        // What `:server` decodes a message of one attached clip and no typed text into.
        val items = listOf<OpenCodeTranscriptItem>(
            OpenCodeTranscriptItem.UserFiles(
                "msg_2-files",
                listOf(OpenCodeTranscriptFile(id = "msg_2-file-0", name = "clip.mov", mime = "video/quicktime", path = "/tmp/a/clip.mov")),
            ),
        )
        assertEquals("msg_2", TranscriptEntry.build(items).first().userMessageID)
    }
}
