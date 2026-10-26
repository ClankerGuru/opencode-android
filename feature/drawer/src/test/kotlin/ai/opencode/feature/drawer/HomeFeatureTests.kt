package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.distantPast
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.session
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.RefreshReason
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.CachedTranscriptSnapshots
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.TranscriptCachePersistence
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ai.opencode.core.testing.TestIsolationRule
import java.time.Instant

/**
 * Port of iOS `HomeFeatureTests` (`OpenCodeTests/HomeFeatureTests.swift`): notifications and
 * Settings, busy state and priority, the transcript caches, snapshots, prefetch and attention.
 * The drawer's pins, mutes, deletion and read state are in `HomeFeatureTestsDrawer.kt`;
 * navigation and the connection in `HomeFeatureTestsNavigation.kt`.
 */
class HomeFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val savedServer = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
    private val testServer = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")

    private fun testSession(
        id: String = "ses_1",
        title: String = "Fix login bug",
        projectID: String = "proj_1",
        directory: String = "/tmp/demo",
        updatedAt: Instant = date(1_000),
    ) = OpenCodeSession(id = id, title = title, projectID = projectID, directory = directory, updatedAt = updatedAt)

    private val decoder = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Notifications need a server plugin most people won't have yet, so a send never interrupts with an offer. */
    @Test
    fun sendingNeverOffersNotificationSetup() = homeTest { h ->
        val session = OpenCodeSession(id = "ses_first", title = "First", projectID = "p", directory = "/work", updatedAt = Instant.now())
        val state = h.state {
            val base = HomeFeature.State(savedServer, snapshot(listOf(session)))
            base.copy(
                notificationState = base.notificationState.copy(notificationPermission = NotificationPermission.NotDetermined),
                sessionDetail = SessionFeature.State.session(savedServer, session, cachedItems = emptyList()),
            )
        }
        val store = h.store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.PromptAdmitted(sessionID = session.id, messageID = "msg_test", requestEpoch = 1)))
        assertEquals(NotificationPermission.NotDetermined, store.state.notificationPermission)
        h.skipInFlightEffects()
    }

    @Test
    fun notifyPluginCheckReportsTheServersPlugin() {
        val cases = listOf(
            OpenCodeNotifyPluginStatus.Active to HomeNotificationsFeature.NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Active),
            OpenCodeNotifyPluginStatus.Missing to HomeNotificationsFeature.NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Missing),
            OpenCodeNotifyPluginStatus.Inactive to HomeNotificationsFeature.NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Inactive),
            null to HomeNotificationsFeature.NotifyPluginCheck.Failed,
        )
        for ((status, expected) in cases) homeTest { h ->
            val state = h.state { HomeFeature.State(savedServer, snapshot(emptyList())) }
            val store = h.store(state) {
                this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                    notifyPluginStatus = { requested ->
                        assertEquals(savedServer, requested)
                        status ?: throw OpenCodeClientError("offline")
                    },
                )
            }
            store.send(Action.NotifyPluginCheckRequested) {
                it.copy(notificationState = it.notificationState.copy(notifyPlugin = HomeNotificationsFeature.NotifyPluginCheck.Checking))
            }
            store.receive(Action.NotifyPluginChecked(status)) {
                it.copy(notificationState = it.notificationState.copy(notifyPlugin = expected))
            }
        }
    }

    /** The drawer's Settings pushes Settings over the drawer rather than presenting a sheet, and Back returns to it. */
    @Test
    fun settingsPushesOverTheDrawerAndBackReturnsToIt() = homeTest { h ->
        val state = h.state { HomeFeature.State(savedServer, snapshot(emptyList())) }
        val store = h.store(state)
        store.send(Action.SettingsTapped) { h.state { it.presentingSettings(SettingsFeature.State(server = savedServer)) } }
        assertTrue(store.state.isSettingsShown)
        store.send(Action.SettingsShownChanged(false)) { it.copy(settings = null) }
        assertFalse(store.state.isSettingsShown)
    }

    @Test
    fun notifyPluginSetUpOpensADraftAskingOpenCodeToInstallIt() = homeTest { h ->
        val state = h.state {
            val base = HomeFeature.State(savedServer, snapshot(emptyList())).presentingSettings(SettingsFeature.State(server = savedServer))
            base.copy(notificationState = base.notificationState.copy(notifyPlugin = HomeNotificationsFeature.NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Missing)))
        }
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.NotifyPluginSetUpTapped)
        assertFalse(store.state.isSettingsShown)
        val draft = requireNotNull(store.state.sessionDetail)
        assertNull(draft.session)
        assertTrue(draft.draft.contains(OpenCodeNotifyPluginStatus.PACKAGE_ENTRY))
        assertTrue(draft.draft.contains(OpenCodeNotifyPluginStatus.PLUGIN_ID))
    }

    @Test
    fun buildsWithoutARelayNeverOfferNotifications() = homeTest { h ->
        val session = OpenCodeSession(id = "ses_first", title = "First", projectID = "p", directory = "/work", updatedAt = Instant.now())
        val state = h.state {
            HomeFeature.State(savedServer, snapshot(listOf(session)))
                .copy(sessionDetail = SessionFeature.State.session(savedServer, session, cachedItems = emptyList()))
        }
        val store = h.store(state) {
            this[SessionNotificationsClient] = SessionNotificationsClient(isAvailable = false, callbackURL = { null }, prepare = {}, forget = {})
            this[NotificationPermissionClient] = NotificationPermissionClient(
                status = { throw AssertionError("Without a relay the app must not read or ask for permission") },
                request = { NotificationPermission.Enabled },
                openSettings = { true },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(Action.NotificationSettingsRequested)
        store.send(detail(SessionFeature.Action.PromptAdmitted(sessionID = session.id, messageID = "msg_test", requestEpoch = 1)))
        assertNull(store.state.notificationPermission)
        h.skipInFlightEffects()
    }

    @Test
    fun notificationSetupHandlesPermissionDecision() {
        for (permission in listOf(NotificationPermission.Enabled, NotificationPermission.Disabled, NotificationPermission.NotDetermined)) homeTest { h ->
            val state = h.state {
                val base = HomeFeature.State(savedServer, snapshot(emptyList()))
                base.copy(notificationState = base.notificationState.copy(notificationPermission = NotificationPermission.NotDetermined))
            }
            val prepared = LockIsolated<List<OpenCodeServer>>(emptyList())
            val store = h.store(state) {
                this[NotificationPermissionClient] = NotificationPermissionClient(status = { permission }, request = { permission }, openSettings = { true })
                this[SessionNotificationsClient] = SessionNotificationsClient(
                    isAvailable = true,
                    callbackURL = { null },
                    prepare = { server -> prepared.append(server) },
                    forget = {},
                )
            }
            store.send(Action.EnableNotificationsTapped) {
                it.copy(notificationState = it.notificationState.copy(isRequestingNotifications = true))
            }
            store.receive(Action.NotificationPermissionRequested(permission)) {
                it.copy(
                    notificationState = it.notificationState.copy(
                        isRequestingNotifications = false,
                        notificationSetupError = if (permission == NotificationPermission.NotDetermined) "Couldn’t request permission. Try again." else null,
                    ),
                )
            }
            store.receive(Action.NotificationPermissionLoaded(permission)) {
                it.copy(notificationState = it.notificationState.copy(notificationPermission = permission))
            }
            store.finish()
            // Granting permission mints the callback ahead of the next send.
            assertEquals(if (permission == NotificationPermission.Enabled) listOf(savedServer) else emptyList<OpenCodeServer>(), prepared.value)
        }
    }

    @Test
    fun deniedNotificationsOpenSettingsAndRefreshOnReturn() = homeTest { h ->
        val state = h.state {
            val base = HomeFeature.State(savedServer, snapshot(emptyList()))
            base.copy(notificationState = base.notificationState.copy(notificationPermission = NotificationPermission.Disabled))
        }
        val opened = LockIsolated(false)
        val store = h.store(state) {
            this[NotificationPermissionClient] = NotificationPermissionClient(
                status = { NotificationPermission.Enabled },
                request = { throw AssertionError("Denied permission must use Settings, not ask again") },
                openSettings = { opened.setValue(true); true },
            )
        }
        store.send(Action.EnableNotificationsTapped) {
            it.copy(notificationState = it.notificationState.copy(isRequestingNotifications = true))
        }
        store.receive(Action.NotificationSettingsOpened(true)) {
            it.copy(notificationState = it.notificationState.copy(isRequestingNotifications = false))
        }
        assertTrue(opened.value)
        store.send(Action.NotificationSettingsRequested)
        store.receive(Action.NotificationPermissionLoaded(NotificationPermission.Enabled)) {
            it.copy(notificationState = it.notificationState.copy(notificationPermission = NotificationPermission.Enabled))
        }
        store.finish()
    }

    @Test
    fun drawerArrivalNeverRepeatsTheReleaseHaptic() = homeTest { h ->
        val drawerHaptics = LockIsolated(0)
        val store = h.store(h.state { HomeFeature.State(testServer, snapshot(emptyList())) }) {
            this[HapticsClient] = HapticsClient(play = { if (it == Haptic.snap) drawerHaptics.increment() })
        }
        store.send(Action.SessionDrawerArrived(isOpen = true)) { it.copy(isDrawerOpen = true) }
        assertEquals(0, drawerHaptics.value)
        store.send(Action.SessionDrawerArrived(isOpen = false)) { it.copy(isDrawerOpen = false) }
        assertEquals(0, drawerHaptics.value)
    }

    @Test
    fun unopenedParentsStayBusyUntilTheirLastDescendantFinishes() = homeTest { h ->
        val root = OpenCodeSession(id = "root", title = "Main", projectID = "p", directory = "/p", updatedAt = distantPast)
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val parents = mapOf("child" to "root", "nested" to "child", "sibling" to "root")
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(root), active = setOf("nested", "sibling"), parents = parents)).copy(sessionDetail = null)
        }
        h.state {
            assertEquals(HomeFeature.State.Indicator.running, state.indicator(root))
            assertTrue(state.catchUpSessions.isEmpty())
            assertEquals(listOf("/p"), HomeFeature.attentionDirectories(state))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.SnapshotRefreshed(snapshot(listOf(root), active = setOf("sibling"), parents = parents)))
        assertEquals(HomeFeature.State.Indicator.running, store.withDependencies { store.state.indicator(root) })
        store.send(Action.SnapshotRefreshed(snapshot(listOf(root))))
        assertNotEquals(HomeFeature.State.Indicator.running, store.withDependencies { store.state.indicator(root) })
        store.finish()
        assertEquals(setOf("a", "b"), SessionActivity.ancestors(setOf("a"), mapOf("a" to "b", "b" to "a")))
    }

    @Test
    fun prioritySortUsesCurrentActivityAndAttentionBeforeRecency() = homeTest { h ->
        h.state {
            val sessions = (0 until 5).map { testSession(id = "root-$it", updatedAt = date(it)) }
            var state = HomeFeature.State(
                testServer,
                snapshot(sessions, active = setOf("nested"), parents = mapOf("nested" to "child", "child" to "root-0")),
            ).copy(attentionSessionIDs = setOf("root-1"))
            assertEquals(listOf("root-1", "root-0", "root-4", "root-3", "root-2"), state.sessionsByPriority().map { it.id })
            state = state.copy(activeSessionIDs = emptySet())
            assertEquals(listOf("root-1", "root-4", "root-3", "root-2", "root-0"), state.sessionsByPriority().map { it.id })
            state = state.copy(attentionSessionIDs = emptySet())
            assertEquals(listOf("root-4", "root-3", "root-2", "root-1", "root-0"), state.sessionsByPriority().map { it.id })
        }
    }

    @Test
    fun restoresRememberedSessionFromPersistedTranscriptOrStartsDraft() = homeTest { h ->
        h.state {
            val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
            val session = OpenCodeSession(id = "remembered", title = "Remembered", projectID = "p", directory = "/p", updatedAt = Instant.now())
            val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "ready offline"))
            var state = HomeFeature.State(server, snapshot(listOf(session)))
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
            state = state.copy(transcriptCache = state.transcriptCache + (session.id to items)).restoringInitialSession()
            assertEquals(session, state.sessionDetail?.session)
            assertEquals(items, state.sessionDetail?.items)
            assertEquals(false, state.sessionDetail?.isLoading)

            state = state.copy(sessions = emptyList()).restoringInitialSession()
            assertEquals(true, state.sessionDetail?.isDraft)
            assertNull(state.lastViewedSessionIDs[server.persistenceID])
        }
    }

    @Test
    fun transcriptSnapshotRoundTripsAndInvalidatesWhenSessionChanges() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val updatedAt = date(1_000)
        val session = OpenCodeSession(id = "ses_cached", title = "Cached", projectID = "p", directory = "/p", updatedAt = updatedAt)
        val items = listOf(
            OpenCodeTranscriptItem.UserText(id = "m1", text = "hello"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "m2", text = "**Hi**\n\n```swift\nlet n = 1\n```"),
            OpenCodeTranscriptItem.Compaction(id = "m3"),
        )
        val cache = CachedTranscriptSnapshots().replaceEntries(
            server.persistenceID,
            listOf(CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = session.id, updatedAt = updatedAt, items = items)),
        )
        val restored = decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), requireNotNull(cache.encodedForPersistence()).decodeToString())
        val restoredItems = requireNotNull(restored.validItems(server.persistenceID, listOf(session))[session.id])
        assertEquals(items.map { it.id }, restoredItems.map { it.id })
        assertEquals(listOf("hello"), restoredItems.mapNotNull { (it as? OpenCodeTranscriptItem.UserText)?.text })

        val changed = session.copy(updatedAt = updatedAt.plusSeconds(1))
        assertEquals(items.map { it.id }, restored.validItems(server.persistenceID, listOf(changed))[session.id]?.map { it.id })
        assertTrue(restored.validItems(server.persistenceID, emptyList()).isEmpty())
        assertTrue(restored.validItems("another-server", listOf(session)).isEmpty())
    }

    @Test
    fun viewOnlyRefreshPublishesBoundedTranscriptForNextRestore() = homeTest { h ->
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "ses_cached", title = "Cached", projectID = "p", directory = "/p", updatedAt = date(1_000))
        val stale = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "old", text = "Old"))
        val fresh = (0 until 45).map { OpenCodeTranscriptItem.UserText(id = "m$it", text = "Fresh $it") }
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session))).copy(
                sessionDetail = SessionFeature.State.session(server, session, cachedItems = stale)
                    .copy(isVisible = true, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open)),
            )
        }
        val store = h.store(state)
        store.exhaustive = false

        store.send(
            detail(
                SessionFeature.Action.RefreshResponse(
                    sessionID = session.id,
                    requestID = 1,
                    result = Result.success(SessionFeature.AuthoritativeRefresh(observed = session, items = fresh, scope = SessionFeature.AuthoritativeRefresh.Scope.Page)),
                ),
            ),
        )
        store.receiveDetail(SessionFeature.Action.AcceptedTranscriptUpdated::class)

        val cached = requireNotNull(store.state.transcriptCache[session.id])
        assertEquals(fresh.takeLast(SessionFeature.initialFetchLimit), cached)
        val persisted = h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }
        assertEquals(cached, persisted.validItems(server.persistenceID, listOf(session))[session.id])

        // A relaunch reads the same shared cache.
        val restored = h.state { HomeFeature.State(server, snapshot(listOf(session))).copy(transcriptCache = emptyMap()).restoringTranscriptCache() }
        assertEquals(cached, restored.transcriptCache[session.id])
        h.skipInFlightEffects()
    }

    @Test
    fun transcriptPersistenceCoalescesAndKeepsProtectedFormat() {
        val memory = TranscriptCachePersistence.Storage.Memory()
        val storage = TranscriptCachePersistence.Storage.inMemory(memory)
        val persistence = TranscriptCachePersistence.make(storage)
        val first = CachedTranscriptSnapshots(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "first", updatedAt = distantPast, items = emptyList())))
        val second = CachedTranscriptSnapshots(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "second", updatedAt = date(1_000), items = emptyList())))

        persistence.schedule(first)
        persistence.schedule(second)
        persistence.flush()

        assertEquals(second, persistence.load())
        assertEquals(second, decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), requireNotNull(storage.data()).decodeToString()))
    }

    @Test
    fun transcriptPersistenceBoundsLargeSnapshotsBeforeItsSingleFinalEncode() {
        val payload = "x".repeat(600_000)
        val entries = (0 until CachedTranscriptSnapshots.MAXIMUM_ENTRIES).map { index ->
            CachedTranscriptSnapshots.Entry(
                serverID = "server",
                sessionID = "ses_$index",
                updatedAt = date(index),
                items = listOf(OpenCodeTranscriptItem.UserText(id = "m_$index", text = payload)),
            )
        }
        val snapshot = CachedTranscriptSnapshots(entries = entries)
        val data = requireNotNull(snapshot.encodedForPersistence())
        val decoded = decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString())

        assertTrue(data.size <= CachedTranscriptSnapshots.MAXIMUM_BYTES)
        assertTrue(decoded.entries.size < entries.size)
        assertEquals(entries.first().sessionID, decoded.entries.firstOrNull()?.sessionID)
    }

    @Test
    fun inMemoryTranscriptCacheRetainsTheOpenSessionAndPrioritySessions() = homeTest { h ->
        h.state {
            val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
            val sessions = (0 until 24).map { index ->
                OpenCodeSession(id = "ses_$index", title = "Session $index", projectID = "p", directory = "/p", updatedAt = date(index))
            }
            var state = HomeFeature.State(server, snapshot(sessions, active = setOf("ses_0")))
            state = state.copy(
                sessionDetail = SessionFeature.State.session(server, sessions[0]),
                sessionSnapshotOrder = listOf("ses_1"),
                transcriptCache = sessions.associate { it.id to listOf(OpenCodeTranscriptItem.UserText(id = it.id, text = it.title)) },
            ).trimmingTranscriptCache()

            assertEquals(HomeFeature.transcriptMemoryLimit, state.transcriptCache.size)
            assertNotNull(state.transcriptCache["ses_0"])
            assertNotNull(state.transcriptCache["ses_1"])
            assertNotNull(state.transcriptCache["ses_23"])
        }
    }

    @Test
    fun runStartedWhileSnapshotInFlightSurvivesTheSnapshot() = homeTest { h ->
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "ses_1", title = "Work", projectID = "p", directory = "/p", updatedAt = distantPast)
        val stale = snapshot(listOf(session))
        val gate = Gate()
        val store = h.store(h.state { HomeFeature.State(server, stale) }) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { _ ->
                    gate.await()
                    stale
                },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionCreated(sessionID = "ses_other", parentID = null)))
        // The server started a run after the snapshot's active read left.
        store.send(Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(sessionID = "ses_1"))) {
            it.copy(activeSessionIDs = setOf("ses_1"), runChangesDuringRefresh = mapOf("ses_1" to true))
        }
        gate.yield()
        gate.finish()
        store.receive(Action.SnapshotRefreshed::class) {
            it.copy(runChangesDuringRefresh = emptyMap(), activeSessionIDs = setOf("ses_1"))
        }
        assertEquals(HomeFeature.State.Indicator.running, store.withDependencies { store.state.indicator(session) })
        store.finish()
    }

    @Test
    fun snapshotPrefetchesHotAndRecentTranscriptsWithoutRegressingCache() = homeTest { h ->
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        fun session(id: String, seconds: Int) = OpenCodeSession(id = id, title = id, projectID = "p", directory = "/p", updatedAt = date(seconds))
        val sessions = listOf(session("old", 1), session("newest", 5), session("running", 2), session("cached", 4), session("mid", 3))
        val snapshot = snapshot(sessions, active = setOf("running"))
        val longer = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "a", text = "a"), OpenCodeTranscriptItem.UserText(id = "b", text = "b"))
        val state = h.state { HomeFeature.State(server, snapshot).let { it.copy(transcriptCache = it.transcriptCache + ("cached" to longer)) } }
        val requested = LockIsolated<List<String>>(emptyList())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { _ -> snapshot },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, id, _ ->
                    requested.append(id)
                    listOf(OpenCodeTranscriptItem.UserText(id = "$id-1", text = id))
                },
            )
        }
        store.exhaustive = false

        store.send(Action.SnapshotRefreshed(snapshot))
        store.receive(Action.TranscriptPrefetched::class)
        store.receive(Action.TranscriptPrefetched::class)
        store.finish()
        assertEquals("running first, then recency; only the top three rows are candidates", listOf("running", "newest"), requested.value)
        assertEquals(1, store.state.transcriptCache["running"]?.size)
        assertEquals(longer, store.state.transcriptCache["cached"])
        assertNull(store.state.transcriptCache["mid"])

        store.send(Action.TranscriptPrefetched(sessionID = "cached", items = listOf(OpenCodeTranscriptItem.UserText(id = "x", text = "x"))))
        assertEquals("A prefetch never overwrites an existing entry", longer, store.state.transcriptCache["cached"])
    }

    /**
     * Every location-scoped read boots that directory's service graph on the server. Home must
     * only ask about directories that can hold a pending request, never the whole history.
     */
    @Test
    fun attentionRefreshQueriesOnlyHotDirectoriesWithinAStrictBound() = homeTest { h ->
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val roots = (0 until 150).map { index ->
            OpenCodeSession(id = "ses_$index", title = "Work $index", projectID = "p$index", directory = "/projects/dir$index", updatedAt = date(index))
        }
        val state = h.state {
            HomeFeature.State(server, snapshot(roots, active = setOf("ses_3", "ses_40"))).copy(
                attentionSessionIDs = setOf("ses_7"),
                sessionDetail = SessionFeature.State.session(server, roots[120], cachedItems = emptyList()),
            )
        }
        val queried = LockIsolated<List<List<String>>>(emptyList())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, directories ->
                    queried.append(directories)
                    setOf("ses_7")
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
            )
        }
        store.exhaustive = false

        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = null)))
        store.receive(Action.AttentionRefreshed(setOf("ses_7")))
        assertEquals(
            "open session, then running and flagged roots by recency; 146 idle directories are never touched",
            listOf(listOf("/projects/dir120", "/projects/dir40", "/projects/dir7", "/projects/dir3")),
            queried.value,
        )

        // Even a swarm of running roots stays under the cap.
        val swarm = state.copy(activeSessionIDs = roots.map { it.id }.toSet())
        assertEquals(attentionDirectoryLimit, HomeFeature.attentionDirectories(swarm).size)
        assertTrue(HomeFeature.attentionDirectories(h.state { HomeFeature.State(server, snapshot(roots)) }).isEmpty())
        h.skipInFlightEffects()
    }

    /**
     * iOS also checks Developer Mode, Demo Call Audio and Touch Probe here: on Android those live
     * in `:devtools`' `DevToolsFeature` (Touch Probe is iOS-only), not `HomeSettingsFeature`.
     */
    @Test
    fun persistSnapshotSkipsUnchangedWriteAndSettingsActionsUpdateSharedState() = homeTest { h ->
        val session = testSession()
        val capturedAt = Instant.EPOCH
        val state = h.state { HomeFeature.State(testServer, snapshot(listOf(session))).persistingSnapshot(capturedAt) }
        val cache = h.state { state.cachedHomeSnapshot }
        val store = h.store(state)

        // Persisting when the cached snapshot already matches produces no write.
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionRenamed(sessionID = session.id, title = session.title)))
        assertEquals(cache, h.state { store.state.cachedHomeSnapshot })

        store.send(Action.Settings(HomeSettingsFeature.Action.SidebarToggled)) { it.copy(isSidebarShown = false) }
        assertFalse(h.shared(SharedKeys.sidebarShown))
        store.send(Action.Settings(HomeSettingsFeature.Action.InAppNoticeModeChanged(InAppNoticeMode.all)))
        assertEquals(InAppNoticeMode.all, h.shared(InAppNoticeMode.key))
        store.send(Action.Settings(HomeSettingsFeature.Action.VoiceModeEnabledChanged(true)))
        assertTrue(h.shared(SharedKeys.voiceModeEnabled))
        store.send(Action.Settings(HomeSettingsFeature.Action.RaiseToTalkChanged(true)))
        assertTrue(h.shared(SharedKeys.raiseToTalk))
        store.send(Action.Settings(HomeSettingsFeature.Action.VoiceEngineChanged(VoiceEngine.realtime)))
        assertEquals(VoiceEngine.realtime, h.shared(VoiceSharedKeys.voiceEngine))
        store.send(Action.Settings(HomeSettingsFeature.Action.CallVoiceChanged(CallVoice.cedar)))
        assertEquals(CallVoice.cedar, h.shared(VoiceSharedKeys.callVoice))
        store.send(Action.Settings(HomeSettingsFeature.Action.OpenAIAPIKeyChanged("sk-test")))
        assertEquals("sk-test", h.shared(SharedKeys.openAIAPIKey))
        store.send(Action.Settings(HomeSettingsFeature.Action.XaiAPIKeyChanged("xai-test")))
        assertEquals("xai-test", h.shared(SharedKeys.xaiAPIKey))
        assertEquals("Each provider keeps its own key", "sk-test", h.shared(SharedKeys.openAIAPIKey))
        store.send(Action.Settings(HomeSettingsFeature.Action.VerifiedVoiceKeyFingerprintChanged("fp-1")))
        assertEquals("fp-1", h.shared(SharedKeys.verifiedVoiceKeyFingerprint))
    }
}
