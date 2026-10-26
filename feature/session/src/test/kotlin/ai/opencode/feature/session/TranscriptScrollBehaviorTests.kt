package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of the `SessionFeature` parts of iOS `TranscriptScrollBehaviorTests.swift` (in its `TranscriptRowGrowthTests`
 * suite): the session's transcript window. The rest of the file is :feature:transcript's.
 *
 * Here: `browsingWindowKeepsItsLeadingBoundaryAcrossTailAppends`, and the `SessionFeature` halves of
 * `jumpToLatestWhenWindowTrimsGlidesToBottomWithoutTopGapOrLossOfPosition` (the store's window trim) and
 * `openingShortCachedSessionAndFinishingPrewarmedLoadingRefreshStayAlignedWithinOnePointFivePt` (`hasEarlierItems` on a short
 * cached session). Their `TranscriptListController` / `UICollectionView` halves are UIKit-only, as are
 * `openingAndDelayedLoadsKeepTheReaderPinnedToTheBottom`, `browsingAnchorHoldsAcrossDelayedLoadsStreamingReconnectPagingAndSelfSizing`,
 * `visiblePreTailRowResizingAfterOpeningSettleDoesNotGrowOrMoveHeldTailRow` and
 * `shortTranscriptHistoryRowLeavingGlidesTopGapClosedOverSeveralFrames`, which only use an inert `SessionFeature` store
 * to drive the UIKit list. Every other test in the file is :feature:transcript's (`TranscriptScrollBehavior` and the list).
 */
class TranscriptRowGrowthTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    /** The `SessionFeature` half: Jump to Latest leaving browsing trims the window back to the live tail. */
    @Test fun jumpToLatestWhenWindowTrimsGlidesToBottomWithoutTopGapOrLossOfPosition() = sessionTest { h ->
        val session = OpenCodeSession(id = "ses_g2a", title = "G2A", projectID = "p", directory = "/p", updatedAt = SessionTestSupport.distantPast)
        val allItems = (0 until 80).map { OpenCodeTranscriptItem.UserText(id = "m$it", text = "Message $it: line 1\nline 2") }
        val sessionState = h.state { SessionFeature.State.session(server = server, session = session, cachedItems = allItems) }
            .copy(hasRenderedFirstContentFrame = true, browsingWindowStartID = "m0")
        assertEquals(80, sessionState.visibleItems.size)
        val store = h.store(sessionState)
        store.exhaustive = false
        store.send(Action.TranscriptBrowsingChanged(false))
        assertNull(store.state.browsingWindowStartID)
        assertEquals(80, store.state.visibleItems.size)
        h.skipInFlightEffects()
    }

    /** The `SessionFeature` half: a short cached session claims no earlier items, before or during its open read. */
    @Test fun openingShortCachedSessionAndFinishingPrewarmedLoadingRefreshStayAlignedWithinOnePointFivePt() = sessionTest { h ->
        val shortSession = OpenCodeSession(id = "ses_c1", title = "Short", projectID = "p", directory = "/p", updatedAt = SessionTestSupport.distantPast)
        val shortCached = listOf(
            OpenCodeTranscriptItem.UserText(id = "u0", text = "Upload this file"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "a0", text = "Uploaded cleanly."),
        )
        var shortState = h.state { SessionFeature.State.session(server = server, session = shortSession, cachedItems = shortCached) }
        assertFalse("Short cached session (< 20 items) must not claim earlier items on frame 0 before refresh", shortState.hasEarlierItems)
        shortState = shortState.copy(refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))
        assertFalse("Starting .open refresh must not toggle hasEarlierItems on a short cached session", shortState.hasEarlierItems)
    }

    @Test fun browsingWindowKeepsItsLeadingBoundaryAcrossTailAppends() = sessionTest { h ->
        val session = OpenCodeSession(id = "ses_window", title = "Window", projectID = "project", directory = "/project", updatedAt = SessionTestSupport.distantPast)
        var state = h.state {
            SessionFeature.State.session(
                server = server,
                session = session,
                cachedItems = (0 until 30).map { OpenCodeTranscriptItem.UserText(id = "message-$it", text = "Message $it") },
            )
        }
        state = state.copy(browsingWindowStartID = state.visibleItems.firstOrNull()?.id)
        val leadingID = state.visibleItems.firstOrNull()?.id

        state = state.copy(items = state.items + (30 until 35).map { OpenCodeTranscriptItem.AssistantMarkdown(id = "message-$it", text = "") })
        assertEquals(leadingID, state.visibleItems.firstOrNull()?.id)
        assertEquals(25, state.visibleItems.size)

        state = state.copy(browsingWindowStartID = null)
        assertEquals("message-15", state.visibleItems.firstOrNull()?.id)
        assertEquals(20, state.visibleItems.size)
    }
}
