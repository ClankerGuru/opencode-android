package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Port of the pickers section of iOS `ModelVisibilityTests.swift` (`SessionFeature.State`'s picker lists).
 *
 * Hide Unused Models (OC-510): which models the Today and Sheet pickers list, kept per server.
 *
 * Not ported here (:server, `ModelVisibility` rules alone): `hideListsEverythingButTheHiddenModelsAndProviders`,
 * `onlyShowListsJustTheChosenModelsAndKeepsEachList`, `aHiddenProviderWinsOverOnlyShow`,
 * `onlyShowIgnoresChosenModelsTheCatalogNoLongerOffers`, `staleEntriesNameWhatTheCatalogDroppedAndCanBeForgotten`,
 * `theCurrentModelStaysListed`.
 * Not ported here (:feature:settings, Settings › Models): `settingsTogglesWriteThisServersChoice`,
 * `settingsForgetsUnavailableModelsOnceTheCatalogLoads`, `aFailedCatalogReadMarksNothingStale`, `undoingEveryChangeForgetsTheServer`.
 */
class ModelVisibilityTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val other = OpenCodeServer(url = "http://localhost:10", username = "", password = "")

    private val cirrus = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4")
    private val mini = OpenCodeModel(modelID = "cirrus-4-mini", providerID = "opencode", name = "Cirrus 4 Mini")
    private val router = OpenCodeModel(modelID = "vendor/cirrus-4", providerID = "openrouter", name = "Cirrus 4")
    private val basalt = OpenCodeModel(modelID = "basalt-pro", providerID = "halcyon", name = "Basalt Pro")
    private val catalog: List<OpenCodeModel> get() = listOf(cirrus, mini, router, basalt)

    private fun Harness.sessionState(visibility: ModelVisibility?, experiment: Boolean = true): SessionFeature.State {
        val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH, model = cirrus.ref)
        val state = state { SessionFeature.State.session(server = server, session = session) }.copy(models = catalog)
        state.hideModelsExperiment.set(experiment)
        if (visibility != null) state.modelVisibilityByServer.set(mapOf(server.persistenceID to visibility))
        return state
    }

    @Test fun experimentOffListsTheWholeCatalog() = sessionTest { h ->
        val state = h.sessionState(ModelVisibility(hiddenModelIDs = setOf(mini.id, basalt.id)), experiment = false)
        h.state {
            assertEquals(catalog, state.pickerModels)
            assertEquals(catalog, state.filteredModels)
            assertEquals(0, state.hiddenModelCount)
            assertEquals(catalog, state.modelSheetSections(recents = emptyList(), recentLimit = 3).flatMap { it.models })
        }
    }

    @Test fun bothPickersListOnlyVisibleModelsUntilShowAll() = sessionTest { h ->
        val state = h.sessionState(ModelVisibility(hiddenProviderIDs = setOf("openrouter"), hiddenModelIDs = setOf(basalt.id)))
        val models = catalog
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(modelCatalog = { _, _ -> OpenCodeModelCatalog(models = models, defaultModel = null) })
        }
        store.withDependencies {
            assertEquals("Today", listOf(cirrus, mini), store.state.filteredModels)
            assertEquals("Sheet", listOf(cirrus, mini), store.state.modelSheetSections(recents = emptyList(), recentLimit = 3).flatMap { it.models })
            assertEquals(2, store.state.hiddenModelCount)
        }

        store.send(Action.ShowAllModelsToggled) { it.copy(showsAllModels = true) }
        store.withDependencies {
            assertEquals(catalog, store.state.filteredModels)
            assertEquals("Show Fewer keeps the count", 2, store.state.hiddenModelCount)
        }

        store.send(Action.ModelListClosed) { it.copy(showsAllModels = false) }
        store.withDependencies { assertEquals(listOf(cirrus, mini), store.state.filteredModels) }
        store.finish()
    }

    /**
     * Every model hidden and no current model: the list is empty but Show
     * All still counts them, so the pickers show it rather than No Models.
     */
    @Test fun hidingEverythingStillOffersShowAll() = sessionTest { h ->
        var state = h.sessionState(ModelVisibility(hiddenProviderIDs = setOf("opencode", "openrouter", "halcyon"))).copy(currentModel = null)
        h.state {
            assertNull(state.currentModelInfo)
            assertTrue(state.filteredModels.isEmpty())
            assertTrue(state.modelSheetSections(recents = emptyList(), recentLimit = 3).isEmpty())
            assertEquals("The empty state gives way to Show All", catalog.size, state.hiddenModelCount)
            state = state.copy(showsAllModels = true)
            assertEquals(catalog, state.filteredModels)
        }
    }

    @Test fun staleOnlyShowChoicesNeverEmptyThePickers() = sessionTest { h ->
        val state = h.sessionState(ModelVisibility(mode = ModelVisibility.Mode.onlyShow, shownModelIDs = setOf("opencode/retired-model"))).copy(currentModel = null)
        h.state {
            assertEquals(catalog, state.filteredModels)
            assertEquals(0, state.hiddenModelCount)
        }
    }

    @Test fun eachServerKeepsItsOwnChoice() = sessionTest { h ->
        val state = h.sessionState(null)
        state.modelVisibilityByServer.set(mapOf(other.persistenceID to ModelVisibility(hiddenModelIDs = setOf(mini.id))))
        h.state { assertEquals(catalog, state.pickerModels) }
    }
}
