package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `MarkdownBlockTests.swift`, the `:server` part: `OpenCodeClient.markdownImage`
 * decoding a data URI into the client's own cache. iOS keys the cache by
 * `persistenceID|directory|source`; here that key is [OpenCodeClient.markdownImageKey]. The
 * Markdown parser's tests are `:feature:transcript`'s, highlighting `:feature:media`'s.
 */
class MarkdownBlockTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun markdownImageClientDecodesDataURIAndCachesInIsolatedTestScope() = runTest {
        val client = OpenCodeClient.testValue
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val dataURI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val key = OpenCodeClient.markdownImageKey(server, "/tmp", dataURI)
        assertEquals("${server.persistenceID}|/tmp|$dataURI", key)
        assertNull(client.cachedMarkdownImage(key))
        val loaded = client.markdownImage(server, "/tmp", dataURI, null)
        assertNotNull(loaded)
        assertSame(loaded, client.cachedMarkdownImage(key))
        assertNull("Each test client has its own cache", OpenCodeClient.testValue.cachedMarkdownImage(key))
    }
}
