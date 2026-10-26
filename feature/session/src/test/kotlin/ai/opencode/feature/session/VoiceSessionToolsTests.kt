package ai.opencode.feature.session

import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Port of the `SessionFeature` test of iOS `VoiceSessionToolsTests.swift` (in its `VoiceModelToolsTests` suite); the
 * rest of the file is :feature:voice's (`VoiceSessionToolsTests.kt` there, see docs/parity/voice.md).
 *
 * Not ported here (:feature:drawer, `HomeFeature`): `VoiceSessionToolsTests.homePublishesTheDrawerStateOnlyDuringACall`.
 * Every other test in the file is :feature:voice's.
 */

class VoiceModelToolsTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    private fun voice(action: VoiceFeature.Action): Action = Action.Voice(PresentationAction.Presented(action))

    private val modelSwitched: (Action) -> Boolean = {
        ((it as? Action.Voice)?.action as? PresentationAction.Presented)?.action.let { a ->
            a is VoiceFeature.Action.Delegate && a.action is VoiceFeature.DelegateAction.ModelSwitched
        }
    }

    @Test fun theScreenFollowsOnlyItsOwnSessionsSwitch() = sessionTest { h ->
        val session = OpenCodeSession(id = "ses_fable", title = "Fable refactor", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = SessionTestSupport.distantPast)
        val state = h.state {
            SessionFeature.State.session(server = server, session = session, cachedItems = emptyList())
                .copy(voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live))
        }
        val store = h.store(state)
        store.exhaustive = false
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        store.send(voice(VoiceFeature.Action.DelegatorSwitchedModel(sessionID = "ses_docs", model = opus)))
        assertNull(store.state.currentModel)
        store.send(voice(VoiceFeature.Action.DelegatorSwitchedModel(sessionID = "ses_fable", model = opus)))
        store.receive(modelSwitched, "voice.delegate.modelSwitched")
        assertEquals(opus, store.state.currentModel)
        assertEquals("A visit to a subagent and back keeps it", opus, store.state.session?.model)
        h.skipInFlightEffects()

        // On a draft, the call's first request creates its session with it.
        SessionTestSupport.sessionTest { h2 ->
            val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
            val draft = h2.state {
                SessionFeature.State.draft(server = server, draftProject = project, projects = listOf(project))
                    .copy(voice = VoiceFeature.State(server, draftDirectory = "/project", model = null).withPhase(VoiceFeature.State.Phase.Live))
            }
            val draftStore = h2.store(draft)
            draftStore.exhaustive = false
            draftStore.send(voice(VoiceFeature.Action.DelegatorSwitchedModel(sessionID = null, model = opus)))
            draftStore.receive(modelSwitched, "voice.delegate.modelSwitched")
            assertEquals(opus, draftStore.state.currentModel)
            assertEquals(opus, draftStore.state.voice?.pendingModel)
        }
    }
}
