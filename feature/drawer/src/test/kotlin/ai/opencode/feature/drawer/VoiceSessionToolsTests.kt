package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoard
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `VoiceSessionToolsTests.swift`'s Home test (Home's board). The delegator's tools
 * are `:feature:voice`'s (`VoiceSessionToolsTests.kt` there).
 */
class VoiceSessionToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val math = OpenCodeSession(id = "ses_math", serverTitle = "Math bug", projectID = "math", directory = "/Users/kit/math", updatedAt = Instant.ofEpochSecond(100))

    @Test
    fun homePublishesTheDrawerStateOnlyDuringACall() = homeTest { h ->
        val waiting = OpenCodeSession(id = "ses_wait", serverTitle = "Waiting", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.ofEpochSecond(50))
        h.setShared(SharedKeys.pinnedSessionKeys, listOf("${server.persistenceID}|${math.id}"))
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(waiting, math), activeSessionIDs = setOf(waiting.id)))
            assertNull(home.voiceSessionBoard)
            home = home.openingDetail(waiting, cachedItems = emptyList())
            assertNull(home.voiceSessionBoard)
            home.withCall(VoiceFeature.State(server, waiting).live())
        }
        val board: VoiceSessionBoard = requireNotNull(h.state { home.voiceSessionBoard })
        assertEquals(setOf(waiting.id), board.busyIDs)
        assertEquals(listOf(math.id), board.pinnedIDs)
        assertTrue(board.attentionDirectories.contains(waiting.directory))

        val client = VoiceSessionBoardClient.inMemory()
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[VoiceSessionBoardClient] = client
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.AttentionRefreshed(setOf(waiting.id)))
        val published = requireNotNull(client.current(server.persistenceID))
        assertEquals(setOf(waiting.id), published.attentionIDs)
        assertEquals(VoiceSessionBoard.Flag.yes, published.needsInput(waiting, running = true))
        // Until Home's own attention read succeeds, other running sessions are unknown.
        val other = waiting.copy(id = "ses_other")
        assertEquals(VoiceSessionBoard.Flag.unknown, published.needsInput(other, running = true))
        client.attentionRead(server.persistenceID, true)
        assertEquals(VoiceSessionBoard.Flag.no, client.current(server.persistenceID)?.needsInput(other, running = true))
        client.attentionRead(server.persistenceID, false)
        assertEquals(VoiceSessionBoard.Flag.unknown, client.current(server.persistenceID)?.needsInput(other, running = true))
        assertNull(client.current("another server"))
        h.skipInFlightEffects()
    }
}
