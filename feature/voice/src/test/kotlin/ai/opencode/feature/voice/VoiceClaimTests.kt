package ai.opencode.feature.voice

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of iOS `VoiceClaimTests`. The voice agent saying it's acting without
 * a delegation, on a test clock: a loaded machine stretching the wait for
 * GPT-Live's own delegation can't make the call delegate a request twice.
 */
class VoiceClaimTests {
    private fun obj(vararg pairs: Pair<String, RealtimeJSON>) = RealtimeJSONs.obj(*pairs)

    private suspend fun TestScope.claimCall(): VoiceClockedCall = clockedCall(
        VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 40.milliseconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(
            liveUnbackedClaimWait = 150.milliseconds,
            liveAnswerPressedAfter = 50.milliseconds,
        ),
    )

    /**
     * Kit's call: GPT-Live said "Opening." to "Open code iOS" without
     * delegating, and nothing happened. The call delegates the request itself,
     * and its answer goes out without a delegation ID GPT-Live never made.
     */
    @Test fun aClaimWithoutADelegationIsDelegatedByTheCall() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Open code iOS", final = true), iterator.next())
        call.advance(80)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Opening."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(149)
        assertFalse("Its own delegation may still follow", call.harness.callLogMarkers.any { it.contains("without delegating") })
        call.advance(1)
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string("Opening."))),
            iterator.next(),
        )
        assertTrue(call.harness.callLogMarkers.any { it.contains("said “Opening.” without delegating") })

        call.coordinator.completeFunction(callID = "client_claim_1", output = "[COMPLETE] Opened OPENCODE IOS.")
        call.waitFor { socket.commands.any { it is LiveCommand.AppendCommentary } }
        assertTrue(socket.commands.any {
            it is LiveCommand.AppendCommentary && it.delegationID == null &&
                it.content == "Result of the request you acknowledged with “Opening.”: Opened OPENCODE IOS."
        })
        call.coordinator.end()
    }

    /**
     * A claim GPT-Live backed with its own delegation, or one with nothing
     * asked since the last delegation, is left alone.
     */
    @Test fun aBackedClaimIsNotDelegatedAgain() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Open code iOS", final = true), iterator.next())
        call.advance(80)
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_open", target = "client", offsetMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_open", name = "delegate", arguments = obj()), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Opening."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        // Said again later with nothing new asked: still nothing to delegate.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking.", startMilliseconds = 5_000, endMilliseconds = 5_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Checking."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = "Checking."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(300)
        assertFalse(call.harness.callLogMarkers.any { it.contains("without delegating") })
        call.coordinator.end()
        while (true) {
            val event = iterator.next() ?: break
            if (event is VoiceCallEvent.FunctionCall) fail("Delegated again: $event")
        }
    }

    /**
     * Kit's call (Oct 15): "Checking." acknowledged a delegation GPT-Live had
     * made, the user kept talking over it, and the call delegated "Checking."
     * again, so GAME ENGINE got the request twice. Either order of the
     * acknowledgement and the delegation event.
     */
    @Test fun anAcknowledgementTheUserTalksOverIsNotDelegatedAgain() {
        for (acknowledgedFirst in listOf(false, true)) anAcknowledgementTheUserTalksOverIsNotDelegatedAgain(acknowledgedFirst)
    }

    private fun anAcknowledgementTheUserTalksOverIsNotDelegatedAgain(acknowledgedFirst: Boolean) = runTest {
        val call = claimCall()
        // iOS's consumer task over `call.events`: every event so far.
        val received = { call.events.remaining() }
        call.userSays("Make Dark Shroud the default", from = 1_000, to = 1_800)
        call.advance(80)
        if (!acknowledgedFirst) call.delegates("del_shroud", at = 1_800)
        call.agentSpeaks("Checking.", from = 2_000, to = 2_400)
        if (acknowledgedFirst) {
            call.advance(20)
            call.delegates("del_shroud", at = 1_800)
        }
        call.advance(100)
        call.userSays("Maybe a little wobble too", from = 2_200, to = 3_000)
        call.advance(80)
        call.playbackDrains()
        call.advance(40)
        call.waitFor { VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking.") in received() }
        call.advance(300)
        assertFalse("acknowledgedFirst: $acknowledgedFirst", call.harness.callLogMarkers.any { it.contains("without delegating") })
        val delegations = received().filterIsInstance<VoiceCallEvent.FunctionCall>()
        assertEquals("acknowledgedFirst: $acknowledgedFirst", listOf(VoiceCallEvent.FunctionCall(callID = "del_shroud", name = "delegate", arguments = obj())), delegations)
        call.coordinator.end()
    }

    /**
     * Words the user said before the claim, transcribed after it started,
     * are still what it claims to act on.
     */
    @Test fun aClaimOnWordsTranscribedLateIsStillDelegated() = runTest {
        val call = claimCall()
        val received = { call.events.remaining() }
        call.socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        call.userSays("Open code iOS", from = 1_000, to = 1_500)
        call.advance(40)
        call.waitFor { VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening.") in received() }
        call.advance(150)
        call.waitFor { received().any { it is VoiceCallEvent.FunctionCall } }
        assertTrue(
            VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string("Opening."))) in received(),
        )
        call.coordinator.end()
    }
}
