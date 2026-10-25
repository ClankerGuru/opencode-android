package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.eventually
import ai.opencode.feature.voice.VoiceFeatureTestSupport.increment
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceFeatureTestSupport.withTestDependencies
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `VoiceFeatureTests+Screen.swift` (an extension of `VoiceFeatureTests`): the tests
 * of `VoiceFeature.State` and `VoiceWorkClient`, and the one whose store is `VoiceFeature`.
 *
 * Not ported here (SessionFeature, :feature:session): `directDelegationAppearsWithoutAnEventStreamEcho`,
 * `serverDelegationAppearsWithoutAnEventStreamEcho`, `theCallCarriesTheScreensPickUntilItIsApplied`,
 * `aCallOnADraftCreatesAnEmptySessionAndMovesOntoIt`, `aDraftCallsRequestCreatesTheScreensSession`,
 * `aCallWhoseSessionCantBeCreatedStaysOnTheDraft`, `firstRequestOnADraftCreatesTheSessionAndTheScreenAdoptsIt`,
 * `aDraftCallsModelIsAppliedOnceToTheSessionItCreates`, `aKeptPickWaitsOnTheSessionsScreen`,
 * `aDraftsSessionIsNotAdoptedOnceItsRequestWasLetGo`; and the SessionFeature half of
 * `aDraftCallsRefusedModelSendsNothing` (its `VoiceWorkClient` half is below).
 */
class VoiceFeatureTestsScreen {
    private lateinit var h: Harness

    @Test fun movingTheCallKeepsItsCaptionsAndLiveReply() = voiceTest { harness ->
        h = harness
        val other = VoiceFeatureTestSupport.session("ses_other", "Other", "/project")
        var voice = h.state { State(server, session) }
        voice = voice.copy(
            captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")),
            liveAssistantText = "On it",
            liveAssistantItemID = "a1",
        )
        voice = h.state { voice.retarget(other) }
        assertTrue(voice.captions.size == 1 && voice.liveAssistantText == "On it" && voice.liveAssistantItemID == "a1")
        voice = h.state { voice.retargetToDraft(directory = "/project", model = null) }
        assertTrue(voice.captions.size == 1 && voice.liveAssistantText == "On it")
    }

    @Test fun newSessionFromADraftCallKeepsTheConversationAndItsWork() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, draftDirectory = "/project", model = null) }
        voice = voice.copy(captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")))
        val captured = voice.tail.capture()
        voice = voice.copy(
            tail = captured.tail,
            pendingWork = listOf(State.PendingWork(callID = "c1", capture = captured.capture, target = voice.target, move = voice.moves)),
        )
        assertTrue(voice.isHere(voice.pendingWork[0]))
        voice = h.state { voice.retargetToDraft(directory = "/project", model = null) }
        assertEquals(1, voice.captions.size)
        assertEquals(listOf("c1"), voice.pendingWork.map { it.callID })
        assertTrue(voice.abandonedCallIDs.isEmpty())
        // It creates its own draft's session; the new draft won't adopt it.
        assertFalse(voice.isHere(voice.pendingWork[0]))
    }

    @Test fun changingADraftCallsProjectKeepsTheConversation() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, draftDirectory = "/project", model = null) }
        voice = voice.copy(captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")))
        val captured = voice.tail.capture()
        voice = voice.copy(tail = captured.tail, pendingWork = listOf(State.PendingWork(callID = "c1", capture = captured.capture)))
        voice = voice.moveDraft(directory = "/other")
        assertTrue(voice.target.directory == "/other" && voice.directoryName == "other")
        assertEquals(1, voice.captions.size)
        assertEquals(listOf("c1"), voice.abandonedCallIDs)
    }

    @Test fun aDraftCallCreatesItsSessionWithTheDraftsModel() = voiceTest {
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val calls = LockIsolated(listOf<String>())
        val created = VoiceFeatureTestSupport.session("ses_new", "", "/project")
        val admission = withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, directory, _ ->
                    calls.append("create $directory")
                    created
                },
                switchModel = { _, sessionID, ref -> calls.append("model $sessionID ${ref.id}") },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    calls.append("prompt $sessionID")
                    "msg_1"
                },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = null, directory = "/project", model = model), "Fix it")
        }
        assertEquals("ses_new", admission.createdSession?.id)
        assertEquals(listOf("create /project", "model ses_new fixture-model", "prompt ses_new"), calls.value)
    }

    /**
     * A delegation into a session applies the screen's waiting pick first,
     * as a typed send would.
     */
    @Test fun aDelegationAppliesTheScreensPickBeforeItsPrompt() = voiceTest {
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture", variant = "high")
        val calls = LockIsolated(listOf<String>())
        withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, sessionID, ref -> calls.append("model $sessionID ${ref.id}") },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    calls.append("prompt $sessionID")
                    "msg_1"
                },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = "ses_1", directory = "/project", model = model), "Fix it")
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = "ses_1", directory = "/project"), "Test it")
        }
        assertEquals(listOf("model ses_1 fixture-model", "prompt ses_1", "prompt ses_1"), calls.value)
    }

    /**
     * A session that lands while the call is connecting isn't a move: on
     * connect the model just learns it's attached to it.
     */
    @Test fun aDraftSessionThatLandsWhileConnectingIsntAMove() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, draftDirectory = "/project", model = null) }.withPhase(State.Phase.Connecting)
        voice = voice.copy(startedTarget = voice.target)
        val session = VoiceFeatureTestSupport.session("ses_call", "", "/project")
        val updates = LockIsolated(listOf<String>())
        val deltas = LockIsolated(listOf<String?>())
        val retargets = LockIsolated(0)
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall {
                copy(
                    setAtEar = { },
                    retarget = { retargets.increment() },
                    updateInstructions = { instructions, delta ->
                        updates.append(instructions)
                        deltas.append(delta)
                    },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.DraftSessionCreated(session))
        assertEquals("ses_call", store.state.target.sessionID)
        assertNull("Configured for the draft", store.state.startedTarget?.sessionID)
        store.send(Action.CallEvent(VoiceCallEvent.Connected))
        assertEquals(store.state.target, store.state.startedTarget)
        eventually { updates.value.isNotEmpty() }
        assertEquals(1, updates.value.size)
        assertEquals(false, updates.value.firstOrNull()?.contains("has not started yet"))
        store.skipReceivedActions()
        assertEquals("Not retargeted", 0, retargets.value)
        assertTrue("No “switched screens” for the model", deltas.value.all { it?.contains(VoiceInstructions.retargetPrefix) != true })
        assertNull(store.state.lastMove)
    }

    /**
     * A refused model sends nothing: the call hears why, and the pick waits
     * on the session it created. (The `VoiceWorkClient` half; the screen's half
     * is SessionFeature's.)
     */
    @Test fun aDraftCallsRefusedModelSendsNothing() = voiceTest {
        val created = VoiceFeatureTestSupport.session("ses_new", "Upload test", "/project")
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val calls = LockIsolated(listOf<String>())
        val admission = withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    calls.append("create")
                    created
                },
                switchModel = { _, _, _ -> throw OpenCodeClientError(message = "Unknown model", statusCode = 400) },
                sendDeliveredPrompt = { _, _, _, _ ->
                    calls.append("prompt")
                    "msg_1"
                },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = null, directory = "/project", model = model), "Fix it")
        }
        assertEquals(listOf("create"), calls.value)
        assertEquals("ses_new", admission.createdSession?.id)
        assertNull(admission.promptID)
        assertEquals("Unknown model", admission.modelError?.message)
    }
}
