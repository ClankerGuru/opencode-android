package ai.opencode.feature.session

import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Port of the `SessionFeature` test of iOS `VoiceLiveTests.swift` (in its `LiveDelegationFeatureTests` suite); the
 * rest of the file is :feature:voice's (`VoiceLiveTests.kt` there, see docs/parity/voice.md).
 *
 * Not ported here (:feature:drawer, `HomeFeature`): `LiveDelegationFeatureTests.aCallArrivingInASessionKnowsItsLatestCompaction`,
 * `draftAdoptingCreatedSessionDoesNotRetargetAndRealRetargetSendsConciseDeltaAndSilentAbandon`,
 * `LiveProtocolTests.quietGateHonorsReducedReplyWaitMaxHoldCapAndNeverCutsOffSpeech`.
 * Not ported here (:app, `VoiceEnginePersistenceTests`): `defaultsToGPTLiveAndPersistsSelectionAcrossCalls`.
 * Every other test in the file is :feature:voice's.
 */

class LiveDelegationFeatureTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", title = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = SessionTestSupport.distantPast)

    @Test fun theSessionScreenReportsTheNewestFinishedCompactionOnlyDuringACall() = sessionTest { h ->
        var state = h.state {
            SessionFeature.State.session(
                server = server,
                session = session,
                cachedItems = listOf(
                    OpenCodeTranscriptItem.Compaction(id = "cmp_old", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed)),
                    OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Hello"),
                    OpenCodeTranscriptItem.Compaction(id = "cmp_new", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed)),
                    OpenCodeTranscriptItem.Compaction(id = "cmp_running", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Running)),
                ),
            )
        }
        assertNull("No call, no scan", state.voiceCompaction)
        state = state.copy(voice = h.state { VoiceFeature.State(server, session) })
        assertEquals(
            "A running compaction hasn't dropped anything yet",
            VoiceFeature.State.CompactionMark(sessionID = "ses_voice", compactionID = "cmp_new"),
            state.voiceCompaction,
        )
        state = state.copy(voice = state.voice!!.let { it.copy(target = it.target.copy(sessionID = "ses_elsewhere")) })
        assertNull("Only the call's own session", state.voiceCompaction)
    }
}
