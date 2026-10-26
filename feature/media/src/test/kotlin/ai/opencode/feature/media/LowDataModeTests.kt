package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.retry.NetworkPathMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ai.opencode.server.model.AgentFileReference
import org.junit.Assert.assertFalse
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The `:feature:media` half of iOS `LowDataModeTests.swift` (`LinkPreviewClient`'s loaders and
 * store). `NetworkPathMonitor` and `TransportFailure` are `:server`'s. Android has no
 * per-request `allowsConstrainedNetworkAccess`: the preview transport refuses every request on a
 * constrained path (`LinkPreviewTransport.ConstrainedRefusal`), so those request asserts have
 * no counterpart; the loaders' deferral is checked as on iOS. `AgentImagePreviews` (media) is
 * ported in full.
 */
class LowDataModeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun linkPreviewsAndSiteIconsDisallowConstrainedNetworkAccessAndDeferWhenConstrained() = runTest {
        val constrained = Locked(true)
        val deferArtwork = Locked(false)
        val requests = Locked(listOf<Request>())

        val loader = LinkPreviewLoader(
            fetch = { request ->
                requests.update { it + request }
                if (request.url.host == "i.ytimg.com") {
                    LinkPreviewResponse("poster".toByteArray(), 200)
                } else {
                    LinkPreviewResponse("""{"title":"Demo Video","author_name":"Kit"}""".toByteArray(), 200)
                }
            },
            directory = null,
            isConstrained = { constrained.current },
            shouldDeferArtwork = { deferArtwork.current },
        )

        val video = LinkPreviewTarget.of("https://www.youtube.com/watch?v=aBcD3fGh1jK")!!
        // While constrained, the network fetch is skipped completely and returns Deferred.
        assertEquals(LinkPreviewResult.Deferred, loader.load(video))
        assertTrue(requests.current.isEmpty())

        // Unconstrained but metered + battery saver: metadata loads, the 2 MB poster artwork is skipped.
        constrained.set(false)
        deferArtwork.set(true)
        val meta = (loader.load(video) as? LinkPreviewResult.Loaded)?.metadata
        assertEquals("Demo Video", meta?.title)
        assertNull(meta?.image)
        assertEquals(1, requests.current.size)

        // SiteIconLoader also defers while constrained without touching the network.
        val iconCalls = Locked(0)
        val iconLoader = SiteIconLoader(
            fetch = {
                iconCalls.update { it + 1 }
                LinkPreviewResponse("icon".toByteArray(), 200)
            },
            directory = null,
            isConstrained = { true },
        )
        assertEquals(SiteIconResult.Deferred, iconLoader.load("example.com"))
        assertEquals(0, iconCalls.current)
    }

    @Test
    fun linkPreviewStoreDoesNotPermanentlyCacheDeferredIconsAndRetriesWhenPathBecomesUnconstrained() = runTest {
        onMain {
            val constrained = Locked(true)
            val previewCalls = Locked(0)
            val iconCalls = Locked(0)
            val sampleIcon = stubSiteIcon("example")
            val sampleMeta = LinkPreviewMetadata(kind = LinkPreviewMetadata.Kind.Post, title = "Example Dev", handle = "example_dev", text = "Hello", site = "X")
            val path = MutableSharedFlow<NetworkPathMonitor.Snapshot>(replay = 1)

            val client = LinkPreviewClient(
                cached = { null },
                stale = { null },
                load = {
                    previewCalls.update { it + 1 }
                    if (constrained.current) LinkPreviewResult.Deferred else LinkPreviewResult.Loaded(sampleMeta)
                },
                cachedIcon = { null },
                loadIcon = {
                    iconCalls.update { it + 1 }
                    if (constrained.current) SiteIconResult.Deferred else SiteIconResult.Icon(sampleIcon)
                },
            )
            val store = LinkPreviewStore(client = client, pathUpdates = { path })
            val arrivedRows = mutableListOf<List<String>>()
            store.onArrival = { arrivedRows += it }

            val post = LinkPreviewTarget.of("https://x.com/example_dev/status/1")!!
            val web = LinkPreviewTarget.of("https://example.com/docs")!!

            path.emit(NetworkPathMonitor.Snapshot(isSatisfied = true, primaryInterface = "wifi wlan0", isExpensive = false, isConstrained = true))
            store.appeared(post, row = "row-post")
            store.appeared(web, row = "row-web")

            for (i in 0 until 50) {
                if (previewCalls.current >= 1 && iconCalls.current >= 1) break
                delay(5)
            }
            assertEquals(1, previewCalls.current)
            assertEquals(1, iconCalls.current)
            assertNull("Deferred icon is not cached as an icon", store.preview(web).icon)

            // When the path becomes unconstrained, both the deferred preview and icon retry and reload their rows.
            constrained.set(false)
            path.emit(NetworkPathMonitor.Snapshot(isSatisfied = true, primaryInterface = "wifi wlan0", isExpensive = false, isConstrained = false))

            for (i in 0 until 100) {
                if (store.preview(web).icon != null && store.preview(post).display != LinkPreview.Display.Pending) break
                delay(5)
            }
            assertEquals(2, previewCalls.current)
            assertEquals(2, iconCalls.current)
            assertEquals(sampleIcon, store.preview(web).icon)
            assertEquals(LinkPreview.Display.Post(author = "Example Dev", handle = "example_dev", text = "Hello"), store.preview(post).display)
            assertTrue(arrivedRows.flatten().contains("row-web"))
            assertTrue(arrivedRows.flatten().contains("row-post"))
            store.close()
        }
    }
    @Test
    fun agentImagePreviewsDeferInLowDataModeRecoverWhenUnconstrainedAndKeepRefusedRechecksUnconfirmed() = runTest {
        val constrained = Locked(true)
        val loadRequests = Locked(listOf<AgentImageRequest>())
        val probeRequests = Locked(listOf<AgentImageRequest>())
        val path = MutableSharedFlow<NetworkPathMonitor.Snapshot>(replay = 1)
        val dispatcher = StandardTestDispatcher(testScheduler)

        val previews = AgentImagePreviews(
            sizes = AgentImageSizeCache.inMemory,
            loader = { request, _, _ ->
                loadRequests.update { it + request }
                if (!request.allowsConstrainedNetworkAccess && constrained.current) {
                    throw AgentImageDownload.FailureException(AgentImageDownload.Failure.Constrained)
                }
                AgentImageResponse.File(AgentImageFixtures.red, AgentImageValidators(entityTag = "\"v1\""))
            },
            decoder = AgentImageFixtures.decoder,
            sleep = {},
            prober = { request, _ ->
                probeRequests.update { it + request }
                AgentImageProbeResult.Size(AgentImageSize(40.0, 30.0), entityTag = "\"v1\"")
            },
            shouldDeferPrefetch = { constrained.current },
            pathUpdates = { path },
            dispatcher = dispatcher,
        )

        val key = AgentImageKey(server = "s", path = "/game/hero.png")
        val sighting = AgentImageSighting(key, "reply-1")
        val request = AgentImageRequest(Request.Builder().url("http://lab/api/fs/read/hero.png").build())

        // 1. The accessibility value distinguishes Data Saver deferral inside the project from outside-the-project files.
        assertEquals("Deferred for Low Data Mode", AgentImagePlan.outsideAccessibilityValue(AgentFileReference(path = "hero.png"), "/game"))
        assertEquals("Outside the project", AgentImagePlan.outsideAccessibilityValue(AgentFileReference(path = "/etc/hosts.png"), "/game"))

        // 2. Under Data Saver, un-allowed images in the session folder are deferred to Tap to load (outside).
        assertTrue(previews.isDeferredByNetwork(key))
        val deferredPlan = AgentImagePlan.make(
            files = listOf(AgentFileReference(path = "hero.png")),
            directory = "/game",
            phase = { _, _ -> previews.entry(key).phase },
            allowed = { previews.isAllowed(key) && it.absolutePath == key.path },
            deferred = { previews.isDeferredByNetwork(key) },
        )
        assertEquals(listOf("hero.png"), deferredPlan.outside.map { it.path })
        assertTrue(deferredPlan.previews.isEmpty())

        // Probe and show both defer without hitting the network.
        previews.probe(key, request)
        previews.show(sighting, request)
        delay(20)
        assertTrue(probeRequests.current.isEmpty())
        assertTrue(loadRequests.current.isEmpty())

        // 3. When the path becomes unconstrained while visible, the deferred image probes and loads.
        constrained.set(false)
        path.emit(NetworkPathMonitor.Snapshot(isSatisfied = true, primaryInterface = "wifi wlan0", isExpensive = false, isConstrained = false))
        for (i in 0 until 100) {
            if (previews.entry(key).phase == AgentImagePreviews.Phase.Loaded) break
            delay(5)
        }
        assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
        assertFalse(previews.isDeferredByNetwork(key))
        assertTrue(previews.isFresh(sighting))
        previews.close()

        // 4. A revalidation (a second reply naming the same file) refused under Data Saver must NOT mark the new sighting fresh.
        val recheckPreviews = AgentImagePreviews(
            sizes = AgentImageSizeCache.inMemory,
            loader = { request, validators, _ ->
                loadRequests.update { it + request }
                if (validators != null && !request.allowsConstrainedNetworkAccess) {
                    throw AgentImageDownload.FailureException(AgentImageDownload.Failure.Constrained)
                }
                AgentImageResponse.File(AgentImageFixtures.blue, AgentImageValidators(entityTag = "\"v1\""))
            },
            decoder = AgentImageFixtures.decoder,
            sleep = {},
            shouldDeferPrefetch = { false },
            pathUpdates = { emptyFlow() },
            dispatcher = dispatcher,
        )
        loadRequests.set(emptyList())
        val firstSighting = AgentImageSighting(key, "reply-a")
        val secondSighting = AgentImageSighting(key, "reply-b")
        recheckPreviews.show(firstSighting, request)
        for (i in 0 until 100) {
            if (recheckPreviews.entry(key).phase == AgentImagePreviews.Phase.Loaded) break
            delay(5)
        }
        assertTrue(recheckPreviews.isFresh(firstSighting))

        recheckPreviews.show(secondSighting, request)
        for (i in 0 until 100) {
            if (loadRequests.current.size >= 2 && recheckPreviews.activeReads == 0) break
            delay(5)
        }
        assertEquals(AgentImagePreviews.Phase.Loaded, recheckPreviews.entry(key).phase)
        assertFalse(recheckPreviews.entry(key).rechecking)
        assertFalse(
            "Refused revalidation must remain unconfirmed so tapping fetches the current file",
            recheckPreviews.isFresh(secondSighting),
        )
        recheckPreviews.close()
    }
}
