package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `:feature:composer` test of iOS `OpenCodeTests/WorkingOrderTests.swift` ([PromptOutbox]
 * alone); the `SessionFeature` tests are `:feature:session`'s, the `HomeFeature` ones
 * `:feature:drawer`'s.
 */
class WorkingOrderTests {
    @Test fun deliveryMarksARecordedSendAsSent() {
        val outbox = PromptOutbox()
        val pending = PendingPrompt(messageID = "msg_VOICE", text = "x", attachments = emptyList(), requestEpoch = 0)
        outbox.recordAdmitted(pending, sessionID = "ses_a", serverKey = "s")
        assertEquals(listOf(true), outbox.contents(serverKey = "s", sessionID = "ses_a").admitted.map { it.wasRunning })
        outbox.markDelivered("msg_VOICE")
        assertEquals(listOf(false), outbox.contents(serverKey = "s", sessionID = "ses_a").admitted.map { it.wasRunning })
        assertTrue(outbox.contents(serverKey = "s", sessionID = "ses_b").admitted.isEmpty())
    }
}
