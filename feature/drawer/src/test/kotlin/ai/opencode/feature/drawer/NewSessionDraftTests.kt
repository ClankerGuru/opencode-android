package ai.opencode.feature.drawer

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.DraftProjectOrder
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.currentLevelScale
import ai.opencode.feature.session.currentModelName
import ai.opencode.feature.session.recentProjects
import ai.opencode.feature.session.setTyped
import ai.opencode.feature.session.taskIdentity
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.settings.SettingsFeature.SettingsDelegate
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `NewSessionDraftTests`' Home tests: drafts Home opens (`makeDraft`) and the fresh
 * task identity each draft-from-a-draft gets. The draft screen's own project sheet and sends are
 * `:feature:session`'s; `switchingServersFromADraft…` is `:app`'s (`AppFeature`).
 * `keepsIdentityMovesAdvanceVisitAndRunTask` is left to `:feature:session`'s port too: its
 * subject is the screen's `.task` under a carried recording or call (`stopSessionWork`,
 * `supersedeRunWork`), driven through Android's `DictationFeature`/`VoiceFeature` states.
 */
class NewSessionDraftTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val projects = (1..7).map { OpenCodeProject(id = "p$it", name = "project-$it", directory = "/srv/code/project-$it") }

    /** Nothing in these tests may create a session or send a prompt. */
    private fun noSends(client: OpenCodeClient) = client.copy(
        createSession = { _, _, _ -> throw AssertionError("Choosing a project must not create a session") },
        sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Choosing a project must not send") },
    )

    @Test
    fun aNewDraftVisitRestoresCurrentProjectRecency() {
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = projects, sessions = emptyList(), activeSessionIDs = emptySet()))
        // iOS's `makeDraft()` is mutating: Home keeps the state it returns.
        val first = home.makeDraft().let { (next, draft) -> home = next; draft }
        var order = DraftProjectOrder()
        order = order.update(first.recentProjects, chosen = first.draftProject, identity = first.taskIdentity)
        val original = order.projects

        home = home.copy(
            sessions = listOf(
                OpenCodeSession(
                    id = "ses_recent", title = "Recent", projectID = projects[6].id,
                    directory = projects[6].directory, updatedAt = Instant.ofEpochSecond(100),
                ),
            ),
        )
        val next = home.makeDraft().let { (state, draft) -> home = state; draft }
        assertNotEquals(first.taskIdentity, next.taskIdentity)
        // A refresh within the original visit must still leave its slots alone.
        order = order.update(next.recentProjects, chosen = null, identity = first.taskIdentity)
        assertEquals(original, order.projects)
        order = order.update(next.recentProjects, chosen = next.draftProject, identity = next.taskIdentity)
        assertEquals(next.recentProjects, order.projects)
        assertNotEquals(original, order.projects)
    }

    @Test
    fun aDraftTheVoiceAgentOpensInAFolderStartsThere() {
        val project = OpenCodeProject(id = "p1", name = "project-1", directory = "/srv/code/project-1")
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet()))
        val known = home.makeDraft(directory = "/srv/code/project-1").second
        assertEquals(project, known.draftProject)
        val folder = home.makeDraft(directory = "/srv/scratch").second
        assertEquals(
            "Not the first project",
            OpenCodeProject(id = "folder:/srv/scratch", name = "scratch", directory = "/srv/scratch"),
            folder.draftProject,
        )
        assertEquals(project, home.makeDraft().second.draftProject)
    }

    /**
     * Every entry point that opens a fresh draft from an existing draft advances `taskIdentity`
     * so the screen's task runs once and loads `models`.
     */
    @Test
    fun everyDraftFromADraftGetsAFreshTaskIdentityAndLoadsModels() = runTest {
        val model = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4", variants = listOf("low", "high"))
        val prior = OpenCodeSession(
            id = "ses_prior", title = "Prior session", projectID = projects[0].id, directory = projects[0].directory,
            updatedAt = Instant.EPOCH, model = model.ref("high"),
        )
        val catalog = OpenCodeModelCatalog(models = listOf(model), defaultModel = model)
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(projects[0], projects[1]), sessions = listOf(prior), activeSessionIDs = emptySet()))
        val initialIdentity = home.sessionDetail?.taskIdentity
        assertEquals(SessionFeature.State.TaskIdentity(serverID = server.persistenceID, visit = 1, sessionID = null), initialIdentity)
        assertEquals("cirrus-4", home.sessionDetail?.currentModelName)
        assertNull(home.sessionDetail?.currentLevelScale)

        val store = TestStore(home, HomeFeature(), this) {
            this[OpenCodeClient] = noSends(OpenCodeClient.testValue).copy(modelCatalog = { _, _ -> catalog })
        }
        fun HomeFeature.State.detail(update: (SessionFeature.State) -> SessionFeature.State) = copy(sessionDetail = update(sessionDetail!!))
        suspend fun runTaskAndLoadModels() {
            store.send(HomeFeature.Action.detail(SessionFeature.Action.Task)) { it.detail { d -> d.copy(isLoadingModels = true) } }
            store.receive(
                { it == HomeFeature.Action.detail(SessionFeature.Action.ModelsResponse(Result.success(catalog))) },
                "modelsResponse.success",
            ) { it.detail { d -> d.copy(isLoadingModels = false, models = listOf(model)) } }
            assertEquals("Cirrus 4", store.state.sessionDetail?.currentModelName)
            assertNotNull(store.state.sessionDetail?.currentLevelScale)
        }
        // Built with the store's dependencies: the screen's `Shared` values are its store's references.
        fun draft(project: OpenCodeProject, listed: List<OpenCodeProject>, navigationID: Int) = store.withDependencies {
            SessionFeature.State.draft(
                server = server, draftProject = project, projects = listed, inheritedModel = model.ref("high"), navigationID = navigationID,
            )
        }

        // 1. Initial draft runs .task and loads models.
        runTaskAndLoadModels()

        // 2. New Session from a draft gets a new taskIdentity and loads models when .task runs.
        store.send(HomeFeature.Action.NewSessionTapped) {
            it.copy(detailGeneration = 2, sessionDetail = draft(projects[0], listOf(projects[0], projects[1]), 2))
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 2, null), store.state.sessionDetail?.taskIdentity)
        assertNotEquals(initialIdentity, store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()

        // 3. What's New "Try It" from a draft gets a new taskIdentity and loads models. On Android
        // What's New lives in Settings, so Try It reaches Home as Settings' `TryWhatsNew` delegate.
        store.exhaustive = false
        store.send(HomeFeature.Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped))
        store.exhaustive = true
        val tryIt = SettingsFeature.Action.Delegate(SettingsDelegate.TryWhatsNew(WhatsNewEntry.Destination.Prompt("Try this"), "math-and-diagrams"))
        store.send(HomeFeature.Action.SettingsScreen(PresentationAction.Presented(tryIt))) {
            it.copy(
                settings = null,
                drawerCloseRequests = 1,
                detailGeneration = 3,
                sessionDetail = draft(projects[0], listOf(projects[0], projects[1]), 3).setTyped("Try this"),
            )
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 3, null), store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()

        // 4. Notify plugin "Set Up" from a draft gets a new taskIdentity and loads models.
        store.send(HomeFeature.Action.NotifyPluginSetUpTapped) {
            it.copy(
                detailGeneration = 4,
                sessionDetail = draft(projects[0], listOf(projects[0], projects[1]), 4).setTyped(HomeFeature.notifyPluginSetUpPrompt),
            )
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 4, null), store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()

        // 5. Voice/deep-link openDraft from a draft gets a new taskIdentity and loads models.
        store.send(HomeFeature.Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenDraft(projects[1].directory)))) {
            it.copy(detailGeneration = 5, sessionDetail = draft(projects[1], listOf(projects[1], projects[0]), 5))
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 5, null), store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()
        store.finish()
    }
}
