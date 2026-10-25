package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * `:server`'s part of iOS `SessionLinkTests`: the URL/ID half of `SessionLink` that moved down
 * here. Parsing and presentation (`OpenCodeMarkdownBlock`, `SessionLinkText`) belong to
 * `:feature:transcript`; the session, Home and link-menu tests to `:feature:session`,
 * `:feature:drawer` and `:feature:media`.
 */
class SessionLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    companion object {
        const val ID = "ses_ea0cb2871ffeNzq62aRonBKB2l"
    }

    @Test
    fun linkURLRoundTripsItsIDAndTitle() {
        val url = SessionLink.url(id = ID, title = "Fix the build")
        assertEquals(ID, SessionLink.id(url))
        assertEquals("Fix the build", SessionLink.title(url))
        assertNull(SessionLink.id("opencode-session://open?id=nope"))
        assertEquals("ses_ea0c…KB2l", SessionLink.shortID(ID))
    }
}
