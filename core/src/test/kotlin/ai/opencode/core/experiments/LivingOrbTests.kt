package ai.opencode.core.experiments

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `LivingOrbTests.swift`, the `:core` part: `flagRestoresEveryExistingCallStyleWhenOff`
 * ([ExperimentalCallStyle.of]). The orb itself is `:feature:voice`'s, its setting `:feature:settings`'.
 */
class LivingOrbTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun flagRestoresEveryExistingCallStyleWhenOff() {
        for (tray in listOf(false, true)) {
            for (floating in listOf(false, true)) {
                assertEquals(
                    ExperimentalCallStyle.of(callTray = tray, floatingCall = floating),
                    ExperimentalCallStyle.of(callTray = tray, floatingCall = floating, livingOrb = false),
                )
                assertEquals(ExperimentalCallStyle.floating, ExperimentalCallStyle.of(callTray = tray, floatingCall = floating, livingOrb = true))
            }
        }
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.livingOrb))
    }
}
