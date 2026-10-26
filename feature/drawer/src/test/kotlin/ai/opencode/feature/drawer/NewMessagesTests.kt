package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `NewMessagesTests`' Home tests: the mark on returning to a session, the drawer's
 * prefetch, and holding the stream on leaving. The divider's own placement is
 * `:feature:transcript`'s, the screen's resume `:feature:session`'s.
 */
class NewMessagesTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")

    private fun <R> on(body: () -> R): R = DependencyValues.withValues(
        DependencyValues.testing().update { this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.newMessagesDivider)) },
        body,
    )

    private fun session(id: String) = OpenCodeSession(id = id, title = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10))
    private fun user(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id = id, text = id)
    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = "Reply $id")

    @Test
    fun switchingBackMarksWhatArrivedSince() {
        val first = session("first")
        val second = session("second")
        val back = on {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(first, second), activeSessionIDs = emptySet()))
            home = home.openingDetail(first, cachedItems = listOf(user("m1"), reply("m2")))
            home = home.openingDetail(second, cachedItems = listOf(user("s1")))
            home.makeDetail(first, cachedItems = listOf(user("m1"), reply("m2"), reply("m3"))).second
        }
        assertEquals("m3", back.newMessages?.itemID)
    }

    @Test
    fun theDrawerReadsLikelySessionsBeforeATap() = runTest {
        val open = session("open")
        val other = session("other")
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, other), activeSessionIDs = emptySet()))
        state = state.copy(transcriptCache = mapOf(other.id to listOf(user("o1"))))
        state = state.openingDetail(open, cachedItems = listOf(user("m1")))
        val reads = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[DateClient] = DateClient { Instant.ofEpochSecond(100) }
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(transcript = { _, id, _ ->
                reads += id
                listOf(OpenCodeTranscriptItem.UserText(id = "o1", text = "o1"), OpenCodeTranscriptItem.UserText(id = "o2", text = "o2"))
            })
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SessionDrawerArrived(isOpen = true))
        store.receive({ it is HomeFeature.Action.CachedTranscriptRefreshed }, "cachedTranscriptRefreshed")
        assertEquals(listOf("other"), reads.toList())
        assertEquals(listOf("o1", "o2"), store.state.transcriptCache[other.id]?.map { it.id })
        // Just read: a press doesn't read it again.
        store.send(HomeFeature.Action.DrawerRowPressed(other.id))
        assertEquals(listOf("other"), reads.toList())
        // The open session is the screen's own to read.
        store.send(HomeFeature.Action.DrawerRowPressed(open.id))
        assertEquals(listOf("other"), reads.toList())
    }

    @Test
    fun leavingTheAppHoldsTheStreamUntilTheReturn() = runTest {
        val held = Collections.synchronizedList(mutableListOf<Duration>())
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
            .let { it.copy(connection = it.connection.copy(isEventStreamLive = true)) }
        val store = TestStore(state, HomeFeature(), this) {
            this[BackgroundTimeClient] = BackgroundTimeClient { _, seconds -> held += seconds }
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SceneEnteredBackground)
        store.finish()
        assertEquals(listOf(HomeFeature.liveUpdatesAfterLeaving), held.toList())
    }
}
