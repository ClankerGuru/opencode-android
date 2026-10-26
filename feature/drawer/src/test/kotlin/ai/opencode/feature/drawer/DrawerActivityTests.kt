package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.motion.DrawerDotArrival
import ai.opencode.feature.drawer.motion.DrawerDotPose
import ai.opencode.feature.drawer.motion.DrawerDotTunables
import ai.opencode.feature.session.DrawerBadge
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.withRequestsConnectionIsAvailable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `DrawerActivityTests.swift`: the drawer's running indicators, matched to the TUI
 * and the desktop app: a root shows Working while it or any session in its family runs
 * (subagents moved to the background run their own sessions); shells don't count. Plus
 * `DrawerDotArrivalTests` (the drawer button's dot).
 *
 * Stores run their effects in `backgroundScope` (iOS `skipInFlightEffects`). iOS's
 * `withObservationTracking` probes of `busySessionIDs` become before/after comparisons of
 * [HomeFeature.State.busySessionIDs] (Android observes the state value, not a property).
 *
 * Other modules (`:app`, `AppFeature`):
 * - `aRelaunchShowsCachedRunningStateOnlyWhileItIsFresh`
 * - `theSingleCacheOfEarlierBuildsMovesIntoPerServerCaches`
 * - `DrawerDotArrivalTests.initialLoadOrRestoreWithDotAlreadyShowingStartsAtRest`'s AppFeature
 *   restore half (the `DrawerDotArrival.initial` half is ported).
 *
 * Partially ported: `tunablesRegisterWithBrightenDefaults` (Android bakes Tunables; no Tuning
 * registry), so only the defaults and `Parameters()` are asserted.
 */
class DrawerActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://drawer.example", username = "", password = "")

    private fun session(id: String, parent: String? = null, directory: String = "/app", idle: Long? = null) = OpenCodeSession(
        id = id, title = id, projectID = directory, directory = directory,
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, parentID = parent,
    )

    private fun snapshot(
        sessions: List<OpenCodeSession>,
        active: Set<String> = emptySet(),
        parents: Map<String, String>? = null,
    ) = OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = active, activeSessionParents = parents)

    private fun home(sessions: List<OpenCodeSession>, active: Set<String> = emptySet(), parents: Map<String, String>? = null) =
        HomeFeature.State(server, snapshot(sessions, active, parents))

    /** `state.sessionDetail = state.makeDetail(for:cachedItems:)`. */
    private fun HomeFeature.State.opening(session: OpenCodeSession, edit: (SessionFeature.State) -> SessionFeature.State = { it }): HomeFeature.State {
        val (s, detail) = makeDetail(session, cachedItems = emptyList())
        return s.copy(sessionDetail = edit(detail))
    }

    /** A refresh that waits for the test to answer it. */
    private class GatedSnapshots {
        val requests = AtomicInteger(0)
        private val answers = Channel<OpenCodeHomeSnapshot>(Channel.UNLIMITED)

        fun answer(snapshot: OpenCodeHomeSnapshot) {
            answers.trySend(snapshot)
        }

        suspend fun next(): OpenCodeHomeSnapshot {
            requests.incrementAndGet()
            return answers.receive()
        }
    }

    @Test
    fun switchingAwayKeepsTheOpenSessionsSubagentRunning() = runTest {
        val parent = session("ses_parent")
        val other = session("ses_other", directory = "/api")
        val child = session("ses_child", parent = parent.id)
        val state = home(listOf(parent, other), active = setOf(parent.id))
            .copy(transcriptCache = mapOf(parent.id to emptyList(), other.id to emptyList()))
            .opening(parent) { it.copy(familySessions = listOf(parent, child)) }
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false

        // The subagent starts while its parent is open, then the parent's own run ends: it
        // moved the subagent to the background.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(child.id)))
        assertEquals(parent.id, store.state.activeSessionParents[child.id])
        assertEquals(Indicator.running, store.state.indicator(parent))

        store.send(HomeFeature.Action.SessionTapped(other.id))
        assertEquals(other.id, store.state.sessionDetail?.session?.id)
        assertEquals("The parent stays Working after a switch away", Indicator.running, store.state.indicator(parent))
        assertEquals(Indicator.none, store.state.indicator(other))
    }

    @Test
    fun runsOfSubagentsNoOpenFamilyHasListedYetReachEveryScreen() = runTest {
        val parent = session("ses_parent")
        val child = session("ses_child", parent = "ses_parent")
        val state = home(listOf(parent), active = setOf(parent.id))
            .opening(parent) { it.copy(familySessions = listOf(parent, child)) }
            .pushingSubagent(child)
        val pushed = state.subagentStack.last().navigationID
        val snapshots = GatedSnapshots()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            // Unattributed, it asks for a list; that read isn't under test.
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(homeSnapshot = { snapshots.next() })
        }
        store.exhaustive = false

        // Created, then started before the family read that lists it replies.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_new")))
        store.skipReceivedActions()
        assertEquals(mapOf("ses_new" to true), store.state.sessionDetail?.familyRunChanges)
        assertEquals(mapOf("ses_new" to true), store.state.subagentStack.first { it.navigationID == pushed }.familyRunChanges)
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded("ses_new")))
        store.skipReceivedActions()
        assertEquals(mapOf("ses_new" to false), store.state.sessionDetail?.familyRunChanges)
        assertEquals(mapOf("ses_new" to false), store.state.subagentStack.first { it.navigationID == pushed }.familyRunChanges)
    }

    /**
     * Its family read took its active status before the start, so the read's ancestry pass never
     * followed it: every screen whose family it joins by Home's links reads again. Listed and
     * unrelated starts don't.
     */
    @Test
    fun anUnlistedSubagentStartingRereadsEveryFamilyItJoins() = runTest {
        val parent = session("ses_parent")
        val child = session("ses_child", parent = "ses_parent")
        var state = home(listOf(parent), active = setOf(parent.id))
            .opening(parent) { it.copy(familyRootID = parent.id, familySessions = listOf(parent, child)) }
            .pushingSubagent(child)
        // From `session.created`, as Home records it.
        state = state.copy(activeSessionParents = state.activeSessionParents + ("ses_grand" to child.id))
        val reads = mutableListOf<String>()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { awaitCancellation() },
                sessionFamily = { _, root ->
                    reads += root.id
                    awaitCancellation()
                },
                transcript = { _, _, _ -> awaitCancellation() },
                getSession = { _, _ -> awaitCancellation() },
                runningShells = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_grand")))
        // Android's TestStore reduces effect actions when asked (iOS's does as they arrive).
        repeat(100) {
            if (reads.size < 2) {
                store.skipReceivedActions()
                yield()
            }
        }
        assertEquals("The open session and the pushed subagent read again", listOf(child.id, parent.id), reads.sorted())

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(child.id)))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_elsewhere")))
        repeat(100) {
            store.skipReceivedActions()
            yield()
        }
        assertEquals(2, reads.size)
    }

    @Test
    fun aSubagentThatStartsMidRefreshKeepsItsRootRunningWhenTheSnapshotLands() = runTest {
        val root = session("ses_root")
        val child = session("ses_child", parent = "ses_root")
        val state = home(listOf(root), parents = mapOf("ses_earlier" to root.id)).copy(sessionDetail = null)
        val snapshots = GatedSnapshots()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { snapshots.next() },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // A new root asks for the list; its `/api/session/active` is read now.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionCreated("ses_new", parentID = null)))
        assertTrue(eventually { snapshots.requests.get() == 1 })
        // Then a subagent is created and starts. Its parent arrived with it, so nothing cancels the request in flight.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionCreated(child.id, parentID = root.id)))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(child.id)))
        assertEquals(Indicator.running, store.state.indicator(root))
        assertEquals(1, snapshots.requests.get())

        // The snapshot read before the start: no subagent, no ancestry.
        snapshots.answer(snapshot(listOf(root)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed") {
            assertEquals(setOf(child.id), it.activeSessionIDs)
            assertEquals(emptyMap<String, Boolean>(), it.runChangesDuringRefresh)
            it
        }
        assertEquals("Links merge, never replaced", mapOf(child.id to root.id, "ses_earlier" to root.id), store.state.activeSessionParents)
        assertEquals(Indicator.running, store.state.indicator(root))
    }

    @Test
    fun aSubagentWithUnknownAncestryIsLookedUpAgainAfterAStaleSnapshot() = runTest {
        val root = session("ses_root")
        val state = home(listOf(root)).copy(sessionDetail = null)
        val snapshots = GatedSnapshots()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { snapshots.next() },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // Started before this stream saw it created: its parent is unknown.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_child")))
        assertTrue(eventually { snapshots.requests.get() == 1 })
        snapshots.answer(snapshot(listOf(root)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals(setOf("ses_child"), store.state.activeSessionIDs)
        assertTrue("The stale snapshot didn't look the subagent up", eventually { snapshots.requests.get() == 2 })
        snapshots.answer(snapshot(listOf(root), active = setOf("ses_child"), parents = mapOf("ses_child" to root.id)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals(Indicator.running, store.state.indicator(root))
        eventually(timeout = 50.milliseconds) { false }
        assertEquals(2, snapshots.requests.get())
    }

    @Test
    fun sessionViewedUpdatesUnreadInPlaceWithoutRefreshing() = runTest {
        val viewed = session("ses_viewed", idle = 2_000)
        val state = home(listOf(viewed)).copy(sessionDetail = null, runChangesDuringRefresh = mapOf("ses_elsewhere" to true))
        assertEquals(Indicator.unread, state.indicator(viewed))
        // Exhaustive: no refresh effect, and the recorded run change survives.
        val store = TestStore(state, HomeFeature(), this)

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(viewed.id, idle = 2_000))) {
            it.copy(sessions = listOf(viewed.copy(viewedMilliseconds = 2_000)))
        }
        assertEquals(Indicator.none, store.state.indicator(store.state.sessions[0]))
        // An older watermark never makes it unread again.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(viewed.id, idle = 1_000)))
        store.finish()
    }

    @Test
    fun backgroundSubagentsKeepTheirRootRunningButShellsDont() = runTest {
        val root = session("ses_root")
        val shellOwner = session("ses_shell", directory = "/api")
        val state = home(listOf(root, shellOwner), active = setOf(root.id)).copy(sessionDetail = null)
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                // The root's own run ended after moving its subagent to the background; the
                // subagent's session keeps running.
                homeSnapshot = { snapshot(listOf(root, shellOwner), active = setOf("ses_child"), parents = mapOf("ses_child" to root.id)) },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded(root.id)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals("A background subagent keeps its root Working", Indicator.running, store.state.indicator(root))

        // A background shell is not a run, here or in the TUI.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ShellStarted(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = shellOwner.id, isUserCommand = true))))
        assertEquals(Indicator.none, store.state.indicator(shellOwner))

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded("ses_child")))
        assertEquals(Indicator.none, store.state.indicator(root))
    }

    @Test
    fun aNewStepMarksASessionRunningLikeTheTUI() = runTest {
        val root = session("ses_root")
        val state = home(listOf(root)).copy(sessionDetail = null)
        val store = TestStore(state, HomeFeature(), backgroundScope)
        store.exhaustive = false

        // Its start came before this stream connected.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(root.id, listOf(OpenCodeLiveContentEvent.StepStarted("msg_1")))))
        assertEquals(setOf(root.id), store.state.activeSessionIDs)
        assertEquals(Indicator.running, store.state.indicator(root))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(root.id, listOf(OpenCodeLiveContentEvent.TextDelta("msg_1", 0, "Hi")))))
        assertEquals(mapOf(root.id to true), store.state.runChangesDuringRefresh)
    }

    @Test
    fun aFailedSnapshotRetriesWithoutBlankingALiveDrawer() = runTest {
        val root = session("ses_root")
        var state = home(listOf(root), active = setOf(root.id)).copy(sessionDetail = null)
        state = state.copy(connection = state.connection.copy(isEventStreamLive = true))
        val attempts = AtomicInteger(0)
        val clock = TestClock()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = {
                    attempts.incrementAndGet()
                    snapshot(listOf(root), active = setOf(root.id))
                },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SnapshotFailed) { it.copy(connection = it.connection.copy(snapshotFailures = 1)) }
        assertEquals(1, store.state.snapshotFailures)
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.connected, store.state.connectionStatus)
        assertEquals(Indicator.running, store.state.indicator(root))
        store.send(HomeFeature.Action.SnapshotFailed)
        assertEquals(2, store.state.snapshotFailures)
        // The second failure backs off 2 s, then retries.
        clock.advance(1.seconds)
        assertEquals(0, attempts.get())
        clock.advance(1.seconds)
        store.receive(HomeFeature.Action.SnapshotRetryRequested)
        clock.advance(250.milliseconds)
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals(0, store.state.snapshotFailures)
        assertEquals(1, attempts.get())
    }

    @Test
    fun multiHopKnownParentsAndListedSubagentsUpdateObservedBusySet() = runTest {
        val now = Instant.ofEpochSecond(100)
        val root = session("ses_root")
        val listedSub = session("ses_listed_sub", parent = root.id)
        val child = session("ses_child", parent = root.id)
        val grandchild = session("ses_grandchild", parent = child.id)
        // Multi-hop ancestry known via familySessions, not yet in activeSessionParents.
        val state = home(listOf(root, listedSub))
            .markingReadLocally(root)
            .markingReadLocally(listedSub)
            .copy(transcriptCache = mapOf(root.id to emptyList(), listedSub.id to emptyList()))
            .opening(root) { it.copy(familySessions = listOf(root, listedSub, child, grandchild)) }
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(now)
            this[HomeSnapshotDate] = DateClient.constant(now)
        }
        store.exhaustive = false

        // 1. A 2-hop grandchild starting walks the full knownParents chain, copies both hops into
        //    activeSessionParents, and updates the busy set for root.
        val busyBefore = store.state.busySessionIDs
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(grandchild.id)))
        assertEquals(mapOf(grandchild.id to child.id, child.id to root.id), store.state.activeSessionParents)
        assertEquals(setOf(grandchild.id), store.state.activeSessionIDs)
        assertEquals(true, store.state.runChangesDuringRefresh[grandchild.id])
        store.receive(HomeFeature.Action.SessionDetail(PresentationAction.Presented(SessionFeature.Action.FamilyRunStateChanged(grandchild.id, isRunning = true))))
        assertEquals(true, store.state.sessionDetail?.familyRunChanges?.get(grandchild.id))
        assertTrue(grandchild.id in store.state.sessionDetail!!.activeFamilySessionIDs)
        assertNotEquals(busyBefore, store.state.busySessionIDs)
        assertEquals(Indicator.running, store.state.indicator(root))
        assertEquals(Indicator.none, store.state.indicator(listedSub))

        // 2. Starting a subagent row that is itself listed updates the busy set even though its
        //    parent root is already busy.
        val busyBeforeListed = store.state.busySessionIDs
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(listedSub.id)))
        assertEquals(root.id, store.state.activeSessionParents[listedSub.id])
        assertTrue(listedSub.id in store.state.activeSessionIDs)
        assertEquals(true, store.state.runChangesDuringRefresh[listedSub.id])
        store.receive(HomeFeature.Action.SessionDetail(PresentationAction.Presented(SessionFeature.Action.FamilyRunStateChanged(listedSub.id, isRunning = true))))
        assertTrue(listedSub.id in store.state.sessionDetail!!.activeFamilySessionIDs)
        assertNotEquals(busyBeforeListed, store.state.busySessionIDs)
        assertEquals(Indicator.running, store.state.indicator(listedSub))
    }

    @Test
    fun familyResponseAndDeletionResyncObservedBusySetWhenChildWasAlreadyBusy() = runTest {
        val now = Instant.ofEpochSecond(100)
        val root = session("ses_root")
        val child = session("ses_child", parent = root.id)
        val grandchild = session("ses_grandchild", parent = child.id)
        val state = home(listOf(root), active = setOf(grandchild.id))
            .markingReadLocally(root)
            .copy(transcriptCache = mapOf(root.id to emptyList()))
            .opening(root) { it.withRequestsConnectionIsAvailable(false) }
        assertEquals(Indicator.none, state.indicator(root))
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(now)
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        val busyBefore = store.state.busySessionIDs
        store.send(
            HomeFeature.Action.SessionDetail(
                PresentationAction.Presented(
                    SessionFeature.Action.FamilyResponse(Result.success(OpenCodeSessionFamily(listOf(root, child, grandchild), setOf(grandchild.id)))),
                ),
            ),
        )
        assertEquals(now, store.state.sessionDetail?.familyReadAt)
        assertEquals(listOf(root, child, grandchild), store.state.sessionDetail?.familySessions)
        assertEquals(setOf(grandchild.id), store.state.sessionDetail?.activeFamilySessionIDs)
        assertEquals(mapOf(grandchild.id to child.id, child.id to root.id), store.state.activeSessionParents)
        assertNotEquals(busyBefore, store.state.busySessionIDs)
        assertEquals(Indicator.running, store.state.indicator(root))

        val busyBeforeDelete = store.state.busySessionIDs
        store.send(HomeFeature.Action.SessionDetail(PresentationAction.Presented(SessionFeature.Action.FamilySessionDeleted(grandchild.id))))
        assertEquals(listOf(root, child), store.state.sessionDetail?.familySessions)
        assertEquals(emptySet<String>(), store.state.sessionDetail?.activeFamilySessionIDs)
        assertNull(store.state.activeSessionParents[grandchild.id])
        assertNotEquals(busyBeforeDelete, store.state.busySessionIDs)
        assertEquals(Indicator.none, store.state.indicator(root))
    }
}

class DrawerDotArrivalTests {
    @get:Rule val isolation = TestIsolationRule()

    private val frame = 1.0 / 60
    private val server = OpenCodeServer(url = "https://drawer-dot.example", username = "", password = "")

    private fun session(id: String, idle: Long? = null, viewed: Long? = null) = OpenCodeSession(
        id = id, title = id, projectID = "/app", directory = "/app",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, viewedMilliseconds = viewed,
    )

    @Test
    fun tunablesRegisterWithBrightenDefaults() {
        assertEquals(0.6, DrawerDotTunables.BRIGHTEN_DURATION, 0.0)
        assertEquals(0.85, DrawerDotTunables.BRIGHTEN_FLASH, 0.0)
        assertEquals(5.5, DrawerDotTunables.BRIGHTEN_GLOW, 0.0)
        assertEquals(DrawerDotArrival.Parameters(brightenDuration = 0.6, brightenFlash = 0.85, brightenGlow = 5.5), DrawerDotArrival.Parameters())
    }

    @Test
    fun initialLoadOrRestoreWithDotAlreadyShowingStartsAtRest() {
        val unread = DrawerDotArrival.initial(DrawerBadge.Unread)
        assertTrue(unread.isSettled(0.0))
        assertEquals(DrawerDotPose.resting, unread.pose(0.0))

        val attention = DrawerDotArrival.initial(DrawerBadge.Attention)
        assertTrue(attention.isSettled(0.0))
        assertEquals(DrawerDotPose.resting, attention.pose(0.0))

        val hidden = DrawerDotArrival.initial(null)
        assertTrue(hidden.isSettled(0.0))
        assertEquals(DrawerDotPose.hidden, hidden.pose(0.0))
    }

    @Test
    fun brightenArrivesAtFullSizeWithGlowAndCoolsToRestingDot() {
        val start = 10.0
        val arrival = DrawerDotArrival.initial(null).retargeted(DrawerBadge.Unread, reduceMotion = false, parameters = DrawerDotArrival.Parameters(), time = start)
        assertFalse(arrival.isSettled(start))
        val pPeak = arrival.pose(start + arrival.duration * 0.12)
        val pMid = arrival.pose(start + arrival.duration * 0.35)
        val pEnd = arrival.pose(start + arrival.duration)

        assertTrue(abs(pPeak.scale - 1.0) < 1e-5)
        assertTrue(abs(pMid.scale - 1.0) < 1e-5)
        assertTrue(pPeak.flash > 0.8)
        assertTrue(pMid.flash > 0.2 && pMid.flash < pPeak.flash)
        assertTrue(pMid.glowOpacity > 0.15)
        assertTrue(pMid.glowRadius > 3.5)
        assertTrue(pMid.glowScale > 1.1)

        // Settles at duration and remains at `resting` forever after.
        assertTrue(arrival.isSettled(start + arrival.duration))
        assertEquals(DrawerDotPose.resting, pEnd)
        assertEquals(DrawerDotPose.resting, arrival.pose(start + arrival.duration + 5.0))
    }

    @Test
    fun arrivalFiresOncePerAppearanceAndNeverRepeatsWhileVisible() {
        val open = session("ses_open", idle = 1_000, viewed = 1_000)
        val s1 = session("ses_1", idle = 1_000, viewed = 1_000)
        val s2 = session("ses_2", idle = 1_000, viewed = 1_000)
        val s3 = session("ses_3", idle = 1_000, viewed = 1_000)
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, s1, s2, s3), activeSessionIDs = emptySet()))
        assertNull(state.drawerBadge(excluding = open.id))

        var arrival = DrawerDotArrival.initial(state.drawerBadge(excluding = open.id))
        var arrivalCount = 0
        fun syncBadge(time: Double) {
            val wasSettled = arrival.isSettled(time)
            val hadTarget = arrival.targetBadge != null
            arrival = arrival.retargeted(state.drawerBadge(excluding = open.id), reduceMotion = false, parameters = DrawerDotArrival.Parameters(), time = time)
            if (!hadTarget && arrival.targetBadge != null && !arrival.isSettled(time) && wasSettled) arrivalCount += 1
        }
        fun editSession(index: Int, edit: (OpenCodeSession) -> OpenCodeSession) {
            state = state.copy(sessions = state.sessions.toMutableList().also { it[index] = edit(it[index]) })
        }

        // 1. First session becomes unread (0 -> 1): dot appears and fires arrival once.
        editSession(1) { it.copy(idleMilliseconds = 2_000) }
        syncBadge(1.0)
        assertEquals(1, arrivalCount)
        assertFalse(arrival.isSettled(1.0))

        // Settle the first arrival.
        val settledAt = 1.0 + arrival.duration
        assertTrue(arrival.isSettled(settledAt))
        assertEquals(DrawerDotPose.resting, arrival.pose(settledAt))

        // 2. Unread count changes (1 -> 2 -> 3) while dot is showing: never re-fires.
        editSession(2) { it.copy(idleMilliseconds = 3_000) }
        syncBadge(2.0)
        assertTrue(arrival.isSettled(2.0))
        assertEquals(DrawerDotPose.resting, arrival.pose(2.0))

        editSession(3) { it.copy(idleMilliseconds = 4_000) }
        syncBadge(3.0)
        assertTrue(arrival.isSettled(3.0))
        assertEquals(DrawerDotPose.resting, arrival.pose(3.0))

        // 3. Another session needs input and resolves back while the dot is already showing:
        // colour updates in place without re-firing the arrival animation.
        state = state.copy(attentionSessionIDs = setOf(s2.id))
        syncBadge(4.0)
        assertEquals(DrawerBadge.Attention, arrival.displayedBadge)
        assertTrue(arrival.isSettled(4.0))
        assertEquals(DrawerDotPose.resting, arrival.pose(4.0))

        state = state.copy(attentionSessionIDs = emptySet())
        syncBadge(5.0)
        assertEquals(DrawerBadge.Unread, arrival.displayedBadge)
        assertTrue(arrival.isSettled(5.0))
        assertEquals(DrawerDotPose.resting, arrival.pose(5.0))
        assertEquals(1, arrivalCount)

        // 4. All other sessions are read (3 -> 0): dot exits to hidden.
        for (i in 1..3) editSession(i) { it.copy(viewedMilliseconds = it.idleMilliseconds) }
        syncBadge(6.0)
        assertNull(arrival.targetBadge)
        val hiddenAt = 6.0 + arrival.duration
        assertTrue(arrival.isSettled(hiddenAt))
        assertEquals(DrawerDotPose.hidden, arrival.pose(hiddenAt))

        // 5. A new unread session arrives after the dot disappeared: fires once for the new appearance.
        editSession(1) { it.copy(idleMilliseconds = 5_000) }
        syncBadge(7.0)
        assertEquals(2, arrivalCount)
        assertFalse(arrival.isSettled(7.0))
    }

    @Test
    fun disappearingMidAnimationContinuesSmoothlyWithoutJumping() {
        val start = 0.0
        val entering = DrawerDotArrival.initial(null).retargeted(DrawerBadge.Unread, reduceMotion = false, time = start)
        val interruptAt = entering.duration * 0.32
        val before = entering.pose(interruptAt)
        val exiting = entering.retargeted(null, reduceMotion = false, time = interruptAt)
        val after = exiting.pose(interruptAt)

        assertTrue(abs(after.opacity - before.opacity) < 1e-9)
        assertTrue(abs(after.scale - before.scale) < 1e-6)
        assertTrue(abs(after.flash - before.flash) < 1e-9)
        assertTrue(abs(after.glowOpacity - before.glowOpacity) < 1e-9)

        // Consecutive 60 Hz frames during exit never jump.
        val opacities = generateSequence(interruptAt) { it + frame }.takeWhile { it <= interruptAt + exiting.duration }.map { exiting.pose(it).opacity }.toList()
        val maxStep = opacities.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0.0
        assertTrue(maxStep < 0.15)
        assertEquals(DrawerDotPose.hidden, exiting.pose(interruptAt + exiting.duration))
    }

    @Test
    fun reduceMotionUsesPlainFadeWithNoGlowOrFlash() {
        val arrival = DrawerDotArrival.initial(null).retargeted(DrawerBadge.Unread, reduceMotion = true, time = 0.0)
        var t = 0.0
        while (t <= arrival.duration) {
            val pose = arrival.pose(t)
            assertEquals(1.0, pose.scale, 0.0)
            assertEquals(0.0, pose.flash, 0.0)
            assertEquals(0.0, pose.glowOpacity, 0.0)
            assertEquals(0.0, pose.glowRadius, 0.0)
            t += frame
        }
        assertEquals(DrawerDotPose.resting, arrival.pose(arrival.duration))
    }
}
