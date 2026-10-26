package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.TranscriptNoticeKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import ai.opencode.feature.transcript.SinceYouLeft as TranscriptSinceYouLeft

/**
 * Port of iOS `SinceYouLeftTests.swift` (`SinceYouLeftTests`, `SinceYouLeftInputTests` and the
 * `SinceYouLeftFixtures` they share).
 *
 * Not ported here: `arrivalAndDismissalTraceMovesTranscriptWithoutJumps`,
 * `rowEnteringViewportDuringGrowthStepReceivesFreshCellConfiguration`,
 * `finishingGrowthsDuringApplySnapshotWhileBrowsingHoldsAnchorWithoutDoubleShift` (UIKit
 * `TranscriptListViewController` motion probes; the list is :feature:transcript's LazyColumn);
 * `multiChunkSummaryProgressesThroughTranscriptTextRevealAndSettlesOnDone` (TranscriptTextReveal,
 * :feature:transcript); `SinceYouLeftEvalDump.dumpPrompts` (an env-gated print for the on-device
 * eval script, not a test).
 */
class SinceYouLeftTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")

    private fun session(id: String) = OpenCodeSession(id = id, title = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10))

    private fun user(id: String, text: String = "Update the spring"): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id = id, text = text)

    private fun reply(id: String, markdown: String? = null): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = markdown ?: "Reply $id")

    private fun editTool(id: String, path: String, secretContent: String = "SUPER_SECRET_FILE_BODY"): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id = id,
        call = OpenCodeToolCall(
            callID = id, messageID = "msg-$id", name = "edit", status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(
                mapOf("path" to OpenCodeJSONValue.Str(path), "oldString" to OpenCodeJSONValue.Str(secretContent), "newString" to OpenCodeJSONValue.Str(secretContent + "_UPDATED")),
            ),
            output = listOf(secretContent), error = null, metadata = emptyMap(),
        ),
    )

    private fun shellTool(id: String, status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id = id,
        call = OpenCodeToolCall(
            callID = id, messageID = "msg-$id", name = "bash", status = status,
            input = OpenCodeJSONValue.Object(mapOf("command" to OpenCodeJSONValue.Str("bun test"))), output = listOf("PASS"),
            error = if (status == OpenCodeToolCall.Status.Error) "exit 1" else null, metadata = emptyMap(),
        ),
    )

    private fun summaryStream(text: () -> String) = SinceYouLeftClient(
        isAvailable = { true },
        summarize = { flowOf(text()) },
    )

    private fun Harness.makeStore(
        sessionID: String,
        cachedItems: List<OpenCodeTranscriptItem> = emptyList(),
        seen: Set<String>? = null,
        leftAt: Instant = Instant.ofEpochSecond(1_000),
        now: Instant = Instant.ofEpochSecond(1_120),
        sinceYouLeft: SinceYouLeftClient = SinceYouLeftClient.unavailable,
        configureState: (State) -> State = { it },
        configureDependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<State, Action> {
        val targetSession = session(sessionID)
        var state = state { State.session(server, targetSession, cachedItems = cachedItems) }.copy(isVisible = true)
        if (seen != null) state = state { state.noteOpening(seen = seen, leftAt = leftAt) }
        state = state { configureState(state) }
        val store = store(state) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.sinceYouLeft))
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(now)
            this[SinceYouLeftClient] = sinceYouLeft
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> OpenCodeSession(id = id, title = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10)) },
                contextUsage = { _, _ -> OpenCodeContextReading.Inconclusive },
                viewSession = { _, _, _ -> },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
            configureDependencies()
        }
        store.exhaustive = false
        return store
    }

    private fun DependencyValues.Builder.client(update: OpenCodeClient.() -> OpenCodeClient) {
        this[OpenCodeClient] = this[OpenCodeClient].update()
    }

    private suspend fun receiveSummary(store: TestStore<State, Action>, chunks: Int = 1) {
        repeat(chunks) { store.receive(Action.SinceYouLeftChunkReceived::class, timeout = 1.seconds) }
        store.receive(Action.SinceYouLeftFinished::class, timeout = 1.seconds)
    }

    @Test fun unseenTurnsStreamSummaryOnReturn() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(
            editTool("t1", path = "/project/OpenCode/DrawerView.swift"),
            reply("m3", markdown = "Adjusted the drawer spring."),
            shellTool("t2", status = OpenCodeToolCall.Status.Completed),
            reply("m4", markdown = "All tests passed."),
        )
        val currentNow = LockIsolated(Instant.ofEpochSecond(1_000))
        val store = h.makeStore(
            sessionID = "ses_syl",
            cachedItems = away,
            sinceYouLeft = SinceYouLeftClient.canned(listOf("Fixed the drawer spring,", "Fixed the drawer spring, ran the tests.")),
            configureState = { it.copy(hasRenderedFirstContentFrame = true, initialTranscriptIsFresh = true) },
            configureDependencies = {
                this[DateClient] = DateClient { currentNow.value }
                client { copy(transcript = { _, _, _ -> back }) }
            },
        )

        store.send(Action.VisibilityChanged(false))
        assertEquals(setOf("m1", "m2"), store.state.sinceYouLeftSeen)
        assertEquals(Instant.ofEpochSecond(1_000), store.state.sinceYouLeftAt)

        // Return 3 minutes later (> 60s minimum absence).
        currentNow.setValue(Instant.ofEpochSecond(1_180))
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        receiveSummary(store, chunks = 2)

        assertEquals(
            SinceYouLeft(sessionID = "ses_syl", lastItemID = "m4", text = "Fixed the drawer spring, ran the tests", phase = SinceYouLeft.Phase.Done, isFallback = false),
            store.state.sinceYouLeft,
        )
    }

    @Test fun shortAbsenceShowsNothing() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val oneTurnBack = away + listOf(reply("m3", markdown = "Single quick reply."))
        val multiTurnBack = away + listOf(reply("m3", markdown = "First turn."), reply("m4", markdown = "Second turn."))

        // 1. Fewer than 2 unseen turns (even after 5 minutes away) -> nil.
        assertNull(
            SinceYouLeft.buildInput(
                sessionID = "ses_short", items = oneTurnBack, seen = setOf("m1", "m2"),
                leftAt = Instant.ofEpochSecond(1_000), now = Instant.ofEpochSecond(1_300),
            ),
        )

        // 2. Under 1 minute away (30s, even with 2 unseen turns) -> nil.
        assertNull(
            SinceYouLeft.buildInput(
                sessionID = "ses_short", items = multiTurnBack, seen = setOf("m1", "m2"),
                leftAt = Instant.ofEpochSecond(1_000), now = Instant.ofEpochSecond(1_030),
            ),
        )

        // 3. Reducer verification: returning after 20 seconds shows nothing.
        val currentNow = LockIsolated(Instant.ofEpochSecond(2_000))
        val store = h.makeStore(
            sessionID = "ses_short",
            cachedItems = away,
            sinceYouLeft = SinceYouLeftClient.canned("Should not be called"),
            configureState = { it.copy(hasRenderedFirstContentFrame = true, initialTranscriptIsFresh = true) },
            configureDependencies = {
                this[DateClient] = DateClient { currentNow.value }
                client { copy(transcript = { _, _, _ -> multiTurnBack }) }
            },
        )

        store.send(Action.VisibilityChanged(false))
        currentNow.setValue(Instant.ofEpochSecond(2_020))
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertNull(store.state.sinceYouLeft)
    }

    @Test fun unavailableModelShowsStructuralFallback() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(
            editTool("t1", path = "/project/OpenCode/SessionView.swift"),
            reply("m3", markdown = "Updated SessionView."),
            editTool("t2", path = "/project/OpenCode/SinceYouLeft.swift"),
            shellTool("t3", status = OpenCodeToolCall.Status.Completed),
            reply("m4", markdown = "Wired SinceYouLeft."),
        )
        val store = h.makeStore(sessionID = "ses_fallback", cachedItems = away, seen = setOf("m1", "m2"), sinceYouLeft = SinceYouLeftClient.unavailable)

        store.send(Action.TranscriptResponse("ses_fallback", Result.success(back)))
        assertEquals(
            SinceYouLeft(sessionID = "ses_fallback", lastItemID = "m4", text = "2 turns · 2 files edited", phase = SinceYouLeft.Phase.Done, isFallback = true),
            store.state.sinceYouLeft,
        )
    }

    @Test fun cancellationOnLeaveStopsInFlightStream() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(reply("m3", markdown = "Turn 1"), reply("m4", markdown = "Turn 2"))
        val cancelled = LockIsolated(false)

        val store = h.makeStore(
            sessionID = "ses_cancel",
            cachedItems = away,
            seen = setOf("m1", "m2"),
            sinceYouLeft = SinceYouLeftClient(
                isAvailable = { true },
                summarize = {
                    flow {
                        try {
                            emit("Working on drawer")
                            awaitCancellation()
                        } finally {
                            cancelled.setValue(true)
                        }
                    }
                },
            ),
            configureState = { it.copy(hasRenderedFirstContentFrame = true) },
        )

        store.send(Action.TranscriptResponse("ses_cancel", Result.success(back)))
        store.receive(Action.SinceYouLeftChunkReceived::class, timeout = 1.seconds)
        assertEquals(SinceYouLeft.Phase.Generating, store.state.sinceYouLeft?.phase)
        assertEquals("Working on drawer", store.state.sinceYouLeft?.text)

        store.send(Action.VisibilityChanged(false))
        assertNull(store.state.sinceYouLeft)
        SessionTestSupport.settle()
        assertTrue(cancelled.value)
    }

    @Test fun cachesPerSessionAndLastMessageID() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(reply("m3", markdown = "Turn 1"), reply("m4", markdown = "Turn 2"))
        val summarizeCalls = LockIsolated(0)

        val store = h.makeStore(
            sessionID = "ses_cache",
            cachedItems = away,
            seen = setOf("m1", "m2"),
            sinceYouLeft = summaryStream {
                summarizeCalls.increment()
                "Cached summary"
            },
        )

        store.send(Action.TranscriptResponse("ses_cache", Result.success(back)))
        receiveSummary(store)
        assertEquals(1, summarizeCalls.value)
        assertEquals("Cached summary", store.state.sinceYouLeft?.text)
        assertEquals(SinceYouLeft.CacheEntry(lastItemID = "m4", text = "Cached summary"), store.state.sinceYouLeftCache["ses_cache"])

        // Reopening with the same unseen tail (m4) hits the cache without calling summarize again.
        val snapshot = SessionFeature.SwitchSnapshot.from(store.state)
        assertEquals(SinceYouLeft.CacheEntry(lastItemID = "m4", text = "Cached summary"), snapshot?.sinceYouLeftCache?.get("ses_cache"))

        val reopenedStore = h.makeStore(
            sessionID = "ses_cache",
            cachedItems = back,
            now = Instant.ofEpochSecond(1_200),
            sinceYouLeft = SinceYouLeftClient(
                isAvailable = { true },
                summarize = {
                    summarizeCalls.increment()
                    emptyFlow()
                },
            ),
            configureState = { state ->
                val restored = snapshot?.restore(state) ?: state
                restored.noteOpening(seen = setOf("m1", "m2"), leftAt = Instant.ofEpochSecond(1_000))
            },
        )

        reopenedStore.send(Action.TranscriptResponse("ses_cache", Result.success(back)))
        assertEquals(1, summarizeCalls.value)
        assertEquals("Cached summary", reopenedStore.state.sinceYouLeft?.text)
        assertEquals(SinceYouLeft.Phase.Done, reopenedStore.state.sinceYouLeft?.phase)
    }

    @Test fun swappingSessionsClearsOutgoingSummaryAndNeverShowsStaleSummaryWhileGenerating() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val backA = away + listOf(reply("m3", markdown = "Turn 1A"), reply("m4", markdown = "Turn 2A"))
        val backB = away + listOf(reply("b3", markdown = "Turn 1B"), reply("b4", markdown = "Turn 2B"))
        val root = session("ses_root")
        val childSession = session("ses_child").copy(parentID = "ses_root")

        val store = h.makeStore(
            sessionID = "ses_root",
            cachedItems = away,
            seen = setOf("m1", "m2"),
            sinceYouLeft = SinceYouLeftClient.canned("Root session summary"),
            configureState = {
                it.copy(
                    familyRootID = "ses_root",
                    familySessions = listOf(root, childSession),
                    familyTranscriptCache = it.familyTranscriptCache + ("ses_child" to away),
                )
            },
            configureDependencies = {
                client {
                    copy(
                        transcript = { _, id, _ -> if (id == "ses_child") backB else backA },
                        getSession = { _, id -> if (id == "ses_child") childSession else root },
                        sessionFamily = { _, r -> OpenCodeSessionFamily(sessions = listOf(r, childSession), activeSessionIDs = emptySet()) },
                    )
                }
            },
        )

        store.send(Action.TranscriptResponse("ses_root", Result.success(backA)))
        receiveSummary(store)
        val outgoingSummary = store.state.sinceYouLeft
        assertEquals("Root session summary", outgoingSummary?.text)

        // Swapping sessions in place clears the outgoing summary immediately.
        store.send(Action.FamilyPicked("ses_child"))
        assertEquals("ses_child", store.state.session?.id)
        assertNull(store.state.sinceYouLeft)
        h.skipInFlightEffects()

        // While a new session's summary is in `.generating` (empty text), `TranscriptEntry.build`
        // omits `.sinceYouLeft` so the transcript is identical to main until text arrives.
        val generatingChild = TranscriptSinceYouLeft(sessionID = "ses_child", lastItemID = "b4", text = "", phase = TranscriptSinceYouLeft.Phase.Generating)
        assertEquals(TranscriptEntry.build(backB), TranscriptEntry.build(backB, sinceYouLeft = generatingChild))
        val populatedChild = TranscriptSinceYouLeft(sessionID = "ses_child", lastItemID = "b4", text = "Child summary", phase = TranscriptSinceYouLeft.Phase.Done)
        assertEquals(TranscriptEntry.SinceYouLeftEntry(populatedChild), TranscriptEntry.build(backB, sinceYouLeft = populatedChild).last())
    }

    @Test fun cacheStoresOneEntryPerSessionAndBoundsSizeAcrossSnapshots() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back1 = away + listOf(reply("m3", markdown = "Turn 1"), reply("m4", markdown = "Turn 2"))
        val back2 = back1 + listOf(reply("m5", markdown = "Turn 3"), reply("m6", markdown = "Turn 4"))

        val summaryText = LockIsolated("First summary")
        val store = h.makeStore(
            sessionID = "ses_bounded",
            cachedItems = away,
            seen = setOf("m1", "m2"),
            sinceYouLeft = summaryStream { summaryText.value },
        )

        store.send(Action.TranscriptResponse("ses_bounded", Result.success(back1)))
        receiveSummary(store)
        assertEquals(SinceYouLeft.CacheEntry(lastItemID = "m4", text = "First summary"), store.state.sinceYouLeftCache["ses_bounded"])
        assertEquals(1, store.state.sinceYouLeftCache.size)

        // Reopening `ses_bounded` after newer turns (`m5`, `m6`) replaces its single cache entry
        // rather than accumulating a second key for the same session.
        val firstSnapshot = SessionFeature.SwitchSnapshot.from(store.state)
        summaryText.setValue("Second summary")
        val secondStore = h.makeStore(
            sessionID = "ses_bounded",
            cachedItems = back1,
            now = Instant.ofEpochSecond(1_300),
            sinceYouLeft = summaryStream { summaryText.value },
            configureState = { state ->
                val restored = (firstSnapshot?.restore(state) ?: state)
                    .noteOpening(seen = back1.map { it.id }.toSet(), leftAt = Instant.ofEpochSecond(1_120))
                assertEquals(1, restored.sinceYouLeftCache.size)
                restored
            },
        )

        secondStore.send(Action.TranscriptResponse("ses_bounded", Result.success(back2)))
        receiveSummary(secondStore)
        assertEquals(SinceYouLeft.CacheEntry(lastItemID = "m6", text = "Second summary"), secondStore.state.sinceYouLeftCache["ses_bounded"])
        assertEquals(1, secondStore.state.sinceYouLeftCache.size)

        // Caching across more than `maxCachedSessions` sessions evicts the oldest entries,
        // and `SwitchSnapshot` only carries entries for the snapshot's own session/family.
        var manyState = secondStore.state
        for (i in 1..24) manyState = manyState.cacheSinceYouLeft(lastItemID = "item_$i", text = "Summary $i", sessionID = "ses_extra_$i")
        assertEquals(SinceYouLeft.maxCachedSessions, manyState.sinceYouLeftCache.size)
        assertNull(manyState.sinceYouLeftCache["ses_extra_1"])
        assertEquals(SinceYouLeft.CacheEntry(lastItemID = "item_24", text = "Summary 24"), manyState.sinceYouLeftCache["ses_extra_24"])

        manyState = manyState.cacheSinceYouLeft(lastItemID = "m6", text = "Second summary", sessionID = "ses_bounded")
        val snapshot = SessionFeature.SwitchSnapshot.from(manyState)
        assertEquals(listOf("ses_bounded"), snapshot?.sinceYouLeftCache?.keys?.sorted())
    }

    @Test fun staysAcrossScrollingAndDismissesOnTapOrSend() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(reply("m3", markdown = "Turn 1"), reply("m4", markdown = "Turn 2"))

        val store = h.makeStore(
            sessionID = "ses_dismiss",
            cachedItems = away,
            seen = setOf("m1", "m2"),
            sinceYouLeft = SinceYouLeftClient.canned("Fixed the spring"),
        )
        val state = store.state

        store.send(Action.TranscriptResponse("ses_dismiss", Result.success(back)))
        receiveSummary(store)
        assertEquals("Fixed the spring", store.state.sinceYouLeft?.text)
        val shown = store.state.sinceYouLeft
        assertEquals(SinceYouLeft(sessionID = "ses_dismiss", lastItemID = "m4", text = "Fixed the spring", phase = SinceYouLeft.Phase.Done), shown)

        // Scrolling up and back down keeps the summary row in the transcript; it only dismisses on ✕ or send.
        store.state.newMessages?.generation?.let { generation ->
            store.send(Action.NewMessagesFit(generation = generation))
            store.send(Action.NewMessagesPassed(generation = generation))
        }
        store.send(Action.TranscriptBrowsingChanged(true))
        assertEquals("Fixed the spring", store.state.sinceYouLeft?.text)
        store.send(Action.TranscriptBrowsingChanged(false))
        assertEquals("Fixed the spring", store.state.sinceYouLeft?.text)
        assertFalse(store.state.sinceYouLeftDismissed)

        // Tapping ✕ dismisses the row and cancels any in-flight generation.
        store.send(Action.SinceYouLeftTapped)
        assertNull(store.state.sinceYouLeft)
        assertTrue(store.state.sinceYouLeftDismissed)

        // Sending a message also dismisses it immediately.
        var sendState = state.copy(sinceYouLeft = SinceYouLeft(sessionID = "ses_dismiss", lastItemID = "m4", text = "Fixed the spring", phase = SinceYouLeft.Phase.Done))
        sendState = withDependencies({ this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.sinceYouLeft)) }) {
            sendState.noteOwnSend()
        }
        assertNull(sendState.sinceYouLeft)
        assertTrue(sendState.sinceYouLeftDismissed)
    }

    @Test fun compactInputBoundsTokensAndExcludesFileContents() {
        val secret = "PRIVATE_KEY_MATERIAL_DO_NOT_INCLUDE"
        val codeBlock = """
            Here is the implementation:

            ```swift
            let token = "$secret"
            ```

            Updated the spring parameters and verified tests.
        """.trimIndent()
        val items = mutableListOf(user("m1"), reply("m2"))
        for (i in 1..40) {
            items += editTool("t$i", path = "/workspace/src/File$i.swift", secretContent = secret)
            items += reply("a$i", markdown = "$codeBlock Turn $i summary paragraph with extra detail.")
        }

        val input = SinceYouLeft.buildInput(
            sessionID = "ses_bounds", items = items, seen = setOf("m1", "m2"),
            leftAt = Instant.ofEpochSecond(1_000), now = Instant.ofEpochSecond(1_300), hasPendingRequest = true,
        )
        assertNotNull(input)
        assertTrue(input!!.prompt.length <= SinceYouLeft.maxInputCharacters)
        assertTrue(input.estimatedTokens <= SinceYouLeft.maxTokenEstimate)
        assertFalse(input.prompt.contains(secret))
        assertEquals("File1.swift", input.editedFiles.first())
        assertEquals("40 turns · 40 files edited · waiting on you", input.structuralFallback)
    }

    @Test fun wordByWordPrefixesStepsAndArrivalOffsets() {
        val sentence = "Fixed the drawer spring, ran the tests"
        assertEquals(
            listOf(
                "Fixed", "Fixed the", "Fixed the drawer", "Fixed the drawer spring,",
                "Fixed the drawer spring, ran", "Fixed the drawer spring, ran the", "Fixed the drawer spring, ran the tests",
            ),
            SinceYouLeft.wordPrefixes(sentence),
        )
        assertEquals(
            listOf("Fixed the drawer", "Fixed the drawer spring,", "Fixed the drawer spring, ran"),
            SinceYouLeft.wordSteps("Fixed the", "Fixed the drawer spring, ran"),
        )
    }
}

/**
 * iOS `SinceYouLeftFixtures`: transcripts for the input tests. `SinceYouLeftInputTests` pins
 * what the model sees; the same sets feed the before/after eval in docs/since-you-left-eval.md.
 */
internal object SinceYouLeftFixtures {
    data class Fixture(val name: String, val items: List<OpenCodeTranscriptItem>, val pendingRequests: List<String> = emptyList())

    const val secret = "SECRET_FILE_BODY_DO_NOT_SEND"
    val seen: Set<String> = setOf("seen-user", "seen-reply")
    private fun nextID(prefix: String) = "$prefix-${UUID.randomUUID().toString().uppercase()}"

    fun user(text: String, agent: Boolean = false): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.UserText(id = nextID("u"), text = text, source = if (agent) OpenCodePromptSource.ParentAgent else null)

    fun reply(markdown: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id = nextID("a"), text = markdown)

    fun shell(command: String, failed: Boolean = false, output: String = "PASS 412 tests"): OpenCodeTranscriptItem {
        val id = nextID("t")
        return OpenCodeTranscriptItem.Tool(
            id = id,
            call = OpenCodeToolCall(
                callID = id, messageID = "msg-$id", name = "bash", status = if (failed) OpenCodeToolCall.Status.Error else OpenCodeToolCall.Status.Completed,
                input = OpenCodeJSONValue.Object(mapOf("command" to OpenCodeJSONValue.Str(command), "description" to OpenCodeJSONValue.Str(command))),
                output = listOf(output, secret), error = if (failed) "exit 1: $output" else null, metadata = emptyMap(),
            ),
        )
    }

    fun tool(name: String, path: String = "/repo/OpenCode/Session/SessionView.swift", failed: Boolean = false): OpenCodeTranscriptItem {
        val id = nextID("t")
        return OpenCodeTranscriptItem.Tool(
            id = id,
            call = OpenCodeToolCall(
                callID = id, messageID = "msg-$id", name = name, status = if (failed) OpenCodeToolCall.Status.Error else OpenCodeToolCall.Status.Completed,
                input = OpenCodeJSONValue.Object(
                    mapOf("filePath" to OpenCodeJSONValue.Str(path), "oldString" to OpenCodeJSONValue.Str(secret), "newString" to OpenCodeJSONValue.Str(secret + "2")),
                ),
                output = listOf(secret), error = if (failed) "oldString not found" else null, metadata = emptyMap(),
            ),
        )
    }

    fun shellNotice(command: String, failed: Boolean = false): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Notice(
        id = nextID("n"), text = "Shell ${if (failed) "failed" else "finished"} · $command", isError = failed,
        kind = TranscriptNoticeKind(source = TranscriptNoticeKind.Source.Shell, outcome = if (failed) TranscriptNoticeKind.Outcome.Failed else TranscriptNoticeKind.Outcome.Finished),
    )

    fun subagentNotice(description: String, failed: Boolean = false): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Notice(
        id = nextID("n"), text = "Explore ${if (failed) "failed" else "finished"} · $description", isError = failed,
        kind = TranscriptNoticeKind(source = TranscriptNoticeKind.Source.Subagent, outcome = if (failed) TranscriptNoticeKind.Outcome.Failed else TranscriptNoticeKind.Outcome.Finished),
    )

    fun runError(message: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.RunError(id = nextID("e"), error = OpenCodeRunError(type = "provider", message = message))

    fun shells(commands: List<String>): List<OpenCodeTranscriptItem> = commands.map { shell(it) }

    val all: List<Fixture>
        get() {
            val prefix = listOf(
                OpenCodeTranscriptItem.UserText(id = "seen-user", text = "Earlier request"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "seen-reply", text = "Earlier reply"),
            )
            return listOf(
                Fixture(
                    "coordinator, shell heavy",
                    prefix + listOf(
                        user("You are a subagent spawned by another session. Own OC-481 drawer lag; rebase onto anomaly/main and run smoke.", agent = true),
                        reply("Starting with the worktree state."),
                    ) + shells(
                        listOf(
                            "git fetch anomaly main", "git status", "git log --oneline -5", "git rebase anomaly/main",
                            "hivemind run -- scripts/test-tier.sh smoke", "bun scripts/board.ts brief '{}'",
                        ),
                    ) + listOf(
                        reply("Rebase was clean. Smoke is running on mini."),
                    ) + shells(listOf("hivemind fetch run-118", "grep -c passed run-118.log", "bun scripts/board.ts update '{\"id\":\"OC-481\"}'")) + listOf(
                        shellNotice("hivemind run -- scripts/test-tier.sh area transcript"),
                        reply(
                            """
                            ## OC-481

                            HEAD `3f2a1b9` on `fix/drawer-lag`, rebased onto main. Smoke passed (412 tests) and `area transcript` passed.

                            - Drawer lag: row heights are cached, so opening the drawer no longer re-measures every row
                            - Argon review found one nit (a stale comment), fixed

                            ```
                            PASS 412 tests in 4m 51s
                            ```

                            Waiting on Kit's ✓ on R-212 before pushing.
                            """.trimIndent(),
                        ),
                    ),
                ),

                Fixture(
                    "fix done, tests running",
                    prefix + listOf(
                        user("The drawer stutters when I open it with 200 sessions, can you fix it?"),
                        reply("Profiling the drawer open first."),
                        tool("read"), tool("read"), tool("grep"),
                        reply("The drawer re-sorts all sessions on every frame of the spring. Moving the sort out of the body."),
                        tool("edit", path = "/repo/OpenCode/Drawer/DrawerView.swift"),
                        tool("edit", path = "/repo/OpenCode/Drawer/DrawerFeature.swift"),
                        shell("hivemind run -- scripts/test-tier.sh area drawer"),
                        reply("Fixed the drawer lag by sorting once when sessions change instead of every frame. The drawer tests are running now; I’ll report when they finish."),
                    ),
                ),

                Fixture(
                    "needs permission to deploy",
                    prefix + listOf(
                        user("Fix the board's broken upload button and deploy it"),
                        reply("Looking at the upload handler."),
                        tool("read", path = "/repo/src/upload.ts"),
                        tool("edit", path = "/repo/src/upload.ts"),
                        shell("bun test"), shell("bun run build"),
                        reply("The upload button posted to the old `/files` route. Pointed it at `/api/upload`; tests and the build pass. Deploying to the Worker next."),
                        shell("wrangler deploy", failed = true, output = "Authentication required"),
                        reply("Deploying needs your OK: wrangler wants to publish the board worker to production."),
                    ),
                    pendingRequests = listOf("Permission to run a command"),
                ),

                Fixture(
                    "asks a question",
                    prefix + listOf(
                        user("Make the composer spring feel snappier"),
                        reply("Trying three spring presets in the lab."),
                        tool("edit", path = "/repo/OpenCode/Composer/ComposerMotion.swift"),
                        shell("scripts/lab-compare.sh composer-spring"),
                        reply(
                            """
                            I recorded three presets side by side: Snappy (response 0.28), Today (0.35), and Soft (0.42).

                            Snappy settles 80 ms sooner but overshoots a little on fast flicks.

                            Which one do you want me to bake in?
                            """.trimIndent(),
                        ),
                    ),
                    pendingRequests = listOf("Question: Which spring preset should ship?"),
                ),

                Fixture(
                    "test failure, unresolved",
                    prefix + listOf(
                        user("Run the transcript tests after the scroll change"),
                        reply("The scroll anchor change is in."),
                        reply("Running the transcript area now."),
                        shell("hivemind run -- scripts/test-tier.sh area transcript", failed = true, output = "FAIL TranscriptScrollTests.testAnchor"),
                        shellNotice("hivemind run -- scripts/test-tier.sh area transcript", failed = true),
                    ),
                ),

                Fixture(
                    "run error",
                    prefix + listOf(
                        user("Port the new Markdown table renderer to the transcript"),
                        reply("Reading the table renderer and the transcript row code."),
                        tool("read"), tool("read"), tool("read"),
                        reply("Tables now render as a horizontally scrolling grid. Moving on to wiring copy support."),
                        tool("edit", path = "/repo/OpenCode/Transcript/MarkdownTable.swift"),
                        runError("The model's context window is full. Compact the session to keep going."),
                    ),
                ),

                Fixture(
                    "investigation, asks to proceed",
                    prefix + listOf(
                        user("Why does the session sometimes miss the last message after reconnecting?"),
                        reply("Checking the SSE reconnect path."),
                        tool("grep"), tool("read"), tool("read"),
                        shell("rg -n lastEventID OpenCode"),
                        shell("sqlite3 'file:opencode.db?mode=ro' 'select count(*) from message'"),
                        reply(
                            """
                            Found it. On reconnect we resume from the last event we *rendered*, not the last event we *received*, so an event that arrives during a render is skipped.

                            The fix is small: track the received ID in `EventBatcher` and resume from that.

                            Want me to make the change and add a regression test?
                            """.trimIndent(),
                        ),
                    ),
                ),

                Fixture(
                    "code-heavy reply",
                    prefix + listOf(
                        user("Add a retry to the link preview fetch"),
                        reply("Adding the retry."),
                        tool("edit", path = "/repo/OpenCode/Media/LinkPreview.swift"),
                        reply(
                            """
                            Here is the change:

                            ```swift
                            let token = "$secret"
                            try await NetworkRetry.${'$'}policy.withValue(.once) { try await fetch(url) }
                            ```

                            Link previews now retry once after a dropped connection, matching the shared retry policy.
                            """.trimIndent(),
                        ),
                        shell("hivemind run -- scripts/test-tier.sh area media"),
                        reply("Media tests pass."),
                    ),
                ),

                Fixture(
                    "subagent fan-out",
                    prefix + listOf(
                        user("Audit the haptics against the vocabulary and fix what's off"),
                        reply("Splitting the audit across three subagents: composer, drawer and voice."),
                        tool("task"), tool("task"), tool("task"),
                        subagentNotice("Audit composer haptics"),
                        subagentNotice("Audit drawer haptics"),
                        subagentNotice("Audit voice haptics", failed = true),
                        reply("Composer and drawer were clean except a stray `.tap` on drawer close, now removed. The voice audit crashed, so I'm rerunning it myself."),
                        tool("read", path = "/repo/OpenCode/Voice/CallTray.swift"),
                        reply("Voice plays `.confirm` on End where it should play `.cancel`. Fixed that too; all three areas match the vocabulary now."),
                    ),
                ),

                Fixture(
                    "many agent turns",
                    prefix + listOf(
                        user("You are a subagent spawned by another session. Rebase fix/model-picker onto main.", agent = true),
                        reply("Rebasing."), shell("git rebase anomaly/main", failed = true, output = "CONFLICT ModelPicker.swift"),
                        reply("Two conflicts in ModelPicker.swift and ModelPickerTests.swift."),
                        user("Keep main's level menu and re-apply your search field on top.", agent = true),
                        reply("Resolving that way."), tool("edit", path = "/repo/OpenCode/Composer/ModelPicker.swift"),
                        shell("git add -A"), shell("git rebase --continue"),
                        reply("Conflicts resolved, keeping main's level menu with the search field on top."),
                        user("Run the composer tests.", agent = true),
                        shell("hivemind run -- scripts/test-tier.sh area composer"),
                        reply("Composer tests pass on the rebased branch (HEAD `91c0d2e`)."),
                    ),
                ),

                Fixture(
                    "tools only, no prose",
                    prefix + listOf(
                        user("Rename SessionHeader to SessionTitleBar everywhere"),
                        tool("edit", path = "/repo/OpenCode/Session/SessionHeader.swift"),
                        tool("edit", path = "/repo/OpenCode/Session/SessionView.swift"),
                        tool("edit", path = "/repo/OpenCode/Drawer/DrawerRow.swift"),
                        shell("git mv SessionHeader.swift SessionTitleBar.swift"),
                        user("Also update the docs"),
                        tool("edit", path = "/repo/docs/drawer-and-settings.md"),
                    ),
                ),

                Fixture(
                    "failure then fixed",
                    prefix + listOf(
                        user("Fix the flaky outbox test"),
                        reply("Reproducing it first."),
                        shell("hivemind run -- xcodebuild test -only-testing OutboxTests", failed = true, output = "FAIL testRetryOrder"),
                        shell("hivemind run -- xcodebuild test -only-testing OutboxTests", failed = true, output = "FAIL testRetryOrder"),
                        reply("It fails when two sends share a timestamp: the outbox sorted by date only. Sorting by date, then client ID."),
                        tool("edit", path = "/repo/OpenCode/Composer/Outbox.swift"),
                        shell("hivemind run -- xcodebuild test -only-testing OutboxTests"),
                        reply("Fixed the flaky outbox test: sends with the same timestamp now keep their order. It passed 20 runs in a row."),
                    ),
                ),

                Fixture(
                    "long report with headings",
                    prefix + listOf(
                        user("Give me a status on the voice call latency work"),
                        reply("Pulling the latest numbers."),
                        tool("read"), shell("bun scripts/perf-report.py voice"), shell("cat perf/voice-latest.json"),
                        reply(
                            """
                            # Voice latency

                            First audio now arrives in 610 ms on average, down from 980 ms.

                            ## What changed

                            - The microphone starts before the WebSocket handshake finishes
                            - Greeting audio is cached per voice

                            | Metric | Before | After |
                            | --- | --- | --- |
                            | First audio | 980 ms | 610 ms |

                            ## Next

                            The remaining 200 ms is the server's model warm-up. I can't fix that from the app; it needs a server change.
                            """.trimIndent(),
                        ),
                    ),
                ),

                Fixture(
                    "work in progress",
                    prefix + listOf(
                        user("Add Spotlight indexing for sessions"),
                        reply("Adding a CoreSpotlight indexer for session titles."),
                        tool("write", path = "/repo/OpenCode/System/SpotlightIndexer.swift"),
                        tool("edit", path = "/repo/OpenCode/App/AppFeature.swift"),
                        reply("The indexer is in. Now wiring deletes so archived sessions leave Spotlight."),
                        tool("edit", path = "/repo/OpenCode/System/SpotlightIndexer.swift"),
                        shell("hivemind run -- scripts/test-tier.sh area settings"),
                    ),
                ),
            )
        }

    fun input(fixture: Fixture): SinceYouLeftInput? = SinceYouLeft.buildInput(
        sessionID = "ses_eval", items = fixture.items, seen = seen,
        hasPendingRequest = fixture.pendingRequests.isNotEmpty(), pendingRequests = fixture.pendingRequests,
        ignoreMinimumAbsenceDuration = true,
    )
}

/** Port of iOS `SinceYouLeftInputTests`. */
class SinceYouLeftInputTests {
    private val commandWords = listOf("git ", "hivemind", "bun ", "xcodebuild", "rg -n", "sqlite3", "Tool:", "Shell", "shell")

    private fun input(name: String): SinceYouLeftInput {
        val fixture = SinceYouLeftFixtures.all.first { it.name == name }
        return assertNotNull(SinceYouLeftFixtures.input(fixture))
    }

    private fun prompt(name: String): String = input(name).prompt

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    @Test fun everyFixtureLeadsWithProseAndNeverListsShellsOrContents() {
        for (fixture in SinceYouLeftFixtures.all) {
            val input = SinceYouLeftFixtures.input(fixture)
            org.junit.Assert.assertNotNull(fixture.name, input)
            val prompt = input!!.prompt
            assertTrue(fixture.name, prompt.length <= SinceYouLeft.maxInputCharacters)
            assertFalse(fixture.name, prompt.contains(SinceYouLeftFixtures.secret))
            assertFalse(fixture.name, prompt.contains("PASS 412"))
            for (word in commandWords) assertFalse("${fixture.name} mentions $word", prompt.contains(word))
            val hasProse = fixture.items.any { it is OpenCodeTranscriptItem.AssistantMarkdown && it.id !in SinceYouLeftFixtures.seen }
            if (fixture.pendingRequests.isNotEmpty()) {
                assertTrue(fixture.name, prompt.startsWith("Needs the developer:\n- ${fixture.pendingRequests[0]}\n\nThe agent's latest update:\n"))
            } else if (hasProse) {
                assertTrue(fixture.name, prompt.startsWith("The agent's latest update:\n"))
            }
        }
    }

    @Test fun latestUpdateIsTheNewestTurnsOpeningAndClosingProse() {
        val prompt = prompt("coordinator, shell heavy")
        val latest = prompt.split("\n\n").first()
        assertTrue(latest.contains("HEAD 3f2a1b9 on fix/drawer-lag, rebased onto main"))
        assertTrue(latest.contains("Waiting on Kit's ✓ on R-212 before pushing"))
        assertFalse(latest.contains("## OC-481"))
        assertTrue(prompt.contains("- Asked: Own OC-481 drawer lag"))
        assertFalse(prompt.contains(OpenCodePromptSource.SUBAGENT_PREAMBLE))
        val earlier = prompt.indexOf("- Agent: Starting with the worktree state.")
        val later = prompt.indexOf("- Agent: Rebase was clean. Smoke is running on mini.")
        assertTrue(earlier >= 0 && later >= 0)
        assertTrue(earlier < later)
    }

    @Test fun pendingRequestsAndRunErrorsFollowTheLatestUpdate() {
        val deploy = prompt("needs permission to deploy")
        assertTrue(deploy.startsWith("Needs the developer:\n- Permission to run a command\n\nThe agent's latest update:\nDeploying needs your OK"))
        // The failed deploy came before the agent's last words, so it isn't repeated.
        assertFalse(deploy.contains("failed"))

        assertTrue(prompt("run error").contains("\n\nProblems:\n- The model's context window is full"))
        assertTrue(
            prompt("test failure, unresolved").startsWith(
                "The agent's latest update:\nThe scroll anchor change is in.\nRunning the transcript area now.\nThen it failed.\n\n",
            ),
        )
        val fixed = prompt("failure then fixed")
        assertTrue(!fixed.contains("failed") && !fixed.contains("Problems:"))
    }

    @Test fun thinLastMessageLeansOnTheOneBeforeIt() {
        val latest = prompt("code-heavy reply").split("\n\n").first()
        assertEquals(
            "The agent's latest update:\nLink previews now retry once after a dropped connection, matching the shared retry policy.\nMedia tests pass.",
            latest,
        )
        assertFalse(prompt("code-heavy reply").contains("- Agent: Link previews"))
    }

    @Test fun firstLineDropsTheModelsExtraLines() {
        assertEquals("Needs your OK to deploy", SinceYouLeft.firstLine("\nNeeds your OK to deploy\nFixed the route"))
        assertEquals("Fixed the drawer lag", SinceYouLeft.firstLine("Fixed the drawer lag"))
        assertEquals("", SinceYouLeft.firstLine(""))
    }

    @Test fun toolOnlyTurnsFallBackToEditedFileNames() {
        val input = input("tools only, no prose")
        assertTrue(input.prompt.startsWith("Files edited: SessionHeader.swift, SessionView.swift, DrawerRow.swift"))
        assertEquals("2 turns · 4 files edited", input.structuralFallback)
    }

    @Test fun newestContextSurvivesWhenTheBudgetRunsOut() {
        val items = mutableListOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "seen-user", text = "Earlier"))
        for (i in 1..120) {
            items += OpenCodeTranscriptItem.UserText(id = "u$i", text = "Request $i " + "detail ".repeat(30))
            items += SinceYouLeftFixtures.shell("bun test")
            items += OpenCodeTranscriptItem.AssistantMarkdown(id = "a$i", text = "Finished step $i. " + "More words. ".repeat(40))
        }
        val input = assertNotNull(
            SinceYouLeft.buildInput(sessionID = "ses_budget", items = items, seen = setOf("seen-user"), ignoreMinimumAbsenceDuration = true),
        )
        assertTrue(input.prompt.length <= SinceYouLeft.maxInputCharacters)
        assertTrue(input.estimatedTokens <= SinceYouLeft.maxTokenEstimate)
        assertTrue(input.prompt.startsWith("The agent's latest update:\nFinished step 120."))
        assertTrue(input.prompt.contains("Asked: Request 120"))
        assertTrue(input.prompt.contains("Finished step 119."))
        assertFalse(input.prompt.contains("Request 1 "))
    }

    @Test fun pendingRequestLinesNeverIncludeCommandsOrPaths() {
        val permissions = listOf(
            OpenCodePermissionRequest(id = "p1", sessionID = "ses_a", action = "bash", resources = listOf("wrangler deploy --env production"), save = null),
            OpenCodePermissionRequest(id = "p2", sessionID = "ses_a", action = "external_directory", resources = listOf("/Users/kit/private"), save = null),
            OpenCodePermissionRequest(id = "p3", sessionID = "ses_other", action = "edit", resources = listOf("/repo/a.swift"), save = null),
        )
        val forms = listOf(
            OpenCodeFormRequest(
                id = "f1", sessionID = "ses_a", title = "Spring",
                fields = listOf(OpenCodeFormField(key = "q0", type = OpenCodeFormFieldType.String, title = "Which spring preset should ship?")),
                metadata = mapOf("kind" to OpenCodeJSONValue.Str("question")),
            ),
        )
        val lines = SinceYouLeft.pendingRequestLines(permissions = permissions, forms = forms, sessionID = "ses_a")
        assertEquals(
            listOf(
                "Question: Which spring preset should ship?",
                "Permission to run a command",
                "Permission to use a folder outside the project",
            ),
            lines,
        )
    }
}
