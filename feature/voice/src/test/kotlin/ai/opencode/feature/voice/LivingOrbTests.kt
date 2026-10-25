package ai.opencode.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Port of iOS `LivingOrbTests.swift`, the `:feature:voice` part. `flagRestoresEveryExistingCallStyleWhenOff`
 * (`ExperimentalCallStyle`, `:core`) and `settingsPersistStyleWithoutEnablingTheExperiment`
 * (`HomeSettingsFeature`, `:feature:settings`) belong to those modules. `colourAndShapePresetsAreIndependentAndResetCleanly`
 * is dropped: it drives the Developer Mode tuning store (presets, registration, reset), which
 * isn't ported — the Android tunables are baked iOS defaults.
 */
class LivingOrbTests {
    @Test fun statePrecedenceAndMuteKeepAgentDistinct() {
        assertEquals(LivingOrbState.idle, LivingOrbState.resolve(mood = FloatingCallMood.live, speaking = false, userLevel = 0.0, muted = false, scene = null))
        assertEquals(LivingOrbState.listening, LivingOrbState.resolve(mood = FloatingCallMood.live, speaking = false, userLevel = 0.5, muted = false, scene = null))
        assertEquals(LivingOrbState.speaking, LivingOrbState.resolve(mood = FloatingCallMood.thinking, speaking = true, userLevel = 0.5, muted = false, scene = null))
        assertEquals(LivingOrbState.thinking, LivingOrbState.resolve(mood = FloatingCallMood.thinking, speaking = false, userLevel = 0.5, muted = true, scene = null))
        assertEquals(LivingOrbState.idle, LivingOrbState.resolve(mood = FloatingCallMood.live, speaking = false, userLevel = 0.5, muted = true, scene = null))
    }

    @Test fun decayFollowersAgreeAcrossRefreshRatesAndReduceMotionFreezes() {
        val look = LivingOrbLook()
        fun run(hz: Double): LivingOrbField {
            val field = LivingOrbField()
            repeat(hz.toInt()) { field.step(dt = 1 / hz, levels = 0.8 to 0.5, state = LivingOrbState.speaking, still = false, look = look) }
            return field
        }
        val a = run(hz = 60.0)
        val b = run(hz = 120.0)
        assertTrue(abs(a.you - b.you) < 0.00001)
        assertTrue(abs(a.agent - b.agent) < 0.00001)
        assertTrue(abs(a.phase - b.phase) < 0.02)
        val still = a
        still.step(dt = 1.0, levels = 1.0 to 1.0, state = LivingOrbState.thinking, still = true, look = look)
        val tint = still.tint
        still.step(dt = 1.0, levels = 0.0 to 0.0, state = LivingOrbState.thinking, still = true, look = look)
        assertTrue(still.phase == 0.0 && still.tint == tint && still.thinking == 1.0)
        still.step(dt = 1.0, levels = 0.0 to 0.0, state = LivingOrbState.speaking, still = true, look = look)
        assertTrue(still.tint != tint && still.phase == 0.0)
    }

    @Test fun allStylesStayFiniteAtTheCentreAndAcrossTunedExtremes() {
        // iOS tunes `density` to 12 and `polarFolds` to 8 through the tuning store.
        val look = LivingOrbLook(density = 12.0, polarFolds = 8)
        for (style in LivingOrbStyle.entries) {
            for (i in 0..100) {
                val x = i / 100.0
                val value = LivingOrbField.pattern(style, x = x, y = 0.0, r = x, u = x, v = 0.0, t = 12.0, p = look)
                assertTrue("$style at $x: $value", value.isFinite() && abs(value) <= 1)
            }
        }
    }
}
