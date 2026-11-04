package ai.opencode.feature.voice

import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceWork
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * From Kit's Oct 24 call: switching sessions with the orb flashed “Now
 * talking in SEER”, “Now talking in HOME” (“I'm the one to switch
 * sessions”).
 *
 * iOS's `forgetTranscripts()` clears the records from the process-wide
 * shared store; each [Harness] has its own in-memory one, so there is
 * nothing to forget.
 */
class VoiceSessionMoveTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val home = OpenCodeSession(id = "ses_move_home", serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = VoiceFeatureTestSupport.distantPast)
    private val seer = OpenCodeSession(id = "ses_move_seer", serverTitle = "SEER", projectID = "p", directory = "/Users/kit/seer", updatedAt = VoiceFeatureTestSupport.distantPast)

    private fun movedCall(h: Harness, reason: String): VoiceFeature.State = h.state {
        VoiceFeature.State(server, home).live().retarget(seer, reason = reason, endedAt = Instant.ofEpochSecond(10))
    }

    private suspend fun retargetDelta(h: Harness, voice: VoiceFeature.State): String? {
        val deltas = LockIsolated(listOf<String?>())
        val store = h.store(voice) {
            voiceCall { copy(retarget = {}, updateInstructions = { _, delta -> deltas.append(delta) }) }
            voiceWork { copy(recentMessages = { _, _, _ -> emptyList() }) }
        }
        store.send(VoiceFeature.Action.TargetChanged)
        store.finish()
        return deltas.value.lastOrNull()
    }

    @Test fun aSwitchTheUserMadeIsQuietButTheModelStillHearsIt() = voiceTest { h ->
        val voice = movedCall(h, reason = "you opened it")
        assertNull("No “Now talking in” notice", voice.unpromptedMove)
        val delta = retargetDelta(h, voice)
        assertNotNull(delta)
        assertTrue(delta!!.startsWith(VoiceInstructions.retargetPrefix))
        assertTrue(delta.contains("\"SEER\""))
        assertTrue(delta.contains("don't announce or mention the move"))
    }

    @Test fun aMoveTheUserDidntMakeIsNoticed() = voiceTest { h ->
        val voice = movedCall(h, reason = VoiceFeature.State.MoveReason.agentSwitched)
        assertEquals(voice.moves, voice.unpromptedMove)
        val delta = retargetDelta(h, voice)
        assertNotNull(delta)
        assertTrue(delta!!.contains("The call moved because the voice agent switched"))
        assertFalse(delta.contains("don't announce"))

        // The user switching back is their own again.
        val back = h.state { voice.retarget(home, reason = "you opened it", endedAt = Instant.ofEpochSecond(20)) }
        assertNull(back.unpromptedMove)
    }
}
