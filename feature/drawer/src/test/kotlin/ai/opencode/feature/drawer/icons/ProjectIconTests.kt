package ai.opencode.feature.drawer.icons

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeProjectIcon
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of iOS `ProjectIconTests.swift`, for what `:feature:drawer` owns and the JVM can run.
 *
 * Other modules:
 * - `experimentalFlagIsOffByDefault` (`ExperimentalFeature.projectIcons`): `:core`'s experiments.
 * - `derivesDisplayNameAndDisambiguatedInitials`, `decodesProjectIconSchemaAndFollowsDesktopPrecedence`
 *   (`ProjectMark`, `OpenCodeProjectIcon`) and `decodesProjectUpdatedStreamFrame`
 *   (`OpenCodeClient.decodeStreamFrame`): `:server`.
 *
 * Deleted (need Android's graphics stack; Robolectric isn't in the build, and these assert on
 * decoded pixels, which only a real `ImageDecoder`/`Bitmap`/`Canvas` produce):
 * - `rasterizesSVGAndClassifiesFullBleedAndTransparentGlyphs`
 * - `composerWrapperRendersItsLayerAndBackground`
 * - `embeddedImagesStayLocalAndBounded` (its `ProjectSVGPaint.color` rows go through
 *   `android.graphics.ColorSpace`, a stub under JVM tests)
 *
 * Partially ported (the parts that don't decode an image):
 * - `serverDataURLsCacheLocallyAndRefreshWhenContentChanges`: a content change makes a new cache
 *   key; nothing is fetched for a data URL's request.
 * - `loaderCachesOnDiskCoalescesAndRespectsLowDataMode`: Low Data Mode defers without fetching or
 *   caching; external URLs never get the server's credentials or headers; scheme-relative URLs are
 *   rejected; a cached 404 answers synchronously. The decoded-icon steps (coalesced 200, disk
 *   reload, built-in OpenCode icon, ETag/304 reuse) need the decoder.
 */
class ProjectIconTests {
    @get:Rule val isolation = TestIsolationRule()
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun homeFeatureAppliesProjectUpdatedEvent() = runTest {
        val server = OpenCodeServer(url = "https://box.example.com:4096", username = "opencode", password = "secret")
        val initialProject = OpenCodeProject(id = "prj_1", name = "voice-control", directory = "/code/voice-control")
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Fix audio route",
            projectID = "prj_1",
            directory = "/code/voice-control",
            updatedAt = Instant.ofEpochSecond(1_700_000_000),
        )
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(initialProject), sessions = listOf(session), activeSessionIDs = emptySet()))
        val store = TestStore(state, HomeFeature(), this) {
            this[HomeSnapshotDate] = DateClient.constant(Instant.EPOCH)
        }

        val updatedProject = OpenCodeProject(
            id = "prj_1",
            name = "hex",
            directory = "/code/voice-control",
            repository = "hex",
            icon = OpenCodeProjectIcon(url = "data:image/svg+xml;base64,PHN2Zy8+"),
        )
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ProjectUpdated(updatedProject))) {
            it.copy(
                projects = listOf(updatedProject),
                sessionDetail = it.sessionDetail?.copy(
                    projects = it.sessionDetail!!.projects.map { project -> if (project.id == updatedProject.id) updatedProject else project },
                    draftProject = if (it.sessionDetail!!.draftProject?.id == updatedProject.id) updatedProject else it.sessionDetail!!.draftProject,
                ),
            )
        }
        assertEquals(listOf(updatedProject), store.state.sessionDetail?.projects)
        assertEquals(updatedProject, store.state.sessionDetail?.draftProject)
        val cached = store.withDependencies { shared(ServerSharedKeys.cachedHomeSnapshots).value[server.persistenceID] }
        assertEquals(
            CachedHomeSnapshot(
                serverID = server.persistenceID,
                snapshot = OpenCodeHomeSnapshot(projects = listOf(updatedProject), sessions = listOf(session), activeSessionIDs = emptySet()),
                capturedAt = Instant.EPOCH,
            ),
            cached,
        )
        store.finish()
    }

    @Test
    fun serverDataURLsCacheLocallyAndRefreshWhenContentChanges() {
        val server = OpenCodeServer(url = "https://example.com", username = "opencode", password = "")
        val old = OpenCodeProject(id = "p", name = "Project", directory = "/code", icon = OpenCodeProjectIcon(url = cachedSVG))
        val request = requireNotNull(old.iconRequest(server))
        assertTrue("A data URL never revalidates", request.isVersioned)
        val markup = String(requireNotNull(ProjectIcon.decodeDataURL(cachedSVG)), Charsets.UTF_8)
        val changed = old.copy(
            icon = OpenCodeProjectIcon(url = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(markup.replace("white", "#00ff00").toByteArray())),
        )
        val next = requireNotNull(changed.iconRequest(server))
        assertNotEquals(request.cacheKey, next.cacheKey)
    }

    @Test
    fun loaderCachesOnDiskCoalescesAndRespectsLowDataMode() = runTest {
        val directory = folder.newFolder("project-icons-test")
        val server = OpenCodeServer(
            url = "https://studio.tail.ts.net:4096",
            username = "opencode",
            password = "secret-password",
            headers = listOf(OpenCodeServer.Header("CF-Access-Client-Id", "id-1")),
        )
        val request = ProjectIconRequest(server = server, projectID = "prj_hex", source = "/assets/hex.svg?v=hash1")
        val fetchCount = AtomicInteger(0)

        // Under Low Data Mode, network fetches defer without hitting the network or caching `missing`.
        val loader = ProjectIconLoader(
            fetch = { _, _, _ ->
                fetchCount.incrementAndGet()
                throw IOException("offline")
            },
            directory = directory,
            now = { Instant.ofEpochSecond(1_700_000_000) },
            isConstrained = { true },
            scope = backgroundScope,
        )
        assertEquals(ProjectIconResult.Deferred, loader.load(request))
        assertEquals(0, fetchCount.get())
        assertNull(loader.cached(request))

        // External URLs never receive the server's Authorization or custom headers, and
        // scheme-relative `//host` URLs are rejected outright.
        val externalBuilt = ProjectIconLoader.buildURLRequest("https://opencode.ai/favicon.svg", server)
        assertEquals(false, externalBuilt?.second)
        assertNull(externalBuilt?.first?.header("Authorization"))
        assertNull(externalBuilt?.first?.header("CF-Access-Client-Id"))
        val serverBuilt = requireNotNull(ProjectIconLoader.buildURLRequest(request.source, server))
        assertTrue(serverBuilt.second)
        assertNotNull(serverBuilt.first.header("Authorization"))
        assertEquals("id-1", serverBuilt.first.header("CF-Access-Client-Id"))

        assertNull(ProjectIconLoader.buildURLRequest("//evil.example.com/icon.svg", server))
        val schemeRelativeReq = ProjectIconRequest(server = server, projectID = "prj_1", source = "//evil.example.com/icon.svg?v=1")
        assertFalse(schemeRelativeReq.isVersioned)
        assertEquals("//evil.example.com/icon.svg?v=1", schemeRelativeReq.resolvedURLString)

        // A cached `missing` (e.g. 404) is answered synchronously by `cached`, so the drawer's
        // project tile doesn't start a load on every appearance.
        val missingLoader = ProjectIconLoader(
            fetch = { _, _, _ ->
                fetchCount.incrementAndGet()
                ProjectIconLoader.FetchResponse(ByteArray(0), 404, Headers.headersOf())
            },
            directory = directory,
            now = { Instant.ofEpochSecond(1_700_000_150) },
            scope = backgroundScope,
        )
        val missingRequest = ProjectIconRequest(server = server, projectID = "prj_404", source = "/assets/missing.svg?v=none")
        val before404 = fetchCount.get()
        assertEquals(ProjectIconResult.Missing, missingLoader.load(missingRequest))
        assertEquals(before404 + 1, fetchCount.get())
        assertEquals(ProjectIconResult.Missing, missingLoader.cached(missingRequest))
    }

    private companion object {
        val cachedSVG = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(
            """<svg viewBox="0 0 64 64"><rect width="64" height="64" fill="#111111"/><rect x="16" y="16" width="32" height="32" fill="white"/></svg>""".toByteArray(),
        )
    }
}
