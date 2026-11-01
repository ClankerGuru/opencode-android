package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Port of the `SessionFeature` half of iOS `NewMessagesTests.swift`.
 *
 * The New divider (an experiment): what arrived while the reader was away,
 * shown only when it doesn't fit on screen, and the reads that bring it
 * before they're back.
 *
 * Not ported here (:feature:transcript, `NewMessagesMark` / `NewMessagesPlacement` / `TranscriptScrollBehavior` alone):
 * `firstUnseenSkipsWhatWasOnScreenAndClientNotices`, `ownMessagesNeverCountAndReadWhatsAboveThem`,
 * `placementFallsToTheNextEntryWithNewItems`, `olderRowsPagedInAboveWhatWasSeenArentNew`,
 * `placementSkipsRowsTheReaderHadAndKeepsATimeWithItsMessage`, `revealsBrowseFromTheDividerOrFollowAtTheEnd`,
 * `landingAtTheDividerDoesntPageInHistory`.
 * Not ported here (:feature:drawer, `HomeFeature`): `switchingBackMarksWhatArrivedSince`,
 * `theDrawerReadsLikelySessionsBeforeATap`, `leavingTheAppHoldsTheStreamUntilTheReturn`.
 */
class NewMessagesTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")

    private fun session(id: String) = OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10))
    private fun user(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id = id, text = id)
    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = "Reply $id")

    private val divider = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.newMessagesDivider))

    /** Runs [body] with the New Messages Divider experiment on (iOS `on { }`). */
    private fun <R> Harness.on(body: () -> R): R =
        DependencyValues.withValues(values.update { this[ExperimentalFeatures] = divider }, body)

    private fun Harness.sessionState(id: String, items: List<OpenCodeTranscriptItem>): SessionFeature.State =
        state { SessionFeature.State.session(server = server, session = session(id), cachedItems = items) }

    private fun Harness.resumeStore(
        state: SessionFeature.State,
        dividerEnabled: Boolean = true,
        transcript: () -> List<OpenCodeTranscriptItem>,
    ): TestStore<SessionFeature.State, Action> {
        val store = store(state) {
            if (dividerEnabled) this[ExperimentalFeatures] = divider
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> transcript() },
                getSession = { _, id -> session(id) },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
                pendingInputs = { _, _ -> emptyList() },
                contextUsage = { _, _ -> OpenCodeContextReading.Inconclusive },
                viewSession = { _, _, _ -> },
            )
        }
        store.exhaustive = false
        return store
    }

    /**
     * Back in the app: the read after the return puts the divider above the
     * first row that wasn't on screen, and a later return with nothing new
     * takes it away.
     */
    @Test fun theReadAfterAReturnPlacesTheDividerThenClearsIt() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(reply("m3"), reply("m4"))
        val reads = LockIsolated(listOf(away, back, back))
        val state = h.sessionState("ses_new", away).copy(hasRenderedFirstContentFrame = true, initialTranscriptIsFresh = true, isVisible = true)
        val store = h.resumeStore(state) {
            reads.withValue { if (it.size > 1) it.drop(1) to it[0] else it to it[0] }
        }

        // A read while the reader is still here changes nothing.
        store.send(Action.RefreshRequested(RefreshReason.Invalidation))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertNull(store.state.newMessages)

        store.send(Action.VisibilityChanged(false))
        assertEquals(setOf("m1", "m2"), store.state.newMessagesBaseline)
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertEquals(NewMessagesMark(itemID = "m3", lastItemID = "m4", generation = 1), store.state.newMessages)
        assertNull(store.state.newMessagesBaseline)

        // Once read past, a quick trip away with nothing new shows nothing.
        store.send(Action.NewMessagesPassed(generation = 1))
        store.send(Action.VisibilityChanged(false))
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertNull(store.state.newMessages)
        h.skipInFlightEffects()
    }

    /**
     * Off (the default), a session is a plain chat: what arrived is still
     * noted, so the list can bring it in at the end, but its mark never
     * shows a divider, however much arrived.
     */
    @Test fun withTheExperimentOffNoMarkShowsADivider() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + (3 until 40).map { reply("m$it") }
        val state = h.sessionState("ses_off", away).copy(hasRenderedFirstContentFrame = true, initialTranscriptIsFresh = true, isVisible = true)
        val store = h.resumeStore(state, dividerEnabled = false) { back }
        store.send(Action.VisibilityChanged(false))
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertEquals("m3", store.state.newMessages?.itemID)
        assertEquals(false, store.state.newMessages?.showsDivider)
        val placement = NewMessagesMark.resolve(store.state.newMessages, store.state.items, TranscriptEntry.build(store.state.items))
        assertEquals(false, placement?.showsDivider)
        h.skipInFlightEffects()

        val opened = h.state { h.sessionState("ses_off_open", back).noteOpening(seen = setOf("m1", "m2")) }
        assertEquals(false, opened.newMessages?.showsDivider)
        val enabled = h.on { h.sessionState("ses_on_open", back).noteOpening(seen = setOf("m1", "m2")) }
        assertEquals(true, enabled.newMessages?.showsDivider)
    }

    /**
     * Several messages that fit on screen show at the bottom with no divider:
     * in front of the reader they're read, so only what comes after them can
     * make one.
     */
    @Test fun messagesThatFitAreReadOnceShown() = sessionTest { h ->
        val state = h.on {
            h.sessionState("ses_fit", listOf(user("m1"), reply("m2"), reply("m3"), reply("m4"), reply("m5")))
                .copy(isVisible = true)
                .noteOpening(seen = setOf("m1", "m2"))
        }
        assertEquals("m3", state.newMessages?.itemID)
        val generation = state.newMessages?.generation ?: 0
        val store = h.store(state) { this[ExperimentalFeatures] = divider }
        store.exhaustive = false
        // An older mark's answer changes nothing.
        store.send(Action.NewMessagesFit(generation = generation - 1))
        assertNotNull(store.state.newMessages)
        store.send(Action.NewMessagesFit(generation = generation))
        assertNull(store.state.newMessages)
        assertEquals(setOf("m1", "m2", "m3", "m4", "m5"), store.state.newMessagesBaseline)
        assertNull(store.state.newMessagesFitThrough)

        var after = store.state
        h.on {
            after = after.updateNewMessagesMark(settles = false)
            assertNull("nothing new since what fit", after.newMessages)
            after = after.copy(items = after.items + reply("m6")).updateNewMessagesMark(settles = true)
        }
        assertEquals("m6", after.newMessages?.itemID)
    }

    /**
     * Decided as the reader arrives, the divider stays as decided while the
     * reply streams on in front of them: what fit is read and nothing that
     * streams in after it makes a mark, and a divider that didn't fit keeps
     * its place. (iOS `@Test(arguments: [true, false])`.)
     */
    @Test fun theArrivalsDecisionHoldsWhileStreaming() {
        for (fits in listOf(true, false)) sessionTest { h ->
            val state = h.on {
                h.sessionState("ses_decided", listOf(user("m1"), reply("m2"), reply("m3")))
                    .copy(isVisible = true, hasRenderedFirstContentFrame = true)
                    .noteOpening(seen = setOf("m1", "m2"))
            }
            val mark = state.newMessages!!
            assertEquals(NewMessagesMark(itemID = "m3", lastItemID = "m3", generation = 1), mark)
            val store = h.store(state) {
                this[ExperimentalFeatures] = divider
                this[Clock] = ImmediateClock()
            }
            if (fits) {
                // The list measured it all on screen.
                store.send(Action.NewMessagesFit(generation = mark.generation)) {
                    it.copy(newMessages = null, newMessagesBaseline = setOf("m1", "m2", "m3"))
                }
            }
            // An older mark's answer changes nothing.
            store.send(Action.NewMessagesFit(generation = mark.generation - 1))
            store.send(
                Action.ContentBatch(
                    sessionID = "ses_decided",
                    events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = "Streaming reply")),
                ),
            ) {
                it.copy(
                    isExternallyActive = true,
                    workingRun = WorkingRun(counter = 1, startID = 1, key = "ses_decided", sawStart = true, startTurn = "m1", baseline = "msg_live-text-0"),
                    streamIsHealthy = true,
                    streamSupportsProjection = true,
                    projectedMessageIDs = setOf("msg_live"),
                    lastProjectedMessageID = "msg_live",
                    liveText = mapOf("msg_live-text-0" to "Streaming reply"),
                    items = it.items + OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_live-text-0", text = "Streaming reply"),
                    // In front of the reader: seen, whatever the read after it brings.
                    newMessagesWatched = setOf("msg_live-text-0"),
                )
            }
            assertEquals("fits=$fits", if (fits) null else mark, store.state.newMessages)
            h.skipInFlightEffects()
        }
    }

    /**
     * Rows the stream brings while away are measured as a whole each time:
     * rows that each fit can add up to a backlog that doesn't. What fit is
     * read only once the reader is back in front of it.
     */
    @Test fun whatFitsWhileAwayIsMeasuredAgainAsMoreArrives() = sessionTest { h ->
        val state = h.on { h.sessionState("ses_away", listOf(user("m1"), reply("m2"), reply("m3"))).noteOpening(seen = setOf("m1", "m2")) }
        val store = h.store(state) {
            this[ExperimentalFeatures] = divider
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false
        store.send(Action.NewMessagesFit(generation = store.state.newMessages?.generation ?: 0))
        assertNull(store.state.newMessages)
        assertEquals("m3", store.state.newMessagesFitThrough)
        assertEquals("not read while away", setOf("m1", "m2"), store.state.newMessagesBaseline)

        val away = h.on { store.state.copy(items = store.state.items + reply("m4")).updateNewMessagesMark(settles = false) }
        assertEquals("the whole span, from the first row they missed", "m3", away.newMessages?.itemID)
        assertNull(away.newMessagesFitThrough)

        // Back in front of a span that fit: it's read.
        store.send(Action.VisibilityChanged(true))
        assertEquals(setOf("m1", "m2", "m3"), store.state.newMessagesBaseline)
        assertNull(store.state.newMessagesFitThrough)
        h.skipInFlightEffects()
    }

    /**
     * A mark placed early (the drawer's copy, or the stream while away) is
     * placed again when the read after the return brings more below it, so
     * the list brings the reader to those rows too; leaving drops it.
     */
    @Test fun moreRowsUnderAnEarlyMarkMakeANewMark() = sessionTest { h ->
        var state = h.on { h.sessionState("ses_more", listOf(user("m1"), reply("m2"), reply("m3"))).noteOpening(seen = setOf("m1", "m2")) }
        val early = state.newMessages
        assertEquals("m3", early?.itemID)
        state = h.on { state.copy(items = state.items + reply("m4")).updateNewMessagesMark(settles = true) }
        assertEquals("m3", state.newMessages?.itemID)
        assertNotEquals(early?.generation, state.newMessages?.generation)
        state = h.on { state.noteLeaving() }
        assertNull(state.newMessages)
        assertEquals(setOf("m1", "m2"), state.newMessagesBaseline)
    }

    /**
     * The divider's window keeps rows above it, short of every loaded row (a
     * window over all of them asks for older history).
     */
    @Test fun theDividersWindowStopsShortOfEveryLoadedRow() = sessionTest { h ->
        val items = (0 until 30).map { reply("m$it") }
        val state = h.on { h.sessionState("ses_window", items).noteOpening(seen = (0 until 12).map { "m$it" }.toSet()) }
        assertEquals("m12", state.newMessages?.itemID)
        assertEquals(29, state.visibleLimit)
    }

    /**
     * Back while a message's read started in the background is still out:
     * the return's page read follows it, and a failed one forgets the baseline.
     */
    @Test fun theReturnAlwaysReadsThePage() = sessionTest { h ->
        val state = h.sessionState("ses_settle", listOf(user("m1"))).copy(
            hasRenderedFirstContentFrame = true,
            newMessagesBaseline = setOf("m1"),
            nextRefreshID = 1,
            refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "m2")),
        )
        val store = h.resumeStore(state) { listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "m1")) }
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshRequested::class)
        assertEquals(RefreshReason.Resume, store.state.refresh?.followup)

        store.send(Action.RefreshResponse(sessionID = "ses_settle", requestID = 1, result = Result.failure(OpenCodeClientError(message = "offline"))))
        assertEquals("the page read still follows", setOf("m1"), store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    @Test fun aFailedReadAfterTheReturnForgetsTheBaseline() = sessionTest { h ->
        val state = h.sessionState("ses_fail", listOf(user("m1"))).copy(
            isVisible = true,
            newMessagesBaseline = setOf("m1"),
            refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Resume),
        )
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.RefreshResponse(sessionID = "ses_fail", requestID = 1, result = Result.failure(OpenCodeClientError(message = "offline"))))
        assertNull(store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    @Test fun aGlanceAtControlCenterIsntLeaving() = sessionTest { h ->
        val state = h.sessionState("ses_glance", listOf(user("m1"))).copy(hasRenderedFirstContentFrame = true, isVisible = true)
        val store = h.store(state) { this[ExperimentalFeatures] = divider }
        store.exhaustive = false
        store.send(Action.SceneActiveChanged(false))
        store.send(Action.VisibilityChanged(false))
        assertNull(store.state.newMessagesBaseline)
        store.send(Action.SceneEnteredBackground)
        assertEquals(setOf("m1"), store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    /** Switched off mid-session, any mark on screen goes. */
    @Test fun switchingTheExperimentOffClearsTheDivider() = sessionTest { h ->
        val state = h.sessionState("ses_toggle", listOf(user("m1"), reply("m2")))
            .copy(newMessages = NewMessagesMark(itemID = "m2", generation = 1, showsDivider = true))
        val store = h.store(state)
        store.send(Action.NewMessagesDividerChanged(true))
        store.send(Action.NewMessagesDividerChanged(false)) { it.copy(newMessages = null) }
        store.finish()

        val quiet = h.sessionState("ses_toggle_quiet", listOf(user("m1"), reply("m2")))
            .copy(newMessages = NewMessagesMark(itemID = "m2", generation = 1, showsDivider = false))
        val quietStore = h.store(quiet)
        quietStore.send(Action.NewMessagesDividerChanged(false)) { it.copy(newMessages = null) }
        quietStore.finish()
    }

    /**
     * A send from this phone reads everything above it, after the return's
     * read settled the divider too: the divider goes, and nothing waiting to
     * be settled counts as new.
     */
    @Test fun sendingClearsTheDivider() = sessionTest { h ->
        val state = h.sessionState("ses_send", listOf(user("m1"), reply("m2"), reply("m3"))).copy(
            newMessages = NewMessagesMark(itemID = "m3", generation = 1, showsDivider = true),
            newMessagesBaseline = setOf("m1", "m2"),
        ).withDraft("Thanks")
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> awaitCancellation() },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        assertNull(store.state.newMessages)
        assertNull(store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    @Test fun passingTheDividerClearsOnlyItsOwnMark() = sessionTest { h ->
        val state = h.sessionState("ses_pass", listOf(user("m1"))).copy(newMessages = NewMessagesMark(itemID = "m1", generation = 2))
        val store = h.store(state)
        store.send(Action.NewMessagesPassed(generation = 1))
        store.send(Action.NewMessagesPassed(generation = 2)) { it.copy(newMessages = null) }
        store.finish()
    }

    /**
     * A session opened again from a copy that already has what arrived since:
     * its first frame carries the divider, and enough rows to show it.
     */
    @Test fun openingFromAFresherCopyPlacesTheDividerInTheFirstFrame() = sessionTest { h ->
        val seen = (0 until 4).map { "m$it" }.toSet()
        val items = listOf(user("m0")) + (1 until 30).map { reply("m$it") }
        val detail = h.on { h.sessionState("ses_open", items).noteOpening(seen = seen) }
        assertEquals("m4", detail.newMessages?.itemID)
        assertTrue(detail.visibleItems.any { it.id == "m4" })
        // With rows above it, so landing there doesn't page in more history.
        assertEquals("m1", detail.visibleItems.firstOrNull()?.id)
        // A copy this run hasn't shown is taken as seen.
        val fresh = h.on { h.sessionState("ses_fresh", items).noteOpening(seen = null) }
        assertNull(fresh.newMessages)
    }
}
