package ai.opencode.feature.drawer.catchup

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.feature.drawer.CatchUpPreviewStores
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.TestStore
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.feature.drawer.openingCatchUpCard
import ai.opencode.feature.drawer.persistingTranscriptCache
import ai.opencode.feature.drawer.publishingTranscript
import ai.opencode.server.OpenCodeClient
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS `CatchUpTests.previewStoresPreserveFirstFrameFlagAcrossSameSessionRefreshesAndResetOnNewSession`
 * and `catchUpPreviewRefreshesWhenPreviewTranscriptIsPrefetchedOrRefreshed`, apart from `CatchUpTests.kt` because the preview's real `Store` needs Android's main looper
 * (Robolectric). Android replaces the preview's state after composition (`commit()`), so the test
 * commits where iOS's store reads the new state at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatchUpPreviewStoresTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private fun session(id: String, updated: Long) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = 2_000, viewedMilliseconds = 1_000,
    )

    @Test
    fun previewStoresPreserveFirstFrameFlagAcrossSameSessionRefreshesAndResetOnNewSession() = DependencyValues.withValues(DependencyValues.testing()) {
        val newer = session("newer", 20)
        val older = session("older", 10)
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(older, newer), activeSessionIDs = emptySet()))
        val previews = CatchUpPreviewStores()
        try {
            val store1 = previews.store(newer, state)
            assertFalse(store1.state.hasRenderedFirstContentFrame)
            store1.send(SessionFeature.Action.FirstContentFrameRendered)
            assertTrue(store1.state.hasRenderedFirstContentFrame)

            // Same session refreshed with updated transcript cache preserves hasRenderedFirstContentFrame.
            state = state.copy(transcriptCache = state.transcriptCache + ("newer" to listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Updated preview"))))
            val store2 = previews.store(newer, state)
            previews.commit()
            assertSame(store1, store2)
            assertTrue("Same-session cache refresh preserves first-frame state", store2.state.hasRenderedFirstContentFrame)

            // Advancing to a different preview session resets hasRenderedFirstContentFrame for its new transcript.
            val store3 = previews.store(older, state)
            previews.commit()
            assertSame(store1, store3)
            assertFalse(store3.state.hasRenderedFirstContentFrame)
        } finally {
            previews.close()
        }
    }

    @Test fun catchUpPreviewRefreshesWhenPreviewTranscriptIsPrefetchedOrRefreshed() = runTest {
        val now = Instant.ofEpochSecond(100)
        val fixture = CatchUpTests()
        var initial = fixture.home()
        initial = initial.withCatchUp(CatchUpFeature.Stack(queue = listOf("waiting", "newer", "older"), returnSessionID = "read"))
        initial = initial.openingCatchUpCard().first

        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(now)
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> listOf(OpenCodeTranscriptItem.UserText(id = "u_refreshed", text = "Refreshed preview")) },
            )
        }

        val previews = CatchUpPreviewStores()
        val newer = store.state.sessions.first { it.id == "newer" }
        val preview = previews.store(newer, store.state)
        assertTrue(preview.state.items.isEmpty())
        assertEquals(0, store.state.previewRevision)

        // 1. Prefetching a session behind the preview card ("older") does not bump previewRevision.
        val olderItems = listOf(OpenCodeTranscriptItem.UserText(id = "u_older", text = "Older card"))
        store.send(HomeFeature.Action.TranscriptPrefetched(sessionID = "older", items = olderItems)) {
            store.withDependencies { it.publishingTranscript("older", olderItems).persistingTranscriptCache(including = "older") }
        }
        assertEquals(0, store.state.previewRevision)

        // 2. Prefetching the Catch Up preview session ("newer") bumps previewRevision.
        val prefetchedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u_prefetched", text = "Prefetched preview"))
        store.send(HomeFeature.Action.TranscriptPrefetched(sessionID = "newer", items = prefetchedItems)) {
            store.withDependencies {
                it.publishingTranscript("newer", prefetchedItems).persistingTranscriptCache(including = "newer")
            }.copy(previewRevision = 1)
        }
        assertEquals(1, store.state.previewRevision)
        // The card re-reads its store when the revision changes (iOS observation tracking).
        assertSame(preview, previews.store(newer, store.state))
        previews.commit()
        assertEquals(prefetchedItems, preview.state.items)

        // 3. Refreshing the cached transcript for "newer" bumps previewRevision again.
        val refreshedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u_refreshed", text = "Refreshed preview"))
        store.send(HomeFeature.Action.DrawerRowPressed("newer")) { it.copy(refreshingTranscripts = setOf("newer")) }
        store.receive(HomeFeature.Action.CachedTranscriptRefreshed(sessionID = "newer", items = refreshedItems)) {
            store.withDependencies {
                it.copy(refreshingTranscripts = emptySet(), transcriptReadAt = it.transcriptReadAt + ("newer" to now))
                    .publishingTranscript("newer", refreshedItems)
                    .persistingTranscriptCache(including = "newer")
            }.copy(previewRevision = 2)
        }
        assertEquals(2, store.state.previewRevision)
        assertSame(preview, previews.store(newer, store.state))
        previews.commit()
        assertEquals(refreshedItems, preview.state.items)
        previews.close()
        store.finish()
    }
}
