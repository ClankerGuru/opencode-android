package ai.opencode.server

import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `TranscriptCachePersistenceTests.swift`, the `:server` half. The `HomeFeature` and
 * `AppFeature` tests are `:feature:drawer`'s and `:app`'s; the two protected-data tests can't
 * exist on Android (no locked-device file protection, see docs/parity/server.md).
 */
class TranscriptCachePersistenceTests {
    @Test fun splicedEnvelopeMatchesASingleFullEncode() {
        val entries = (0 until 3).map { index ->
            CachedTranscriptSnapshots.Entry(
                serverID = "server", sessionID = "ses_$index", updatedAt = Instant.ofEpochSecond(index.toLong()),
                items = listOf(
                    OpenCodeTranscriptItem.UserText(id = "u_$index", text = "Hello \"$index\""),
                    OpenCodeTranscriptItem.AssistantMarkdown(id = "a_$index", text = "**Hi**\n\n```swift\nlet n = $index\n```"),
                    OpenCodeTranscriptItem.Compaction(id = "c_$index", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed, summary = "## Kept")),
                ),
            )
        }
        val json = CachedTranscriptSnapshots.persistenceJson
        for (snapshot in listOf(CachedTranscriptSnapshots(), CachedTranscriptSnapshots(entries = entries))) {
            val data = requireNotNull(snapshot.encodedForPersistence())
            val full = json.encodeToString(CachedTranscriptSnapshots.serializer(), snapshot).toByteArray()
            assertEquals(full.size, data.size)
            assertEquals(
                json.decodeFromString(CachedTranscriptSnapshots.serializer(), full.decodeToString()),
                json.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString()),
            )
        }
    }
}
