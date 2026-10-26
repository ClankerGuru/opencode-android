package ai.opencode.feature.system.share

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ServerIDTests.swift`'s `shareSheetIDsUseTheSameShape` (`ShareSendRequest`); the
 * rest of that file is `:server`'s `ServerIDTests`.
 */
class ServerIDTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun shareSheetIDsUseTheSameShape() {
        val first = ShareSendRequest.newMessageID()
        val second = ShareSendRequest.newMessageID()
        assertTrue(matches(first, prefix = "msg_") && first < second)
        val date = 1_800_000_000_000L
        // The session's randomness is the message ID's random tail, not its time.
        assertEquals("ses_ce7a3afffffeQQuLgSGsIe7ZHF", ShareSendRequest.newSessionID(atEpochMillis = date, entropy = "msg_15cb96740001QQuLgSGsIe7ZHF"))
    }

    private companion object {
        fun matches(id: String, prefix: String): Boolean =
            Regex("(msg|ses)_[0-9a-f]{12}[0-9A-Za-z]{14}").matches(id) && id.startsWith(prefix)
    }
}
