package ai.opencode.feature.transcript.user

import ai.opencode.server.model.OpenCodeDelivery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `:feature:transcript` test of iOS `OpenCodeTests/VoiceHandoffParseTests.swift`
 * ([VoiceMessageIndicator]); the parsing and formatting tests are `:feature:voice`'s.
 */
class VoiceHandoffParseTests {
    @Test fun theLabelRowFallbackLeadsWithThePendingPart() {
        val row = VoiceMessageIndicator.LabelRow
        assertEquals(VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Voice)), row.chrome(pending = null))
        // Pending leads, so delivery removes it without moving Voice.
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Steer), VoiceMessageIndicator.LabelPart.Voice)),
            row.chrome(pending = OpenCodeDelivery.Steer),
        )
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Queue), VoiceMessageIndicator.LabelPart.Voice)),
            row.chrome(pending = OpenCodeDelivery.Queue),
        )
    }
}
