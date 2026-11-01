package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeRunMessagesPage
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `VoiceBackgroundResultTests.swift`.
 *
 * From a call log (Oct 25): asked for eye-tracking research, HOME started
 * a background subagent and replied that it had. The watch resolved on
 * that reply, so when the research landed two minutes and three unrelated
 * delegations later, nothing told the user until he asked twice. Work a
 * watched run starts in the background is watched too, and its result is
 * spoken once, at the next opening.
 *
 * `runMessagesDecodeLaunchesAndCompletions` is skipped here (owner `:server`): it tests
 * `OpenCodeClient.decodeRunMessages`, which is internal to `:server`.
 */
class VoiceBackgroundResultTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val home = "ses_home"
    private val child = "ses_eye"
    private val started = "I've started the eye tracking research in the background."
    private val report = "macOS has no eye tracking; a webcam tracker is the realistic option."

    private val homeSession: OpenCodeSession
        get() = OpenCodeSession(id = home, serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = distantPast)

    private data class Announcement(val promptIDs: List<String>, val text: String)

    /** iOS `VoiceFeature.State(server:session:)` live, delegating, its watch live. */
    private fun liveVoice(shared: SpeechTestShared): VoiceFeature.State = shared.run {
        VoiceFeature.State(server, homeSession)
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesDelegator = true)
            .let { it.copy(replyWatch = it.replyWatch.copy(callPhase = ReplyWatch.CallPhase.live)) }
    }

    @Test fun backgroundWorkIsSpokenOnceWhenItLands() = runBlocking<Unit> {
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        val eye = ReplyWatch.Prompt(promptID = "msg_eye", sessionID = home, title = "HOME", topic = "Research eye tracking for the Mac")
        val others = listOf(
            ReplyWatch.Prompt(promptID = "msg_merge", sessionID = "ses_ios", title = "OpenCode iOS", topic = "Fix the merge conflicts"),
            ReplyWatch.Prompt(promptID = "msg_prune", sessionID = "ses_dots", title = "Dotfiles", topic = "Apply the prune"),
            ReplyWatch.Prompt(promptID = "msg_board", sessionID = "ses_board", title = "Board", topic = "Move OC-12 to Done"),
        )
        val replies = mapOf(
            "msg_eye" to started,
            "msg_merge" to "Resolved both merge conflicts.",
            "msg_prune" to "Removed the empty skill folders.",
            "msg_board" to "OC-12 is Done.",
        )
        val background = ReplyWatch.Prompt(work, title = "HOME", saidTurn = 0)
        val finished = Channel<Unit>(Channel.UNLIMITED)
        val announced = Locked(listOf<Announcement>())
        val idled = Locked(listOf<String>())
        val clock = TestClock()

        val shared = SpeechTestShared()
        val voice = liveVoice(shared)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.copy(
                awaitReply = { _, _, promptID -> replies[promptID] },
                backgroundWork = { _, sessionID, promptID, _ ->
                    if (sessionID == home && promptID == "msg_eye") listOf(work) else emptyList()
                },
                awaitIdle = { _, sessionID ->
                    idled.update { it + sessionID }
                    finished.receiveCatching()
                },
                backgroundReply = { _, sessionID -> if (sessionID == child) report else null },
            )
            this[VoiceCallClient] = speechVoiceCall(
                announceSession = { _, promptIDs, text -> announced.update { it + Announcement(promptIDs, text) } },
            )
        }

        // HOME replies that it started the work: that reply is spoken, and the
        // work it started in the background is watched in its own session.
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(eye)))) { it.stepping(ReplyWatch.Event.Sent(listOf(eye))) }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = started))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = started))
        }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
        assertTrue(store.state.replyWatch.isWatching(sessionID = child))
        assertFalse(store.state.replyWatch.isWatching(sessionID = home))

        // The conversation moves on: three unrelated delegations finish.
        for ((turn, prompt) in others.withIndex()) {
            val topic = prompt.topic ?: ""
            store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u$turn", text = topic, final = true))) {
                val recorded = it.recording(VoiceTranscriptTail.Role.user, topic, id = "u$turn")
                recorded.copy(
                    replyWatch = recorded.replyWatch.noteUserActed(),
                    captions = recorded.captions + VoiceFeature.State.Caption(id = "user-u$turn", role = VoiceTranscriptTail.Role.user, text = topic),
                )
            }
            store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(prompt)))) { it.stepping(ReplyWatch.Event.Sent(listOf(prompt))) }
            val reply = replies[prompt.promptID]
            store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = reply))) {
                it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = reply))
            }
        }

        // The research lands while the voice is mid-request: it waits for that
        // request's answer, then speaks.
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorStarted(requestID = "r_status"))) {
            it.stepping(ReplyWatch.Event.DelegatorStarted(requestID = "r_status"))
        }
        finished.trySend(Unit)
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))
        }
        assertEquals(listOf(background.promptID), store.state.replyWatch.deferredNotices.map { it.promptID })
        assertFalse("Held while the request runs", announced.value.any { it.promptIDs == listOf(background.promptID) })
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorFinished(requestID = "r_status", spoken = "OC-12 is Done."))) {
            it.stepping(ReplyWatch.Event.DelegatorFinished(requestID = "r_status", spoken = "OC-12 is Done."))
        }
        store.finish()
        effects.skipInFlightEffects()

        assertTrue(idled.value.toString(), idled.value.all { it == child })
        val spoken = announced.value.filter { it.promptIDs == listOf(background.promptID) }
        assertEquals(1, spoken.size)
        val notice = ReplyWatch.Outcome.Replied(report)
            .notice("HOME", answering = listOf("Eye tracking research"), alreadyTold = null, background = "Eye tracking research")
        assertEquals(VoiceHandoff.earlierRequest(notice, topic = "Eye tracking research"), spoken.firstOrNull()?.text)
        assertTrue(spoken.firstOrNull()?.text?.contains(report) == true)
        assertEquals("The started reply, three results, the research", 5, announced.value.size)
        assertTrue(store.state.replyWatch.watched.isEmpty())
        val summary = store.state.replyWatch.statusSummary(viewing = homeSession, draftDirectory = "/Users/kit/home")
        assertTrue(summary, summary.contains("Background work “Eye tracking research” that \"HOME\" started (subagent session id $child): finished"))
        finished.close()
    }

    private fun user(id: String): OpenCodeRunMessage =
        OpenCodeRunMessage(id = id, type = "user", text = "Research eye tracking")

    private fun assistant(id: String, text: String = "", launching: String? = null): OpenCodeRunMessage {
        val launches = launching?.let { listOf(OpenCodeRunMessage.Launch(sessionID = it, description = "Eye tracking research")) } ?: emptyList()
        return OpenCodeRunMessage(id = id, type = "assistant", text = text, launches = launches)
    }

    private fun completion(id: String, of: String): OpenCodeRunMessage =
        OpenCodeRunMessage(id = id, type = "synthetic", completedChildID = of)

    /** Tool-only steps: a long run. */
    private fun steps(prefix: String, count: Int): List<OpenCodeRunMessage> =
        (0 until count).map { assistant("$prefix$it") }

    /**
     * Only background subagents the watched run started are followed: not
     * one an earlier prompt started, nor one the next prompt did.
     */
    @Test fun backgroundLaunchesAreTheRunsOwn() {
        val messages = listOf(
            user("msg_old"), assistant("a0", launching = "ses_prune"), assistant("a0t", "Started the prune."),
            user("msg_eye"), assistant("a1", launching = child), assistant("a1t", started),
            user("msg_next"), assistant("a2", launching = "ses_later"),
        )
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        assertEquals(listOf(work), VoiceWorkClient.pendingBackgroundWork(messages, home, afterPromptID = "msg_eye", reply = started))
        assertTrue(VoiceWorkClient.pendingBackgroundWork(messages, home, afterPromptID = "msg_missing", reply = started).isEmpty())
    }

    /**
     * Sol's review: work counts as covered only when its completion landed
     * before text of the reply the call read. One that finished after that
     * reply, even in the moment before the search ran, is still news; so is
     * one whose completion woke a later run of the session nobody read.
     */
    @Test fun onlyACompletionBeforeTheReadReplyCoversTheWork() {
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        val finishedAfter = listOf(user("msg_eye"), assistant("a1", launching = child), assistant("a1t", started), completion("s1", of = child))
        assertEquals(listOf(work), VoiceWorkClient.pendingBackgroundWork(finishedAfter, home, afterPromptID = "msg_eye", reply = started))
        val wokeALaterRun = finishedAfter + assistant("a2t", "The research is in: no eye tracking on macOS.")
        assertEquals(listOf(work), VoiceWorkClient.pendingBackgroundWork(wokeALaterRun, home, afterPromptID = "msg_eye", reply = started))
        val finishedWithin = listOf(user("msg_eye"), assistant("a1", launching = child), completion("s1", of = child), assistant("a1t", report))
        assertTrue(VoiceWorkClient.pendingBackgroundWork(finishedWithin, home, afterPromptID = "msg_eye", reply = report).isEmpty())
        assertEquals("No reply read covers nothing", listOf(work), VoiceWorkClient.pendingBackgroundWork(finishedWithin, home, afterPromptID = "msg_eye", reply = null))
    }

    /**
     * The run's prompt is past the first page: discovery pages back to it,
     * and stops after `backgroundSearchPages` when it's gone.
     */
    @Test fun discoveryPagesBackToTheRunsPrompt() = runBlocking<Unit> {
        val reads = Locked(listOf<String?>())
        val pages: Map<String?, OpenCodeRunMessagesPage> = mapOf(
            null to OpenCodeRunMessagesPage(messages = steps("n", 39) + assistant("a1t", started), next = "older"),
            "older" to OpenCodeRunMessagesPage(messages = listOf(user("msg_eye"), assistant("a1", launching = child)) + steps("o", 38), next = "oldest"),
        )
        val found = withDependenciesSuspending({
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, cursor ->
                    reads.update { it + cursor }
                    pages[cursor] ?: OpenCodeRunMessagesPage(messages = steps(cursor ?: "x", 40), next = "${cursor ?: ""}+")
                },
            )
        }) {
            VoiceWorkClient.live(VoiceWorkCoordinator()).backgroundWork(server, home, "msg_eye", started)
        }
        assertEquals(listOf(child), found.map { it.sessionID })
        assertEquals(listOf(null, "older"), reads.value)

        reads.set(emptyList())
        val gone = withDependenciesSuspending({
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, cursor ->
                    reads.update { it + cursor }
                    OpenCodeRunMessagesPage(messages = steps(cursor ?: "x", 40), next = "${cursor ?: ""}+")
                },
            )
        }) {
            VoiceWorkClient.live(VoiceWorkCoordinator()).backgroundWork(server, home, "msg_gone", started)
        }
        assertTrue(gone.isEmpty())
        assertEquals(VoiceWorkClient.backgroundSearchPages, reads.value.size)
    }

    /**
     * The race end to end, on a run longer than a page: the research finished
     * after HOME's reply was read and before the search. It's watched, read
     * at once (its session is already idle) and spoken once.
     */
    @Test fun workThatFinishedAfterTheReplyWasReadIsSpokenOnce() {
        val pages: Map<String?, OpenCodeRunMessagesPage> = mapOf(
            null to OpenCodeRunMessagesPage(messages = steps("n", 38) + assistant("a1t", started) + completion("s1", of = child), next = "older"),
            "older" to OpenCodeRunMessagesPage(messages = listOf(user("msg_eye"), assistant("a1", launching = child)) + steps("o", 38), next = null),
        )
        val announced = runEyeRequest(reply = started, pages = pages, watches = true)
        assertEquals(listOf(listOf("msg_eye"), listOf("background-$child")), announced.map { it.promptIDs })
        assertTrue(announced.lastOrNull()?.text?.contains(report) == true)
    }

    /** Work whose completion HOME's read reply came after was told in it. */
    @Test fun workTheReadReplyCoveredIsNotSpokenAgain() {
        val pages: Map<String?, OpenCodeRunMessagesPage> = mapOf(
            null to OpenCodeRunMessagesPage(messages = listOf(user("msg_eye"), assistant("a1", launching = child), completion("s1", of = child), assistant("a1t", report)), next = null),
        )
        val announced = runEyeRequest(reply = report, pages = pages, watches = false)
        assertEquals(listOf(listOf("msg_eye")), announced.map { it.promptIDs })
    }

    private fun runEyeRequest(reply: String, pages: Map<String?, OpenCodeRunMessagesPage>, watches: Boolean): List<Announcement> = runBlocking {
        val eye = ReplyWatch.Prompt(promptID = "msg_eye", sessionID = home, title = "HOME", topic = "Research eye tracking for the Mac")
        val background = ReplyWatch.Prompt(
            VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research"),
            title = "HOME",
            saidTurn = 0,
        )
        val announced = Locked(listOf<Announcement>())
        val idled = Locked(listOf<String>())
        val shared = SpeechTestShared()
        val voice = liveVoice(shared)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.copy(
                awaitReply = { _, _, _ -> reply },
                backgroundWork = VoiceWorkClient.live(VoiceWorkCoordinator()).backgroundWork,
                awaitIdle = { _, sessionID -> idled.update { it + sessionID } },
                backgroundReply = { _, _ -> report },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, cursor -> pages[cursor] ?: OpenCodeRunMessagesPage(messages = emptyList(), next = null) },
            )
            this[VoiceCallClient] = speechVoiceCall(
                announceSession = { _, promptIDs, text -> announced.update { it + Announcement(promptIDs, text) } },
            )
        }
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(eye)))) { it.stepping(ReplyWatch.Event.Sent(listOf(eye))) }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = reply))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = reply))
        }
        if (watches) {
            store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
            store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))) {
                it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))
            }
        }
        store.finish()
        effects.skipInFlightEffects()
        assertTrue(store.state.replyWatch.watched.isEmpty())
        assertTrue(idled.value.all { it == child })
        announced.value
    }

    /**
     * A request that reads the session that started the work holds its
     * result like that session's own, so its answer goes first.
     */
    @Test fun aReadOfTheParentSessionHoldsTheResult() {
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(work, title = "HOME", saidTurn = 0))))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "r1"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "r1", isWaiting = true))
        val effects = watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "background-$child", reply = report))
        assertEquals(setOf("r1"), watch.deferredNotices.firstOrNull()?.heldBy)
        assertTrue(effects.contains(ReplyWatch.Effect.ExpireDeferral(key = "background-$child", after = ReplyWatch.claimedResultLimit, claimed = true)))
        assertFalse("Background work's own result follows nothing", effects.any { it is ReplyWatch.Effect.FindBackgroundWork })
    }
}

private val epoch: DateClient = DateClient.constant(Instant.EPOCH)
private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
