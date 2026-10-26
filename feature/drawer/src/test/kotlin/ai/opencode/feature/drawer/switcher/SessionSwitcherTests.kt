package ai.opencode.feature.drawer.switcher

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Port of iOS `SessionSwitcherTests.swift`. */
class SessionSwitcherTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun initial(): HomeFeature.State = HomeFeature.State(
        server = OpenCodeServer(url = "http://localhost:9", username = "", password = ""),
        snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()),
    )

    @Test fun openingAndClosingGridDoesNotLoadOrNavigate() = runTest {
        val store = TestStore(initial(), HomeFeature(), this) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.sessionSwitcher))
        }
        // Exhaustivity and unimplemented dependencies catch any accidental request
        // fan-out, read receipt or navigation while presenting the grid.
        store.send(HomeFeature.Action.SessionSwitcher(SessionSwitcherFeature.Action.PresentationChanged(true))) {
            it.copy(sessionSwitcher = it.sessionSwitcher.copy(isPresented = true))
        }
        store.send(HomeFeature.Action.SessionSwitcher(SessionSwitcherFeature.Action.PresentationChanged(false))) {
            it.copy(sessionSwitcher = it.sessionSwitcher.copy(isPresented = false))
        }
        store.finish()
    }

    @Test fun staleCardCannotNavigate() = runTest {
        val store = TestStore(initial(), HomeFeature(), this) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.sessionSwitcher))
        }
        store.send(HomeFeature.Action.SessionSwitcher(SessionSwitcherFeature.Action.Selected("deleted-session")))
        store.finish()
    }

    @Test fun zoomStartsAtTheLiveScreenAndEndsInTheStack() {
        val layout = SessionSwitcherLayout(size = Size(440f, 956f))
        val full = layout.zoom(2, target = 2, focus = 2f, progress = 0f)
        assertEquals(SessionSwitcherPose(center = Offset(220f, 478f), scale = 1f), full)
        for (index in 0 until 6) {
            assertEquals(layout.deck((index - 2).toFloat()), layout.zoom(index, target = 2, focus = 2f, progress = 1f))
        }
        // Older cards wait behind the full card, smaller, so none shows at its edges.
        val behind = layout.zoom(3, target = 2, focus = 2f, progress = 0f)
        assertTrue(behind.center == full.center && behind.scale < 1)
        // The front card's bottom edge follows the finger 1:1.
        val lifted = layout.zoom(2, target = 2, focus = 2f, progress = 0.5f)
        assertTrue(abs((956 - lifted.frame(layout.size).bottom) - layout.zoomReach * 0.5f) < 0.001f)
    }

    @Test fun theStackTucksOlderCardsBehindToTheLeft() {
        val layout = SessionSwitcherLayout(size = Size(440f, 956f))
        val xs = (0..32).map { layout.deck(-2f + it * 0.25f).center.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> a > b })
        val scales = (0..12).map { layout.deck(it * 0.5f).scale }
        assertTrue(scales.zipWithNext().all { (a, b) -> a >= b })
        assertEquals(220f, layout.deck(0f).center.x)
    }

    @Test fun landingOnADeletedSessionGivesUpInsteadOfWaitingForever() {
        val landing = SessionSwitcherLanding(id = "b", now = 10.0)
        assertEquals(SessionSwitcherLanding.Outcome.waiting, landing.outcome(selectedID = "a", listed = true, now = 10.2))
        assertEquals(SessionSwitcherLanding.Outcome.arrived, landing.outcome(selectedID = "b", listed = true, now = 10.2))
        // Home ignores a selection whose session was deleted: no update arrives.
        assertEquals(SessionSwitcherLanding.Outcome.abandoned, landing.outcome(selectedID = "a", listed = false, now = 10.2))
        assertEquals(
            SessionSwitcherLanding.Outcome.abandoned,
            landing.outcome(selectedID = "a", listed = true, now = 10 + SessionSwitcherLanding.TIMEOUT),
        )
    }

    @Test fun deletedSessionsLeaveTheRowAndTheStackKeepsItsCard() {
        // Row: draft (null), a, b, c, d; b and d deleted.
        val prune = SessionSwitcherPrune(ids = listOf(null, "a", "b", "c", "d"), listed = setOf("a", "c"))
        assertTrue(prune.removed == listOf(2, 4) && prune.remaining == 3)
        assertEquals(listOf(0, 1, null, 2, null), listOf(0, 1, 2, 3, 4).map(prune::index))
        assertEquals("The card in front stays in front", 2f, prune.focus(3f))
        assertEquals("A deleted front card hands over to the next older one", 2f, prune.focus(2f))
        assertEquals("Clamped to the oldest card left", 2f, prune.focus(4f))
        assertTrue(SessionSwitcherPrune(ids = listOf(null, "a"), listed = setOf("a")).isEmpty)
    }

    @Test fun swipeRestsAtFullScreenAndCommitsOntoTheNeighbour() {
        val layout = SessionSwitcherLayout(size = Size(440f, 956f))
        val (rest, _) = layout.swipe(offset = 0f)
        assertTrue(rest.scale == 1f && rest.center.x == 220f)
        val (_, landed) = layout.swipe(offset = layout.rowStride)
        assertTrue(abs(landed.center.x - 220) < 0.001f && landed.scale == 1f)
        val (current, neighbour) = layout.swipe(offset = 120f)
        assertTrue(current.center.x == 340f && current.scale < 1)
        assertTrue(neighbour.center.x < current.center.x)
    }
}
