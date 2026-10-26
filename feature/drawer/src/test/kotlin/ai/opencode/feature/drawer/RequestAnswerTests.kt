package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceCall
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.beginAnswering
import ai.opencode.feature.session.callOwnsAudio
import ai.opencode.feature.session.from
import ai.opencode.feature.session.isAnsweringRequest
import ai.opencode.feature.session.noteOpening
import ai.opencode.feature.session.parkedComposer
import ai.opencode.feature.session.permissionRequests
import ai.opencode.feature.session.wantsRequestDock
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.VoiceTranscriptRecord
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `RequestAnswerTests.swift`'s Home test. The rest are `:feature:session`'s
 * (`SessionFeature`/`RequestDockFeature` stores and the request bar's layout).
 */
class RequestAnswerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val session = OpenCodeSession(id = "ses_answer", serverTitle = "Answer", projectID = "proj", directory = "/tmp/answer", updatedAt = Instant.now())

    private fun SessionFeature.State.withPermissions(permissions: List<OpenCodePermissionRequest>): SessionFeature.State =
        copy(requestDock = requestDock.copy(permissionRequests = permissions))

    @Test
    fun switchingIntoASnapshotDuringACallEndsTheRestoredAnswer() = homeTest { h ->
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val target = OpenCodeSession(id = "ses_target", serverTitle = "Target", projectID = "proj", directory = "/tmp/target", updatedAt = Instant.ofEpochSecond(20))
        val targetShell = OpenCodePermissionRequest(
            id = "per_target", sessionID = target.id, action = "shell",
            resources = listOf("rm -rf .lab-output"), save = listOf("rm *"),
        )
        val initialTarget = h.state {
            SessionFeature.State.session(server, target, cachedItems = emptyList())
                .withPermissions(listOf(targetShell))
                .copy(hasLoadedPendingRequests = true)
                .beginAnswering()
        }
        assertTrue(initialTarget.isAnsweringRequest)

        val voice = h.state { VoiceFeature.State(server, target) }
        val caller = h.state { SessionFeature.State.session(server, session, cachedItems = emptyList(), navigationID = 1).copy(voice = voice) }

        val home = h.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, target), activeSessionIDs = emptySet())).copy(
                transcriptCache = mapOf(session.id to emptyList(), target.id to emptyList()),
                sessionDetail = caller,
                sessionSnapshots = mapOf(target.id to requireNotNull(SessionFeature.SwitchSnapshot.from(initialTarget))),
                sessionSnapshotOrder = listOf(target.id),
            )
        }

        val store = h.store(home) {
            this[UuidClient] = UuidClient.incrementing()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(30))
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
            voiceCall { copy(end = {}) }
            this[VoiceSessionBoardClient] = VoiceSessionBoardClient.inMemory()
        }

        val expectedTarget = store.withDependencies {
            SessionFeature.State.session(server, target, cachedItems = emptyList(), navigationID = 1, visit = 2)
                .withPermissions(listOf(targetShell))
                .copy(hasLoadedPendingRequests = true)
                .noteOpening(seen = emptySet())
                .copy(voice = voice.copy(targetNeedsInput = true), inheritsLiveWork = true)
        }

        store.send(HomeFeature.Action.SessionTapped(target.id)) { state ->
            store.withDependencies {
                val key = state.sessionKey(target.id)
                state.updatingLastViewedSessionIDs { it + (server.persistenceID to target.id) }
                    .updatingSessionReadDates { it + (key to target.updatedAt) }
                    .updatingManuallyUnreadSessionKeys { it - key }
                    .copy(
                        sessionSnapshots = state.sessionSnapshots + (session.id to requireNotNull(SessionFeature.SwitchSnapshot.from(caller))),
                        sessionSnapshotOrder = listOf(target.id, session.id),
                        detailGeneration = 2,
                        sessionDetail = expectedTarget,
                    )
            }
        }
        assertEquals(true, store.state.sessionDetail?.callOwnsAudio)
        assertEquals(false, store.state.sessionDetail?.wantsRequestDock)
        assertEquals(false, store.state.sessionDetail?.isAnsweringRequest)
        assertNull(store.state.sessionDetail?.parkedComposer)

        store.send(detail(SessionFeature.Action.PendingRequestsResponse(sessionID = target.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))) { state ->
            val detail = state.sessionDetail!!
            state.copy(sessionDetail = detail.withPermissions(emptyList()).copy(voice = detail.voice?.copy(targetNeedsInput = false)))
        }
        assertEquals(false, store.state.sessionDetail?.isAnsweringRequest)
        assertEquals(false, store.state.sessionDetail?.wantsRequestDock)

        val recordKey = "${server.id}:${target.id}"
        store.send(voice(VoiceFeature.Action.EndTapped)) { state ->
            val detail = state.sessionDetail!!
            state.copy(sessionDetail = detail.copy(voice = detail.voice?.withPhase(VoiceFeature.State.Phase.Ended)))
        }
        assertEquals(
            VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(30), tail = emptyList(), log = emptyList()),
            store.withDependencies { shared(VoiceSharedKeys.voiceTranscripts).value[recordKey] },
        )
        store.receive(voice(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended)))
        store.receive(detail(SessionFeature.Action.VoiceExitFinished)) { state ->
            state.copy(sessionDetail = state.sessionDetail?.copy(voice = null))
        }
        assertEquals(false, store.state.sessionDetail?.callOwnsAudio)
        assertEquals(false, store.state.sessionDetail?.wantsRequestDock)
        assertEquals(false, store.state.sessionDetail?.isAnsweringRequest)
        assertNull(store.state.sessionDetail?.parkedComposer)
    }
}
