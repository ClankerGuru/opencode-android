package ai.opencode.feature.system

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.intents.ControlIntents
import ai.opencode.feature.system.intents.OpenCodeControlKind
import ai.opencode.feature.system.intents.SessionControlValue
import ai.opencode.feature.system.intents.SessionEntity
import ai.opencode.feature.system.intents.SystemPromptSender
import ai.opencode.feature.system.intents.SystemQuickAction
import ai.opencode.feature.system.intents.controlTarget
import ai.opencode.feature.system.intents.projectEntities
import ai.opencode.feature.system.intents.sessionEntities
import ai.opencode.feature.system.intents.suggestedSessionEntities
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/*
 * Ports of iOS `SystemIntegrationTests.swift`. Suites (and tests) whose store is `HomeFeature` or
 * `AppFeature` belong to `:feature:drawer` / `:app`: `SystemRoutingTests`, `SystemColdLaunchTests`,
 * `SystemVoiceAvailabilityTests`, `SystemCallTests`, and Home's `systemDirectoryEntry` tests in
 * `SessionDirectoryTests`.
 */

private val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
private val otherServer = OpenCodeServer(url = "https://other.example", username = "kit", password = "test")
private val serverKey = SessionDirectory.serverKey(server.persistenceID)

private fun directoryServer(lastViewed: String? = "ses_older", pinned: List<String> = emptyList()) = SessionDirectory.Server(
    key = serverKey,
    name = "saved.example",
    lastViewedSessionID = lastViewed,
    pinnedIDs = pinned,
    sessions = listOf(
        SessionDirectory.Session(id = "ses_newer", title = "Fix login", projectID = "prj_app", projectName = "app", updatedAt = Instant.ofEpochSecond(300)),
        SessionDirectory.Session(id = "ses_loose", title = "Scratch", projectID = "global", projectName = null, updatedAt = Instant.ofEpochSecond(200)),
        SessionDirectory.Session(id = "ses_older", title = "Older work", projectID = "prj_docs", projectName = "docs", updatedAt = Instant.ofEpochSecond(100)),
    ),
    projects = listOf(SessionDirectory.Project("prj_app", "app", "/work/app"), SessionDirectory.Project("prj_docs", "docs", "/work/docs")),
)

private fun directory(entry: SessionDirectory.Server = directoryServer()): SessionDirectory = SessionDirectory().publish(entry)

/** `homeBuildsItsEntryFromTheListPinsAndLastViewed` and `entryNeverCarriesMessageText` are Home's (`:feature:drawer`). */
class SessionDirectoryTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun serverKeysAreShortStableAndHideTheAddress() {
        assertEquals(SessionDirectory.serverKey(server.persistenceID), serverKey)
        assertEquals(12, serverKey.length)
        assertFalse(serverKey.contains("saved"))
        assertTrue(serverKey != SessionDirectory.serverKey(otherServer.persistenceID))
    }

    @Test
    fun scopedIDsRoundTrip() {
        val scoped = ScopedID(serverKey, "ses_newer")
        assertEquals(scoped, ScopedID.parse(scoped.rawValue))
        assertNull(ScopedID.parse("no-colon"))
        assertNull(ScopedID.parse(":ses_x"))
    }

    @Test
    fun writesAndLoadsFromTheAppGroupFile() {
        val file = File.createTempFile("system-sessions-", ".json")
        try {
            val written = directory()
            written.write(file)
            assertEquals(written, SessionDirectory.load(file))
        } finally {
            file.delete()
        }
    }

    /** Android's `removeServer` returns the new directory, not the removed entry. */
    @Test
    fun removingTheCurrentServerClearsIt() {
        val before = directory()
        assertTrue(serverKey in before.servers)
        val after = before.removeServer(serverKey)
        assertNull(after.current)
        assertTrue(after.servers.isEmpty())
    }
}

/** `spotlightIndexesOnlyTitlesAndProjects`: Android's index document is an AppSearch `GenericDocument` (an Android runtime class). */
class SystemEntityQueryTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun suggestionsOfferTheChoicesThenPinnedThenRecents() {
        val suggested = directory(directoryServer(pinned = listOf("ses_older"))).suggestedSessionEntities()
        assertEquals(
            listOf(SessionEntity.LAST_VIEWED_ID, SessionEntity.NEW_SESSION_ID, "$serverKey:ses_older", "$serverKey:ses_newer", "$serverKey:ses_loose"),
            suggested.map { it.id },
        )
        assertEquals("Last Viewed Session", suggested[0].title)
        assertEquals("Older work", suggested[0].detail)
        assertEquals("app", suggested[1].detail)
        assertEquals("docs", suggested[2].detail)
    }

    @Test
    fun lastViewedFallsBackToTheNewestSession() {
        assertEquals("Fix login", SessionEntity.lastViewed(directoryServer(lastViewed = null)).detail)
        assertNull(SessionEntity.lastViewed(null).detail)
    }

    @Test
    fun resolvesStoredIDsFromAnyServer() {
        val otherKey = SessionDirectory.serverKey(otherServer.persistenceID)
        val directory = directory().publish(
            SessionDirectory.Server(
                key = otherKey, name = "other", pinnedIDs = emptyList(),
                sessions = listOf(SessionDirectory.Session(id = "ses_remote", title = "Remote", projectID = "p", projectName = null, updatedAt = Instant.now())),
                projects = emptyList(),
            ),
        )
        val resolved = directory.sessionEntities(listOf("$serverKey:ses_newer", "$otherKey:ses_remote", "$serverKey:ses_gone", SessionEntity.NEW_SESSION_ID))
        assertEquals(listOf("Fix login", "Remote", "New Session"), resolved.map { it.title })
    }

    @Test
    fun searchMatchesTitlesAndProjectsOnTheCurrentServer() {
        val directory = directory()
        assertEquals(listOf("Fix login"), directory.sessionEntities("login").map { it.title })
        assertEquals(listOf("Older work"), directory.sessionEntities("docs").map { it.title })
        assertTrue(directory.sessionEntities("nothing").isEmpty())
        assertTrue(SessionDirectory().sessionEntities("login").isEmpty())
    }

    @Test
    fun projectsResolveAndSearch() {
        val directory = directory()
        assertEquals(listOf("app", "docs"), directory.projectEntities("").map { it.name })
        assertEquals(listOf("docs"), directory.projectEntities("do").map { it.name })
        assertEquals(listOf("docs"), directory.projectEntities(listOf("$serverKey:prj_docs")).map { it.name })
    }

    @Test
    fun entitiesNameTheirActions() {
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = true), SessionEntity.lastViewed(null).action(startsCall = true))
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft(null)), SessionEntity.newSession(null).action())
        val session = SessionEntity.of(directoryServer().sessions[0], serverKey)
        assertEquals(SystemAction(serverKey, SystemAction.Destination.Session("ses_newer")), session.action())
        assertNull(SessionEntity(id = "garbage", title = "", detail = null).action())
    }
}

class SessionControlTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aControlsChoiceBecomesItsAction() {
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = true), SystemAction.controlTarget(null, startsCall = true))
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = true), SystemAction.controlTarget(SessionEntity.LAST_VIEWED_ID, startsCall = true))
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft(null), startsCall = true), SystemAction.controlTarget(SessionEntity.NEW_SESSION_ID, startsCall = true))
        assertEquals(SystemAction(serverKey, SystemAction.Destination.Session("ses_older")), SystemAction.controlTarget("$serverKey:ses_older", startsCall = false))
        // An ID that isn't a choice or a scoped session is Last Viewed Session.
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed), SystemAction.controlTarget("garbage", startsCall = false))
    }

    @Test
    fun theControlsCopyKeepsPinsRecentsAndLastViewedButNoFoldersOrTimes() {
        val extra = (0 until 60).map { index ->
            SessionDirectory.Session(id = "ses_$index", title = "Work $index", projectID = "prj_app", projectName = "app", updatedAt = Instant.ofEpochSecond(1_000L - index))
        }
        val base = directoryServer(lastViewed = "ses_older", pinned = listOf("ses_loose"))
        val entry = base.copy(sessions = extra + base.sessions)
        val copy = directory(entry).forControls
        val server = copy.current!!
        assertEquals(SessionDirectory.MAXIMUM_CONTROL_SESSIONS + 2, server.sessions.size)
        assertEquals(listOf("ses_loose"), server.pinned.map { it.id })
        assertEquals("ses_older", server.lastViewed?.id)
        assertEquals(listOf("ses_0", "ses_1", "ses_2"), server.sessions.take(3).map { it.id })
        // iOS `.distantPast`; Android's copy uses the epoch.
        assertTrue(server.sessions.all { it.updatedAt == Instant.EPOCH })
        assertEquals(listOf("app", "docs"), server.projects.map { it.name })
        assertTrue(server.projects.all { it.directory.isEmpty() })
        assertTrue(server.name.isEmpty())
        // The configuration's suggestions and stored choices still resolve.
        assertEquals(listOf("Last Viewed Session", "New Session", "Scratch"), copy.suggestedSessionEntities().take(3).map { it.title })
        assertEquals(listOf("Older work"), copy.sessionEntities(listOf("$serverKey:ses_older")).map { it.title })
        assertEquals("app", SessionEntity.newSession(server).detail)
    }

    @Test
    fun theControlsCopyRoundTripsThroughItsStore() {
        val store = ControlDirectoryStore.inMemory()
        assertNull(store.load())
        store.save(directory().forControls)
        assertEquals(directory().forControls, store.load())
    }

    @Test
    fun aControlNamesItsChoiceAsItReadsNow() {
        val renamed = SessionEntity(id = "$serverKey:ses_older", title = "Old title", detail = "docs")
        val value = SessionControlValue.of(renamed, directory())
        assertEquals("$serverKey:ses_older", value.targetID)
        assertEquals("Older work", value.title)
        assertEquals("docs", value.detail)

        // A session the directory no longer lists keeps the name it was chosen with.
        val gone = SessionEntity(id = "$serverKey:ses_gone", title = "Deleted work", detail = null)
        assertEquals("Deleted work", SessionControlValue.of(gone, directory()).title)

        assertEquals("Last Viewed Session", SessionControlValue.of(null, directory()).title)
        assertNull(SessionControlValue.of(null, directory()).targetID)
        assertEquals("Last Viewed Session", SessionControlValue.of(SessionEntity.lastViewed(null), SessionDirectory()).title)
        val new = SessionControlValue.of(SessionEntity.newSession(null), SessionDirectory())
        assertEquals("New Session", new.title)
        assertTrue(new.isNewSession)
    }

    /** iOS's intents post to the shared inbox; Android's `perform` returns the action `SystemActionActivity` posts. */
    @Test
    fun controlIntentsPostTheirChoice() {
        assertEquals(
            SystemAction(serverKey, SystemAction.Destination.Session("ses_newer"), startsCall = true),
            ControlIntents.perform(OpenCodeControlKind.START_CALL, "$serverKey:ses_newer"),
        )
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = true), ControlIntents.perform(OpenCodeControlKind.START_CALL, null))
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft(null)), ControlIntents.perform(OpenCodeControlKind.OPEN_SESSION, SessionEntity.NEW_SESSION_ID))
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft(null)), ControlIntents.perform(OpenCodeControlKind.NEW_SESSION, null))
    }
}

class SystemActionTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun linksOpenSessionsAndDrafts() {
        assertEquals(SystemAction(serverKey, SystemAction.Destination.Session("ses_newer")), SystemAction.fromUrl("opencode://session/ses_newer?server=$serverKey"))
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft("prj_app")), SystemAction.fromUrl("opencode://new-session?project=prj_app"))
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed), SystemAction.fromUrl("opencode://last-session"))
    }

    @Test
    fun linksNeverStartCalls() {
        assertNull(SystemAction.fromUrl("opencode://call"))
        assertNull(SystemAction.fromUrl("https://session/ses_x"))
        assertNull(SystemAction.fromUrl("opencode://session"))
    }

    /** iOS Spotlight identifiers → a session shortcut's scoped ID. */
    @Test
    fun spotlightIdentifiersAreScopedSessions() {
        assertEquals(SystemAction(serverKey, SystemAction.Destination.Session("ses_newer")), SystemAction.fromScopedIdentifier("$serverKey:ses_newer"))
        assertNull(SystemAction.fromScopedIdentifier("ses_newer"))
    }

    @Test
    fun theInboxHoldsActionsUntilSomeoneListens() = runBlocking {
        val inbox = SystemActionInbox()
        inbox.post(SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = true))
        val actions = inbox.actions().produceIn(this)
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = true), actions.receive())
        inbox.post(SystemAction(destination = SystemAction.Destination.Draft(null)))
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft(null)), actions.receive())
        actions.cancel()
    }

    @Test
    fun aNewListenerReplacesTheOld() = runBlocking {
        val inbox = SystemActionInbox()
        val first = inbox.actions().produceIn(this)
        repeat(5) { yield() }
        val second = inbox.actions().produceIn(this)
        repeat(5) { yield() }
        inbox.post(SystemAction(destination = SystemAction.Destination.LastViewed))
        assertNull(first.receiveCatching().getOrNull())
        assertEquals(SystemAction(destination = SystemAction.Destination.LastViewed), second.receive())
        second.cancel()
    }
}

class QuickActionTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun recentSessionsFillTheFourSlots() {
        val items = SystemQuickAction.items(directoryServer(), voiceAvailable = false)
        assertEquals(List(3) { SystemQuickAction.Kind.OpenSession }, items.map { it.kind })
        assertEquals(listOf("Fix login", "Scratch", "Older work"), items.map { it.title })
        assertEquals(listOf("app", null, "docs"), items.map { it.subtitle })
        assertEquals(SystemQuickAction.LIMIT, items.size + SystemQuickAction.STATIC_COUNT)
    }

    @Test
    fun startCallTakesASlotWhenVoiceIsSetUp() {
        val items = SystemQuickAction.items(directoryServer(), voiceAvailable = true)
        assertEquals(listOf(SystemQuickAction.Kind.StartCall, SystemQuickAction.Kind.OpenSession, SystemQuickAction.Kind.OpenSession), items.map { it.kind })
        assertEquals("Start Call", items[0].title)
        assertEquals("Older work", items[0].subtitle)
    }

    @Test
    fun noServerNoDynamicItems() {
        assertTrue(SystemQuickAction.items(null, voiceAvailable = true).isEmpty())
    }

    @Test
    fun tappedItemsBecomeActions() {
        for (item in SystemQuickAction.items(directoryServer(), voiceAvailable = true)) {
            assertEquals(item.action, SystemQuickAction.action(item.kind.rawValue, item.userInfo))
        }
        assertEquals(
            SystemAction(serverKey, SystemAction.Destination.LastViewed, startsCall = true),
            SystemQuickAction.action("ai.opencode.mobilecode.start-call", mapOf("server" to serverKey)),
        )
        // The static New Session item carries no user info.
        assertEquals(SystemAction(destination = SystemAction.Destination.Draft(null)), SystemQuickAction.action("ai.opencode.mobilecode.new-session", null))
        assertNull(SystemQuickAction.action("ai.opencode.mobilecode.open-session", null))
        assertNull(SystemQuickAction.action("com.example.other", null))
    }
}

class SystemPromptSenderTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Sent {
        val prompts = mutableListOf<Triple<String, String, OpenCodeDelivery?>>()
        val created = mutableListOf<String>()
    }

    private fun client(sent: Sent): OpenCodeClient = OpenCodeClient.testValue.copy(
        sendDeliveredPrompt = { requested, sessionID, text, delivery ->
            assertEquals(server, requested)
            sent.prompts += Triple(sessionID, text, delivery)
            "msg_1"
        },
        createSession = { requested, directory, _ ->
            assertEquals(server, requested)
            sent.created += directory
            OpenCodeSession(id = "ses_created", title = "New session", projectID = "prj_app", directory = directory, updatedAt = Instant.now())
        },
    )

    @Test
    fun sendsToAChosenSessionWithItsDelivery() = runBlocking {
        val sent = Sent()
        val result = SystemPromptSender.send(" Ship it ", "$serverKey:ses_newer", OpenCodeDelivery.Queue, directory(), listOf(otherServer, server), client(sent))
        assertEquals(listOf("ses_newer"), sent.prompts.map { it.first })
        assertEquals(listOf("Ship it"), sent.prompts.map { it.second })
        assertEquals(listOf(OpenCodeDelivery.Queue), sent.prompts.map { it.third })
        assertEquals("Queued for “Fix login”", result.confirmation)
    }

    @Test
    fun defaultsToTheLastViewedSession() = runBlocking {
        val sent = Sent()
        val result = SystemPromptSender.send("Hi", null, OpenCodeDelivery.Steer, directory(), listOf(server), client(sent))
        assertEquals(listOf("ses_older"), sent.prompts.map { it.first })
        assertEquals("Sent to “Older work”", result.confirmation)
    }

    @Test
    fun newSessionStartsInTheMostRecentProject() = runBlocking {
        val sent = Sent()
        val result = SystemPromptSender.send("Hi", SessionEntity.NEW_SESSION_ID, OpenCodeDelivery.Steer, directory(), listOf(server), client(sent))
        assertEquals(listOf("/work/app"), sent.created)
        assertEquals(listOf("ses_created"), sent.prompts.map { it.first })
        assertEquals("Started a new session in app", result.confirmation)
    }

    @Test
    fun explainsWhatsMissing() = runBlocking {
        val sent = Sent()
        try {
            SystemPromptSender.send("Hi", null, OpenCodeDelivery.Steer, directory(), listOf(otherServer), client(sent))
            fail("Expected noServer")
        } catch (failure: SystemPromptSender.Failure) {
            assertEquals(SystemPromptSender.Failure.noServer, failure)
        }
        try {
            SystemPromptSender.send("  ", null, OpenCodeDelivery.Steer, directory(), listOf(server), client(sent))
            fail("Expected emptyMessage")
        } catch (failure: SystemPromptSender.Failure) {
            assertEquals(SystemPromptSender.Failure.emptyMessage, failure)
        }
        assertTrue(sent.prompts.isEmpty())
    }
}

/** iOS waits real time for its debounce; the publisher's scope runs on the test's virtual clock here. */
class SystemPublisherTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun entry(key: String, title: String = "Work") = SessionDirectory.Server(
        key = key, name = key, pinnedIDs = emptyList(),
        sessions = listOf(SessionDirectory.Session(id = "ses_$key", title = title, projectID = "p", projectName = null, updatedAt = Instant.ofEpochSecond(1))),
        projects = emptyList(),
    )

    private val publishers = Job()

    /** The test dispatcher's scope, so its debounce runs on virtual time; each test cancels it when done. */
    private fun TestScope.publisherScope(): CoroutineScope = CoroutineScope(coroutineContext + publishers)

    @Test
    fun removingAServerCancelsItsPendingPublish() = runTest {
        val jobs = mutableListOf<SystemIntegrationPublisher.Job>()
        val publisher = SystemIntegrationPublisher(delay = kotlin.time.Duration.parse("50ms"), scope = publisherScope()) { jobs += it }
        publisher.schedule(entry("a"), voiceAvailable = false)
        publisher.remove("a")
        advanceUntilIdle()
        assertEquals(listOf(SystemIntegrationPublisher.Job.Remove("a")), jobs)
        publishers.cancel()
    }

    @Test
    fun eachServerDebouncesOnItsOwn() = runTest {
        val jobs = mutableListOf<SystemIntegrationPublisher.Job>()
        val publisher = SystemIntegrationPublisher(delay = kotlin.time.Duration.parse("50ms"), scope = publisherScope()) { jobs += it }
        publisher.schedule(entry("a", title = "First"), voiceAvailable = false)
        publisher.schedule(entry("b"), voiceAvailable = false)
        publisher.schedule(entry("a", title = "Second"), voiceAvailable = true)
        advanceUntilIdle()
        assertEquals(2, jobs.size)
        assertTrue(SystemIntegrationPublisher.Job.Publish(entry("b"), voiceAvailable = false) in jobs)
        assertTrue(SystemIntegrationPublisher.Job.Publish(entry("a", title = "Second"), voiceAvailable = true) in jobs)
        publishers.cancel()
    }

    @Test
    fun aRemovalAfterAPublishRunsAfterIt() = runTest {
        val jobs = mutableListOf<SystemIntegrationPublisher.Job>()
        val publisher = SystemIntegrationPublisher(delay = kotlin.time.Duration.parse("20ms"), scope = publisherScope()) { jobs += it }
        publisher.schedule(entry("a"), voiceAvailable = false)
        advanceUntilIdle()
        publisher.remove("a")
        advanceUntilIdle()
        assertEquals(listOf(SystemIntegrationPublisher.Job.Publish(entry("a"), voiceAvailable = false), SystemIntegrationPublisher.Job.Remove("a")), jobs)
        publishers.cancel()
    }
}
