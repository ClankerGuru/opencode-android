package ai.opencode.feature.voice

import ai.opencode.core.haptics.Haptic
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port of the `VoiceCallHaptic` test of iOS `HapticsAuditTests.swift`; the rest of that suite is
 * ported in `:core`, `:feature:session` and `:feature:drawer`.
 */
class HapticsAuditTests {
    /**
     * A call's start, connect and end feel like the rest of the app's confirm, success and
     * cancel wherever its own patterns can't play.
     */
    @Test fun theCallsFallbacksComeFromTheVocabulary() {
        assertEquals(Haptic.confirm.feedback, VoiceCallHaptic.callStarted.fallback.feedback)
        assertEquals(Haptic.success.feedback, VoiceCallHaptic.connected.fallback.feedback)
        assertEquals(Haptic.cancel.feedback, VoiceCallHaptic.ended.fallback.feedback)
    }
}
