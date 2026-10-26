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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS `CatchUpTests.previewStoresPreserveFirstFrameFlagAcrossSameSessionRefreshesAndResetOnNewSession`,
 * apart from `CatchUpTests.kt` because the preview's real `Store` needs Android's main looper
 * (Robolectric). Android replaces the preview's state after composition (`commit()`), so the test
 * commits where iOS's store reads the new state at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatchUpPreviewStoresTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private fun session(id: String, updated: Long) = OpenCodeSession(
        id = id, title = id, projectID = "p", directory = "/project",
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
}
