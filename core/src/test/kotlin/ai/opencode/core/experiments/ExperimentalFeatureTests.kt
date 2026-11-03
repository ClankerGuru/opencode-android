package ai.opencode.core.experiments

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ExperimentalFeatureTests`. `theSessionsPageDividerSwitchKeepsTheSavedChoice`
 * drives `HomeSettingsFeature`, so it's in `:feature:settings`' `ExperimentalFeatureTests`, with
 * the `settingsRowID` line of `callStyleMapsOntoTheTwoCallExperiments`.
 */
class ExperimentalFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS `withDependencies { $0.defaultAppStorage = fresh }`: a fresh test container's storage. */
    private fun <R> withFreshStorage(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), block)

    /**
     * iOS `defaults.set(_:forKey:)` on a fresh suite: a raw saved value, written even when it
     * equals the key's default (a nullable key, so `false` is stored rather than skipped).
     */
    private fun SharedStore.save(id: String, value: Boolean) = reference(SharedKey.appStorage<Boolean?>(id, null)).set(value)

    /** iOS `defaults.object(forKey:) != nil`. */
    private fun SharedStore.has(id: String) = contains(SharedKey.appStorage<Boolean?>(id, null))

    /** iOS `defaults.bool(forKey:)`. */
    private fun SharedStore.bool(id: String) = reference(SharedKey.appStorage(id, false)).value

    @Test
    fun experimentsAreOffByDefaultAndPersistUnderAStableKey() {
        withFreshStorage {
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            assertFalse(inAppBrowser.value)
            inAppBrowser.set(true)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            assertEquals("The key is the phone's saved choice: never rename it", "experimental-in-app-browser", inAppBrowser.key.id)
            assertTrue(dependency(SharedStore).has("experimental-in-app-browser"))
        }
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.inAppBrowser))
        assertTrue(ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))(ExperimentalFeature.inAppBrowser))
    }

    /** The New Messages Divider is on for everyone who never touched its switch; someone who switched it off keeps it off. */
    @Test
    fun theNewMessagesDividerIsOnUntilSwitchedOff() {
        withFreshStorage {
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider))
            assertTrue(shared(experimental(ExperimentalFeature.newMessagesDivider)).value)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.projectIcons))
            for (feature in ExperimentalFeature.allCases) {
                if (feature == ExperimentalFeature.newMessagesDivider || feature == ExperimentalFeature.projectIcons) continue
                assertFalse("$feature stays off", ExperimentalFeatures.liveValue(feature))
            }
        }
        withFreshStorage {
            dependency(SharedStore).save("experimental-new-messages-divider", false)
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider))
        }
        assertFalse("tests opt in", ExperimentalFeatures.testValue(ExperimentalFeature.newMessagesDivider))
    }

    @Test
    fun onlyTheDividerHasGraduatedOutOfExperimental() {
        assertEquals(listOf(ExperimentalFeature.newMessagesDivider), ExperimentalFeature.allCases.filter { it.isGraduated })
        assertFalse(ExperimentalGroup.entries.flatMap { it.features }.contains(ExperimentalFeature.newMessagesDivider))
    }

    @Test
    fun everyExperimentHasACopyForSettings() {
        for (feature in ExperimentalFeature.allCases) {
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
        assertEquals(
            ExperimentalFeature.allCases.filter { !it.isGraduated }.toSet(),
            ExperimentalGroup.entries.flatMap { it.features }.toSet(),
        )
        assertEquals(
            listOf(ExperimentalFeature.composerAutocomplete, ExperimentalFeature.plusMenuSheet, ExperimentalFeature.agentSelection, ExperimentalFeature.hideModels),
            ExperimentalGroup.composer.features,
        )
        assertEquals(
            listOf(
                ExperimentalFeature.callTray, ExperimentalFeature.livingOrb, ExperimentalFeature.serverVoice,
                ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.openLinksFromCalls, ExperimentalFeature.audioInCalls,
            ),
            ExperimentalGroup.calls.features,
        )
        assertEquals(
            "Call Style writes the other two",
            listOf(
                ExperimentalFeature.serverVoice, ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.openLinksFromCalls, ExperimentalFeature.audioInCalls,
            ),
            ExperimentalGroup.calls.toggles,
        )
        assertEquals(listOf(ExperimentalFeature.imageLinkPreviews, ExperimentalFeature.inAppBrowser), ExperimentalGroup.transcript.features)
        assertEquals(
            listOf(
                ExperimentalFeature.inboxSidebar, ExperimentalFeature.projectIcons, ExperimentalFeature.serverDetails, ExperimentalFeature.serverFlow,
                ExperimentalFeature.welcomePreview, ExperimentalFeature.sessionLiveActivity, ExperimentalFeature.soundEffects,
            ),
            ExperimentalGroup.drawer.features,
        )
        assertEquals(listOf("Composer", "Calls", "Transcript", "Drawer"), ExperimentalGroup.entries.map { it.title })
    }

    @Test
    fun removedExperimentsLeaveNoSwitchBehind() {
        withFreshStorage {
            val defaults = dependency(SharedStore)
            defaults.save("experimental-session-switcher", true)
            defaults.save("experimental-server-title-switch", true)
            defaults.save("experimental-in-app-browser", true)
            assertNull(ExperimentalFeature.fromRawValue("session-switcher"))
            assertNull(ExperimentalFeature.fromRawValue("server-title-switch"))
            ExperimentalFeature.removeRetiredSwitches(defaults)
            assertFalse(defaults.has("experimental-session-switcher"))
            assertFalse(defaults.has("experimental-server-title-switch"))
            assertTrue("Live experiments keep their switch", defaults.bool("experimental-in-app-browser"))
        }
    }

    @Test
    fun modelPickerStyleReplacesTheTwoModelPickerSwitches() {
        assertEquals(listOf("Today", "Sheet", "One Picker", "Grow from Composer"), ModelPickerStyle.entries.map { it.title })
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

    @Test
    fun modelPickerStyleMigratesTheOldSwitchesOnce() {
        fun migrated(setup: (SharedStore) -> Unit): Pair<String?, Boolean> = withFreshStorage {
            val defaults = dependency(SharedStore)
            setup(defaults)
            ModelPickerStyle.migrateLegacySwitches(defaults)
            val leftovers = defaults.has(ModelPickerStyle.LEGACY_SHEET_KEY) || defaults.has(ModelPickerStyle.LEGACY_GROW_KEY)
            val stored = if (defaults.contains(ModelPickerStyle.key)) defaults.reference(ModelPickerStyle.key).value.rawValue else null
            stored to leftovers
        }
        assertEquals(null to false, migrated { })
        assertEquals("sheet" to false, migrated { it.save(ModelPickerStyle.LEGACY_SHEET_KEY, true) })
        assertEquals(
            "grow-from-composer" to false,
            migrated {
                it.save(ModelPickerStyle.LEGACY_SHEET_KEY, true)
                it.save(ModelPickerStyle.LEGACY_GROW_KEY, true)
            },
        )
        assertEquals(null to false, migrated { it.save(ModelPickerStyle.LEGACY_GROW_KEY, false) })
        assertEquals(
            "A style already chosen stays",
            "today" to false,
            migrated {
                it.reference(SharedKey.appStorage<ModelPickerStyle?>(ModelPickerStyle.STORAGE_KEY, null)).set(ModelPickerStyle.today)
                it.save(ModelPickerStyle.LEGACY_GROW_KEY, true)
            },
        )
    }

    @Test
    fun labExperimentsPickAModelPickerStyle() {
        assertNull(ModelPickerStyle.labStyle(""))
        assertNull(ModelPickerStyle.labStyle("in-app-browser"))
        assertEquals(ModelPickerStyle.sheet, ModelPickerStyle.labStyle("model-picker=sheet"))
        assertEquals(ModelPickerStyle.onePicker, ModelPickerStyle.labStyle("model-picker=one-picker"))
        assertEquals(ModelPickerStyle.onePicker, ModelPickerStyle.labStyle("one-picker"))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.labStyle("in-app-browser,model-picker=grow-from-composer"))
        assertEquals(ModelPickerStyle.today, ModelPickerStyle.labStyle("model-picker=today,custom-model-picker"))
        assertEquals(ModelPickerStyle.sheet, ModelPickerStyle.labStyle("new-model-picker"))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.labStyle("custom-model-picker"))
        assertEquals(ModelPickerStyle.growFromComposer, ModelPickerStyle.labStyle("new-model-picker,custom-model-picker"))
    }

    @Test
    fun callStyleMapsOntoTheTwoCallExperiments() {
        assertEquals(ExperimentalCallStyle.standard, ExperimentalCallStyle.of(callTray = false, livingOrb = false))
        assertEquals(ExperimentalCallStyle.floating, ExperimentalCallStyle.of(callTray = false, livingOrb = true))
        assertEquals(ExperimentalCallStyle.tray, ExperimentalCallStyle.of(callTray = true, livingOrb = false))
        assertEquals("Living Orb wins when both are on", ExperimentalCallStyle.floating, ExperimentalCallStyle.of(callTray = true, livingOrb = true))
        for (style in ExperimentalCallStyle.entries) {
            assertEquals(style, ExperimentalCallStyle.of(style.callTray, style.livingOrb))
            assertFalse("Choosing a style leaves at most one switch on", style.callTray && style.livingOrb)
            assertTrue(style.summary.split(" ").size <= 6)
        }
        assertEquals(listOf("Standard", "Living Orb", "Tray"), ExperimentalCallStyle.entries.map { it.title })
    }

    @Test
    fun theOldFloatingCallSwitchMovesToLivingOrbOnce() {
        data class Migrated(val livingOrb: Boolean, val tray: Boolean, val leftover: Boolean)
        fun migrated(setup: (SharedStore) -> Unit): Migrated = withFreshStorage {
            val defaults = dependency(SharedStore)
            setup(defaults)
            ExperimentalCallStyle.migrateFloatingCall(defaults)
            Migrated(
                defaults.bool("experimental-living-orb"), defaults.bool("experimental-call-tray"),
                defaults.has(ExperimentalCallStyle.LEGACY_FLOATING_KEY),
            )
        }
        assertEquals(Migrated(false, false, false), migrated { })
        assertEquals(Migrated(true, false, false), migrated { it.save(ExperimentalCallStyle.LEGACY_FLOATING_KEY, true) })
        assertEquals(Migrated(false, false, false), migrated { it.save(ExperimentalCallStyle.LEGACY_FLOATING_KEY, false) })
        assertEquals(
            "Call Tray won over Floating Call, so the tray stays",
            Migrated(false, true, false),
            migrated {
                it.save(ExperimentalCallStyle.LEGACY_FLOATING_KEY, true)
                it.save("experimental-call-tray", true)
            },
        )
        assertEquals(
            "Living Orb already on stays on",
            Migrated(true, false, false),
            migrated {
                it.save(ExperimentalCallStyle.LEGACY_FLOATING_KEY, false)
                it.save("experimental-living-orb", true)
            },
        )
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
