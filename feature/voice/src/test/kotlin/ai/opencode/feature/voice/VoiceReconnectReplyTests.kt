package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `VoiceReconnectReplyTests.swift`. From Kit's Oct 21 call: a request steered
 * into OPENCODE IOS while it was still answering the one before, then two network switches.
 * The steer arrives after the earlier reply's text (V2 adds it at the next step), and the
 * delegator's round fails once the voice connection is replaced, after its message was
 * already sent.
 */
class VoiceReconnectReplyTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val ios = "ses_ios"
    private val earlierReply = "I've started two agents: one is finding why the call sounds start late."
    private val steerReply = "Right now the app doesn't use recorded sound files for calls."
    private val prompts: List<ReplyWatch.Prompt>
        get() = listOf(
            ReplyWatch.Prompt(promptID = "msg_first", sessionID = ios, title = "OPENCODE IOS", topic = "Call tones start late"),
            ReplyWatch.Prompt(promptID = "msg_steer", sessionID = ios, title = "OPENCODE IOS", topic = "Prioritize ElevenLabs"),
        )

    private fun s(value: String) = JsonPrimitive(value)
    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(linkedMapOf(*pairs))

    /**
     * `/message` as the server returns it (newest first), with or without
     * the steer's own reply yet.
     */
    private fun transcript(steerAnswered: Boolean): ByteArray {
        fun handoff(instruction: String) = VoiceHandoff.format(instruction = instruction, transcriptDelta = emptyList())
        fun content(type: String, text: String) = JsonArray(listOf(obj("type" to s(type), "text" to s(text))))
        val chronological = mutableListOf(
            obj("id" to s("msg_first"), "type" to s("user"), "text" to s(handoff("The call tones start late"))),
            obj("id" to s("msg_a1"), "type" to s("assistant"), "content" to content("reasoning", "Starting agents")),
            obj("id" to s("msg_a2"), "type" to s("assistant"), "content" to content("text", earlierReply)),
            obj("id" to s("msg_steer"), "type" to s("user"), "text" to s(handoff("Prioritize ElevenLabs"))),
            obj("id" to s("msg_a3"), "type" to s("assistant"), "content" to content("reasoning", "Reading the sound code")),
        )
        if (steerAnswered) {
            chronological += obj("id" to s("msg_a4"), "type" to s("assistant"), "content" to content("text", steerReply))
        }
        return obj("data" to JsonArray(chronological.reversed())).toString().toByteArray()
    }

    /** As `watchPrompt` reads: the prompt's own reply, else its run's. */
    private fun read(promptID: String, steerAnswered: Boolean): String? {
        val json = transcript(steerAnswered)
        return OpenCodeClient.assistantReply(messagesJSON = json, afterMessageID = promptID)
            ?: OpenCodeClient.assistantReply(messagesJSON = json, afterMessageID = promptID, acrossSteers = true)
    }

    private fun announcements(effects: List<ReplyWatch.Effect>): List<Pair<List<String>, String>> =
        effects.mapNotNull { (it as? ReplyWatch.Effect.Announce)?.let { announce -> announce.promptIDs to announce.text } }

    private fun logged(effects: List<ReplyWatch.Effect>): Map<String, ReplyWatch.Outcome> {
        val outcomes = mutableMapOf<String, ReplyWatch.Outcome>()
        for (effect in effects) {
            val resolved = (effect as? ReplyWatch.Effect.Log)?.entry as? ReplyWatch.LogEntry.Resolved ?: continue
            outcomes[resolved.promptID] = resolved.outcome
        }
        return outcomes
    }

    /**
     * Both waits fail while the network switches, retry, the call reconnects
     * and the reads land.
     */
    private fun reconnectMidWait(steerAnswered: Boolean): List<ReplyWatch.Effect> {
        var watch = ReplyWatch(callPhase = ReplyWatch.CallPhase.live)
        fun step(event: ReplyWatch.Event): List<ReplyWatch.Effect> = watch.transition(event).let { watch = it.first; it.second }
        step(ReplyWatch.Event.Sent(prompts))
        for (prompt in prompts) {
            val retry = step(ReplyWatch.Event.WaitFailed(promptID = prompt.promptID, error = ReplyWatch.WaitError.Retryable("Couldn’t reach the OpenCode server")))
            assertTrue(retry.any { it is ReplyWatch.Effect.RetryWait && it.prompt.promptID == prompt.promptID })
        }
        assertEquals(listOf(ReplyWatch.Effect.RefreshActiveSessions(source = ReplyWatch.SettlementSource.reconnect)), step(ReplyWatch.Event.Reconnected))
        step(ReplyWatch.Event.ActiveSessionsSnapshot(emptySet(), source = ReplyWatch.SettlementSource.reconnect))
        val effects = mutableListOf<ReplyWatch.Effect>()
        for (prompt in prompts) {
            effects += step(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = read(prompt.promptID, steerAnswered)))
        }
        assertTrue(watch.watched.isEmpty())
        return effects
    }

    @Test fun aSteersReadNeverReturnsTheReplyWrittenBeforeIt() {
        assertNull(read("msg_steer", steerAnswered = false))
        assertEquals(steerReply, read("msg_steer", steerAnswered = true))
        assertEquals(earlierReply, read("msg_first", steerAnswered = true))
        val items = OpenCodeClient.decodeTranscript(transcript(steerAnswered = false))
        assertNull(VoiceWorkClient.replyAfterPrompt(items, afterPromptID = "msg_steer"))
    }

    /**
     * The steer settles with no text of its own: the earlier reply answers
     * only the earlier request, never the steer.
     */
    @Test fun aReconnectMidWaitNeverHandsTheSteerTheEarlierReply() {
        val effects = reconnectMidWait(steerAnswered = false)
        val spoken = announcements(effects)
        assertEquals(listOf(listOf("msg_first"), listOf("msg_steer")), spoken.map { it.first })
        assertEquals(ReplyWatch.Outcome.Replied(earlierReply).notice("OPENCODE IOS"), spoken[0].second)
        assertEquals(ReplyWatch.Outcome.FinishedWithoutText.notice("OPENCODE IOS"), spoken[1].second)
        assertFalse(spoken.any { it.second.contains("This one reply answers") })
        assertEquals(mapOf("msg_first" to ReplyWatch.Outcome.Replied(earlierReply), "msg_steer" to ReplyWatch.Outcome.FinishedWithoutText), logged(effects))
    }

    /**
     * With its reply written, the run's notice carries both replies, its own
     * last, and the call log names each prompt's own reply.
     */
    @Test fun aReconnectMidWaitGivesTheSteerItsOwnReply() {
        val effects = reconnectMidWait(steerAnswered = true)
        val spoken = announcements(effects)
        assertEquals(listOf(listOf("msg_first", "msg_steer")), spoken.map { it.first })
        assertTrue(spoken.firstOrNull()?.second?.endsWith(steerReply) == true)
        assertTrue(spoken.firstOrNull()?.second?.contains(earlierReply + "\n\n" + steerReply) == true)
        assertEquals(mapOf("msg_first" to ReplyWatch.Outcome.Replied(earlierReply), "msg_steer" to ReplyWatch.Outcome.Replied(steerReply)), logged(effects))
        assertEquals(
            "Reply from “OPENCODE IOS” to msg_steer: $steerReply",
            ReplyWatch.Outcome.Replied(steerReply).logMarker("OPENCODE IOS", promptID = "msg_steer"),
        )
    }

    // MARK: The delegator's request after a reconnect

    private val sent = VoiceDelegator.SentPrompt(sessionID = "ses_ios", title = "OPENCODE IOS", promptID = "msg_steer", topic = "Prioritize ElevenLabs")
    private val replaced = OpenCodeClientError("The voice connection was replaced before that request finished.")

    private suspend fun onCall(sendsFirst: Boolean): VoiceDelegator.Outcome {
        val rounds = ArrayDeque<Result<VoiceDelegator.Response>>()
        if (sendsFirst) {
            rounds += Result.success(VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "send_message", arguments = """{"text":"Prioritize ElevenLabs"}""")), text = ""))
        }
        rounds += Result.failure(replaced)
        val delivered = mutableListOf<VoiceDelegator.SentPrompt>()
        val sent = sent
        val tools = VoiceDelegatorTools(
            run = {
                delivered += sent
                """{"message_id":"msg_steer"}"""
            },
            sent = { delivered.toList() },
            delivered = { delivered.toList() },
            note = { },
        )
        val client = VoiceDelegatorClient(isEnabled = true) { _, _, _ -> throw OpenCodeClientError("unused") }
        return client.runOnCall(
            delegationID = "del_1",
            rounds = VoiceDelegatorRounds(next = { rounds.removeFirst() }, submit = { _, _ -> true }),
            tools = tools,
        )
    }

    @Test fun aRequestReplacedAfterItsSendStillReportsTheSend() = runTest {
        val outcome = onCall(sendsFirst = true)
        assertEquals(
            VoiceDelegator.Outcome(
                reply = VoiceDelegator.deliveredBeforeFailure(listOf(sent)),
                responseID = null,
                unanswered = listOf(sent),
                interrupted = replaced.message,
            ),
            outcome,
        )
        assertTrue(outcome.reply.contains("reached “OPENCODE IOS”"))
    }

    @Test fun aRequestReplacedBeforeAnySendStillFails() = runTest {
        try {
            onCall(sendsFirst = false)
            throw AssertionError("Nothing was sent, so the request failed")
        } catch (error: OpenCodeClientError) {
            assertEquals(replaced, error)
        }
    }

    private fun voiceOnCall(shared: VoiceTestShared): VoiceFeature.State {
        val session = OpenCodeSession(id = ios, serverTitle = "OPENCODE IOS", projectID = "p", directory = "/Users/kit/ios", updatedAt = Instant.EPOCH)
        return shared.read {
            var voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live).copy(usesDelegator = true)
            voice = voice.copy(replyWatch = voice.replyWatch.transition(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.live)).first)
            voice.copy(tail = voice.tail.record(VoiceTranscriptTail.Role.user, "But mostly the Eleven Labs", id = "u1").tail)
        }
    }

    /**
     * On the phone's key the delegator chains requests by response ID. The
     * recovered request's last response has a `send_message` call whose
     * output never reached it, so the next request chains onto the response
     * before it, and the Responses API (stood in for here) takes it.
     */
    @Test fun theNextDelegationAfterARecoveredSendHasNoDanglingFunctionCalls() = runTest {
        val shared = VoiceTestShared()
        shared.set(SharedKeys.openAIAPIKey, "sk-test")
        val voice = voiceOnCall(shared).copy(delegatorResponseID = "r0")
        // Calls each response made, and the requests it got: (previous, input).
        val pendingCalls = mutableMapOf("r0" to emptyList<String>())
        val requests = mutableListOf<Pair<String?, List<VoiceDelegator.InputItem>>>()
        val replaced = replaced
        val completed = mutableListOf<String>()
        // iOS `skipInFlightEffects`: the store's effects run in a scope the test cancels.
        val effects = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = voiceTestStore(shared, { voice }, effects) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, previous ->
                requests += previous to input
                val number = requests.size
                val answered = input.mapNotNull { (it as? VoiceDelegator.InputItem.ToolOutput)?.callID }.toSet()
                val missing = previous?.let { pendingCalls[it] }?.firstOrNull { it !in answered }
                if (missing != null) throw OpenCodeClientError("No tool output found for function call $missing.", statusCode = 400)
                when (number) {
                    1 -> {
                        pendingCalls["r1"] = listOf("t1")
                        VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "send_message", arguments = """{"text":"Prioritize ElevenLabs"}""")), text = "")
                    }
                    2 -> throw replaced
                    else -> {
                        pendingCalls["r3"] = emptyList()
                        VoiceDelegator.Response(id = "r3", calls = emptyList(), text = "It's still on the sound options.")
                    }
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(sendDeliveredPrompt = { _, _, _, _ -> "msg_steer" })
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, _, _ ->
                delay(60.seconds)
                null
            })
            this[VoiceCallClient] = testVoiceCall(completeFunction = { _, output -> completed += output })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = RealtimeJSONs.obj())))
        store.receiveAfterActorWork(::isDelegatorFinished, timeout = 5.seconds)
        assertEquals("r0", store.state.delegatorResponseID)
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u2", text = "How's it going?", final = true)))
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d2", name = "delegate", arguments = RealtimeJSONs.obj())))
        store.receiveAfterActorWork(::isDelegatorFinished, timeout = 5.seconds)
        untilAfterActorWork { completed.size >= 2 }
        assertEquals(listOf("r0", "r1", "r0"), requests.map { it.first })
        assertTrue(requests.last().second.all { it is VoiceDelegator.InputItem.User })
        assertEquals(VoiceHandoff.completion("It's still on the sound options."), completed.lastOrNull())
        assertEquals("r3", store.state.delegatorResponseID)
        effects.cancel()
    }

    /**
     * Through the call: the Responses round after `send_message` fails as the
     * connection is replaced. The voice agent hears the message went through,
     * never "could not take that request", and the reply stays watched.
     */
    @Test fun theCallKeepsWatchingARequestSentBeforeTheConnectionWasReplaced() = runTest {
        val shared = VoiceTestShared()
        val voice = voiceOnCall(shared)
        val rounds = ArrayDeque(
            listOf(
                Result.success(VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "send_message", arguments = """{"text":"Prioritize ElevenLabs"}""")), text = "")),
                Result.failure<VoiceDelegator.Response>(replaced),
            ),
        )
        val completed = mutableListOf<String>()
        val effects = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val store = voiceTestStore(shared, { voice }, effects) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                throw AssertionError("The phone's delegator must not run on a Responses delegation")
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(sendDeliveredPrompt = { _, _, _, _ -> "msg_steer" })
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, _, _ ->
                delay(60.seconds)
                null
            })
            this[VoiceCallClient] = testVoiceCall(
                delegatorRound = { rounds.removeFirst() },
                submitDelegatorInput = { _, _ -> true },
                completeFunction = { _, output -> completed += output },
            )
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "del_1")))
        store.receiveAfterActorWork(::isDelegatorFinished, timeout = 5.seconds)
        untilAfterActorWork { completed.isNotEmpty() }
        val note = VoiceDelegator.deliveredBeforeFailure(listOf(VoiceDelegator.SentPrompt(sessionID = ios, title = "OPENCODE IOS", promptID = "msg_steer")))
        assertEquals(listOf(VoiceHandoff.completion(note)), completed)
        assertFalse(completed.any { it.contains("could not take that request") })
        assertEquals(listOf("msg_steer"), store.state.replyWatch.watched.map { it.promptID })
        assertTrue(store.state.delegatorWork.isEmpty())
        effects.cancel()
    }
}
