package ai.opencode.core.experiments

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Port of iOS `ExperimentalFeatureTests`. */
class ExperimentalFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS `withDependencies { $0.defaultAppStorage = fresh }`: a fresh test container's storage. */
    private fun <R> withFreshStorage(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), block)

    @Test
    fun experimentsAreOffByDefaultAndPersistUnderAStableKey() {
        withFreshStorage {
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            assertFalse(inAppBrowser.value)
            inAppBrowser.set(true)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            assertEquals("The key is the phone's saved choice: never rename it", "experimental-in-app-browser", inAppBrowser.key.id)
        }
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.inAppBrowser))
        assertTrue(ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))(ExperimentalFeature.inAppBrowser))
    }

    @Test
    fun everyExperimentHasACopyForSettings() {
        for (feature in ExperimentalFeature.entries) {
            assertTrue(feature.title.isNotEmpty())
            assertTrue(feature.summary.isNotEmpty())
            assertFalse("One line, no trailing period", feature.summary.endsWith("."))
            assertTrue("A few words: ${feature.summary}", feature.summary.split(" ").filter { it.isNotEmpty() }.size <= 6)
        }
        assertEquals("In-App Browser", ExperimentalFeature.inAppBrowser.title)
    }

    @Test
    fun everyExperimentSitsInOneGroupInAStableOrder() {
        assertEquals(listOf(ExperimentalGroup.composer, ExperimentalGroup.calls, ExperimentalGroup.transcript, ExperimentalGroup.drawer), ExperimentalGroup.entries)
        assertEquals(ExperimentalFeature.entries.toSet(), ExperimentalGroup.entries.flatMap { it.features }.toSet())
        assertEquals(
            listOf(ExperimentalFeature.composerAutocomplete, ExperimentalFeature.plusMenuSheet, ExperimentalFeature.agentSelection, ExperimentalFeature.hideModels),
            ExperimentalGroup.composer.features,
        )
        assertEquals(
            listOf(ExperimentalFeature.callTray, ExperimentalFeature.floatingCall, ExperimentalFeature.livingOrb, ExperimentalFeature.serverVoice, ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator),
            ExperimentalGroup.calls.features,
        )
        assertEquals(
            "Call Style writes the other two",
            listOf(ExperimentalFeature.livingOrb, ExperimentalFeature.serverVoice, ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator),
            ExperimentalGroup.calls.toggles,
        )
        assertEquals(
            listOf(ExperimentalFeature.newMessagesDivider, ExperimentalFeature.sinceYouLeft, ExperimentalFeature.imageLinkPreviews, ExperimentalFeature.inAppBrowser),
            ExperimentalGroup.transcript.features,
        )
        assertEquals(
            listOf(ExperimentalFeature.projectIcons, ExperimentalFeature.sessionSwitcher, ExperimentalFeature.serverTitleSwitch, ExperimentalFeature.serverDetails),
            ExperimentalGroup.drawer.features,
        )
        assertEquals(listOf("Composer", "Calls", "Transcript", "Drawer"), ExperimentalGroup.entries.map { it.title })
    }

    @Test
    fun modelPickerStyleReplacesTheTwoModelPickerSwitches() {
        assertEquals(listOf("Today", "Sheet", "Grow from Composer"), ModelPickerStyle.entries.map { it.title })
        assertEquals(ModelPickerStyle.today, ModelPickerStyle.of(newModelPicker = false, customModelPicker = false))
        assertEquals(ModelPickerStyle.sheet, ModelPickerStyle.of(newModelPicker = true, customModelPicker = false))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.of(newModelPicker = false, customModelPicker = true))
        assertEquals(
            "Custom Model Picker won when both were on",
            ModelPickerStyle.growFromComposer,
            ModelPickerStyle.of(newModelPicker = true, customModelPicker = true),
        )
        for (style in ModelPickerStyle.entries) {
            assertTrue(style.summary.split(" ").size <= 6)
        }
    }

    /**
     * Android's `migrateLegacySwitches` is a pure function of what the caller read (the stored
     * style and the two legacy switches, null when absent); the caller writes the result and
     * removes the switches, so iOS's "no leftovers" half is the caller's.
     */
    @Test
    fun modelPickerStyleMigratesTheOldSwitchesOnce() {
        fun migrated(stored: ModelPickerStyle? = null, sheet: Boolean? = null, grow: Boolean? = null): String? =
            ModelPickerStyle.migrateLegacySwitches(stored, sheet, grow)?.rawValue ?: stored?.rawValue
        assertNull(migrated())
        assertEquals("sheet", migrated(sheet = true))
        assertEquals("grow-from-composer", migrated(sheet = true, grow = true))
        assertNull(migrated(grow = false))
        assertEquals("A style already chosen stays", "today", migrated(stored = ModelPickerStyle.today, grow = true))
    }

    @Test
    fun labExperimentsPickAModelPickerStyle() {
        assertNull(ModelPickerStyle.labStyle(""))
        assertNull(ModelPickerStyle.labStyle("in-app-browser"))
        assertEquals(ModelPickerStyle.sheet, ModelPickerStyle.labStyle("model-picker=sheet"))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.labStyle("in-app-browser,model-picker=grow-from-composer"))
        assertEquals(ModelPickerStyle.today, ModelPickerStyle.labStyle("model-picker=today,custom-model-picker"))
        assertEquals(ModelPickerStyle.sheet, ModelPickerStyle.labStyle("new-model-picker"))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.labStyle("custom-model-picker"))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.labStyle("new-model-picker,custom-model-picker"))
    }

    @Test
    fun callStyleMapsOntoTheTwoCallExperiments() {
        assertEquals(ExperimentalCallStyle.standard, ExperimentalCallStyle.of(callTray = false, floatingCall = false))
        assertEquals(ExperimentalCallStyle.floating, ExperimentalCallStyle.of(callTray = false, floatingCall = true))
        assertEquals(ExperimentalCallStyle.tray, ExperimentalCallStyle.of(callTray = true, floatingCall = false))
        assertEquals("Call Tray wins when both are on", ExperimentalCallStyle.tray, ExperimentalCallStyle.of(callTray = true, floatingCall = true))
        for (style in ExperimentalCallStyle.entries) {
            assertEquals(style, ExperimentalCallStyle.of(style.callTray, style.floatingCall))
            assertFalse("Choosing a style leaves at most one switch on", style.callTray && style.floatingCall)
            assertTrue(style.summary.split(" ").size <= 6)
        }
        assertEquals(listOf("Standard", "Floating", "Tray"), ExperimentalCallStyle.entries.map { it.title })
    }

    /** `VoiceAudioDemoMode` lives in `:feature:voice`; its half of this test belongs there. */
    @Test
    fun voiceDemoModeRequiresDeveloperMode() {
        withFreshStorage {
            val developerMode = shared(SharedKeys.developerMode)
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            val demoMode = shared(SharedKeys.voiceDemoMode)
            inAppBrowser.set(true)
            demoMode.set(true)
            developerMode.set(false)
            assertTrue(ExperimentalFeature.inAppBrowser.isActive(true))
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
        }
    }
}
