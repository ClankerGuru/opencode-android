package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Port of iOS `VoiceLiveTests.swift`: `LiveProtocolTests`. */
class LiveProtocolTests {
    private fun s(value: String) = RealtimeJSONs.string(value)

    @Test fun sessionStartEncodesClientDelegationAudioFormatAndSeededInput() {
        val config = LiveSessionConfig(
            instructions = "Be concise.",
            input = listOf(
                LiveInputMessage(LiveInputMessage.Role.developer, "Continuing an earlier call."),
                LiveInputMessage(LiveInputMessage.Role.user, "Check the upload test."),
                LiveInputMessage(LiveInputMessage.Role.assistant, "Looking now."),
            ),
        )
        val json = decodeJSON(LiveCommand.SessionStart(config, eventID = "evt_start").encoded())
        assertEquals(s("session.start"), json["type"])
        assertEquals(s("evt_start"), json["event_id"])
        val session = json["session"]!!
        assertEquals(s("gpt-live-1"), session["model"])
        assertEquals(s("Be concise."), session["instructions"])
        assertEquals(s("audio/pcm"), session["audio"]?.get("format")?.get("type"))
        assertEquals(RealtimeJSONs.number(24_000), session["audio"]?.get("format")?.get("rate"))
        assertEquals(s("marin"), session["audio"]?.get("output")?.get("voice"))
        assertEquals(s("client"), session["delegation"]?.get("type"))

        val input = session["input"] as? JsonArray
        if (input == null || input.size != 3) throw AssertionError("Expected 3 seeded input messages")
        assertEquals(s("developer"), input[0]["role"])
        assertEquals(RealtimeJSONs.array(listOf(RealtimeJSONs.obj("type" to s("input_text"), "text" to s("Continuing an earlier call.")))), input[0]["content"])
        assertEquals(s("user"), input[1]["role"])
        assertEquals(RealtimeJSONs.array(listOf(RealtimeJSONs.obj("type" to s("input_text"), "text" to s("Check the upload test.")))), input[1]["content"])
        assertEquals(s("assistant"), input[2]["role"])
        assertEquals(RealtimeJSONs.array(listOf(RealtimeJSONs.obj("type" to s("output_text"), "text" to s("Looking now.")))), input[2]["content"])
    }

    @Test fun commandsEncodeDocumentedWireShapes() {
        // Odd byte count is aligned down to complete 16-bit samples.
        val audio = decodeJSON(LiveCommand.AppendAudio(byteArrayOf(1, 2, 3)).encoded())
        assertEquals(s("session.input_audio.append"), audio["type"])
        assertEquals(s(Base64.getEncoder().encodeToString(byteArrayOf(1, 2))), audio["audio"])

        val thinking = decodeJSON(LiveCommand.AppendThinking(eventID = "th_1", delegationID = "item_123", content = "Running tests.").encoded())
        assertEquals(s("session.thinking.append"), thinking["type"])
        assertEquals(s("th_1"), thinking["event_id"])
        assertEquals(s("item_123"), thinking["delegation_id"])
        assertEquals(s("Running tests."), thinking["content"])

        val commentary = decodeJSON(LiveCommand.AppendCommentary(eventID = "cm_1", delegationID = "item_123", content = "All tests pass.").encoded())
        assertEquals(s("session.commentary.append"), commentary["type"])
        assertEquals(s("item_123"), commentary["delegation_id"])
        assertEquals(s("All tests pass."), commentary["content"])

        val instructions = decodeJSON(LiveCommand.AppendInstructions(eventID = "in_1", delegationID = null, content = "Stay brief.").encoded())
        assertEquals(s("session.instructions.append"), instructions["type"])
        assertEquals(JsonNull, instructions["delegation_id"])
        assertEquals(s("Stay brief."), instructions["content"])

        val mute = decodeJSON(LiveCommand.Mute(eventID = "m_1").encoded())
        assertEquals(RealtimeJSONs.obj("type" to s("session.input_audio.mute"), "event_id" to s("m_1")), mute)

        val unmute = decodeJSON(LiveCommand.Unmute(eventID = "u_1").encoded())
        assertEquals(RealtimeJSONs.obj("type" to s("session.input_audio.unmute"), "event_id" to s("u_1")), unmute)

        val close = decodeJSON(LiveCommand.Close(eventID = "cl_1").encoded())
        assertEquals(RealtimeJSONs.obj("type" to s("session.close"), "event_id" to s("cl_1")), close)
    }

    @Test fun decodesDocumentedServerEvents() {
        val cases: List<Pair<String, LiveServerEvent>> = listOf(
            """{"type":"session.started","event_id":"e1","client_event_id":"evt_start","session":{"id":"live_1","expires_at":1791314434}}""" to
                LiveServerEvent.SessionStarted(id = "live_1", expiresAt = Instant.ofEpochSecond(1_791_314_434)),
            """{"type":"session.input_transcript.delta","event_id":"e2","delta":"What is","start_ms":1000,"end_ms":1200}""" to
                LiveServerEvent.InputTranscriptDelta(delta = "What is", startMilliseconds = 1_000, endMilliseconds = 1_200),
            """{"type":"session.output_transcript.delta","event_id":"e3","delta":" Checking","start_ms":1400,"end_ms":1600}""" to
                LiveServerEvent.OutputTranscriptDelta(delta = " Checking", startMilliseconds = 1_400, endMilliseconds = 1_600),
            """{"type":"session.output_audio.delta","delta":"AAD//w==","start_ms":1400,"end_ms":1500}""" to
                LiveServerEvent.OutputAudio(pcm = byteArrayOf(0, 0, -1, -1), startMilliseconds = 1_400, endMilliseconds = 1_500),
            """{"type":"session.delegation.created","event_id":"e4","offset_ms":1000,"delegation":{"id":"item_9tA","type":"delegation","target":"client"}}""" to
                LiveServerEvent.DelegationCreated(id = "item_9tA", target = "client", offsetMilliseconds = 1_000),
            """{"type":"session.input_audio.muted","event_id":"e5","client_event_id":"m_1"}""" to
                LiveServerEvent.InputAudioMuted(clientEventID = "m_1"),
            """{"type":"session.input_audio.unmuted","event_id":"e6","client_event_id":"u_1"}""" to
                LiveServerEvent.InputAudioUnmuted(clientEventID = "u_1"),
            """{"type":"session.thinking.appended","event_id":"e7","client_event_id":"th_1","start_ms":600,"end_ms":800}""" to
                LiveServerEvent.ContextAppended(kind = LiveAppendKind.thinking, clientEventID = "th_1", startMilliseconds = 600, endMilliseconds = 800),
            """{"type":"session.commentary.appended","event_id":"e8","client_event_id":"cm_1","start_ms":800,"end_ms":1000}""" to
                LiveServerEvent.ContextAppended(kind = LiveAppendKind.commentary, clientEventID = "cm_1", startMilliseconds = 800, endMilliseconds = 1_000),
            """{"type":"session.instructions.appended","event_id":"e9","client_event_id":"in_1","start_ms":800,"end_ms":1000}""" to
                LiveServerEvent.ContextAppended(kind = LiveAppendKind.instructions, clientEventID = "in_1", startMilliseconds = 800, endMilliseconds = 1_000),
            """{"type":"session.usage.updated","event_id":"e10","usage":{"seconds":12},"context_window":{"usage_ratio":0.42}}""" to
                LiveServerEvent.UsageUpdated(seconds = 12, contextUsageRatio = 0.42),
            """{"type":"session.closed","event_id":"e11","reason":"expired","usage":{"seconds":7200}}""" to
                LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 7_200),
            """{"type":"error","event_id":"e12","error":{"type":"invalid_request_error","code":"immutable_field_update","message":"Cannot change delegation.","param":"session.delegation.type","client_event_id":"evt_u"}}""" to
                LiveServerEvent.Error(
                    LiveServerError(
                        message = "Cannot change delegation.",
                        code = "immutable_field_update",
                        type = "invalid_request_error",
                        clientEventID = "evt_u",
                    ),
                ),
        )
        for ((json, expected) in cases) {
            assertEquals(expected, LiveServerEvent.decode(json.toByteArray()))
        }
    }

    @Test fun commentaryChunkerStripsCompletionPrefixCollapsesCodeAndRespectsTokenLimit() {
        val raw = """
            [COMPLETE] Fixed the flaky upload test by binding an ephemeral port.

            ```swift
            let port = try server.bindEphemeral()
            ```

            All 42 tests in the suite now pass.
        """.trimIndent()
        val chunks = LiveContentChunker.commentaryChunks(raw)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("Fixed the flaky upload test"))
        assertTrue(chunks[0].contains("All 42 tests in the suite now pass."))
        assertFalse(chunks[0].contains("[COMPLETE]"))
        assertFalse(chunks[0].contains("bindEphemeral"))

        val longSentence = "The test suite verified another module cleanly. ".repeat(50)
        val split = LiveContentChunker.commentaryChunks("[COMPLETE] $longSentence", maxCharacters = 400)
        assertEquals(LiveContentChunker.maxCommentaryChunks, split.size)
        assertTrue(split.all { it.length <= 400 })
    }

    @Test fun liveInstructionsFollowPromptingGuideTemplate() {
        val context = VoiceInstructions.Context(
            sessionTitle = "Voice lab",
            directoryName = "opencode-ios",
            recentMessages = listOf("user: check the build"),
            previousTail = listOf("user: hello", "assistant: hi there"),
            previousEndedAt = Instant.ofEpochSecond(0),
        )
        val prompt = VoiceInstructions.build(context, engine = VoiceEngine.gptLive)
        assertTrue(prompt.contains("Backchannel policy:"))
        assertTrue(prompt.contains("Interruption policy:"))
        assertTrue(prompt.contains("Delegation policy:"))
        assertTrue(prompt.contains("Tools:"))
        assertTrue(prompt.contains("Delegate when:"))
        assertTrue(prompt.contains("Do not delegate when:"))
        assertTrue(prompt.contains("\"Voice lab\""))
        assertTrue(prompt.contains("\"opencode-ios\""))
        assertTrue(prompt.contains("# Session So Far"))
        assertTrue(prompt.contains("# Previous Voice Call"))
        assertTrue(prompt.contains("Always delegate any check on whether a session has responded, finished, or is still working"))

        val managed = context.copy(managesSessions = true)
        val managedPrompt = VoiceInstructions.build(managed, engine = VoiceEngine.gptLive)
        assertTrue(managedPrompt.contains("You can message, read, or check any session in place without moving the user's screen; only open a session or draft when the user asks to view or switch to it."))
        assertTrue(VoiceDelegator.instructions.contains("Do NOT call open_session just to message, read, or check another session"))
        assertTrue(VoiceDelegator.instructions.contains("always call read_session (or list_sessions) in this turn"))
    }

    @Test fun quietGateNeverCutsOffSpeechAndHasNoCap() {
        assertEquals(900.milliseconds, VoiceCallTiming.live.liveAppendQuiet)
        assertEquals(1_500.milliseconds, VoiceCallTiming.live.liveAppendReplyWait)
        assertEquals("Nothing forces a held update out while the user talks", Duration.ZERO, VoiceCallTiming.live.liveAppendMaxHold)
        assertEquals(2.seconds, VoiceCallTiming.live.liveUserQuietGap)
        assertEquals(0.35f, VoiceCallTiming.live.liveMicVoiceLevel)
        assertEquals(600.milliseconds, VoiceCallTiming.live.liveBargeIn)

        val t0 = Duration.ZERO

        // 1. Assistant's 5 s response with a playback drain: the full 900 ms
        // post-speech `quietWindow` is still enforced after playback drains at 5 s.
        var assistantGate = VoiceCallTiming.live.quietGate
        assistantGate = assistantGate.noteAssistantSpeaking(true, t0)
        assistantGate = assistantGate.noteHeld(t0)
        assertEquals(900.milliseconds, assistantGate.delay(t0 + 3.seconds))
        assistantGate = assistantGate.noteAssistantSpeaking(false, t0 + 5.seconds)
        assertEquals(
            "Draining after a 5 s assistant reply must still enforce the 900 ms post-speech quiet window",
            900.milliseconds, assistantGate.delay(t0 + 5.seconds),
        )
        assertEquals(400.milliseconds, assistantGate.delay(t0 + 5_500.milliseconds))
        assertNull(assistantGate.delay(t0 + 5_900.milliseconds))

        // 2. User's continuous 6 s utterance holds throughout, then the 2 s
        // quiet gap applies before releasing at 8 s.
        var userGate = VoiceCallTiming.live.quietGate
        userGate = userGate.noteUserSpoke(t0)
        userGate = userGate.noteHeld(t0)
        for (step in 1..12) {
            val at = t0 + (step * 500).milliseconds
            userGate = userGate.noteUserSpoke(at)
            assertEquals("Continuous user speech at ${step * 500} ms holds", 2.seconds, userGate.delay(at))
        }
        assertEquals(1_500.milliseconds, userGate.delay(t0 + 6_500.milliseconds))
        assertNull(userGate.delay(t0 + 8_000.milliseconds))

        // 3. Kit's Oct 30 dictation: transcript fragments 1.2 s apart for 20 s
        // never release a held update (a 5 s cap used to, 1.5 s into a pause).
        var dictation = VoiceCallTiming.live.quietGate
        dictation = dictation.noteUserSpoke(t0)
        dictation = dictation.noteHeld(t0)
        for (fragment in 1..16) {
            val at = t0 + (fragment * 1_200).milliseconds
            assertNotNull("Held between fragments at ${fragment * 1_200} ms", dictation.delay(at - 1.milliseconds))
            dictation = dictation.noteUserSpoke(at)
        }
        // The microphone hears them talk on with no fragment for 2.5 s.
        val lastFragment = t0 + 19_200.milliseconds
        dictation = dictation.noteUserVoice(lastFragment + 2_500.milliseconds)
        assertEquals(1_500.milliseconds, dictation.delay(lastFragment + 3_000.milliseconds))
        assertEquals("user speaking", dictation.holdReason(lastFragment + 3_000.milliseconds))
        assertNull("Out after 2 s of real quiet", dictation.delay(lastFragment + 4_500.milliseconds))
    }
}

/**
 * Port of iOS `LiveCallCoordinatorTests`. The harness runs the coordinator on virtual time
 * (`VoiceLiveTestSupport.kt`), so iOS's wall-clock sleeps and limits are exact here.
 */
class LiveCallCoordinatorTests {
    private fun obj(vararg pairs: Pair<String, RealtimeJSON>) = RealtimeJSONs.obj(*pairs)
    private fun text(value: String) = obj("text" to RealtimeJSONs.string(value))

    private suspend fun TestScope.connected(harness: LiveHarness, delegation: LiveDelegation = LiveDelegation.client): Pair<EventIterator, FakeLiveSocket> {
        val events = EventIterator(harness.start(delegation = delegation), backgroundScope)
        val socket = harness.nextOpened()
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())
        return events to socket
    }

    /**
     * Server Voice: GPT-Live runs the delegator on its Responses backend. The
     * call hands each finished round to the phone, sends the tool results
     * back with `response.item.create` and continues with `response.create`,
     * and never appends with the delegation's ID (Responses rejects it).
     */
    @Test fun responsesDelegationHandsToolRoundsToThePhoneAndTakesTheirResults() = runTest {
        val harness = LiveHarness(this)
        val backend = VoiceDelegator.responsesDelegation(context = "Viewing session \"Upload\".")
        val events = EventIterator(harness.start(delegation = LiveDelegation.responses(backend)), backgroundScope)
        val socket = harness.nextOpened()
        assertEquals(LiveDelegation.responses(backend), socket.config.delegation)
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Which projects do I have?", startMilliseconds = 500, endMilliseconds = 1_400))
        events.next()
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "responses", offsetMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.ServerDelegation(callID = "del_1"), events.next())

        val call = VoiceDelegator.ToolCall(callID = "call_1", name = "list_projects", arguments = "{}")
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Created(responseID = "resp_1")))
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.FunctionCall(call)))
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Finished(responseID = "resp_1", status = "completed", error = null)))
        val first = harness.coordinator.delegatorRound(delegationID = "del_1")
        assertEquals(Result.success(VoiceDelegator.Response(id = "resp_1", calls = listOf(call), text = "")), first)

        val accepted = harness.coordinator.submitDelegatorInput(
            delegationID = "del_1",
            input = listOf(VoiceDelegator.InputItem.ToolOutput(callID = "call_1", output = """[{"name":"opencode-ios"}]""")),
        )
        assertTrue(accepted)
        val backendCommands = socket.commands.filter { it is LiveCommand.ResponseItemCreate || it is LiveCommand.ResponseCreate }
        assertEquals(2, backendCommands.size)
        val firstCommand = backendCommands.first()
        if (firstCommand is LiveCommand.ResponseItemCreate) {
            assertEquals(VoiceDelegator.InputItem.ToolOutput(callID = "call_1", output = """[{"name":"opencode-ios"}]""").liveItem, firstCommand.item)
        } else {
            throw AssertionError("The tool result goes first")
        }
        if (backendCommands.last() !is LiveCommand.ResponseCreate) throw AssertionError("Then the backend continues")

        // An answer arriving before the phone asks for it waits for it.
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Message("You have one project, opencode-ios.")))
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Finished(responseID = "resp_2", status = "completed", error = null)))
        delay(50)
        val second = harness.coordinator.delegatorRound(delegationID = "del_1")
        assertEquals(Result.success(VoiceDelegator.Response(id = "resp_2", calls = emptyList(), text = "You have one project, opencode-ios.")), second)

        // GPT-Live already has the answer: completing it appends nothing.
        harness.coordinator.completeFunction(callID = "del_1", output = "")
        delay(50)
        assertFalse(socket.commands.any {
            when (it) {
                is LiveCommand.AppendCommentary -> it.delegationID != null
                is LiveCommand.AppendThinking -> it.delegationID != null
                is LiveCommand.AppendInstructions -> it.delegationID != null
                else -> false
            }
        })
        assertFalse(socket.commands.any { it is LiveCommand.AppendCommentary })

        // The screen moved: the backend's prompt follows, once.
        harness.coordinator.updateDelegatorInstructions("Viewing session \"Docs\".")
        harness.coordinator.updateDelegatorInstructions("Viewing session \"Docs\".")
        assertEquals(1, socket.commands.count { it is LiveCommand.UpdateDelegatorInstructions })

        // A round asked for after the call ends gives up instead of hanging.
        harness.coordinator.end()
        val late = harness.coordinator.delegatorRound(delegationID = "del_1")
        if (late.isSuccess) throw AssertionError("No rounds after the call ends")
    }

    /**
     * A Responses delegation waiting on its next round gives up when the call
     * ends, so its tool loop never hangs.
     */
    @Test fun responsesDelegationRoundFailsWhenTheCallEnds() = runTest {
        val harness = LiveHarness(this)
        val (events, socket) = connected(harness, LiveDelegation.responses(VoiceDelegator.responsesDelegation(context = "Draft.")))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_2", target = "responses", offsetMilliseconds = 900))
        assertEquals(VoiceCallEvent.ServerDelegation(callID = "del_2"), events.next())
        val coordinator = harness.coordinator
        val waiting = async { coordinator.delegatorRound(delegationID = "del_2") }
        delay(50)
        coordinator.end()
        val round = waiting.await()
        assertEquals(OpenCodeClientError("The voice call ended."), round.exceptionOrNull())
    }

    /** A client-delegation call ignores Responses delegations it didn't ask for. */
    @Test fun clientDelegationIgnoresResponsesDelegations() = runTest {
        val harness = LiveHarness(this)
        val (events, socket) = connected(harness)
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_3", target = "responses", offsetMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_4", target = "client", offsetMilliseconds = 1_000))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_4", name = "delegate", arguments = obj()), events.next())
        harness.coordinator.end()
    }

    private val baseTiming = VoiceCallTiming(
        rotateAfter = 3_000.0,
        forceRotateAfter = 3_500.0,
        retryDelays = listOf(0.01),
        liveDelegationSettle = Duration.ZERO,
        liveTranscriptSettle = 40.milliseconds,
        liveAcknowledgementWait = Duration.ZERO,
    )

    /**
     * Measured against gpt-live-1: any append stops it mid-sentence, so
     * appends wait for a quiet moment, and for a reply the user is owed.
     */
    @Test fun appendsWaitUntilNeitherSideIsSpeaking() = runTest {
        val timing = baseTiming.copy(liveAppendQuiet = 250.milliseconds, liveAppendReplyWait = 600.milliseconds)
        val harness = LiveHarness(this, timing)
        val (events, socket) = connected(harness)
        val appends = { socket.appendContents() }

        // The assistant is talking: context and news are held, in order.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Once upon a time", startMilliseconds = 100, endMilliseconds = 400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Once upon a time"), events.next())
        harness.coordinator.updateInstructions(full = "Full.", delta = "Context for session B.")
        harness.coordinator.announce("Session B finished.")
        delay(120)
        assertTrue("Nothing may interrupt the assistant mid-sentence", appends().isEmpty())
        waitUntil { appends().size == 2 }
        assertEquals(listOf("Context for session B.", "Session B finished."), appends())

        // The user just spoke and the reply hasn't started: hold past the plain quiet.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What changed?", startMilliseconds = 2_000, endMilliseconds = 2_400))
        // The story's caption settles first.
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "What changed?", final = true))
        harness.coordinator.updateInstructions(full = "Full.", delta = "Context for session C.")
        delay(400)
        assertEquals("A reply is owed; context waits for it", 2, appends().size)
        waitUntil { appends().size == 3 }
        assertEquals("Context for session C.", appends().last())

        // Each delivery is a call-log marker with how long it was held; a held
        // notice has its own "Sent held notice" line instead.
        val marks = harness.callLogMarkers
        assertEquals(2, marks.size)
        assertTrue(marks.all { it.startsWith("Sent instructions to the voice agent") })
        assertTrue(marks.first().startsWith("Sent instructions to the voice agent · held "))
        val held = marks.first().split("·")[1].split(" ").filter { it.isNotEmpty() }[1].toDoubleOrNull() ?: 0.0
        assertTrue("It waited out the assistant's sentence", held >= 0.1)
        assertTrue(marks.first().contains("it last spoke"))

        // A held multi-chunk completion notice is suppressed whole (no orphan trailing chunks) by its prompt.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Did Blog game finish?", startMilliseconds = 6_000, endMilliseconds = 6_400))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Did Blog game finish?", final = true))
        val longSummary = "Verified another scoring rule in the test suite. ".repeat(40)
        val multiChunkNotice = "The session “Blog game” finished the work you started there. Its reply: $longSummary"
        assertTrue(LiveContentChunker.chunks(multiChunkNotice).size > 1)
        harness.coordinator.announce(multiChunkNotice, sessionID = "ses_blog", promptIDs = listOf("msg_blog"))
        harness.coordinator.cancelPendingAnnouncements(promptIDs = listOf("msg_blog"))
        delay(700)
        assertEquals("All chunks of the cancelled multi-chunk announcement must be removed", 3, appends().size)
        harness.coordinator.end()
    }

    private class Idle(val harness: LiveHarness, val socket: FakeLiveSocket, val events: EventIterator) {
        fun spoken(): List<String> = socket.spoken()
    }

    private suspend fun TestScope.idleHarness(
        resultMaxHold: Duration = 1.seconds,
        answerSpeakingHold: Duration = 4.seconds,
    ): Idle {
        val timing = baseTiming.copy(
            liveAppendQuiet = 150.milliseconds,
            liveAppendReplyWait = 300.milliseconds,
            liveResultMaxHold = resultMaxHold,
            liveAnswerSpeakingHold = answerSpeakingHold,
            liveAnswerStartWait = 400.milliseconds,
        )
        val harness = LiveHarness(this, timing)
        val (events, socket) = connected(harness)
        return Idle(harness, socket, events)
    }

    /**
     * A result that lands while the user is talking (or just said "okay,
     * thanks") waits only for the reply that's about to start, then is spoken.
     */
    @Test fun aResultLandingMidSpeechIsSpokenAtTheNextQuietMoment() = runTest {
        val idle = idleHarness()
        val (harness, socket) = idle.harness to idle.socket
        val result = "The session “Upload” finished the work you started there. Its reply: All 11 upload tests pass."
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Okay, thanks", startMilliseconds = 1_000, endMilliseconds = 1_400))
        delay(30)
        val landed = now
        harness.coordinator.announce(result, sessionID = "ses_upload")
        delay(80)
        assertTrue("Never over the user or right after them", idle.spoken().isEmpty())
        waitUntil { idle.spoken().contains(result) }
        assertTrue("No long quiet first", now - landed < 900.milliseconds)
        assertFalse("Spoken, not turned into background", socket.commands.any {
            it is LiveCommand.AppendThinking && it.content.contains("All 11 upload tests pass")
        })
        harness.coordinator.end()
    }

    /**
     * A newer request in flight doesn't hold an earlier result back: it
     * speaks as soon as nobody is talking, and the answer follows when ready.
     */
    @Test fun aNewerRequestInFlightDoesNotHoldAnEarlierResult() = runTest {
        val idle = idleHarness()
        val (harness, socket) = idle.harness to idle.socket
        val result = "The session “Upload” finished the work you started there. Its reply: Done — fixed the retry loop."
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Wait, check the docs instead", startMilliseconds = 1_000, endMilliseconds = 1_600))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_docs", target = "client", offsetMilliseconds = 1_600))
        delay(50)
        val landed = now
        harness.coordinator.announce(result, sessionID = "ses_upload")
        waitUntil { idle.spoken().contains(result) }
        assertTrue("Not held while the newer request runs", now - landed < 900.milliseconds)
        harness.coordinator.completeFunction(callID = "del_docs", output = "[COMPLETE] The docs are up to date.")
        waitUntil { idle.spoken().any { it.contains("The docs are up to date.") } }
        assertTrue(idle.spoken().any { it.contains("The docs are up to date.") })
        harness.coordinator.end()
    }

    /**
     * The voice agent talking on and on holds a result only so long, then it
     * goes out even though that cuts the speech off.
     */
    @Test fun continuousSpeechHoldsAResultOnlyUpToTheLimit() = runTest {
        val idle = idleHarness(resultMaxHold = 600.milliseconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "And another thing about the weather", startMilliseconds = 1_000, endMilliseconds = 9_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_000, endMilliseconds = 1_100))
        delay(100)
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        val landed = now
        harness.coordinator.announce(result, sessionID = "ses_upload")
        delay(300)
        assertTrue("Held while the voice agent is mid-sentence", idle.spoken().isEmpty())
        waitUntil { idle.spoken().contains(result) }
        val held = now - landed
        assertTrue("Out at the limit, still speaking: $held", held >= 550.milliseconds && held < 2.seconds)
        harness.coordinator.end()
    }

    /**
     * A delegation's answer waits on the voice agent's chatter only as long
     * as any result does.
     */
    @Test fun continuousSpeechHoldsAnAnswerOnlyUpToTheLimit() = runTest {
        val idle = idleHarness(resultMaxHold = 600.milliseconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(400)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Meanwhile, a long story", startMilliseconds = 1_000, endMilliseconds = 9_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_000, endMilliseconds = 1_100))
        delay(100)
        val ready = now
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        assertTrue(idle.spoken().isEmpty())
        waitUntil { idle.spoken().isNotEmpty() }
        assertTrue("Not held for the whole story", now - ready < 2.seconds)
        assertEquals(listOf(rulesAnswer), idle.spoken())
        harness.coordinator.end()
    }

    /**
     * While the voice agent speaks a delegation's answer, a result waits for
     * it to finish (up to the longer limit) rather than cutting it off.
     */
    @Test fun aResultNeverCutsOffTheAnswerBeingSpoken() = runTest {
        val idle = idleHarness(resultMaxHold = 400.milliseconds, answerSpeakingHold = 5.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        waitUntil { idle.spoken().isNotEmpty() }
        // The voice agent reads the answer out.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "One player is the giant desk spider", startMilliseconds = 2_000, endMilliseconds = 6_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 2_000, endMilliseconds = 2_100))
        delay(100)
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        harness.coordinator.announce(result, sessionID = "ses_upload")
        delay(1_000)
        assertFalse("Past the usual limit, but the answer is still being spoken", idle.spoken().contains(result))
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        val finished = now
        waitUntil { idle.spoken().contains(result) }
        assertTrue("Right after the answer", now - finished < 2.seconds)
        assertEquals(listOf(rulesAnswer, result), idle.spoken())
        harness.coordinator.end()
    }

    /**
     * A result whose limit runs out while an answer is queued behind the
     * same chatter goes out after the answer, and still gives the voice agent
     * its moment to start speaking it.
     */
    @Test fun anExpiredResultFollowsAQueuedAnswerAndStillWaitsForIt() = runTest {
        val idle = idleHarness(resultMaxHold = 600.milliseconds, answerSpeakingHold = 5.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(400)
        // The voice agent chatters on and never stops.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Meanwhile, a long story", startMilliseconds = 1_000, endMilliseconds = 9_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_000, endMilliseconds = 1_100))
        delay(50)
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        harness.coordinator.announce(result, sessionID = "ses_upload")
        delay(100)
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        waitUntil { idle.spoken().isNotEmpty() }
        val answerOut = now
        assertEquals("The answer first, alone", listOf(rulesAnswer), idle.spoken())
        waitUntil { idle.spoken().size == 2 }
        assertEquals(listOf(rulesAnswer, result), idle.spoken())
        assertTrue("It gave the answer its moment to start", now - answerOut >= 300.milliseconds)
        harness.coordinator.end()
    }

    /**
     * Once the answer has been spoken, later speech isn't taken for it: a
     * result waits on that speech only the usual limit.
     */
    @Test fun speechAfterTheAnswerHoldsAResultOnlyTheUsualLimit() = runTest {
        val idle = idleHarness(resultMaxHold = 500.milliseconds, answerSpeakingHold = 5.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        waitUntil { idle.spoken().isNotEmpty() }
        // The answer is spoken and ends.
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 2_000, endMilliseconds = 2_100))
        delay(100)
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        delay(400)
        // Then the voice agent talks about something else, on and on.
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 4_000, endMilliseconds = 4_100))
        delay(50)
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        val landed = now
        harness.coordinator.announce(result, sessionID = "ses_upload")
        waitUntil { idle.spoken().contains(result) }
        val held = now - landed
        assertTrue("The usual limit, not the answer's: $held", held >= 400.milliseconds && held < 1_500.milliseconds)
        harness.coordinator.end()
    }

    /**
     * The user talks past even the answer's limit while a result and an
     * answer wait. When they stop, the answer goes first and still gets its
     * moment to start: the result's own deadline, long gone, doesn't cut that.
     */
    @Test fun aLongHeldResultStillWaitsForAnAnswerSentAfterTheUserStops() = runTest {
        val idle = idleHarness(resultMaxHold = 300.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "So", startMilliseconds = 1_000, endMilliseconds = 1_050))
        delay(30)
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        harness.coordinator.announce(result, sessionID = "ses_upload")
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 1_050))
        for (index in 1..20) {
            delay(80)
            socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " and", startMilliseconds = 1_000 + index * 80, endMilliseconds = 1_050 + index * 80))
            if (index == 3) {
                harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
            }
        }
        assertTrue("Held while the user talks, past every limit", idle.spoken().isEmpty())
        waitUntil { idle.spoken().isNotEmpty() }
        val answerOut = now
        assertEquals(listOf(rulesAnswer), idle.spoken())
        waitUntil { idle.spoken().size == 2 }
        assertEquals(listOf(rulesAnswer, result), idle.spoken())
        assertTrue("The answer got its moment to start", now - answerOut >= 300.milliseconds)
        harness.coordinator.end()
    }

    /**
     * An answer sent while the voice agent is still talking: its audio
     * follows the cut-off speech without a break, and is protected as the
     * answer, past the usual limit, until it ends.
     */
    @Test fun theAnswersAudioIsProtectedWhenItFollowsSpeechWithoutABreak() = runTest {
        val idle = idleHarness(resultMaxHold = 400.milliseconds, answerSpeakingHold = 4.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(400)
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_000, endMilliseconds = 1_100))
        delay(50)
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        // The chatter holds the answer up to its limit; it goes out mid-speech.
        waitUntil { idle.spoken().isNotEmpty() }
        // The answer's audio arrives with no drain in between.
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_600, endMilliseconds = 1_700))
        delay(50)
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        harness.coordinator.announce(result, sessionID = "ses_upload")
        for (index in 1..6) {
            delay(150)
            socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_700 + index * 150, endMilliseconds = 1_800 + index * 150))
        }
        assertFalse("Held past the start wait and the usual limit: it's the answer", idle.spoken().contains(result))
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        val ended = now
        waitUntil { idle.spoken().contains(result) }
        assertTrue(now - ended < 2.seconds)
        assertEquals(listOf(rulesAnswer, result), idle.spoken())
        harness.coordinator.end()
    }

    /**
     * An answer's chunks go out whole and in order even when a send stalls:
     * a result whose limit runs out meanwhile waits its turn instead of
     * landing between them.
     */
    @Test fun aResultNeverLandsBetweenAnAnswersChunks() = runTest {
        val idle = idleHarness(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        val longAnswer = "$rulesAnswer ".repeat(12)
        val chunks = LiveContentChunker.commentaryChunks("[COMPLETE] $longAnswer")
        assertTrue(chunks.size > 1)
        socket.stallFirstCommentary = true
        val answering = launch { harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $longAnswer") }
        waitUntil { socket.commentaryEntered == 1 }
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        harness.coordinator.announce(result, sessionID = "ses_upload")
        // Past the result's limit and the answer's start wait, the first chunk still stuck.
        delay(700)
        assertTrue("Nothing overtakes the stalled chunk", idle.spoken().isEmpty())
        socket.stallFirstCommentary = false
        answering.join()
        waitUntil { idle.spoken().size == chunks.size + 1 }
        assertEquals("The answer whole, then the result", chunks + result, idle.spoken())
        harness.coordinator.end()
    }

    /**
     * A result is stuck mid-send on the socket, so the slot is taken, while
     * a delegation's answer waits its turn after the user's request.
     */
    private suspend fun TestScope.answerWaitingForTheSlot(): Pair<Idle, Job> {
        val idle = idleHarness(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        socket.stallFirstCommentary = true
        // Stalls inside the send, so it isn't awaited.
        launch { harness.coordinator.announce("The session “Upload” finished the work you started there. Its reply: All tests pass.", sessionID = "ses_upload") }
        waitUntil { socket.commentaryEntered == 1 }
        val answering = launch { harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer") }
        delay(50)
        assertTrue(idle.spoken().isEmpty())
        return idle to answering
    }

    /**
     * The session is replaced while the answer waits its turn: it goes to the
     * new session without its delegation, as an earlier request's result.
     */
    @Test fun anAnswerWaitingForTheSlotGoesAsAnEarlierResultWhenTheSessionIsReplaced() = runTest {
        val (idle, answering) = answerWaitingForTheSlot()
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        waitUntil { harness.sockets.size == 2 }
        val next = harness.sockets[1]
        next.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        delay(100)
        socket.stallFirstCommentary = false
        answering.join()
        waitUntil { next.commentary().any { it.second.contains("roly-poly") } }
        val sent = next.commentary().firstOrNull { it.second.contains("roly-poly") } ?: throw AssertionError("Expected the answer on the new session")
        assertNull(sent.first)
        assertTrue(sent.second.startsWith("Result of an earlier request"))
        assertFalse("Never the old session's delegation", (socket.commentary() + next.commentary()).any { it.first == "del_rules" })
        harness.coordinator.end()
    }

    /**
     * The user starts talking while the answer waits its turn: once it has
     * the slot it checks again and holds on until they pause.
     */
    @Test fun anAnswerWaitingForTheSlotHoldsForTheUserWhoStartedTalking() = runTest {
        val (idle, answering) = answerWaitingForTheSlot()
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Oh and", startMilliseconds = 3_000, endMilliseconds = 3_050))
        delay(30)
        socket.stallFirstCommentary = false
        answering.join()
        for (index in 1..6) {
            delay(80)
            socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " also", startMilliseconds = 3_000 + index * 80, endMilliseconds = 3_050 + index * 80))
            assertFalse("Never over the user (${index * 80} ms)", idle.spoken().any { it.contains("roly-poly") })
        }
        val stopped = now
        waitUntil { idle.spoken().any { it.contains("roly-poly") } }
        assertTrue(now - stopped < 700.milliseconds)
        harness.coordinator.end()
    }

    /**
     * A cancelled waiter gives up at once, and ending the call turns away
     * everyone still waiting: nothing they carried is sent or queued, even
     * when the send holding the slot fails afterwards.
     */
    @Test fun waitersForTheSlotGiveUpWhenCancelledOrTheCallEnds() = runTest {
        val (idle, answering) = answerWaitingForTheSlot()
        val (harness, socket) = idle.harness to idle.socket
        val instructions = launch { harness.coordinator.updateInstructions(full = "Full.", delta = "Context for session C.") }
        val cancelled = launch { harness.coordinator.updateInstructions(full = "Full.", delta = "Cancelled context.") }
        delay(50)
        cancelled.cancel()
        cancelled.join()
        harness.coordinator.end()
        answering.join()
        instructions.join()
        // The send that held the slot fails now the call is over.
        socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        delay(100)
        assertFalse(socket.commands.any {
            when (it) {
                is LiveCommand.AppendCommentary -> it.content.contains("roly-poly")
                is LiveCommand.AppendInstructions -> it.content.contains("context")
                else -> false
            }
        })
        assertEquals("Nothing queued after the call ended", 0, harness.pendingAppendCount())
    }

    /**
     * The call ends while an answer holds the slot, its first chunk stuck in
     * the send. Whether that send then fails or goes through, no further
     * chunk is sent and nothing is queued.
     */
    @Test fun anAnswerMidSendStopsWhenTheCallEnds() {
        for (lateSendFails in listOf(true, false)) anAnswerMidSendStopsWhenTheCallEnds(lateSendFails)
    }

    private fun anAnswerMidSendStopsWhenTheCallEnds(lateSendFails: Boolean) = runTest {
        val idle = idleHarness(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        val longAnswer = "$rulesAnswer ".repeat(12)
        assertTrue(LiveContentChunker.commentaryChunks("[COMPLETE] $longAnswer").size > 1)
        socket.stallFirstCommentary = true
        val answering = launch { harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $longAnswer") }
        waitUntil { socket.commentaryEntered == 1 }
        harness.coordinator.end()
        if (lateSendFails) socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        answering.join()
        delay(100)
        assertEquals("Only the chunk already under way (lateSendFails: $lateSendFails)", if (lateSendFails) 0 else 1, socket.commentary().size)
        assertEquals("No further chunk was even tried", 1, socket.commentaryEntered)
        assertEquals("Nothing queued after the call ended", 0, harness.pendingAppendCount())
    }

    /**
     * A replacement session attaches while an answer's first chunk is stuck
     * in the old session's send. Whether that send then goes through or
     * fails, the rest goes to the new session at once, as an earlier
     * request's result: every part once, nothing left waiting.
     */
    @Test fun anAnswerCutOffByAReplacementFinishesOnTheNewSession() {
        for (lateSendFails in listOf(false, true)) anAnswerCutOffByAReplacementFinishesOnTheNewSession(lateSendFails)
    }

    private fun anAnswerCutOffByAReplacementFinishesOnTheNewSession(lateSendFails: Boolean) = runTest {
        val idle = idleHarness(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        val output = "[COMPLETE] " + "$rulesAnswer ".repeat(12)
        val chunks = LiveContentChunker.commentaryChunks(output)
        assertTrue(chunks.size > 1)
        socket.stallFirstCommentary = true
        val answering = launch { harness.coordinator.completeFunction(callID = "del_rules", output = output) }
        waitUntil { socket.commentaryEntered == 1 }
        socket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        waitUntil { harness.sockets.size == 2 }
        val next = harness.sockets[1]
        next.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        delay(200)
        if (lateSendFails) socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        answering.join()
        val expected = chunks.joinToString("").filter { !it.isWhitespace() }
        val prefix = LiveCallCoordinator.earlierConnectionLead.trim(' ', '\t')
        val delivered = {
            (socket.commentary() + next.commentary()).joinToString("") { it.second.replace(prefix, "") }.filter { !it.isWhitespace() }
        }
        waitUntil { delivered().length >= expected.length }
        delay(150)
        assertEquals("Every part once, in order (lateSendFails: $lateSendFails)", expected, delivered())
        assertEquals(if (lateSendFails) 0 else 1, socket.commentary().size)
        assertTrue(next.commentary().all { it.first == null })
        assertTrue(next.commentary().firstOrNull()?.second?.startsWith(prefix) == true)
        assertEquals("Nothing stranded", 0, harness.pendingAppendCount())
        harness.coordinator.end()
    }

    /**
     * An answer held behind the voice agent's speech is flushed, and its
     * first chunk fails after a replacement session attached: the whole
     * answer goes to the new session, once, in order.
     */
    @Test fun aHeldAnswerWhoseFirstChunkFailsGoesWholeToTheNewSession() = runTest {
        val idle = idleHarness(resultMaxHold = 300.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 500, endMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 900))
        delay(500)
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_000, endMilliseconds = 1_100))
        delay(50)
        val output = "[COMPLETE] " + "$rulesAnswer ".repeat(12)
        val chunks = LiveContentChunker.commentaryChunks(output)
        assertTrue("An answer's chunks are capped; two are enough to put the end before the beginning", chunks.size > 1)
        socket.stallFirstCommentary = true
        harness.coordinator.completeFunction(callID = "del_rules", output = output)
        assertEquals("Held behind the speech", 0, socket.commentaryEntered)
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        waitUntil { socket.commentaryEntered == 1 }
        socket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        waitUntil { harness.sockets.size == 2 }
        val next = harness.sockets[1]
        next.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        delay(200)
        socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        val expected = chunks.joinToString("").filter { !it.isWhitespace() }
        val delivered = { next.commentary().joinToString("") { it.second }.filter { !it.isWhitespace() } }
        waitUntil { delivered().length >= expected.length }
        delay(150)
        assertTrue(socket.commentary().isEmpty())
        assertEquals("The beginning first, every part once", expected, delivered())
        assertTrue(next.commentary().all { it.first == null })
        assertEquals(0, harness.pendingAppendCount())
        harness.coordinator.end()
    }

    /**
     * The limit only ever cuts the voice agent off: while the user is still
     * talking, a result waits, however long it has been held.
     */
    @Test fun theUserSpeakingHoldsAResultPastTheLimit() = runTest {
        val idle = idleHarness(resultMaxHold = 300.milliseconds)
        val (harness, socket) = idle.harness to idle.socket
        val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "So", startMilliseconds = 1_000, endMilliseconds = 1_050))
        delay(30)
        harness.coordinator.announce(result, sessionID = "ses_upload")
        for (index in 1..10) {
            delay(80)
            socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " and", startMilliseconds = 1_000 + index * 80, endMilliseconds = 1_050 + index * 80))
            assertFalse("Held while the user talks (${index * 80} ms)", idle.spoken().contains(result))
        }
        val stopped = now
        waitUntil { idle.spoken().contains(result) }
        assertTrue("Out once they pause", now - stopped < 2.seconds)
        harness.coordinator.end()
    }

    /**
     * The delegator's answer cancels only results it covered: a notice that
     * also answers other requests still speaks after it.
     */
    @Test fun theDelegatorCancelsOnlyTheResultsItAnswered() = runTest {
        val idle = idleHarness()
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "When does Karma Cafe close?", startMilliseconds = 1_000, endMilliseconds = 1_600))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_cafe", target = "client", offsetMilliseconds = 1_600))
        delay(50)
        val briefing = "This one reply answers 2 earlier requests: … Its reply: Inbox: two X DMs need replies."
        val alone = "The session “HOME” finished the work you started there. Its reply: Karma Cafe closed at 4."
        harness.coordinator.announce(briefing, sessionID = "ses_home", promptIDs = listOf("msg_inbox", "msg_cafe"))
        harness.coordinator.announce(alone, sessionID = "ses_home", promptIDs = listOf("msg_cafe"))
        harness.coordinator.cancelPendingAnnouncements(promptIDs = listOf("msg_cafe"))
        harness.coordinator.completeFunction(callID = "del_cafe", output = "[COMPLETE] Karma Cafe closed at 4 today.")
        waitUntil { idle.spoken().contains(briefing) }
        val order = idle.spoken()
        assertFalse("The part the delegator said isn't said twice", order.contains(alone))
        val answer = order.indexOfFirst { it.contains("Karma Cafe closed at 4 today.") }
        assertTrue(answer >= 0)
        assertTrue(answer < order.indexOf(briefing))
        harness.coordinator.end()
    }

    /**
     * Results released with the delegator's answer never go out on top of it:
     * they give the voice agent a moment (`liveAnswerStartWait`) to start
     * speaking it, then wait while it does.
     */
    @Test fun aResultReleasedWithTheDelegatorsAnswerWaitsForItToBeSpoken() = runTest {
        val idle = idleHarness()
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "When does Karma Cafe close?", startMilliseconds = 1_000, endMilliseconds = 1_600))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_cafe", target = "client", offsetMilliseconds = 1_600))
        delay(900)
        val briefing = "This one reply answers 3 earlier requests: … Its reply: Inbox: two X DMs need replies."
        harness.coordinator.completeFunction(callID = "del_cafe", output = "[COMPLETE] Karma Cafe closed at 4 today.")
        harness.coordinator.announce(briefing, sessionID = "ses_home", promptIDs = listOf("msg_inbox"))
        delay(100)
        assertTrue(idle.spoken().any { it.contains("Karma Cafe closed at 4 today.") })
        assertFalse("Not over the answer the voice agent is about to speak", idle.spoken().contains(briefing))
        waitUntil { idle.spoken().contains(briefing) }
        assertTrue(idle.spoken().contains(briefing))
        harness.coordinator.end()
    }

    /** A read covering every prompt in the session cancels a combined notice. */
    @Test fun aReadCoveringTheWholeRunCancelsItsCombinedNotice() = runTest {
        val idle = idleHarness()
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What did HOME say?", startMilliseconds = 1_000, endMilliseconds = 1_600))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_read", target = "client", offsetMilliseconds = 1_600))
        delay(50)
        val combined = "This one reply answers 2 earlier requests: … Its reply: two DMs need replies."
        harness.coordinator.announce(combined, sessionID = "ses_home", promptIDs = listOf("msg_inbox", "msg_dms"))
        harness.coordinator.cancelPendingAnnouncements(promptIDs = listOf("msg_inbox", "msg_dms", "msg_narrow"))
        harness.coordinator.completeFunction(callID = "del_read", output = "[COMPLETE] HOME says two DMs need replies.")
        waitUntil { idle.spoken().any { it.contains("HOME says two DMs need replies.") } }
        delay(900)
        assertFalse("Said once, by the delegator", idle.spoken().contains(combined))
        harness.coordinator.end()
    }

    /**
     * Kit's call (TestFlight 301, 170–203 s): he asked for the rules, then
     * "Wait, don't you have them?" while the delegator read them. Its answer
     * was ready 1.4 s after he stopped, held for the voice agent's reply; the
     * voice agent answered from an older result ("I don't have them, what I
     * got back was cut off"), and the rules went out 23 s later.
     */
    private suspend fun TestScope.pressedHarness(pressedHold: Duration): Idle {
        val timing = baseTiming.copy(
            liveAppendQuiet = 150.milliseconds,
            liveAppendReplyWait = 1_500.milliseconds,
            liveAnswerPressedHold = pressedHold,
            liveAnswerPressedAfter = 300.milliseconds,
        )
        val harness = LiveHarness(this, timing)
        val (events, socket) = connected(harness)
        return Idle(harness, socket, events)
    }

    @Test fun theAnswerTheUserIsWaitingOnGoesOutBeforeTheVoiceAgentFillsTheGap() = runTest {
        val idle = pressedHarness(pressedHold = 600.milliseconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Okay, and what are the rules to the game", startMilliseconds = 170_200, endMilliseconds = 171_260))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 171_260))
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Let me check.", startMilliseconds = 172_540, endMilliseconds = 173_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 172_540, endMilliseconds = 172_640))
        delay(100)
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        delay(500)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Wait, wait. Don't you have them? Didn't it tell you them already", startMilliseconds = 176_100, endMilliseconds = 178_740))
        delay(250)
        // The delegator's fresh answer lands before the voice agent's reply starts.
        val ready = now
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        waitUntil { idle.spoken().isNotEmpty() }
        assertTrue("Not held for the 1.5 s reply window: it is the reply", now - ready < 900.milliseconds)
        assertEquals(listOf(rulesAnswer), idle.spoken())
        assertTrue(socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == "del_rules" })
        harness.coordinator.end()
    }

    @Test fun anAnswerTheUserPressedForStopsTheVoiceAgentTalkingWithoutIt() = runTest {
        val idle = pressedHarness(pressedHold = 600.milliseconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Okay, and what are the rules to the game", startMilliseconds = 170_200, endMilliseconds = 171_260))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 171_260))
        delay(500)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Don't you have them?", startMilliseconds = 176_100, endMilliseconds = 178_740))
        delay(200)
        // The voice agent answers from an older result while the delegator works.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "I don't have them, what I got back was cut off", startMilliseconds = 180_160, endMilliseconds = 183_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 180_160, endMilliseconds = 180_260))
        delay(50)
        val ready = now
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        assertTrue("Briefly held: never cut a sentence off at once", idle.spoken().isEmpty())
        waitUntil { idle.spoken().isNotEmpty() }
        assertTrue(now - ready >= 400.milliseconds)
        assertEquals("Out while the voice agent still talks without it, not after it", listOf(rulesAnswer), idle.spoken())
        harness.coordinator.end()
    }

    /**
     * Without the user asking again, the voice agent's speech holds the
     * answer as before: an answer never cuts off a sentence it didn't need to.
     */
    @Test fun anAnswerNobodyPressedForWaitsForTheVoiceAgentToFinish() = runTest {
        val idle = pressedHarness(pressedHold = 300.milliseconds)
        val (harness, socket) = idle.harness to idle.socket
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What are the rules?", startMilliseconds = 170_200, endMilliseconds = 171_260))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_rules", target = "client", offsetMilliseconds = 171_260))
        delay(400)
        // A gasp the transcript tags is not the user asking again.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "[gasp", startMilliseconds = 171_700, endMilliseconds = 171_800))
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Let me look into that, and meanwhile here's a long aside", startMilliseconds = 172_000, endMilliseconds = 175_000))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 172_000, endMilliseconds = 172_100))
        delay(100)
        // A held progress note says less than the answer that follows it.
        harness.coordinator.noteProgress(callID = "del_rules", text = "Reading GAME ENGINE.")
        harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer")
        delay(700)
        assertTrue("Held while the voice agent speaks", idle.spoken().isEmpty())
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        waitUntil { idle.spoken().isNotEmpty() }
        assertEquals(listOf(rulesAnswer), idle.spoken())
        assertFalse("The progress note went stale behind its answer and was dropped", socket.commands.any { it is LiveCommand.AppendThinking })
        harness.coordinator.end()
    }

    @Test fun answerDelayWaitsOnlyForSpeechAndCapsTheVoiceAgentOnceTheUserPresses() {
        val t0 = Duration.ZERO
        val ms = { value: Int -> t0 + value.milliseconds }
        var gate = VoiceCallTiming.live.quietGate
        gate = gate.noteUserSpoke(t0)
        // Everything waits out the user's 2 s quiet gap, past the reply window.
        assertEquals(1_500.milliseconds, gate.delay(ms(500)))
        // The answer is that reply: the user's quiet gap only.
        assertEquals(1_800.milliseconds, gate.answerDelay(ms(200), readyAt = t0, pressed = false, pressedHold = 2.seconds))
        assertNull(gate.answerDelay(ms(2_000), readyAt = t0, pressed = false, pressedHold = 2.seconds))

        // The voice agent talks from 1 s: held, unless the user asked again,
        // then for at most `pressedHold` of that talk (not of the wait before it).
        gate = gate.noteAssistantSpeaking(true, ms(1_000))
        assertEquals(900.milliseconds, gate.answerDelay(ms(5_000), readyAt = t0, pressed = false, pressedHold = 2.seconds))
        assertEquals(900.milliseconds, gate.answerDelay(ms(1_500), readyAt = t0, pressed = true, pressedHold = 2.seconds))
        gate = gate.noteAssistantSpeaking(true, ms(2_000))
        assertEquals("One run of speech, from 1 s", 500.milliseconds, gate.answerDelay(ms(2_500), readyAt = t0, pressed = true, pressedHold = 2.seconds))
        assertNull(gate.answerDelay(ms(3_000), readyAt = t0, pressed = true, pressedHold = 2.seconds))
        // The user's own words still hold it.
        gate = gate.noteUserSpoke(ms(3_000))
        assertEquals(1_900.milliseconds, gate.answerDelay(ms(3_100), readyAt = t0, pressed = true, pressedHold = 2.seconds))

        // A tag isn't the user asking again; GPT-Live rarely closes one.
        assertFalse(LiveCallCoordinator.hasWords("[gasp"))
        assertFalse(LiveCallCoordinator.hasWords("[laughs] "))
        assertTrue(LiveCallCoordinator.hasWords("[gasp Okay, that's weird"))
        assertTrue(LiveCallCoordinator.hasWords("] Okay, and what are the rules"))
    }

    @Test fun recognizesSpokenClaimsOfActing() {
        for (claim in listOf(
            "Opening.", "Checking.", "I'm checking right now.", "Let me look into that.", "Got it, switching you now.",
            "On it.", "I'll take a look.", "Sure, opening OPENCODE IOS.", "Ooh, let's take a look.",
            "Let's check the upload test.", "Digging into it.",
        )) {
            assertTrue(claim, VoiceActionClaim.isClaim(claim))
        }
        // Natural reactions the prompt now invites, and suggestions, aren't claims.
        for (reply in listOf(
            "Sure, which one do you want?", "Hey! What's up?", "Let me know if you need anything.", "I'll let you know.",
            "Looking good.", "Okay.", "Checking the upload test took a while, and it eventually passed with two flaky retries.",
            "Yeah.", "Yeah, good call.", "Yeah, I heard you.", "Ooh, that's a weird one.", "Let's start with the tests.",
            "Let's stop there for today.", "Nice, let's see if that works.", "Huh, that's annoying.",
        )) {
            assertFalse(reply, VoiceActionClaim.isClaim(reply))
        }
    }

    /**
     * "Open code iOS" answered the voice agent's own question; it said
     * "Opening." and never delegated.
     */
    @Test fun theVoiceIsToldAClaimNeedsADelegationAndAnAnswerIsNoFragment() {
        val live = VoiceInstructions.buildLive(
            VoiceInstructions.Context(sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(), previousEndedAt = null, managesSessions = true),
        )
        assertTrue(live.contains("Nothing happens unless you delegate. If you say you're doing something, delegate it in that same turn; if you won't delegate, ask or answer instead."))
        assertTrue(live.contains("the answer completes their earlier request, so delegate that request now"))
        assertTrue(live.contains("A short answer to your own question, or a short report about earlier work, is not a fragment"))
    }

    private suspend fun TestScope.claimHarness(): Pair<LiveHarness, Pair<EventIterator, FakeLiveSocket>> {
        val timing = baseTiming.copy(liveUnbackedClaimWait = 150.milliseconds, liveAnswerPressedAfter = 50.milliseconds)
        val harness = LiveHarness(this, timing)
        return harness to connected(harness)
    }

    /**
     * Kit's call: GPT-Live said "Opening." to "Open code iOS" without
     * delegating, and nothing happened. The call delegates the request itself,
     * and its answer goes out without a delegation ID GPT-Live never made.
     */
    @Test fun aClaimWithoutADelegationIsDelegatedByTheCall() = runTest {
        val (harness, started) = claimHarness()
        val (events, socket) = started
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Open code iOS", final = true), events.next())
        delay(80)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Opening."), events.next())
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening."), events.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = text("Opening.")), events.next())
        assertTrue(harness.callLogMarkers.any { it.contains("said “Opening.” without delegating") })

        harness.coordinator.completeFunction(callID = "client_claim_1", output = "[COMPLETE] Opened OPENCODE IOS.")
        waitUntil { socket.commands.any { it is LiveCommand.AppendCommentary } }
        assertTrue(socket.commands.any {
            it is LiveCommand.AppendCommentary && it.delegationID == null &&
                it.content == "Result of the request you acknowledged with “Opening.”: Opened OPENCODE IOS."
        })
        harness.coordinator.end()
    }

    /**
     * A claim GPT-Live backed with its own delegation, or one with nothing
     * asked since the last delegation, is left alone.
     */
    @Test fun aBackedClaimIsNotDelegatedAgain() = runTest {
        val (harness, started) = claimHarness()
        val (events, socket) = started
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Open code iOS", final = true), events.next())
        delay(80)
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_open", target = "client", offsetMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_open", name = "delegate", arguments = obj()), events.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Opening."), events.next())
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening."), events.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, events.next())
        // Said again later with nothing new asked: still nothing to delegate.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking.", startMilliseconds = 5_000, endMilliseconds = 5_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Checking."), events.next())
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = "Checking."), events.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, events.next())
        delay(300)
        assertFalse(harness.callLogMarkers.any { it.contains("without delegating") })
        harness.coordinator.end()
        while (true) {
            val event = events.next() ?: break
            if (event is VoiceCallEvent.FunctionCall) throw AssertionError("Delegated again: $event")
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
        val (harness, started) = claimHarness()
        val (events, socket) = started
        val received = mutableListOf<VoiceCallEvent>()
        val consumer = launch { while (true) received += events.next() ?: break }
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Make Dark Shroud the default", startMilliseconds = 1_000, endMilliseconds = 1_800))
        delay(80)
        if (!acknowledgedFirst) socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_shroud", target = "client", offsetMilliseconds = 1_800))
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 2_000, endMilliseconds = 2_100))
        if (acknowledgedFirst) {
            delay(20)
            socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_shroud", target = "client", offsetMilliseconds = 1_800))
        }
        delay(100)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Maybe a little wobble too", startMilliseconds = 2_200, endMilliseconds = 3_000))
        delay(80)
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        waitUntil { received.contains(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking.")) }
        delay(300)
        assertFalse(harness.callLogMarkers.any { it.contains("without delegating") })
        val delegations = received.filterIsInstance<VoiceCallEvent.FunctionCall>()
        assertEquals("acknowledgedFirst: $acknowledgedFirst", listOf(VoiceCallEvent.FunctionCall(callID = "del_shroud", name = "delegate", arguments = obj())), delegations)
        harness.coordinator.end()
        consumer.cancel()
    }

    /**
     * Words the user said before the claim, transcribed after it started,
     * are still what it claims to act on.
     */
    @Test fun aClaimOnWordsTranscribedLateIsStillDelegated() = runTest {
        val (harness, started) = claimHarness()
        val (events, socket) = started
        val received = mutableListOf<VoiceCallEvent>()
        val consumer = launch { while (true) received += events.next() ?: break }
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        waitUntil { received.any { it is VoiceCallEvent.FunctionCall } }
        assertTrue(received.contains(VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = text("Opening."))))
        harness.coordinator.end()
        consumer.cancel()
    }

    @Test fun accumulatesTranscriptDeltasFlushesOnBargeInAndForwardsDelegationUpdates() = runTest {
        val harness = LiveHarness(this)
        val (events, socket) = connected(harness)

        // User transcript deltas grow a single utterance until delegation.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Can you", startMilliseconds = 100, endMilliseconds = 300))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Can you", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " check the upload test?", startMilliseconds = 300, endMilliseconds = 700))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Can you check the upload test?", final = true), events.next())

        // Client delegation carries only the delegation ID; task text comes from the accumulated transcript.
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 700))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())

        // Progress and completion use session.thinking.append and session.commentary.append with the delegation ID.
        harness.coordinator.noteProgress(callID = "item_del_1", text = "Running upload tests.")
        harness.coordinator.completeFunction(callID = "item_del_1", output = "[COMPLETE] The upload test passed.")

        assertTrue(socket.commands.any { it is LiveCommand.AppendThinking && it.delegationID == "item_del_1" && it.content == "Running upload tests." })
        assertTrue(socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == "item_del_1" && it.content == "The upload test passed." })

        // Overlapping full-duplex speech: user audio and transcript deltas stream
        // while the server keeps sending assistant audio and transcript deltas.
        // The client must not flush playback during overlap; it stops only when
        // the server signals it stopped or cut off.
        harness.audioLevels.updateUser(speechPCM)
        harness.capture(speechPCM)

        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Spinach, carrot", startMilliseconds = 900, endMilliseconds = 1_100))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Spinach, carrot"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 900, endMilliseconds = 1_000))
        waitUntil { harness.enqueues == 1 }

        // User speaks simultaneously while the assistant continues speaking.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Start now even though I am talking", startMilliseconds = 1_000, endMilliseconds = 1_400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Start now even though I am talking", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = ", tomato, broccoli", startMilliseconds = 1_100, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = ", tomato, broccoli"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_100, endMilliseconds = 1_200))
        waitUntil { harness.enqueues == 2 }

        // The shared meters aren't checked here: other suites' harnesses reset
        // them in parallel, which made this test flaky. Not flushing is the guarantee.
        assertEquals("Never flush assistant playback just because the user is speaking", 0, harness.flushes)

        // Server signals a speech cut-off: now the utterance settles where the server stopped and playback flushes.
        socket.yieldEvent(LiveServerEvent.Error(LiveServerError(message = "Assistant output audio cut off.", code = "output_audio_cut_off", type = "moderation_error")))
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Spinach, carrot, tomato, broccoli"), events.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, events.next())
        assertEquals(1, harness.flushes)
        assertEquals(0f, harness.audioLevels.snapshot().assistant)

        // Mute and unmute send GPT-Live's input audio control commands.
        harness.coordinator.setMuted(true)
        harness.coordinator.setMuted(false)
        assertTrue(socket.commands.any { it is LiveCommand.Mute })
        assertTrue(socket.commands.any { it is LiveCommand.Unmute })

        harness.coordinator.end()
    }

    @Test fun rotationAndExpirySeedReplacementWithSessionInputAndDeliverOrphanCommentary() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 0.05,
                forceRotateAfter = 30.0,
                retryDelays = listOf(0.01, 0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 20.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ),
        )
        val events = EventIterator(harness.start(), backgroundScope)
        val first = harness.nextOpened()

        first.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())
        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Why is the upload test flaky?", startMilliseconds = 100, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Why is the upload test flaky?", final = true), events.next())
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 500))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())

        // Standby opens seeded with `session.input` containing the developer note and user turn.
        val second = harness.nextOpened()
        assertTrue(second.config.input.any { it.role == LiveInputMessage.Role.developer && it.text.contains("One request you delegated is still running") })
        assertTrue(second.config.input.contains(LiveInputMessage(LiveInputMessage.Role.user, "Why is the upload test flaky?")))

        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        waitUntil { first.closed }
        assertTrue(first.closed)
        assertEquals(listOf("voice_rotation_swapped"), harness.voiceEvents)

        // Result for `item_del_1` (created on `first`) arrives after the swap: sent as session-wide commentary (`delegation_id: null`).
        harness.coordinator.completeFunction(callID = "item_del_1", output = "[COMPLETE] It races the progress callback.")
        assertTrue(second.commands.any {
            it is LiveCommand.AppendCommentary && it.delegationID == null && it.content.contains("It races the progress callback.")
        })

        // Now simulate `session.closed` with reason `expired`: reconnects to a third socket.
        second.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 7_200))
        assertEquals(VoiceCallEvent.Reconnecting, events.next())
        val third = harness.nextOpened()
        third.yieldEvent(LiveServerEvent.SessionStarted(id = "live_3", expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, events.next())
        assertEquals(listOf("voice_rotation_swapped", "voice_session_expired", "voice_socket_reconnected"), harness.voiceEvents)

        harness.coordinator.end()
    }

    /**
     * Replays Kit's call (Oct 19): after a network switch, "Um... Could pick a
     * gymnasium Thanks", said over the HOME result, came back from the voice
     * agent as "an unrelated note here about picking a gym". His words reach
     * the model only as his own: live audio, `user` turns in a seed, or a
     * catch-up transcript that says so; never inside a result or a note.
     */
    @Test fun replayedGymCallKeepsTheUsersWordsTheirOwnAcrossReconnects() = runTest {
        val harness = LiveHarness(this)
        val (events, first) = connected(harness)

        val request = "You cuts my wife, letting her know that I'm going to the gym and just, uh, make sure you use, I think we have a tool that lets you get notified if there's a response. You can tell me"
        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = request, startMilliseconds = 99_000, endMilliseconds = 108_000))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = request, final = true), events.next())
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_home", target = "client", offsetMilliseconds = 108_000))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_home", name = "delegate", arguments = obj()), events.next())
        val acknowledgement = "Yeah, okay. I can have that ready for you, just a second."
        first.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = acknowledgement, startMilliseconds = 108_200, endMilliseconds = 114_000))
        events.skipUntil(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = acknowledgement))

        // t≈116.8 s: the phone moved to cellular.
        first.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)
        val second = harness.nextOpened()
        assertTrue(second.config.input.contains(LiveInputMessage(LiveInputMessage.Role.user, request)))
        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        events.skipUntil(VoiceCallEvent.Reconnected)

        // t≈120.8 s: the delegator's answer, now a result from the retired session.
        val answer = "Started that in HOME, including checking for reply notifications. The text hasn’t been confirmed sent yet."
        harness.coordinator.completeFunction(callID = "item_home", output = "[COMPLETE] $answer")
        waitUntil { second.commentary().any { it.second.contains(answer) } }
        assertEquals(listOf(LiveCallCoordinator.earlierConnectionLead + answer), second.commentary().map { it.second })

        // t≈121–125 s: the user talks over the reply.
        val overlap = "Um... Could pick a gymnasium Thanks"
        second.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Yep, I've started that in HOME.", startMilliseconds = 500, endMilliseconds = 3_000))
        second.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = overlap, startMilliseconds = 2_000, endMilliseconds = 4_300))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = overlap, final = true))

        // Another switch: the next session is seeded with his words as his.
        second.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)
        val third = harness.nextOpened()
        val seed = third.config.input
        assertTrue(seed.contains(LiveInputMessage(LiveInputMessage.Role.user, overlap)))
        assertFalse(seed.any { it.role != LiveInputMessage.Role.user && it.text.contains("gymnasium", ignoreCase = true) })
        val header = seed.firstOrNull() ?: throw AssertionError("Expected a seed header")
        assertEquals(LiveInputMessage.Role.developer, header.role)
        assertTrue("The real result, without its delivery lead", header.text.contains("- $answer"))
        assertFalse(header.text.contains("gym"))

        // Words said before the new session confirms reach it as a transcript of his words.
        val followUp = "Picking a gym, what do you mean"
        delay(50)
        third.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = followUp, startMilliseconds = 100, endMilliseconds = 1_000))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-3", text = followUp, final = true))
        third.yieldEvent(LiveServerEvent.SessionStarted(id = "live_3", expiresAt = null))
        waitUntil { third.appends().any { it.first == "thinking" } }
        val catchUp = third.appends().firstOrNull { it.first == "thinking" } ?: throw AssertionError("Expected a catch-up transcript")
        assertEquals(catchUpLead + "\nUser: $followUp", catchUp.second)
        assertTrue(harness.callLogMarkers.contains("Sent catch-up transcript to the voice agent on the new session (1 line)"))

        // Nothing the model was given puts his words in a result or a note.
        for (socket in listOf(first, second, third)) {
            for (append in socket.appends().filter { it.second.contains("gym", ignoreCase = true) }) {
                assertTrue("${append.first}: ${append.second}", append.first == "thinking" && append.second.startsWith(catchUpLead))
            }
            assertFalse(socket.commentary().any { it.second.contains("gym", ignoreCase = true) })
        }
        harness.coordinator.end()
    }

    /**
     * Replays Kit's call (Oct 19) after the gym note: a switch cut "…it'll
     * lock" off mid-sentence and the next session, seeded with it as its own
     * turn, finished it and riffed on his son's "Bye, Keanu" ("kids say stuff…
     * I can say bye to him"). After "I was talking to somebody else… Catch me
     * up" and another switch, it repeated those lines word for word: its own
     * replies were all it knew of the call. A cut-off reply is seeded as cut
     * off, never as a turn to finish, and the seed lists the real results.
     */
    @Test fun replayedKeanuCallSeedsRealResultsAndNeverAReplyToFinish() = runTest {
        val timing = VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01, 0.01, 0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 1.seconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(liveResultMaxHold = 200.milliseconds)
        val harness = LiveHarness(this, timing)
        val (events, first) = connected(harness)

        // t≈195 s: talking to his son, not to the voice agent.
        val keanu = "[laugh Hey. No, no, right. Cool. Yeah, my son was saying your name left right after you passed. He's like, Goodbye, Keanu"
        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = keanu, startMilliseconds = 1_000, endMilliseconds = 8_000))
        first.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "I didn't actually hear it, but, I'm sure it was.", startMilliseconds = 9_000, endMilliseconds = 11_000))

        // t≈215.6 s: OPENCODE IOS replies to the feedback request.
        val reply = "Got it. There was no note about a gym. The voice agent made that up, and I've got an agent tracing exactly where it came from."
        val notice = VoiceHandoff.earlierRequest(
            "The session “OPENCODE IOS” finished the work you started there. Tell the user only what's new since you last spoke about it; don't repeat the request or anything you already said, and if nothing is new, say nothing. Its reply: $reply",
        )
        harness.coordinator.announce(notice, sessionID = "ses_ios", promptIDs = listOf("msg_ios"))
        waitUntil { first.commentary().any { it.second == notice } }
        assertEquals(listOf(notice), first.commentary().map { it.second })

        // t≈216–227 s: its reply, cut off by the next network switch.
        val cutOff = "Here's the result. There was never a gym note, and I made it up. The session's now tracing exactly where that came from, and it'll lock"
        first.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = cutOff, startMilliseconds = 20_000, endMilliseconds = 31_000))
        events.skipUntil(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = cutOff))
        first.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)

        val second = harness.nextOpened()
        val firstSeed = second.config.input
        assertFalse("Seeded as its own turn, it gets finished", firstSeed.any { it.role == LiveInputMessage.Role.assistant && it.text.contains("it'll lock") })
        assertTrue(firstSeed.contains(LiveInputMessage(
            LiveInputMessage.Role.developer,
            "Your reply here was cut off; the user heard only part of: “$cutOff”. Don't finish it or say it again.",
        )))
        assertTrue(firstSeed.contains(LiveInputMessage(LiveInputMessage.Role.assistant, "I didn't actually hear it, but, I'm sure it was.")))
        val firstHeader = firstSeed.firstOrNull()?.text ?: throw AssertionError("Expected a seed header")
        assertTrue(firstHeader.startsWith(LiveCallCoordinator.seedHeader))
        assertTrue(firstHeader.contains("- “OPENCODE IOS” replied: $reply"))
        assertFalse("Results, not delivery directions", firstHeader.contains(VoiceHandoff.earlierRequestLead.trim(' ', '\t')))
        assertFalse(firstHeader.contains("Keanu"))

        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        events.skipUntil(VoiceCallEvent.Reconnected)
        // t≈229.6 s: what the old seed got: it finished the sentence and riffed.
        val riff = "that down. Anyway, yeah, kids say stuff and it just lands that way. I can say \"bye\" to him for real right now if he's around."
        second.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = riff, startMilliseconds = 500, endMilliseconds = 11_000))
        events.skipUntil(VoiceCallEvent.AssistantDone(itemID = "live-a-3", text = riff))
        // t≈275.5 s: his correction.
        val correction = "Okay, sorry. Um, I was talking to somebody else. That's what the... Keanu stuff was about, um. What were- I messed everything up. So what- what did you do? Catch me up"
        second.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = correction, startMilliseconds = 47_000, endMilliseconds = 54_500))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = correction, final = true))
        // t≈283.7 s: back to Wi-Fi, right after "Catch me up".
        second.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)

        val third = harness.nextOpened()
        val secondSeed = third.config.input
        val header = secondSeed.firstOrNull()?.text ?: throw AssertionError("Expected a seed header")
        assertTrue(header.contains("never finish or repeat an earlier reply of yours, and when the user corrected you, the correction stands"))
        assertTrue(header.contains("They are the only work you can report; a recap lists these and nothing else:\n- “OPENCODE IOS” replied: $reply"))
        assertEquals("His correction is his own last word", LiveInputMessage(LiveInputMessage.Role.user, correction), secondSeed.last())
        assertEquals(listOf(LiveInputMessage.Role.developer), secondSeed.filter { it.text.contains("it'll lock") }.map { it.role })
        assertFalse(secondSeed.any { it.role == LiveInputMessage.Role.developer && it.text.contains("kids say stuff") })
        assertTrue("Nothing redelivered: the result went out once", second.appends().isEmpty() && third.appends().isEmpty())
        harness.coordinator.end()
    }

    @Test fun resultRecordKeepsResultsWithoutTheirDeliveryDirections() {
        var record = VoiceResultRecord()
        record = record.record(LiveCallCoordinator.earlierConnectionLead + "Started that in HOME.")
        record = record.record("Result of the request you acknowledged with “Opening.”: Opened OPENCODE IOS.")
        record = record.record(VoiceHandoff.earlierRequest("The session “Blog” finished the work you started there without a text reply. Tell the user briefly that “Blog” finished its steps."))
        record = record.record("Sent that to the OPENCODE IOS session.")
        record = record.record("Sent that to the OPENCODE IOS session.")
        assertEquals(
            listOf(
                "Started that in HOME.",
                "Opened OPENCODE IOS.",
                "The session “Blog” finished the work you started there without a text reply.",
                "Sent that to the OPENCODE IOS session.",
            ),
            record.entries,
        )
        for (index in 0 until 10) record = record.record("Result $index. " + "x".repeat(400))
        assertEquals(VoiceResultRecord.maxEntries, record.entries.size)
        assertTrue(record.entries.all { it.length <= VoiceResultRecord.entryLimit })
        assertTrue(record.entries.last().startsWith("Result 9."))
    }

    /**
     * Every engine is told what it hears is the user's, unclear speech gets
     * "Sorry, I didn't catch that", background speech gets silence, a
     * correction stands, and a recap is only real results.
     */
    @Test fun everyEngineIsToldWhatItHearsIsTheUsersOwnAndNeverANote() {
        val context = VoiceInstructions.Context(sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(), previousEndedAt = null, managesSessions = true)
        val section = VoiceInstructions.whatYouHearSection
        for (prompt in listOf(VoiceInstructions.buildLive(context), VoiceInstructions.build(context, engine = VoiceEngine.realtime), VoiceInstructions.build(context, engine = VoiceEngine.grok))) {
            assertTrue(prompt.contains(section))
        }
        assertTrue(section.contains("It is never a note, a message, or part of a result: never call it one."))
        assertTrue(section.contains("Never say you got a note, message, or result that isn't in one, and never guess what one means."))
        assertTrue(section.contains("say \"Sorry, I didn't catch that.\""))
        assertTrue(section.contains("get no reply at all: keep listening. No greeting back, no laugh"))
        assertTrue(section.contains("the correction replaces your earlier take"))
        assertTrue(section.contains("lists only the requests you delegated and their actual results"))
        assertTrue(section.contains("No emotional probing"))
        assertTrue(VoiceDelegator.instructions.contains("Never mention a note, message, or content that isn't in a tool result"))
        assertTrue(VoiceDelegator.instructions.contains("reply only \"Sorry, I didn't catch that.\""))
    }

    @Test fun replacementsThatFailBeforeConfirmingUseUpRetriesAndFirstSessionFailureFailsTheCall() = runTest {
        val unconfirmed = LiveHarness(this)
        val unconfirmedEvents = EventIterator(unconfirmed.start(), backgroundScope)
        val initial = unconfirmed.nextOpened()
        initial.finish(NetworkConnectionLost())
        val startError = runCatching { unconfirmedEvents.next() }.exceptionOrNull()
        assertTrue("Expected the network connection lost: $startError", startError is NetworkConnectionLost)
        delay(30)
        assertEquals(1, unconfirmed.sockets.size)
        assertEquals(listOf("voice_connect_failed"), unconfirmed.voiceEvents)

        val harness = LiveHarness(this)
        val (events, first) = connected(harness)
        first.finish(NetworkConnectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, events.next())

        // Each replacement connects, then fails before `session.started`.
        repeat(3) {
            val socket = harness.nextOpened()
            socket.finish(NetworkConnectionLost())
        }
        val ended = runCatching { events.next() }.exceptionOrNull()
        assertTrue("Expected the call to drop: $ended", ended is RealtimeClientError.Dropped)
        assertEquals(4, harness.sockets.size)
        assertEquals(
            listOf("voice_socket_dropped", "voice_connect_failed", "voice_connect_failed", "voice_connect_failed", "voice_reconnect_exhausted"),
            harness.voiceEvents,
        )
    }

    @Test fun aResultFinishingWhileALostSocketClosesIsKeptForTheNextSession() = runTest {
        val harness = LiveHarness(this)
        val (events, first) = connected(harness)
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 100))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())
        first.holdsClose = true
        first.finish(NetworkConnectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, events.next())

        waitUntil { first.isHeldClosing }
        harness.coordinator.completeFunction(callID = "item_del_1", output = "[COMPLETE] Fixed it.")

        val second = harness.nextOpened()
        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, events.next())
        assertTrue(waitUntil {
            second.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == null && it.content.contains("Fixed it.") }
        })

        first.releaseClose()
        harness.coordinator.end()
    }

    @Test fun lateTranscriptDeltasAfterDelegationSettleUpdateSameUserCaptionAndAudioInterruptionSuppressesPlayback() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = 25.milliseconds,
                liveTranscriptSettle = 40.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ),
        )
        val (events, socket) = connected(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check why the upload test", startMilliseconds = 0, endMilliseconds = 800))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 800))

        // Wait for `liveDelegationSettle` to fire `emitDelegation`.
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())

        // Near-contiguous trailing delta with a 10 ms gap (`start_ms == 810` after `end_ms == 800`,
        // `gap == 10 <= 200`) arrives after the settle window: it must update `live-u-1` in place
        // rather than splitting into `live-u-2`.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " is failing in OpenCodeTests.", startMilliseconds = 810, endMilliseconds = 1_200))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test is failing in OpenCodeTests.", final = true), events.next())

        // A follow-up spoken 400 ms later (`start_ms == 1600 > 1200 + 200`), even before
        // the assistant speaks, starts a new utterance `live-u-2`.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Also check the retry test.", startMilliseconds = 1_600, endMilliseconds = 2_000))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Also check the retry test.", final = true), events.next())

        // A 1-byte (`pcm.count == 1`) audio buffer is ignored and never enqueues.
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = byteArrayOf(1), startMilliseconds = 2_000, endMilliseconds = 2_010))
        delay(10)
        assertEquals(0, harness.enqueues)

        // Brief buffer drain mid-sentence does not split one assistant reply into two bubbles.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking", startMilliseconds = 2_100, endMilliseconds = 2_200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Checking"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = byteArrayOf(1, 2), startMilliseconds = 2_100, endMilliseconds = 2_200))
        waitUntil { harness.enqueues == 1 }
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " now.", startMilliseconds = 2_200, endMilliseconds = 2_300))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = " now."), events.next())

        // Audio interruption settles the live assistant utterance immediately so it isn't stuck open.
        val enqueuesBefore = harness.enqueues
        harness.audioEvent(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking now."), events.next())
        assertEquals(VoiceCallEvent.AudioInterrupted, events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = byteArrayOf(1, 2), startMilliseconds = 2_300, endMilliseconds = 2_400))
        delay(20)
        assertEquals(enqueuesBefore, harness.enqueues)
        harness.audioEvent(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, events.next())

        harness.coordinator.end()
    }

    private fun acknowledgementTiming(liveTranscriptSettle: Duration, liveAcknowledgementWait: Duration) = VoiceCallTiming(
        rotateAfter = 3_000.0,
        forceRotateAfter = 3_500.0,
        retryDelays = listOf(0.01),
        liveDelegationSettle = Duration.ZERO,
        liveTranscriptSettle = liveTranscriptSettle,
        liveAcknowledgementWait = liveAcknowledgementWait,
    )

    @Test fun delegationWaitsForTheSpokenAcknowledgementToLabelIt() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 5.seconds))
        val (events, socket) = connected(harness)

        // The agent is still finishing an earlier reply when the user's request is delegated.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "The build is green", startMilliseconds = 0, endMilliseconds = 200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "The build is green"), events.next())
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 300, endMilliseconds = 900))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "client", offsetMilliseconds = 900))

        // That earlier reply finishing is not the acknowledgement: it started before the delegation.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = ".", startMilliseconds = 200, endMilliseconds = 300))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "."), events.next())
        harness.audioEvent(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "The build is green."), events.next())
        assertEquals(VoiceCallEvent.AudioInterrupted, events.next())
        harness.audioEvent(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, events.next())

        // The acknowledgement labels the delegation once its sentence ends.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " Checking the", startMilliseconds = 1_100, endMilliseconds = 1_300))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = " Checking the"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " upload test.", startMilliseconds = 1_300, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = " upload test."), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = text("Checking the upload test.")), events.next())
        harness.coordinator.end()
    }

    @Test fun delegationGoesWithoutALabelWhenTheAcknowledgementIsLate() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 50.milliseconds))
        val (events, socket) = connected(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 0, endMilliseconds = 600))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "client", offsetMilliseconds = 600))
        // Started, but its sentence doesn't end within the wait.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking the", startMilliseconds = 800, endMilliseconds = 1_000))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Checking the"), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = obj()), events.next())
        // Finishing later labels nothing again.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " upload test.", startMilliseconds = 1_000, endMilliseconds = 1_200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = " upload test."), events.next())
        harness.coordinator.end()
    }

    /**
     * A move to another session while a delegation waits for its
     * acknowledgement keeps it: the conversation is the call's, so the
     * request goes out labelled as usual and nobody hears it wasn't sent.
     */
    @Test fun movingSessionsMidWaitKeepsTheDelegation() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 5.seconds))
        val (events, socket) = connected(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 0, endMilliseconds = 600))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_a", target = "client", offsetMilliseconds = 600))
        delay(20)

        harness.coordinator.updateInstructions(full = "Attached to B.", delta = "${VoiceInstructions.retargetPrefix} You are attached to B.")
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking the upload test.", startMilliseconds = 800, endMilliseconds = 1_000))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Checking the upload test."), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_a", name = "delegate", arguments = text("Checking the upload test.")), events.next())
        assertFalse(socket.commands.any { it is LiveCommand.AppendThinking && it.content.contains("wasn't sent") })
        harness.coordinator.end()
    }

    /**
     * Delegations go out in the order they were made, each before any words
     * that came after it: a waiting one goes unlabelled when the next
     * delegation settles or the user starts a new turn, so the call captures
     * only its own words.
     */
    @Test fun backToBackDelegationsKeepTheirOrderAndTheirOwnWords() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 5.seconds))
        val (events, socket) = connected(harness)

        // Two delegations in quick succession.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Run the tests", startMilliseconds = 0, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Run the tests", final = true), events.next())
        val started = now
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "client", offsetMilliseconds = 500))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_2", target = "client", offsetMilliseconds = 520))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = obj()), events.next())

        // A new turn while del_2 waits: del_2 goes before its words.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "And the lint", startMilliseconds = 1_500, endMilliseconds = 2_000))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_2", name = "delegate", arguments = obj()), events.next())
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "And the lint", final = true), events.next())
        assertTrue("Neither waited out the acknowledgement", now - started < 2.seconds)

        // The next one still gets its acknowledgement.
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_3", target = "client", offsetMilliseconds = 2_000))
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Running the lint.", startMilliseconds = 2_200, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Running the lint."), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_3", name = "delegate", arguments = text("Running the lint.")), events.next())
        harness.coordinator.end()
    }

    /**
     * Abandoning a delegation that's still waiting for its acknowledgement
     * drops it too.
     */
    @Test fun abandoningADelegationMidWaitDropsIt() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 80.milliseconds))
        val (events, socket) = connected(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 0, endMilliseconds = 600))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_a", target = "client", offsetMilliseconds = 600))
        delay(20)
        harness.coordinator.abandonDelegation(callID = "del_a")
        delay(150)
        // Past its deadline: had it survived, it would come before this.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Okay.", startMilliseconds = 2_000, endMilliseconds = 2_200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Okay."), events.next())
        harness.coordinator.end()
    }

    @Test fun finishStopsAudioBeforeSocketCloseAndMultiChunkFallbackAndStandbyGuardsWork() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 0.04,
                forceRotateAfter = 30.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 20.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ),
        )
        val events = EventIterator(harness.start(), backgroundScope)
        val first = harness.nextOpened()

        first.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        // Standby opens; before it confirms, `updateInstructions(full:delta:)` updates `config.instructions`
        // to the full prompt while sending only the concise delta, then `sessionClosed` discards the standby.
        val deadStandby = harness.nextOpened()
        harness.coordinator.updateInstructions(
            full = "Full replacement instructions for future session.start.",
            delta = "Session changed. Short delta.",
        )
        assertTrue(first.commands.any { it is LiveCommand.AppendInstructions && it.delegationID == null && it.content == "Session changed. Short delta." })
        deadStandby.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        waitUntil { deadStandby.closed }
        assertTrue("A standby that closes before promotion is discarded immediately", deadStandby.closed)

        // Multi-chunk commentary where chunk 1 succeeds and chunk 2 throws:
        // only chunk 2 is queued in `undelivered` for the replacement session.
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_multi", target = "client", offsetMilliseconds = 100))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_multi", name = "delegate", arguments = obj()), events.next())
        first.failOnCommentaryIndex = 1
        val chunk1 = "First chunk words. ".repeat(72)
        val chunk2 = "Second chunk distinct conclusion."
        harness.coordinator.completeFunction(callID = "del_multi", output = "[COMPLETE] $chunk1\n\n$chunk2")
        val commentaryMarks = { harness.callLogMarkers.filter { it.startsWith("Sent commentary") } }
        assertEquals("Only the chunk the socket took is marked sent", 1, commentaryMarks().size)

        // Trigger reconnect and verify `second` gets the FULL instructions in `session.start`
        // and only chunk 2 (not chunk 1's tail) in `session.commentary.append`.
        first.holdsClose = true
        first.finish(NetworkConnectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, events.next())
        val second = harness.nextOpened()
        assertEquals("Full replacement instructions for future session.start.", second.config.instructions)
        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, events.next())
        waitUntil { second.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == null && it.content.contains(chunk2) } }
        val secondCommentary = second.spoken()
        assertEquals(1, secondCommentary.size)
        assertTrue(secondCommentary.first().contains(chunk2))
        assertFalse("Chunk 1 already succeeded and must not be re-sent", secondCommentary.first().contains("First chunk words."))
        val drainMark = "Sent 1 undelivered append to the voice agent on the new session"
        waitUntil { harness.callLogMarkers.contains(drainMark) }
        assertTrue(harness.callLogMarkers.contains(drainMark))
        first.releaseClose()

        // `end()` stops audio and finishes the event stream even while `second.close()` is held open.
        second.holdsClose = true
        val endTask = launch { harness.coordinator.end() }
        waitUntil { harness.audioStopped == 1 }
        assertEquals("Microphone and speaker stop before socket close completes", 1, harness.audioStopped)
        assertNull("Event stream finishes before socket close completes", events.next())
        second.releaseClose()
        endTask.join()
    }

    companion object {
        private const val catchUpLead = "Transcript of what was said while the call switched connections, for context. User lines are the user's own words spoken to you, not a note or a result:"

        private const val rulesAnswer = "One player is the giant desk spider, and up to four others are roly-poly pillbugs. The pillbugs win by stealing four of the five golden crumbs and bringing them back to their matchbox nest."

        /** 240 samples of 8,000 (little-endian Int16). */
        private val speechPCM: ByteArray = ByteArray(480) { if (it % 2 == 0) 0x40 else 0x1F }
    }
}

/** Port of iOS `LiveRTCTests`. */
class LiveRTCTests {
    @Test fun sessionRequestLeavesTheAudioFormatToSDPAndPreNegotiatesTheEventChannel() {
        val config = LiveSessionConfig(instructions = "Be concise.", input = listOf(LiveInputMessage(LiveInputMessage.Role.user, "Hi")))
        val json = decodeJSON(LiveRTC.requestBody(config = config, sdp = "v=0\r\n"))
        assertEquals(RealtimeJSONs.string("webrtc"), json["transport"]?.get("type"))
        assertEquals(RealtimeJSONs.string("v=0\r\n"), json["transport"]?.get("sdp"))
        assertEquals(RealtimeJSONs.number(4), json["transport"]?.get("dcid"))
        val session = json["session"]!!
        assertEquals(RealtimeJSONs.string("gpt-live-1"), session["model"])
        assertNull(session["audio"]?.get("format"))
        assertEquals(RealtimeJSONs.string("marin"), session["audio"]?.get("output")?.get("voice"))
        assertEquals(RealtimeJSONs.string("client"), session["delegation"]?.get("type"))
        assertNotNull(session["input"])
        assertNull("The HTTP request starts the session; no session.start", json["type"])
    }

    /**
     * One second later: `received` packets in, `lost` lost, `concealed` of
     * 48 000 samples concealed.
     */
    private fun step(
        last: LiveRTCLinkSample, received: Double = 50.0, lost: Double = 0.0, concealed: Double = 0.0,
        roundTrip: Double = 0.06, outboundLoss: Double = 0.0,
    ): LiveRTCLinkSample = LiveRTCLinkSample(
        path = "udp", roundTrip = roundTrip,
        packetsReceived = last.packetsReceived + received, packetsLost = last.packetsLost + lost,
        samplesReceived = last.samplesReceived + 48_000, samplesConcealed = last.samplesConcealed + concealed,
        jitter = 0.002, outboundLoss = outboundLoss,
    )

    @Test fun aCleanLinkStaysHealthy() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        repeat(20) {
            sample = step(sample, lost = 1.0, concealed = 400.0)
            assertNull(watch.add(sample))
        }
        assertFalse(watch.isPoor)
        assertTrue(watch.summary()?.startsWith("WebRTC · udp") == true)
    }

    @Test fun lossMakesItWeakAndThreeHealthySecondsRecoverIt() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        sample = step(sample, received = 40.0, lost = 10.0)
        assertNull("one lossy second is hidden by the jitter buffer", watch.add(sample))
        assertTrue("but the log still notes it", watch.summary()?.startsWith("⚠︎") == true)
        sample = step(sample, received = 40.0, lost = 10.0)
        assertEquals("two make it weak", true, watch.add(sample))
        sample = step(sample, received = 40.0, lost = 10.0)
        assertNull("reported once", watch.add(sample))
        sample = step(sample)
        assertNull(watch.add(sample))
        sample = step(sample)
        assertNull(watch.add(sample))
        sample = step(sample)
        assertEquals(false, watch.add(sample))
        assertFalse(watch.isPoor)
    }

    @Test fun concealmentAStalledDownlinkOursBeingLostOrALongRoundTripAreWeak() {
        val tester = LiveRTCLinkWatchTester()
        for (weaken in listOf<(LiveRTCLinkSample, LiveRTCLinkWatchTester) -> LiveRTCLinkSample>(
            { s, t -> t.step(s, concealed = 12_000.0) },
            { s, t -> t.step(s, outboundLoss = 0.2) },
            { s, t -> t.step(s, roundTrip = 1.2) },
        )) {
            val watch = LiveRTCLinkWatch()
            var sample = LiveRTCLinkSample()
            watch.add(sample)
            sample = weaken(sample, tester)
            assertNull("a lone bad second doesn't warn", watch.add(sample))
            sample = weaken(sample, tester)
            assertEquals(true, watch.add(sample))
        }
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        sample = step(sample, received = 0.0)
        assertNull("one quiet second is fine", watch.add(sample))
        sample = step(sample, received = 0.0)
        assertEquals("two are a stalled downlink", true, watch.add(sample))
    }

    @Test fun blipsDontWarnButTwoBadSecondsOfThreeDo() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        repeat(5) {
            sample = step(sample, roundTrip = 1.2)
            assertNull(watch.add(sample))
            sample = step(sample)
            assertNull(watch.add(sample))
            sample = step(sample)
            assertNull("a blip every few seconds never warns", watch.add(sample))
        }
        sample = step(sample, received = 40.0, lost = 10.0)
        assertNull(watch.add(sample))
        sample = step(sample)
        assertNull(watch.add(sample))
        sample = step(sample, concealed = 12_000.0)
        assertEquals("two bad seconds in three warn, whatever went wrong", true, watch.add(sample))
    }

    @Test fun concealmentOverSilenceIsNotWeak() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        repeat(5) {
            val next = step(sample, concealed = 30_000.0)
            sample = next.copy(silentConcealedSamples = sample.silentConcealedSamples + 30_000)
            assertNull(watch.add(sample))
        }
        assertFalse(watch.isPoor)
    }

    @Test fun audibleConcealmentCountsWhenSomeIsOverSilence() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        for (expected in listOf<Boolean?>(null, true)) {
            val next = step(sample, concealed = 30_000.0)
            sample = next.copy(silentConcealedSamples = sample.silentConcealedSamples + 10_000)
            assertEquals("20,000 of 48,000 samples concealed over speech", expected, watch.add(sample))
        }
    }

    @Test fun aResetSilenceCounterDoesntLookLikeConcealment() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample(packetsReceived = 50.0, samplesReceived = 48_000.0, samplesConcealed = 40_000.0, silentConcealedSamples = 40_000.0)
        watch.add(sample)
        repeat(3) {
            sample = LiveRTCLinkSample(
                packetsReceived = sample.packetsReceived + 50, samplesReceived = sample.samplesReceived + 48_000,
                samplesConcealed = sample.samplesConcealed, silentConcealedSamples = 0.0,
            )
            assertNull(watch.add(sample))
        }
        assertFalse(watch.isPoor)
    }

    @Test fun aQuietSecondDoesntCountTowardRecovery() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        sample = step(sample, received = 40.0, lost = 10.0)
        watch.add(sample)
        sample = step(sample, received = 40.0, lost = 10.0)
        assertEquals(true, watch.add(sample))
        for (received in listOf(50.0, 50.0, 0.0, 50.0, 50.0)) {
            sample = step(sample, received = received)
            assertNull("recovery needs 3 seconds that heard speech", watch.add(sample))
        }
        sample = step(sample)
        assertEquals(false, watch.add(sample))
    }

    @Test fun aDroppedLinkOrABackedUpEventChannelIsWeak() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        watch.transportDown = true
        sample = step(sample)
        assertEquals(true, watch.add(sample))
        watch.transportDown = false
        repeat(2) {
            sample = step(sample)
            assertNull(watch.add(sample))
        }
        sample = step(sample)
        assertEquals("3 healthy seconds after it reconnects", false, watch.add(sample))
        // Reconnected before the next sample: still weak until it has recovered.
        watch.transportLost()
        assertTrue(watch.isPoor)
        watch.transportDown = false
        repeat(2) {
            sample = step(sample)
            assertNull(watch.add(sample))
        }
        sample = step(sample)
        assertEquals(false, watch.add(sample))
        val other = LiveRTCLinkWatch()
        var second = LiveRTCLinkSample()
        other.add(second)
        second = step(second)
        assertEquals(true, other.add(second, channelBacklog = 100_000))
    }

    @Test fun onlyAnotherInterfaceCountsAsANetworkChange() {
        val watch = LiveRTCNetworkWatch()
        assertFalse("the first path is the session's", watch.update("wifi en0"))
        assertFalse(watch.update("wifi en0"))
        assertFalse("no network: WebRTC rides out short gaps", watch.update(null))
        assertFalse(watch.update("wifi en0"))
        assertTrue(watch.update("cellular pdp_ip0"))
        assertFalse(watch.update("cellular pdp_ip0"))
    }

    @Test fun aNetworkThatBlocksWebRTCUsesWebSocketsForTenMinutes() {
        val clock = Instant.ofEpochSecond(1_000_000)
        val choice = LiveTransportChoice(DateClient { clock })
        assertTrue(choice.prefersWebRTC)
        choice.webRTCFailed(at = clock)
        assertFalse(choice.prefersWebRTC)
        choice.webRTCConnected()
        assertTrue(choice.prefersWebRTC)
        choice.webRTCFailed(at = clock.minusMillis(((LiveTransportChoice.webSocketFor + 1) * 1_000).toLong()))
        assertTrue("tries WebRTC again after a while", choice.prefersWebRTC)
    }
}

/** Lets the weak-link cases share `LiveRTCTests.step` without capturing the suite. */
private class LiveRTCLinkWatchTester {
    fun step(last: LiveRTCLinkSample, concealed: Double = 0.0, roundTrip: Double = 0.06, outboundLoss: Double = 0.0): LiveRTCLinkSample =
        LiveRTCLinkSample(
            path = "udp", roundTrip = roundTrip,
            packetsReceived = last.packetsReceived + 50, packetsLost = last.packetsLost,
            samplesReceived = last.samplesReceived + 48_000, samplesConcealed = last.samplesConcealed + concealed,
            jitter = 0.002, outboundLoss = outboundLoss,
        )
}

/** Port of iOS `RealtimeRTCTests`. */
class RealtimeRTCTests {
    private val config = RealtimeSessionConfig(instructions = "Be brief.", tools = emptyList())

    @Test fun callRequestIsMultipartWithTheSessionAndPreNegotiatedChannel() {
        val request = DependencyValues.withValues(DependencyValues.testing().update { this[UuidClient] = UuidClient.incrementing() }) {
            RealtimeRTC.request(apiKey = "sk-test", config = config, sdp = "v=0")
        }
        assertEquals("https://api.openai.com/v1/realtime/calls?dcid=4", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        // OkHttp carries the content type on the body rather than as a header.
        assertTrue(request.body?.contentType()?.toString()?.startsWith("multipart/form-data; boundary=") == true)
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("name=\"sdp\"\r\n\r\nv=0\r\n"))
        assertTrue(body.contains("name=\"session\""))
        assertTrue(body.contains("\"model\":\"gpt-realtime-2.1\""))
        assertFalse("WebRTC negotiates the format in SDP", body.contains("audio/pcm"))
    }

    @Test fun updatesOverWebRTCLeaveTheAudioFormatOut() {
        val rtc = config.sessionUpdate(includeAudioFormat = false)
        assertNull(rtc["session"]?.get("audio")?.get("input")?.get("format"))
        assertNull(rtc["session"]?.get("audio")?.get("output")?.get("format"))
        assertEquals(RealtimeJSONs.string("marin"), rtc["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals(RealtimeJSONs.string("semantic_vad"), rtc["session"]?.get("audio")?.get("input")?.get("turn_detection")?.get("type"))
        assertEquals(RealtimeJSONs.string("audio/pcm"), config.sessionUpdate["session"]?.get("audio")?.get("input")?.get("format")?.get("type"))
    }

    private fun event(json: String): ByteArray = json.toByteArray()
    private val chunk = ByteArray(1_920) // 40 ms

    @Test fun speechIsWhatTheServiceSaysItIsSendingTaggedWithItsResponseAndItem() {
        val speech = RealtimeRTCSpeech()
        assertNull("silence before a reply is dropped", speech.event(chunk))
        speech.observe(event("""{"type":"response.created","response":{"id":"resp_1"}}"""))
        speech.observe(event("""{"type":"response.output_item.added","item":{"id":"item_1","type":"message"}}"""))
        speech.observe(event("""{"type":"output_audio_buffer.started","response_id":"resp_1"}"""))
        assertEquals(RealtimeServerEvent.OutputAudio(responseID = "resp_1", itemID = "item_1", pcm = chunk), speech.event(chunk))
        speech.observe(event("""{"type":"output_audio_buffer.stopped","response_id":"resp_1"}"""))
        var tail = 0
        while (speech.event(chunk) != null) tail += 1
        assertEquals("a short tail lets the last words through", RealtimeRTCSpeech.tailMilliseconds / 40, tail)
    }

    @Test fun aClearedBufferStopsAtOnce() {
        val speech = RealtimeRTCSpeech()
        speech.observe(event("""{"type":"output_audio_buffer.started","response_id":"resp_1"}"""))
        assertNotNull(speech.event(chunk))
        speech.observe(event("""{"type":"output_audio_buffer.cleared","response_id":"resp_1"}"""))
        assertNull(speech.event(chunk))
    }
}

/** Port of iOS `VoiceDelegatorRoundTests`. */
class VoiceDelegatorRoundTests {
    /**
     * Runs a request whose model asks for a wait every round until
     * `answerOnLastRound` lets it answer, returning the outcome and every input.
     */
    private suspend fun run(answerOnLastRound: Boolean): Pair<VoiceDelegator.Outcome, List<List<VoiceDelegator.InputItem>>> {
        val inputs = mutableListOf<List<VoiceDelegator.InputItem>>()
        val client = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
            inputs += input
            val round = inputs.size
            if (answerOnLastRound && input.contains(VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote))) {
                VoiceDelegator.Response(id = "r$round", calls = emptyList(), text = "The build is still running.")
            } else {
                VoiceDelegator.Response(id = "r$round", calls = listOf(VoiceDelegator.ToolCall(callID = "c$round", name = "wait_for_session", arguments = "{}")), text = "")
            }
        }
        val tools = VoiceDelegatorTools(
            run = { """{"finished":false,"latest_reply":""}""" },
            sent = { emptyList() },
            note = { },
        )
        val outcome = client.run(apiKey = "key", request = "Wait for the build.", previousResponseID = null, tools = tools)
        return outcome to inputs
    }

    /**
     * Many waits on a long job fit, and the last round answers with where
     * things stand rather than giving up.
     */
    @Test fun longWorkGetsManyRoundsAndTheLastOneAnswers() = runTest {
        val (outcome, inputs) = run(answerOnLastRound = true)
        assertTrue(VoiceDelegator.maxRounds >= 30)
        assertEquals(VoiceDelegator.maxRounds, inputs.size)
        assertEquals("The build is still running.", outcome.reply)
        assertEquals("r${VoiceDelegator.maxRounds}", outcome.responseID)
        // Only the last round is told to answer.
        assertTrue(inputs.dropLast(1).all { !it.contains(VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote)) })
        assertEquals(VoiceDelegator.InputItem.ToolOutput(callID = "c${VoiceDelegator.maxRounds - 1}", output = """{"finished":false,"latest_reply":""}"""), inputs.last().first())
        assertEquals(VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote), inputs.last().last())
    }

    @Test fun aRunThatNeverAnswersStopsAtTheCapSayingWorkContinues() = runTest {
        val (outcome, inputs) = run(answerOnLastRound = false)
        assertEquals(VoiceDelegator.maxRounds, inputs.size)
        assertTrue("Never chain onto unanswered calls", outcome.reply == VoiceDelegator.outOfRoundsReply && outcome.responseID == null)
    }
}

/**
 * Port of iOS `VoiceEnginePersistenceTests`. Its migration tests drive `AppFeature`
 * (owned by `:app`) and aren't ported here; the per-call engine test is the voice module's.
 */
class VoiceEnginePersistenceTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)

    @Test fun defaultsToGPTLiveAndPersistsSelectionAcrossCalls() = runTest {
        val shared = VoiceTestShared()
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        var voiceEngine = shared.get(VoiceSharedKeys.voiceEngine)
        assertEquals(VoiceEngine.gptLive, voiceEngine)
        assertEquals("GPT-Live", voiceEngine.title)
        assertEquals("gpt-live-1", voiceEngine.modelID)

        val startedConfigs = mutableListOf<RealtimeSessionConfig>()
        val verifiedModels = mutableListOf<String>()
        val startedKeys = mutableListOf<String>()
        val store = voiceTestStore(shared, { VoiceFeature.State(server, session) }) {
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, model -> verifiedModels += model })
            this[VoiceCallClient] = testVoiceCall(start = { key, config ->
                startedKeys += key
                startedConfigs += config
                emptyFlow()
            })
            this[VoiceWorkClient] = testVoiceWork()
        }
        store.exhaustive = false
        shared.set(SharedKeys.openAIAPIKey, "sk-test")

        store.send(VoiceFeature.Action.Start)
        store.receive(::isCallEnded)
        assertEquals(listOf("gpt-live-1"), verifiedModels)
        assertEquals(VoiceEngine.gptLive, startedConfigs.first().engine)
        assertEquals("gpt-live-1", startedConfigs.first().model)
        assertTrue(startedConfigs.first().tools.isEmpty())

        // Switching to Realtime persists and starts the next call with Realtime's model and tools.
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.realtime)
        voiceEngine = shared.get(VoiceSharedKeys.voiceEngine)
        assertEquals(VoiceEngine.realtime, voiceEngine)
        assertEquals("Realtime", voiceEngine.title)
        assertEquals("gpt-realtime-2.1", voiceEngine.modelID)

        store.send(VoiceFeature.Action.Start)
        store.receive(::isCallEnded)
        assertEquals(listOf("gpt-live-1", "gpt-realtime-2.1"), verifiedModels)
        assertEquals(VoiceEngine.realtime, startedConfigs.last().engine)
        assertEquals("gpt-realtime-2.1", startedConfigs.last().model)
        assertEquals(listOf("delegate", "end_voice_session"), startedConfigs.last().tools.map { it.name })

        // Grok Voice needs its own xAI key: the OpenAI one doesn't start it.
        assertEquals(listOf("sk-test", "sk-test"), startedKeys)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.grok)
        voiceEngine = shared.get(VoiceSharedKeys.voiceEngine)
        assertEquals("Grok Voice", voiceEngine.title)
        assertEquals(VoiceProvider.xAI, voiceEngine.provider)
        store.send(VoiceFeature.Action.Start)
        assertEquals(VoiceFeature.State.Phase.Failed("Add your xAI API key in Settings to use Voice."), store.state.phase)
        assertEquals(2, startedKeys.size)
        // iOS also checks `SessionFeature.State.isVoiceAvailable(enabled: true, apiKey:)` (owned by
        // :feature:session); the voice module's part is that Grok has no key from the OpenAI one.
        assertNull(voiceEngine.apiKey(openAI = "sk-test", xAI = null))

        shared.set(SharedKeys.xaiAPIKey, "xai-test")
        store.send(VoiceFeature.Action.Start)
        store.receive(::isCallEnded)
        assertEquals("grok-voice-3.0", verifiedModels.last())
        assertEquals(listOf("sk-test", "sk-test", "xai-test"), startedKeys)
        assertEquals(VoiceEngine.grok, startedConfigs.last().engine)
        assertEquals("grok-voice-3.0", startedConfigs.last().model)
        assertEquals("Grok delegates through tools, like Realtime", listOf("delegate", "end_voice_session"), startedConfigs.last().tools.map { it.name })
        assertEquals(false, store.state.usesDelegator)
        shared.set(SharedKeys.xaiAPIKey, null)

        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
    }
}

/** Port of iOS `LiveDelegationFeatureTests` (the `VoiceFeature` ones; see the report for the rest). */
class LiveDelegationFeatureTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)
    private val emptyArgs = RealtimeJSONs.obj()

    private fun callEvent(event: VoiceCallEvent) = VoiceFeature.Action.CallEvent(event)

    private fun TestScope.liveStore(
        shared: VoiceTestShared,
        dependencies: DependencyValues.Builder.() -> Unit,
    ): TestStore<VoiceFeature.State, VoiceFeature.Action> {
        val store = voiceTestStore(shared, { VoiceFeature.State(server, session) }, dependencies = dependencies)
        store.exhaustive = false
        shared.set(SharedKeys.openAIAPIKey, "sk-test")
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        return store
    }

    private suspend fun TestStore<VoiceFeature.State, VoiceFeature.Action>.connect(events: CallEvents) {
        send(VoiceFeature.Action.Start)
        events.yieldEvent(VoiceCallEvent.Connected)
        receive(::isCallEvent) { it.withPhase(VoiceFeature.State.Phase.Live) }
    }

    @Test fun clientDelegationBuildsPromptFromAccumulatedUserTranscriptsWithoutBlockingConversation() = runTest {
        val events = CallEvents()
        val releaseReply = Gate<String>()
        val submitted = mutableListOf<String>()
        val progress = mutableListOf<Pair<String, String>>()
        val completed = mutableListOf<Pair<String, String>>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                noteProgress = { callID, text ->
                    yield()
                    if (!currentCoroutineContext().isActive) return@testVoiceCall
                    progress += callID to text
                },
                completeFunction = { callID, output -> completed += callID to output },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    VoiceWorkAdmission(promptID = "msg_live_1")
                },
                awaitReply = { _, _, _ -> releaseReply.first() },
            )
        }
        store.connect(events)

        // User speaks across two utterances before GPT-Live delegates.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Can you check why the upload test is failing?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Which target?"))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "In OpenCodeTests.", final = true))
        store.receive(::isCallEvent)

        // GPT-Live client delegation has empty tool arguments.
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)

        // Verify the bubble instruction has the user's own combined words and app context.
        val prompt = submitted.first()
        val parsed = VoiceHandoff.parse(prompt) ?: throw AssertionError("Expected a voice handoff")
        assertEquals("Can you check why the upload test is failing? In OpenCodeTests.", parsed.instruction)
        assertEquals(
            listOf(
                "user: Can you check why the upload test is failing?",
                "assistant: Which target?",
                "user: In OpenCodeTests.",
            ),
            parsed.transcriptDelta,
        )
        assertTrue(parsed.context?.contains("Session \"Voice lab\" in project \"project\".") == true)
        assertEquals("item_del_1", progress.firstOrNull()?.first)
        assertTrue(progress.firstOrNull()?.second?.contains("Running in session \"Voice lab\"") == true)

        // Conversation continues non-blocking while the OpenCode session is still working!
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-3", text = "While that runs, how are we doing?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Still running the test suite."))
        store.receive(::isCallEvent)
        assertTrue(store.state.isWorking)
        assertEquals("While that runs, how are we doing?", store.state.captions.last().text)

        // Now the OpenCode session finishes and feeds the result back for item_del_1.
        releaseReply.yieldValue("The upload test passed after freeing the port.")
        releaseReply.finish()
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }
        assertEquals("item_del_1", completed.firstOrNull()?.first)
        assertEquals("[COMPLETE] The upload test passed after freeing the port.", completed.firstOrNull()?.second)

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }

    @Test fun capabilityDiscoveryScenariosDelegateExternalDataUnknownIntegrationsUnavailableSourcesAndPendingFollowUps() = runTest {
        val events = CallEvents()
        val pendingReplies = mutableMapOf<String, Gate<String>>()
        val submitted = mutableListOf<Pair<String, String>>()
        val completed = mutableMapOf<String, String>()
        var nextPromptNumber = 0
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                completeFunction = { callID, output -> completed[callID] = output },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    nextPromptNumber += 1
                    val promptID = "msg_cap_$nextPromptNumber"
                    val instruction = VoiceHandoff.parse(text)?.instruction ?: ""
                    submitted += promptID to instruction
                    VoiceWorkAdmission(promptID = promptID)
                },
                awaitReply = { _, _, promptID -> pendingReplies.getOrPut(promptID) { Gate() }.first() },
            )
        }
        store.connect(events)

        val scenarios = listOf(
            // 1. Messages (fictional)
            Triple("del_messages", "My friend Jordan texted me, what did they say?", "Jordan said dinner is moved to 7:30 PM at Harbor Noodle."),
            // 2. Mail (fictional)
            Triple("del_mail", "Check if the receipt from Northwind Books arrived in my email.", "Northwind Books sent receipt #NW-4021 for \$34.00."),
            // 3. Purchase history (fictional)
            Triple("del_purchases", "Look up when I ordered the desk lamp in my purchase history.", "Ordered on September 14 for \$68.00; delivered September 17."),
            // 4. Unknown integration (fictional)
            Triple("del_unknown", "Check the deploy status in AcmeDeploy.", "No AcmeDeploy CLI or credentials are configured in this workspace."),
            // 5. Unavailable source (fictional narrow blocker)
            Triple("del_unavailable", "Read the notes from the external backup drive.", "The backup volume /Volumes/FieldArchive is not mounted."),
        )

        for ((index, scenario) in scenarios.withIndex()) {
            val (callID, userUtterance, sessionReply) = scenario
            val promptID = "msg_cap_${index + 1}"
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_$callID", text = userUtterance, final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = callID, name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)

            assertEquals(userUtterance, submitted.lastOrNull()?.second)
            pendingReplies.getOrPut(promptID) { Gate() }.apply { yieldValue(sessionReply); finish() }
            store.receive(::isWorkReplied)
            assertEquals("[COMPLETE] $sessionReply", completed[callID])
        }

        // 6. Pending delegation: a follow-up while the first request is still running delegates immediately to steer it.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_pending_1", text = "Search all test logs for timeouts.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_pending_1", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals(1, store.state.pendingWork.size)

        // Follow-up arrives before del_pending_1 finishes: still delegates!
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_pending_2", text = "Actually only check the last two runs, how is it going?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_pending_2", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals(2, store.state.pendingWork.size)
        assertEquals("Actually only check the last two runs, how is it going?", submitted.lastOrNull()?.second)

        pendingReplies.getOrPut("msg_cap_6") { Gate() }.apply { yieldValue("Checked the last two runs: zero timeouts."); finish() }
        pendingReplies.getOrPut("msg_cap_7") { Gate() }.apply { yieldValue("Wrapped up with the last two runs."); finish() }
        store.receive(::isWorkReplied)
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }

    /**
     * D2: the voice agent owns follow-ups and corrections. Words that land
     * after a delegation never steer the session on their own; the next
     * delegation carries everything said since the previous one.
     */
    @Test fun lateWordsWaitForTheNextDelegationWhichCarriesEverythingSinceThePreviousOne() = runTest {
        val events = CallEvents()
        val firstSubmitGate = Gate<Unit>()
        val enteredFirstSubmit = Gate<Unit>()
        val pendingReplies = mutableMapOf<String, Gate<String>>()
        val submitted = mutableListOf<String>()
        val progress = mutableListOf<Pair<String, String>>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                noteProgress = { callID, text -> progress += callID to text },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    val count = submitted.size
                    if (count == 1) {
                        enteredFirstSubmit.yieldValue(Unit)
                        firstSubmitGate.first()
                    }
                    VoiceWorkAdmission(promptID = "msg_$count")
                },
                awaitReply = { _, _, promptID -> pendingReplies.getOrPut(promptID) { Gate() }.first() },
            )
        }
        store.connect(events)

        // 1. A delegation goes out with what was said so far, labelled by the acknowledgement.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = RealtimeJSONs.obj("text" to RealtimeJSONs.string("Checking the upload test."))))
        store.receive(::isCallEvent)
        enteredFirstSubmit.first()

        // 2. The utterance keeps going while the first prompt is in flight, and after it lands:
        // the caption updates in place, and nothing else is sent.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test is failing", final = true))
        store.receive(::isCallEvent)
        firstSubmitGate.yieldValue(Unit)
        firstSubmitGate.finish()
        store.receive(::isWorkAdmitted)
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test is failing on staging.", final = true))
        store.receive(::isCallEvent)
        assertEquals(listOf("user-live-u-1"), store.state.captions.map { it.id })
        assertEquals("Check why the upload test is failing on staging.", store.state.captions.first().text)
        delay(30)
        assertEquals("Late words never send a second steer prompt", 1, submitted.size)
        assertEquals("msg_1", store.state.pendingWork.firstOrNull()?.promptID)
        assertFalse("No late-words note to the voice agent", progress.any { it.second.contains("User finished saying") })

        // 3. A correction: the voice agent delegates again, and that delegation
        // carries everything said since the first one.
        events.yieldEvent(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking the upload test."))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Actually only the last run.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_2", name = "delegate", arguments = RealtimeJSONs.obj("text" to RealtimeJSONs.string("Checking the last staging run."))))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals(2, submitted.size)
        val first = VoiceHandoff.parse(submitted[0]) ?: throw AssertionError("Expected a voice handoff")
        assertEquals("Check why the upload test", first.instruction)
        assertEquals("Checking the upload test.", first.framing)
        val correction = VoiceHandoff.parse(submitted[1]) ?: throw AssertionError("Expected a voice handoff")
        assertEquals("is failing on staging. Actually only the last run.", correction.instruction)
        assertEquals("Checking the last staging run.", correction.framing)
        assertEquals(
            listOf(
                "user (updated utterance): Check why the upload test is failing on staging.",
                "assistant: Checking the upload test.",
                "user: Actually only the last run.",
            ),
            correction.transcriptDelta,
        )
        assertEquals(listOf("msg_1", "msg_2"), store.state.pendingWork.map { it.promptID })

        pendingReplies.getOrPut("msg_1") { Gate() }.apply { yieldValue("Superseded by the correction."); finish() }
        pendingReplies.getOrPut("msg_2") { Gate() }.apply { yieldValue("The last staging run failed on a timeout."); finish() }
        store.receive(::isWorkReplied)
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }

    /**
     * D4a: the long header goes once per call per session, and again after
     * the session compacts or after a long gap; otherwise the short marker.
     */
    @Test fun theLongHeaderGoesOncePerCallPerSessionAndAfterCompactionOrALongGap() = runTest {
        val submitted = mutableListOf<String>()
        var clock = Instant.ofEpochSecond(1_000)
        // Each call gets its own event stream.
        val calls = mutableListOf<CallEvents>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[DateClient] = DateClient { clock }
            this[VoiceCallClient] = testVoiceCall(start = { _, _ ->
                val call = CallEvents()
                calls += call
                call.flow
            })
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    VoiceWorkAdmission(promptID = "msg_${submitted.size}")
                },
                awaitReply = { _, _, _ -> "Done." },
            )
        }

        fun call() = calls.last()
        var count = 0
        suspend fun delegate(after: Double = 5.0): String {
            clock = clock.plusMillis((after * 1_000).toLong())
            count += 1
            call().yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_$count", text = "Request $count.", final = true))
            store.receive(::isCallEvent)
            call().yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_$count", name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)
            store.receive(::isWorkReplied)
            val prompt = submitted[count - 1]
            assertEquals("Both forms parse", "Request $count.", VoiceHandoff.parse(prompt)?.instruction)
            return prompt
        }
        fun isFull(prompt: String) = prompt.startsWith(VoiceHandoff.header + "\n")
        fun isContinued(prompt: String) = prompt.startsWith(VoiceHandoff.continuedHeader + "\n")

        store.send(VoiceFeature.Action.Start)
        waitUntil { calls.isNotEmpty() }
        call().yieldEvent(VoiceCallEvent.Connected)
        store.receive(::isCallEvent) { it.withPhase(VoiceFeature.State.Phase.Live) }

        // First into the session: full. Repeats: short.
        assertTrue(isFull(delegate()))
        assertTrue(isContinued(delegate()))
        assertTrue("Within the lapse", isContinued(delegate(after = 29.0 * 60)))

        // The session compacted: the summary dropped the explanation.
        store.send(VoiceFeature.Action.CompactionObserved(VoiceFeature.State.CompactionMark(sessionID = "ses_voice", compactionID = "cmp_1")))
        assertTrue(isFull(delegate()))
        assertTrue(isContinued(delegate()))
        // A compaction seen in another session doesn't count here.
        store.send(VoiceFeature.Action.CompactionObserved(VoiceFeature.State.CompactionMark(sessionID = "ses_elsewhere", compactionID = "cmp_x")))
        assertTrue(isContinued(delegate()))

        // A long gap without a delegation.
        assertTrue(isFull(delegate(after = VoiceHandoff.headerLapse + 1)))
        assertTrue(isContinued(delegate()))

        // A new call explains itself again.
        store.send(VoiceFeature.Action.EndTapped)
        store.receive(::isDelegateEnded)
        store.send(VoiceFeature.Action.Start)
        waitUntil { calls.size >= 2 }
        call().yieldEvent(VoiceCallEvent.Connected)
        store.receive(::isCallEvent)
        assertTrue(isFull(delegate()))

        call().finish()
        store.skipReceivedActions()
        store.finish()
    }

    /**
     * A request on its way when the call moves still goes to its session,
     * header and all, so coming back doesn't explain the call again.
     */
    @Test fun movingAwayBeforeAHeaderIsAdmittedKeepsItsRequest() {
        val shared = VoiceTestShared()
        val values = DependencyValues.testing().update {
            this[SharedStore] = shared.store
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }
        DependencyValues.withValues(values) {
            val other = OpenCodeSession(id = "ses_other", serverTitle = "Other", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)
            val tail = VoiceTranscriptTail()
            val capture = tail.capture().capture
            var voice = VoiceFeature.State(server, session)
            voice = voice.copy(handoffs = voice.handoffs + ("ses_voice" to VoiceFeature.State.Handoff(lastDelegationAt = Instant.EPOCH, compactionID = null)))
            voice = voice.copy(pendingWork = listOf(VoiceFeature.State.PendingWork(callID = "del_1", capture = capture, explainsHeader = true, target = voice.target, move = voice.moves)))
            voice = voice.retarget(other)
            assertEquals(listOf("del_1"), voice.pendingWork.map { it.callID })
            assertEquals("ses_voice", voice.pendingWork.firstOrNull()?.target?.sessionID)
            assertNotNull(voice.handoffs["ses_voice"])
            assertTrue(voice.abandonedCallIDs.isEmpty())
            assertFalse("Its run is the other session's, not this one's", voice.hasAdmittedWork)
        }
    }

    @Test fun aFailedFirstDelegationLeavesTheHeaderForTheNext() = runTest {
        val events = CallEvents()
        val submitted = mutableListOf<String>()
        val shared = VoiceTestShared()
        val store = voiceTestStore(shared, { VoiceFeature.State(server, session) }) {
            this[VoiceCallClient] = testVoiceCall(start = { _, _ -> events.flow })
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    val count = submitted.size
                    if (count == 1) throw OpenCodeClientError("HTTP 503")
                    VoiceWorkAdmission(promptID = "msg_$count")
                },
                awaitReply = { _, _, _ -> "Done." },
            )
        }
        store.exhaustive = false
        shared.set(SharedKeys.openAIAPIKey, "sk-test")
        store.send(VoiceFeature.Action.Start)
        events.yieldEvent(VoiceCallEvent.Connected)
        store.receive(::isCallEvent)
        for (index in 1..2) {
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_$index", text = "Request $index.", final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_$index", name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            if (index == 1) store.receive(::isWorkFailed) else store.receive(::isWorkAdmitted)
        }
        assertTrue(submitted.all { it.startsWith(VoiceHandoff.header + "\n") })
        events.finish()
        store.skipReceivedActions()
        store.finish()
    }

    @Test fun aVagueAcknowledgementLabelsTheVoiceMessageWithTheUsersWords() = runTest {
        val events = CallEvents()
        val submitted = mutableListOf<String>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(start = { _, _ -> events.flow })
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    VoiceWorkAdmission(promptID = "msg_${submitted.size}")
                },
                awaitReply = { _, _, _ -> "Done." },
            )
        }
        store.connect(events)

        // Vague, then missing: both show the user's words.
        for ((index, arguments) in listOf(RealtimeJSONs.obj("text" to RealtimeJSONs.string("Sure, I'll take a look.")), emptyArgs).withIndex()) {
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-$index", text = "Run the parser tests $index.", final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_$index", name = "delegate", arguments = arguments))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)
            store.receive(::isWorkReplied)
            val delegation = VoiceHandoff.parse(submitted[index]) ?: throw AssertionError("Expected a voice handoff")
            assertEquals("Run the parser tests $index.", delegation.instruction)
            assertNull(delegation.framing)
            assertEquals("Run the parser tests $index.", delegation.displayText)
        }

        events.finish()
        store.receive(::isCallEnded)
        store.skipReceivedActions()
        store.finish()
    }

    /**
     * The call drops delegations still waiting in the coordinator as soon as
     * it moves, not after re-reading the new session's messages.
     */
    @Test fun movingTheCallDropsWaitingDelegationsBeforeReadingTheNewSession() = runTest {
        val order = mutableListOf<String>()
        val gate = Gate<Unit>()
        val shared = VoiceTestShared()
        val store = voiceTestStore(shared, {
            VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live).copy(activeEngine = VoiceEngine.gptLive)
        }) {
            this[VoiceCallClient] = testVoiceCall(
                retarget = { order += "retarget" },
                updateInstructions = { _, _ -> order += "instructions" },
            )
            this[VoiceWorkClient] = testVoiceWork(recentMessages = { _, _, _ ->
                order += "read"
                gate.first()
                emptyList()
            })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.TargetChanged)
        waitUntil { order.contains("read") }
        assertEquals("retarget", order.first())
        gate.yieldValue(Unit)
        gate.finish()
        waitUntil { order.contains("instructions") }
        assertEquals(listOf("retarget", "read", "instructions"), order)
        store.skipReceivedActions()
    }

    @Test fun clarificationStatusCheckRepeatedReadAndAmbiguousWriteHandledDistinctly() = runTest {
        val events = CallEvents()
        val pendingReplies = mutableMapOf<String, Gate<String>>()
        val submitted = mutableListOf<Pair<String, String>>()
        val completed = mutableMapOf<String, String>()
        var nextPromptNumber = 0
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                completeFunction = { callID, output -> completed[callID] = output },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    nextPromptNumber += 1
                    val promptID = "msg_flow_$nextPromptNumber"
                    val instruction = VoiceHandoff.parse(text)?.instruction ?: ""
                    submitted += promptID to instruction
                    VoiceWorkAdmission(promptID = promptID)
                },
                awaitReply = { _, _, promptID -> pendingReplies.getOrPut(promptID) { Gate() }.first() },
            )
        }
        fun reply(promptID: String, text: String) = pendingReplies.getOrPut(promptID) { Gate() }.apply { yieldValue(text); finish() }
        store.connect(events)

        // 1. Initial task starts running.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_1", text = "Run the parser tests.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)

        // 2. Clarification while running steers the existing task immediately.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_2", text = "Only run the markdown table cases.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_clarify", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals("Only run the markdown table cases.", submitted.lastOrNull()?.second)

        // 3. Explicit poke/status request while running asks the session for its current state.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_3", text = "How is that test run going?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_status", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals("How is that test run going?", submitted.lastOrNull()?.second)

        reply("msg_flow_3", "12 of 14 markdown table test cases have passed; 2 are still running.")
        store.receive(::isWorkReplied)
        assertEquals("[COMPLETE] 12 of 14 markdown table test cases have passed; 2 are still running.", completed["del_status"])

        reply("msg_flow_1", "Initial run superseded by filter.")
        reply("msg_flow_2", "All 14 markdown table test cases passed.")
        store.receive(::isWorkReplied)
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }

        // 4. Repeated read-only request is delegated again without refusal.
        for (attempt in 1..2) {
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_read_$attempt", text = "Check the latest CI status.", final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_read_$attempt", name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)
            assertEquals("Check the latest CI status.", submitted.lastOrNull()?.second)
            reply("msg_flow_${3 + attempt}", "CI build #812 is green.")
            store.receive(::isWorkReplied)
        }

        // 5. Ambiguous external write: status check delegates first, then retries only after confirming it didn't happen.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_write_check", text = "Check whether the invoice email to Riverstone Studio was sent.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_write_check", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        reply("msg_flow_6", "No invoice email was sent to Riverstone Studio; the draft is still unsent.")
        store.receive(::isWorkReplied)
        assertTrue(completed["del_write_check"]?.contains("still unsent") == true)

        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_write_retry", text = "Send the invoice email to Riverstone Studio now.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_write_retry", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        reply("msg_flow_7", "Sent invoice #RS-104 to Riverstone Studio.")
        store.receive(::isWorkReplied)
        assertEquals("[COMPLETE] Sent invoice #RS-104 to Riverstone Studio.", completed["del_write_retry"])

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }
}
