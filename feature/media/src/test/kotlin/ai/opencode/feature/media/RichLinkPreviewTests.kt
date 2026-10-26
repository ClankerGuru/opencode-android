package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Port of iOS `RichLinkPreviewTests.swift`. Times are milliseconds. */
class RichLinkPreviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun source(text: String): RichLinkSource? = RichLinkSource.of(text)

    @Test
    fun firstPartyRequestsCarryOnlyParsedIdentifiers() {
        val pr = source("https://github.com/acme/widget/pull/42?search=private-text#discussion")!!
        assertEquals("https://api.github.com/repos/acme/widget/pulls/42", pr.request.url.toString())
        // iOS `httpShouldHandleCookies == false`: the preview transport has no cookie jar.
        assertNull(pr.request.header("Cookie"))
        assertNull(pr.request.header("Authorization"))
        val video = source("https://www.youtube.com/watch?v=aBcD3fGh1jK&t=44&extra=private")!!
        assertEquals("https://www.youtube.com/watch?v=aBcD3fGh1jK", video.request.url.queryParameter("url"))
        assertEquals("https://i.ytimg.com/vi/aBcD3fGh1jK/hqdefault.jpg", video.imageURL)
        assertEquals(video, source("https://youtu.be/aBcD3fGh1jK"))
        assertEquals(video, source("https://youtube.com/shorts/aBcD3fGh1jK"))
    }

    @Test
    fun unsupportedShapesNeverBecomeRequests() {
        for (text in listOf(
            "https://github.com.evil.com/a/b/pull/1", "https://github.com/acme/widget/pull/1/extra",
            "https://github.com/acme/widget/pull/-2", "https://github.com/acme/widget/pull/0",
            "https://github.com/acme/widget/pull/%31", "https://github.com/a%2Fb/widget",
            "https://github.com/acme/widget/commit/not-a-sha", "https://github.com/acme/widget/blob/main/a.swift",
            "https://github.com/orgs/anomalyco", "https://github.com/topics/swift", "https://github.com/settings/tokens",
            "https://github.com/acme/widget?token=secret", "https://github.com:8443/acme/widget",
            "https://youtu.be/not-an-id", "https://youtube.com/watch?v=aBcD3fGh1jK&v=other",
            "https://youtube.com.evil.com/watch?v=aBcD3fGh1jK", "https://example.com/image.jpg",
        )) {
            assertNull(text, source(text))
        }
    }

    @Test
    fun githubUsesActualStateAndDiffRatherThanInferringFromURL() {
        val pr = source("https://github.com/acme/widget/pull/42")!!
        val metadata = pr.metadata(
            """{"title":"Retry with jitter","state":"closed","merged":true,"draft":false,"user":{"login":"alex","id":12},"additions":84,"deletions":12,"head":{"ref":"retry"},"base":{"ref":"main"},"labels":[{"name":"networking"}]}""".toByteArray(),
        )!!
        assertEquals(GitHubPreview.State.Merged, metadata.github?.state)
        assertTrue(metadata.github?.additions == 84 && metadata.github?.deletions == 12)
        assertEquals("acme/widget #42", metadata.github?.caption)
        assertEquals("alex", metadata.github?.author)
        assertEquals(listOf("networking"), metadata.github?.labels)
        val issue = source("https://github.com/acme/widget/issues/5")!!
        assertEquals(
            GitHubPreview.State.NotPlanned,
            issue.metadata("""{"title":"Old report","state":"closed","state_reason":"not_planned"}""".toByteArray())?.github?.state,
        )
        assertNull(pr.metadata("""{"message":"Not Found"}""".toByteArray()))
    }

    @Test
    fun richResponseCannotSupplyAnArbitraryImageFetch() {
        val video = source("https://youtu.be/aBcD3fGh1jK")!!
        val result = video.metadata("""{"title":"A talk","author_name":"Alex","thumbnail_url":"http://127.0.0.1/private"}""".toByteArray())!!
        assertTrue(result.title == "A talk" && result.image == null)
        assertEquals("i.ytimg.com", video.imageURL?.toHttpUrl()?.host)
    }

    @Test
    fun githubCacheExpiresInMinutesAndRateLimitsAreRetryable() = runTest {
        val time = Locked(0L)
        val calls = Locked(0)
        val status = Locked(200)
        val target = LinkPreviewTarget.of("https://github.com/acme/widget")!!
        val loader = LinkPreviewLoader(
            fetch = {
                calls.update { it + 1 }
                LinkPreviewResponse("""{"full_name":"acme/widget","description":"Uploads","stargazers_count":42}""".toByteArray(), status.current)
            },
            directory = null,
            now = { time.current },
        )
        val loaded = loader.load(target)
        val metadata = (loaded as? LinkPreviewResult.Loaded)?.metadata
        assertTrue("Expected metadata", metadata != null)
        loader.load(target)
        assertEquals(1, calls.current)
        time.update { it + 301_000 }
        loader.load(target)
        assertEquals(2, calls.current)
        // When expired and rate-limited (403), the loader reports Deferred for retry backoff while keeping stale metadata.
        time.update { it + 301_000 }
        status.set(403)
        assertNull(loader.cached(target))
        assertEquals(metadata, loader.stale(target))
        assertEquals(LinkPreviewResult.Deferred, loader.load(target))
        assertEquals(metadata, loader.stale(target))
        assertEquals(3, calls.current)
        val limited = LinkPreviewLoader(fetch = { LinkPreviewResponse(ByteArray(0), 403) }, directory = null)
        assertEquals(LinkPreviewResult.Deferred, limited.load(target))
        assertNull(limited.cached(target))
        assertNull(limited.stale(target))
    }

    /**
     * iOS parses Markdown (`:feature:transcript`), which appends a pull request card block; the
     * extractor reads only the prose's own links, given here as the transcript hands them.
     */
    @Test
    fun aPRIsOneCardEvenWithCachedAppendedBlocks() {
        val blocks = with(LinkBlocks) {
            listOf(
                paragraph(
                    0,
                    text("Read "), link("the PR", "https://github.com/acme/widget/pull/42"), text(", then "),
                    link("the repo", "https://github.com/acme/widget"), text("."),
                ),
                other(1),
            )
        }
        assertEquals(
            listOf("https://github.com/acme/widget/pull/42", "https://github.com/acme/widget"),
            LinkPreviewExtractor.targets(blocks).map { it.key },
        )
        val signed = listOf(LinkBlocks.paragraph(0, LinkBlocks.link("Private link", "https://github.com/acme/widget/pull/42?token=secret")), LinkBlocks.other(1))
        assertTrue("A synthetic PR block must not bypass the original URL policy", LinkPreviewExtractor.targets(signed).isEmpty())
    }
}
