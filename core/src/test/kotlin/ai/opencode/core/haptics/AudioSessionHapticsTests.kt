package ai.opencode.core.haptics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `AudioSessionHapticsTests.swift` › `AudioSessionHaptics`. The category and mode are
 * iOS's `AVAudioSession` raw values (Android reports `AudioManager`'s mode and no category).
 * `LiveDictationAudioSessionTests` is an opt-in suite on the simulator's real microphone and audio
 * session (`OPENCODE_LIVE_DICTATION=1`), not ported.
 */
class AudioSessionHapticsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun traceLineIsContentFreeAndNamesWhatMutesHaptics() {
        val audio = AudioSessionHaptics.AudioState(
            category = "PlayAndRecord", mode = "VoiceChat", allowsHaptics = false, dictating = true, otherAudio = false,
        )
        assertEquals(
            "haptic_impact app=active audio=PlayAndRecord/VoiceChat allow=0 dictating=1 call=0 other=0",
            AudioSessionHaptics.traceLine(kind = "impact", appActive = true, callActive = false, audio = audio),
        )
    }
}
