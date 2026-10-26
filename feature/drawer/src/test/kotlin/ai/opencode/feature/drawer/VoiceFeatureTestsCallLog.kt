package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `VoiceFeatureTests+CallLog`'s Home tests: a live call follows Home's session
 * switches. The call log tests are `:feature:voice`'s (`VoiceFeatureTestsCallLog.kt` there).
 */
class VoiceFeatureTestsCallLog {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", title = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)

    private fun liveCall(home: HomeFeature.State, on: OpenCodeSession): HomeFeature.State =
        home.copy(sessionDetail = home.sessionDetail!!.copy(voice = VoiceFeature.State(server, on).withPhase(VoiceFeature.State.Phase.Live)))

    private val targetChanged = { action: HomeFeature.Action ->
        action == HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(VoiceFeature.Action.TargetChanged)))
    }

    @Test
    fun switchingSessionsInHomeKeepsAnActiveCallAndRetargetsIt() = runTest {
        val other = OpenCodeSession(id = "ses_other", title = "iOS UI", projectID = "p", directory = "/Users/kit/opencode-ios", updatedAt = Instant.EPOCH)
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, other), activeSessionIDs = setOf(other.id)))
        home = home.copy(
            transcriptCache = home.transcriptCache +
                (session.id to listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "first"))) +
                (other.id to listOf(OpenCodeTranscriptItem.UserText(id = "m2", text = "second"))),
        )
        home = home.openingDetail(session, cachedItems = home.transcriptCache[session.id])
        home = liveCall(home, session)
        val initialNavID = home.sessionDetail?.navigationID

        val instructions = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[DateClient] = DateClient { Instant.EPOCH }
            this[VoiceCallClient] = VoiceCallClient.testValue.let { inert ->
                VoiceCallClient(
                    start = inert.start, completeFunction = inert.completeFunction, noteProgress = inert.noteProgress,
                    abandonDelegation = inert.abandonDelegation, setMuted = inert.setMuted, end = inert.end,
                    updateInstructions = { full, _ -> instructions += full },
                )
            }
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SessionTapped(other.id))
        assertEquals(other.id, store.state.sessionDetail?.session?.id)
        assertEquals(initialNavID, store.state.sessionDetail?.navigationID)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertEquals(other.id, store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals("iOS UI", store.state.sessionDetail?.voice?.sessionTitle)
        store.receive(targetChanged, "voice.targetChanged")
        // The voice model learns which session it now works on.
        assertTrue(eventually { instructions.lastOrNull()?.contains("\"iOS UI\"") == true })
    }

    @Test
    fun newSessionKeepsTheCallAndMovesItToTheDraft() = runTest {
        val project = OpenCodeProject(id = "p", name = "opencode-ios", directory = "/Users/kit/opencode-ios")
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session), activeSessionIDs = emptySet()))
        home = liveCall(home.openingDetail(session), session)
        val initialNavID = home.sessionDetail?.navigationID

        val instructions = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[DateClient] = DateClient { Instant.EPOCH }
            this[VoiceCallClient] = VoiceCallClient.testValue.let { inert ->
                VoiceCallClient(
                    start = inert.start, completeFunction = inert.completeFunction, noteProgress = inert.noteProgress,
                    abandonDelegation = inert.abandonDelegation, setMuted = inert.setMuted, end = inert.end,
                    updateInstructions = { full, _ -> instructions += full },
                )
            }
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.NewSessionTapped)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        // Same identity, so the call's effects survive the swap.
        assertEquals(initialNavID, store.state.sessionDetail?.navigationID)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertNull(store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals(project.directory, store.state.sessionDetail?.voice?.target?.directory)
        store.receive(targetChanged, "voice.targetChanged")
        assertTrue(eventually { instructions.lastOrNull()?.contains("has not started yet") == true })
    }
}
