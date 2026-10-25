package ai.opencode.core.haptics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * `:core`'s part of iOS `HapticsAuditTests.swift` (`LiveHapticsTests`). The audit suite proper
 * (`HapticsAuditTests`: which Session/Home actions play which `Haptic`) drives `SessionFeature`
 * and `HomeFeature`, so it belongs to `:feature:session` / `:feature:drawer`;
 * `theCallsFallbacksComeFromTheVocabulary` (`VoiceCallHaptic`) belongs to `:feature:voice`.
 * `aRequestArrivingInTheBackgroundPlaysNothing` isn't ported: Android's foreground gate is
 * `ProcessLifecycleOwner` + `Vibrator`, which need an Android runtime (no Robolectric here).
 */
class LiveHapticsTests {
    @get:Rule val isolation = TestIsolationRule()

    /**
     * iOS forbids raw `UIFeedbackGenerator`/`.sensoryFeedback` outside `HapticsClient.swift`.
     * Android's equivalents are the `Vibrator` APIs and Compose/View haptic feedback; only
     * `HapticsClient.kt` and the voice call's pattern engine (`VoiceHapticEngine.kt`, iOS's
     * `CHHapticEngine`, which iOS's scan also allows) may use them.
     */
    @Test
    fun noDirectFeedbackGeneratorsOrSensoryFeedbackOutsideHapticsClientAndShareExtension() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val pattern = Regex("""\bVibrator(Manager)?\b|\bVibrationEffect\b|performHapticFeedback|LocalHapticFeedback|HapticFeedbackConstants""")
        val allowPattern = Regex("""//\s*correct-allow:\s*haptics\s*(?:--|—|-)\s*\S.*?\(\s*expires\s+\d{4}-\d{2}-\d{2}\s*,\s*approved by\s+[A-Za-z][A-Za-z0-9 _.-]*\s*\)""")
        val allowed = setOf(
            "core/src/main/kotlin/ai/opencode/core/haptics/HapticsClient.kt",
            "feature/voice/src/main/kotlin/ai/opencode/feature/voice/VoiceHapticEngine.kt",
        )
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }
            .forEach { file ->
                val rel = file.relativeTo(root).path
                if (rel in allowed) return@forEach
                file.readLines().forEachIndexed { index, raw ->
                    val code = raw.substringBefore("//")
                    if (pattern.containsMatchIn(code) && !allowPattern.containsMatchIn(raw)) {
                        violations += "$rel:${index + 1}: ${code.trim()}"
                    }
                }
            }
        assertTrue(
            "Use dependency(HapticsClient) or HapticEffect (core/haptics/HapticsClient.kt) instead of raw Vibrator or haptic feedback: $violations",
            violations.isEmpty(),
        )
    }
}
